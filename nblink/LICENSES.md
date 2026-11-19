# The Go packages in the NetBird library, and their licenses

The build makes `app/libs/nblink.aar` from these Go modules (the Android builds for 64-bit ARM, 32-bit ARM and
x86-64 together). The list is for NetBird v0.83.1. Each module has its full license text in its source code,
which the build downloads into the Go module folder (`go env GOMODCACHE`).

NetBird: the repository of NetBird has two licenses. The client, which the app uses, is BSD 3-Clause. The
folders `management/`, `signal/`, `relay/` and `combined/` are AGPL 3.0. No package from these folders is in the
library. To check this, run `GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -deps ./` in `nblink/`.

The three modules with the Mozilla Public License 2.0 are used without changes.

| Module | Version | License |
|---|---|---|
| `cunicu.li/go-rosenpass` | v0.5.42 | Apache-2.0 |
| `github.com/anmitsu/go-shlex` | v0.0.0-20200514113438-38f4b401e2be | MIT |
| `github.com/aws/aws-sdk-go-v2/config` | v1.31.6 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/credentials` | v1.18.10 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/feature/ec2/imds` | v1.18.6 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/configsources` | v1.4.6 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/endpoints/v2` | v2.7.6 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/ini` | v1.8.3 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding` | v1.13.1 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/internal/presigned-url` | v1.13.6 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/route53` | v1.42.3 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/ssooidc` | v1.34.2 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/sso` | v1.29.1 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/sts` | v1.38.2 | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2` | v1.38.3 | Apache-2.0 |
| `github.com/aws/smithy-go` | v1.23.0 | Apache-2.0 |
| `github.com/beorn7/perks` | v1.0.1 | MIT |
| `github.com/caddyserver/certmagic` | v0.21.3 | Apache-2.0 |
| `github.com/caddyserver/zerossl` | v0.1.3 | MIT |
| `github.com/cenkalti/backoff/v4` | v4.3.0 | MIT |
| `github.com/cespare/xxhash/v2` | v2.3.0 | MIT |
| `github.com/cilium/ebpf` | v0.19.0 | MIT |
| `github.com/cloudflare/circl` | v1.6.3 | BSD-3-Clause |
| `github.com/coder/websocket` | v1.8.14 | ISC |
| `github.com/creack/pty` | v1.1.24 | MIT |
| `github.com/DeRuina/timberjack` | v1.4.2 | MIT |
| `github.com/fsnotify/fsnotify` | v1.9.0 | BSD-3-Clause |
| `github.com/gliderlabs/ssh` | v0.3.9-0.20250127223818-909fa952d4e4 | BSD-3-Clause |
| `github.com/godbus/dbus/v5` | v5.2.2 | BSD-2-Clause |
| `github.com/golang-jwt/jwt/v5` | v5.3.1 | MIT |
| `github.com/golang/protobuf` | v1.5.4 | BSD-3-Clause |
| `github.com/google/btree` | v1.1.3 | Apache-2.0 |
| `github.com/google/gopacket` | v1.1.19 | BSD-3-Clause |
| `github.com/google/go-tpm` | v0.9.8 | Apache-2.0 |
| `github.com/google/uuid` | v1.6.0 | BSD-3-Clause |
| `github.com/gopacket/gopacket` | v1.4.0 | BSD-3-Clause |
| `github.com/grpc-ecosystem/grpc-gateway/v2` | v2.26.3 | BSD-3-Clause |
| `github.com/hashicorp/errwrap` | v1.1.0 | MPL-2.0 |
| `github.com/hashicorp/go-multierror` | v1.1.1 | MPL-2.0 |
| `github.com/hashicorp/go-version` | v1.7.0 | MPL-2.0 |
| `github.com/huin/goupnp` | v1.2.0 | BSD-2-Clause |
| `github.com/jackpal/go-nat-pmp` | v1.0.2 | Apache-2.0 |
| `github.com/jmespath/go-jmespath` | v0.4.0 | Apache-2.0 |
| `github.com/klauspost/compress` | v1.18.7 | Apache-2.0 |
| `github.com/klauspost/cpuid/v2` | v2.3.0 | MIT |
| `github.com/koron/go-ssdp` | v0.0.4 | MIT |
| `github.com/kr/fs` | v0.1.0 | BSD-3-Clause |
| `github.com/libdns/libdns` | v0.2.2 | MIT |
| `github.com/libdns/route53` | v1.5.0 | MIT |
| `github.com/libp2p/go-netroute` | v0.4.0 | BSD-3-Clause |
| `github.com/mdlayher/genetlink` | v1.3.2 | MIT |
| `github.com/mdlayher/netlink` | v1.7.3-0.20250113171957-fbb4dce95f42 | MIT |
| `github.com/mdlayher/socket` | v0.5.1 | MIT |
| `github.com/mholt/acmez/v2` | v2.0.1 | Apache-2.0 |
| `github.com/miekg/dns` | v1.1.72 | BSD-3-Clause |
| `github.com/mitchellh/hashstructure/v2` | v2.0.2 | MIT |
| `github.com/munnerz/goautoneg` | v0.0.0-20191010083416-a7dc8b61c822 | BSD-3-Clause |
| `github.com/netbirdio/go-nat` | v0.0.0-20260821095157-6b2c8c5c74e8 | Apache-2.0 |
| `github.com/netbirdio/ice/v4` | v4.0.0-20261021152109-d0e5dfacb548 | MIT |
| `github.com/netbirdio/netbird` | v0.83.1 | BSD-3-Clause (client part) |
| `github.com/netbirdio/wireguard-go` | v0.0.0-20261021152105-0de46b309cdb | MIT |
| `github.com/pion/dtls/v2` | v2.2.10 | MIT |
| `github.com/pion/dtls/v3` | v3.0.9 | MIT |
| `github.com/pion/logging` | v0.2.4 | MIT |
| `github.com/pion/mdns/v2` | v2.0.7 | MIT |
| `github.com/pion/randutil` | v0.1.0 | MIT |
| `github.com/pion/stun/v2` | v2.0.0 | MIT |
| `github.com/pion/stun/v3` | v3.1.0 | MIT |
| `github.com/pion/transport/v2` | v2.2.4 | MIT |
| `github.com/pion/transport/v3` | v3.1.1 | MIT |
| `github.com/pion/turn/v3` | v3.0.1 | MIT |
| `github.com/pion/turn/v4` | v4.1.1 | MIT |
| `github.com/pkg/sftp` | v1.13.9 | BSD-2-Clause |
| `github.com/prometheus/client_golang` | v1.23.2 | Apache-2.0 |
| `github.com/prometheus/client_model` | v0.6.2 | Apache-2.0 |
| `github.com/prometheus/common` | v0.67.5 | Apache-2.0 |
| `github.com/prometheus/procfs` | v0.19.2 | Apache-2.0 |
| `github.com/quic-go/quic-go` | v0.64.0 | MIT |
| `github.com/sirupsen/logrus` | v1.9.4 | MIT |
| `github.com/skratchdot/open-golang` | v0.0.0-20200116055534-eef842397966 | MIT |
| `github.com/things-go/go-socks5` | v0.0.4 | MIT |
| `github.com/vishvananda/netlink` | v1.3.1 | Apache-2.0 |
| `github.com/vishvananda/netns` | v0.0.5 | Apache-2.0 |
| `github.com/wlynxg/anet` | v0.0.5 | BSD-3-Clause |
| `github.com/zeebo/blake3` | v0.2.3 | CC0-1.0 |
| `golang.org/x/crypto` | v0.55.0 | BSD-3-Clause |
| `golang.org/x/exp` | v0.0.0-20260410095643-746e56fc9e2f | BSD-3-Clause |
| `golang.org/x/net` | v0.58.0 | BSD-3-Clause |
| `golang.org/x/oauth2` | v0.36.0 | BSD-3-Clause |
| `golang.org/x/sync` | v0.23.0 | BSD-3-Clause |
| `golang.org/x/sys` | v0.47.0 | BSD-3-Clause |
| `golang.org/x/text` | v0.41.0 | BSD-3-Clause |
| `golang.org/x/time` | v0.15.0 | BSD-3-Clause |
| `golang.zx2c4.com/wireguard/wgctrl` | v0.0.0-20241231184526-a9ab2273dd10 | MIT |
| `google.golang.org/genproto/googleapis/api` | v0.0.0-20260319201613-d00831a3d3e7 | Apache-2.0 |
| `google.golang.org/genproto/googleapis/rpc` | v0.0.0-20260401024825-9d38bb4040a9 | Apache-2.0 |
| `google.golang.org/grpc` | v1.80.0 | Apache-2.0 |
| `google.golang.org/protobuf` | v1.36.11 | BSD-3-Clause |
| `gopkg.in/yaml.v3` | v3.0.1 | Apache-2.0 |
| `go.uber.org/multierr` | v1.11.0 | MIT |
| `go.uber.org/zap` | v1.27.0 | MIT |
| `go.yaml.in/yaml/v2` | v2.4.3 | Apache-2.0 |
| `gvisor.dev/gvisor` | v0.0.0-20260219192049-0f2374377e89 | Apache-2.0 |
| Go standard library | go1.26.10 | BSD-3-Clause |
