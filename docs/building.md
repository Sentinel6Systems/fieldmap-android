# Building Fieldmap for Android

How to build the app from source, and how its parts work. For using the app, see the [user guide](user-guide.md).

You need these four things:

- a JDK 17
- the Android SDK with platform 35
- the Android NDK 28.2.13676358. Install it with `sdkmanager "ndk;28.2.13676358"`, or with the SDK Manager of
  Android Studio (tab **SDK Tools**, tick **Show Package Details**).
- Go 1.26.10. Download it from <https://go.dev/dl/> and unpack it into the folder `~/sdk/go1.26.10`. You do not
  need to install it for the full computer. If your Go is in a different folder, set `FIELDMAP_GO` to that folder.

With Android Studio, open the folder. From a shell:

```bash
git clone https://github.com/Sentinel6Systems/fieldmap-android.git
cd fieldmap-android
echo "sdk.dir=<path to your Android SDK>" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # the unit tests
```

The first build downloads the source code of NetBird and of the other Go packages (about 1 GB in `~/go`) and
takes some minutes.

## The NetBird library

The app has its own NetBird client. This client is NetBird's Go client, built into a library for Android
(`app/libs/nblink.aar`). The repository does not contain the built library. Gradle builds it from source with
the script `nblink/build.sh`. Gradle builds it again only when a file in `nblink/` changes. You can also run the
script by hand:

```bash
./nblink/build.sh
```

All versions are fixed, so that each build uses the same source code:

| What | Version | Where it is fixed |
|---|---|---|
| NetBird | v0.83.1 | `nblink/go.mod` |
| All other Go packages | the versions that NetBird v0.83.1 uses | `nblink/go.mod`, checked with `nblink/go.sum` |
| gomobile (makes the Android binding) | the version that NetBird v0.83.1 uses | `nblink/go.mod` |
| Go | 1.26.10 | the `toolchain` line of `nblink/go.mod`. The script stops if your Go is a different version. |
| Android NDK | 28.2.13676358 | `nblink/build.sh` |

Two builds on one computer gave the same library, byte for byte.

The build changes one file of NetBird: `nblink/_patch/discover_pion.go` takes the place of NetBird's file of
the same name. The reason: an app on Android 11 or newer cannot get the list of network connections of the
device in the way that NetBird asks for it. NetBird then sends all data through a relay server. With the
changed file, the device and the server can also connect directly. The change is a few lines. The script makes it
in a copy of NetBird's source code (in `/tmp/fieldmap-nblink`, removed after the build). The script stops if
NetBird's file is not the one that the change was made for. To build with NetBird's source code as it is,
remove the line in `nblink/build.sh` that copies the file. The app then connects through the relay only.

To use a newer NetBird, change its version in `nblink/go.mod`, copy the `toolchain` line and the `replace` lines
from the `go.mod` of that NetBird version, and run `go mod tidy` in `nblink/`. Then test the app against a
NetBird network before you release it.

The library is large: about 38 MB for each processor type. The APK has the libraries of three processor types
(64-bit ARM, 32-bit ARM, and x86-64 for emulators) in compressed form. Android unpacks only the library of the
device.

## Release builds

A release build is signed with your own key, which never goes into the repository. Keep the keystore and a
small properties file somewhere outside it:

```properties
storeFile=<path to your keystore>
storePassword=<PASSWORD>
keyAlias=<key alias>
keyPassword=<PASSWORD>
```

```bash
./gradlew assembleRelease -Pfieldmap.signing=<path to that properties file>
```

Without `-Pfieldmap.signing` the release build is left unsigned.


## How it works

### Offline areas

- A offline area has all zoom levels from the whole world down to the zoom that you choose (11 to 14). Zoom 14 is the
  deepest level of the vector tiles of the server. The styles show some layers only closer in (zoom 15 to 18), but
  they draw them from the zoom 14 tiles. Thus zoom 14 has all the map features. At zoom 11, 12 or 13, the map
  shows less detail when you zoom in past the chosen zoom.
- The app saves hillshade as deep as the server has it, to a maximum of the chosen zoom. The app does not save the
  1 m hillshade above zoom 14 (`hillshade-detail` of fieldmap-server).
- The size before a download is an estimate. The app counts the tiles and measures some of them on the server.
  Towns have more data than empty land, so the real size is a little different.
- The app does not start a download that leaves less than 500 MB free on the device. It also does not start a
  download of more than 200,000 tiles. Choose a lower zoom, or save a large area as two or more offline areas. Above 1 GB, the app tells you
  that the download is large.
- The app also keeps the maps that you only looked at, to a maximum of 200 MB. It removes the oldest first.
  Thus the area around the maps that you looked at on the local network often works without a connection. Do not
  rely on this. Save the offline areas that you need.
- **Update All** downloads all offline areas again. Use it after a new build of the maps on the server. You
  cannot update one offline area only, because the offline areas share their files.
- When you delete a offline area, the app removes its maps from the device. It also removes the maps that you only
  looked at.
- Each offline area keeps the address of the server. If the server gets a different address, the offline areas still work. The
  app saves new offline areas from the new address.
- The download runs while the app is open. Downloads do not run in the background yet.

### Pairing

The app makes a key in the key store of the device. Nothing can read the key out of the key store.
The server gives the app a certificate for this key and the addresses of the server on the internet. Outside the server's
network, the app tries these addresses. It tries the last address that worked first. The app trusts only the
certificate of the server. When the device is on the local network again, the app gets the new addresses. The server
keeps the make and the model of the device, so that the owner can see which device is which.

### NetBird

What it costs:

- **Time.** The app needs some seconds more to connect: it must join the NetBird network first. In the test,
  this took 1 to 4 seconds.
- **Battery.** While the app is connected through NetBird, the NetBird client keeps a connection open and sends
  small messages all the time. This uses more battery than a direct address. The cost on a real device is not
  measured yet. The app runs the client only while it is the way to the server.
- **Data.** With no map and no positions on the way, the client used about 1 MB in one hour in the test. Map data
  and positions need about 5 % more data than on a direct address.
- **Speed.** The device and the server try to connect to each other directly. Some internet connections do not
  permit this. Then all data goes through a relay server of the NetBird network, and the map loads slower. The
  data stays encrypted on this way too.
- **Memory and storage.** The client uses about 55 MB of memory while it runs. The app is about 65 MB larger on
  the device than a version with no NetBird client.

Save your offline areas on the local network, not through NetBird, if you can. A large offline area is a large download.

What stays the same: NetBird is only the road to the server. The app talks to the same gateway of the server and does
the same check of certificates as on a direct address. A member of the NetBird network without a certificate
from the server gets nothing from the server. Maps, positions and markers go through NetBird encrypted. NetBird cannot
read them.

What the NetBird network knows about the device: its name ("Fieldmap" with the make and model), its Android
version, its internet address, and the times when it is connected. The owner of the server sees the device in the
list of members on the NetBird dashboard.

What the app keeps on the device: the setup key, encrypted with a key in the key store of the device, and the
device's own NetBird key in the private storage of the app. The app never shows the setup key and never writes
it to a log. **Unpair this device** deletes both. The app also deletes both when the owner removes the device
on the server. When the owner switches off NetBird on the server, the app deletes the setup key and keeps the device's
own NetBird key. When the owner switches NetBird on again on the same NetBird network, the device is the same
member as before, and the dashboard shows no new member. On a different NetBird network, the app deletes the old
key and the device joins as a new member.

This is not a VPN of the system. Android does not ask for a VPN permission and shows no key icon. The NetBird
client is a part of the app. Only the requests of the app to the server use it. A different VPN on the device
continues to work.

If the owner removes a device on the **Devices** page of the server, the device is still a member of the NetBird
network. It gets nothing from the server, because its certificate is not valid any more. To remove it from the
NetBird network also, the owner deletes it on the NetBird dashboard.

## Layout of the code

| Path | What it is |
|---|---|
| `app/src/main/java/.../ui/` | The one screen (`MainActivity`), its panels (`Panels`), the rounds of live sharing (`Sharing`), and the pieces they are built from (`Ui`, `Views`) |
| `.../server/` | Talking to the tile server: its styles, its tile sets, finding it on the network, and which way requests travel (`Http`) |
| `.../remote/` | Pairing: the device's key, being let in by the server, its addresses, the connection to its gateway. NetBird as a second way: what the server says about it and the rules for the order of the ways (`NetBird`), the client and its start and stop (`NetBirdLink`), the stored setup key (`Secret`) |
| `nblink/` | The NetBird client of the app: a small Go package around NetBird's own client, and the script that builds it for Android |
| `.../offline/` | Offline areas on top of MapLibre's offline regions; counting tiles and estimating sizes |
| `.../data/` | Markers and their file format |
| `.../share/` | Live sharing: what is sent to and read from the server's `/mapshare/` service, bringing shared markers together (last change wins), how a position ages, and the service that keeps the position shared in the background (`ShareService`) |
| `.../geo/` | UTM and MGRS; the GPS source |
| `app/src/main/res/values*/colors.xml` | The colors of fieldmap-server's `ui/fieldmap.css`, light and dark |
| `app/src/test/` | Unit tests |

## Shared with fieldmap-server

Both projects are by the same authors and under the same license. The app takes from the server project: the
colors and control shapes of `ui/fieldmap.css`, the mark in the header (`ui/nav.js`), the marker palette
(`print/src/palette.js`), the UTM/MGRS formulas (`print/src/utm.js`, rewritten in Kotlin and checked against
it), and the drawings file format (`print/src/drawings.js`).

The way the position button steps through follow modes, and saving a framed area after seeing its size, are
common in offline map apps (OsmAnd, Organic Maps and others were looked at for how they behave). No code or
artwork was taken from them.

