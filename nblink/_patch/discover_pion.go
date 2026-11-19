// This file takes the place of client/internal/stdnet/discover_pion.go of NetBird when the
// library of the app is built (see ../build.sh, "overlay"). It is NetBird's own file with one
// change: the list of the device's network interfaces comes from the package anet.
//
// Why: an app on Android 11 or newer is not allowed to ask the system for that list the way
// Go's own library asks. Without the list NetBird makes no direct connection between two
// members, and all traffic goes through a relay server. anet asks in a way that Android
// permits. NetBird already has anet among its packages (v0.0.5, through pion/transport).
//
// Original: Copyright (c) 2022-2026 NetBird GmbH & AUTHORS, BSD 3-Clause License.
package stdnet

import (
	"github.com/pion/transport/v3"
	"github.com/wlynxg/anet"
)

type pionDiscover struct {
}

func (d pionDiscover) iFaces() ([]*transport.Interface, error) {
	ifs := []*transport.Interface{}

	oifs, err := anet.Interfaces()
	if err != nil {
		return nil, err
	}

	for i := range oifs {
		oif := oifs[i]
		ifc := transport.NewInterface(oif)

		addrs, err := anet.InterfaceAddrsByInterface(&oif)
		if err != nil {
			return nil, err
		}

		for _, addr := range addrs {
			ifc.AddAddress(addr)
		}

		ifs = append(ifs, ifc)
	}

	return ifs, nil
}
