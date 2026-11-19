# fieldmap-android

Fieldmap is a small Android map app for the maps of a
[fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server). On the server's local network you browse
its maps and download the areas you need; away from it, the device shows those areas with your GPS position, with
no network at all.

It is made for driving, hiking and scouting: one map, a few large buttons, readable in sunlight and usable with
gloves. It shows where you are; it does not give turn-by-turn directions.

- **The server's maps**, in all its styles, drawn from vector tiles so they stay sharp at every zoom.
- **Offline Areas**: frame an area, choose the detail, check the size and download it.
- **Your position**: GPS position and accuracy always on screen, in MGRS, UTM or latitude/longitude; follow mode
  with north up or heading up.
- **Markers**: touch and hold the map to drop one; 12 colors; GeoJSON files shared with the server's Print Map page.
- **Remote access**: pair once on the local network, then reach the server over the internet. Encrypted, paired
  devices only, no account, no system VPN. Works through NetBird VPN too, with nothing to set up in the app.
- **Location Sharing**: see the other devices on the server and share markers, also with the app in the
  background. Off until you start it.

No account, no advertising, no analytics. The app connects only to the server you set (and its NetBird network, if
the server owner set one up). It also works with other [TileServer GL](https://github.com/maptiler/tileserver-gl)
servers that have vector styles.

Status: version 0.1.0. Needs Android 8.0 or newer. See [what was tested](docs/testing.md).

## Install

From a fieldmap-server: connect the device to the server's local network, open the server in the browser, go
to **Admin** › **Devices** and press **Download App** under **Android App**. No app store or account needed.

There is no published release yet, so for now the server offers the app only if its owner added a build. To build it
yourself, see [building](docs/building.md), then install with `adb install app/build/outputs/apk/debug/app-debug.apk`
or copy the `.apk` to the device and open it.

## First steps

1. On the server's local network, open the app and press **Find Server** (or type the **Server Address** and
   press **Connect**).
2. Go to **Menu** › **Offline Areas** › **Download Area**, frame the area you need, and press **Download**.
3. Press **Follow** to show your position. Touch and hold the map to add a marker.

## Documentation

- [User guide](docs/user-guide.md): connect, offline areas, offline use, markers, pairing, location sharing, NetBird,
  permissions, troubleshooting.
- [Building](docs/building.md): build from source, the NetBird library, release signing, code layout.
- [What was tested](docs/testing.md).
- The server: [fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server) and its user guide.

## Not built yet

- Sharing that starts by itself after a restart (needs "all the time" location permission).
- Downloads in the background.
- Groups (today one server is one group), and messages between devices.
- TAK compatibility (CoT): ATAK and other TAK apps can't join the server's location sharing.

## Attribution and licenses

- Map data: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL); tile schema:
  [OpenMapTiles](https://openmaptiles.org/). The app shows this line on the map.
- Map drawing and offline storage: [MapLibre Native](https://github.com/maplibre/maplibre-native) (BSD 2-Clause)
- AndroidX libraries, Kotlin coroutines and [OkHttp](https://github.com/square/okhttp) (Apache License 2.0)
- The NetBird client: [NetBird](https://github.com/netbirdio/netbird) (BSD 3-Clause; the app uses only the client
  part, and no part of NetBird's servers, which have a different license). The library that the build makes
  from it also contains the Go packages that the client needs. The largest are
  [wireguard-go](https://git.zx2c4.com/wireguard-go) (MIT), [gVisor](https://gvisor.dev) (Apache License 2.0),
  [Pion](https://github.com/pion) (MIT) and [gRPC-Go](https://github.com/grpc/grpc-go) (Apache License 2.0).
  [nblink/LICENSES.md](nblink/LICENSES.md) lists all of them with their licenses.

The code in this repository is under the MIT license. See [LICENSE](LICENSE).
