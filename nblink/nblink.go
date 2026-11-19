// Package nblink lets the app be a member of a NetBird network with no system VPN.
//
// It runs NetBird's own Go client (client/embed) with userspace networking: WireGuard and the
// network stack live inside the app's process, and only connections that the app opens through
// this package use the NetBird network. Android shows no VPN question and no key icon.
//
// The app does not get a socket on the NetBird network. It gets a port on 127.0.0.1 that
// forwards to one address on that network, so that the app's usual HTTP client, with its usual
// certificate check, can use it like any other address of the box.
//
// The exported names follow what gomobile can bind: strings, numbers, errors and one struct.
package nblink

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/netbirdio/netbird/client/embed"
)

// Link is one running NetBird client.
type Link struct {
	client *embed.Client

	mu        sync.Mutex
	listeners []net.Listener
	conns     map[net.Conn]struct{}
	stopped   bool
}

// Start joins the NetBird network and returns when the client runs, or with the reason why it
// does not. It can take some seconds: call it from a background thread.
//
//   - management: the address of the management server, for example https://api.netbird.io:443
//   - setupKey: the key that lets a new member in. Used at the first start only; after that the
//     member's own key in configPath is enough.
//   - deviceName: the member's name on the NetBird dashboard
//   - configPath: a file in the app's private folder. It keeps the member's own key, so that
//     the same device is the same member after a restart.
//   - verbose: write the client's log to the system log (debug builds only)
func Start(management, setupKey, deviceName, configPath string, timeoutSeconds int, verbose bool) (*Link, error) {
	if management == "" || setupKey == "" || configPath == "" {
		return nil, errors.New("management, setup key and config path are needed")
	}
	var logOutput io.Writer = io.Discard
	level := "error"
	if verbose {
		logOutput = os.Stderr
		level = "info"
	}
	// This client reaches one member, the box. Lazy connections keep it from opening a
	// WireGuard session to every other member of the network (battery, data).
	lazy := true
	randomPort := 0
	client, err := embed.New(embed.Options{
		DeviceName:    deviceName,
		SetupKey:      setupKey,
		ManagementURL: management,
		ConfigPath:    configPath,
		LogOutput:     logOutput,
		LogLevel:      level,
		// The app only opens connections to the box. Nothing on the NetBird network gets in,
		// and the app takes no routes and no IPv6 addresses from it.
		DisableClientRoutes:   true,
		DisableIPv6:           true,
		BlockInbound:          true,
		LazyConnectionEnabled: &lazy,
		WireguardPort:         &randomPort,
	})
	if err != nil {
		return nil, fmt.Errorf("set up: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	if err := client.Start(ctx); err != nil {
		return nil, fmt.Errorf("start: %w", err)
	}
	return &Link{client: client, conns: map[net.Conn]struct{}{}}, nil
}

// Forward opens a port on 127.0.0.1 and passes every connection to host:port on the NetBird
// network. It returns the local port. host is a NetBird address or a NetBird name of a member.
func (l *Link) Forward(host string, port int) (int, error) {
	if port < 1 || port > 65535 {
		return 0, fmt.Errorf("port %d", port)
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("listen: %w", err)
	}
	l.mu.Lock()
	if l.stopped {
		l.mu.Unlock()
		_ = ln.Close()
		return 0, errors.New("stopped")
	}
	l.listeners = append(l.listeners, ln)
	l.mu.Unlock()

	go l.accept(ln, host, port)
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (l *Link) accept(ln net.Listener, host string, port int) {
	for {
		local, err := ln.Accept()
		if err != nil {
			return
		}
		go l.pipe(local, host, port)
	}
}

func (l *Link) pipe(local net.Conn, host string, port int) {
	defer local.Close()
	target, err := l.address(host)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := l.client.DialContext(ctx, "tcp", net.JoinHostPort(target, strconv.Itoa(port)))
	cancel()
	if err != nil {
		return
	}
	defer remote.Close()
	if !l.track(local, remote) {
		return
	}
	defer l.untrack(local, remote)

	done := make(chan struct{}, 2)
	copyTo := func(dst, src net.Conn) {
		_, _ = io.Copy(dst, src)
		// One side finished: let the other side see the end of the stream.
		if c, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
		done <- struct{}{}
	}
	go copyTo(remote, local)
	go copyTo(local, remote)
	<-done
	// The second direction gets a short time to finish, then both close.
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
}

// address gives the NetBird address for host. A NetBird name is looked up in the member list
// that the client has anyway, so no DNS question leaves the device.
func (l *Link) address(host string) (string, error) {
	if _, err := netip.ParseAddr(host); err == nil {
		return host, nil
	}
	status, err := l.client.Status()
	if err != nil {
		return "", err
	}
	want := strings.TrimSuffix(strings.ToLower(host), ".")
	for _, p := range status.Peers {
		name := strings.TrimSuffix(strings.ToLower(p.FQDN), ".")
		if name == want || strings.SplitN(name, ".", 2)[0] == want {
			return p.IP, nil
		}
	}
	return "", fmt.Errorf("no member with this name")
}

func (l *Link) track(conns ...net.Conn) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.stopped {
		return false
	}
	for _, c := range conns {
		l.conns[c] = struct{}{}
	}
	return true
}

func (l *Link) untrack(conns ...net.Conn) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, c := range conns {
		delete(l.conns, c)
	}
}

// Status tells in one line how the client is doing, for the app's own checks:
// "management=<bool> signal=<bool> peer=<state> relayed=<bool> rx=<bytes> tx=<bytes>".
// peer is the state of the member with this address or name ("-" when it is not in the list).
func (l *Link) Status(host string) string {
	status, err := l.client.Status()
	if err != nil {
		return "error"
	}
	want := strings.TrimSuffix(strings.ToLower(host), ".")
	peer, relayed := "-", false
	var rx, tx int64
	for _, p := range status.Peers {
		rx += p.BytesRx
		tx += p.BytesTx
		name := strings.TrimSuffix(strings.ToLower(p.FQDN), ".")
		if p.IP == host || name == want || strings.SplitN(name, ".", 2)[0] == want {
			peer, relayed = p.ConnStatus.String(), p.Relayed
		}
	}
	return fmt.Sprintf("management=%t signal=%t peer=%s relayed=%t rx=%d tx=%d",
		status.ManagementState.Connected, status.SignalState.Connected, peer, relayed, rx, tx)
}

// Stop closes the forwarded ports and their connections, and leaves the NetBird network.
func (l *Link) Stop() {
	l.mu.Lock()
	if l.stopped {
		l.mu.Unlock()
		return
	}
	l.stopped = true
	listeners, conns := l.listeners, l.conns
	l.listeners, l.conns = nil, map[net.Conn]struct{}{}
	l.mu.Unlock()

	for _, ln := range listeners {
		_ = ln.Close()
	}
	for c := range conns {
		_ = c.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = l.client.Stop(ctx)
}
