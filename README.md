# fieldmap-android

Fieldmap is a small Android map app for the maps of a [fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server)
box. At home, on the box's network, you look at its maps and save the areas you need. Away from it, with no
network at all, the phone shows those areas with your GPS position on them, and you can drop coloured dot
markers on what you find.

It is meant for driving, hiking and scouting: one map, a few large buttons, readable in sunlight and usable
with gloves. It is not a route planner: it shows where you are, it does not give turn-by-turn directions.

- **The maps of your box**: every style the tile server offers (topographic light and dark, OpenStreetMap, ...),
  drawn on the phone from vector tiles, so they stay sharp at any zoom.
- **Saved areas**: frame an area, see about how large the download is, press Download. Everything the map needs
  comes along: the tiles of every layer (base map, contours, hillshade), the style, the fonts and the symbols.
- **Where you are**: the phone's GPS position on the map, with one button to follow it north-up or
  heading-up. The grid reference (MGRS) and latitude/longitude are always at the top.
- **Markers**: hold a finger on a spot for two seconds and it is marked. Name the dot and pick one of twelve
  colours. They are the markers of the box's print tool, and the two can exchange them as a file.
- **Away from home, while there is internet**: a box that offers it lets a phone in with one button press on
  its own network. From then on the app reaches the box from anywhere, over an encrypted link that only phones
  the box has let in can use.
- **Nothing else**: no account, no advertising, no analytics. The app talks only to the server you give it.

It works with any [TileServer GL](https://github.com/maptiler/tileserver-gl) server that offers vector styles;
it was made for fieldmap-server and looks like its pages.

Status: first version, 0.1.0. Tested on an emulator and one phone (see "What was tested"). Needs Android 8.0 or newer.

## Install

There is no published release yet. Build the app from this repository (next section) and install the file it
makes, with a cable:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

or copy the `.apk` file to the phone and open it there (Android asks once whether to allow installing from
that source).

## Build

You need a JDK 17 and the Android SDK (platform 35). With Android Studio, open the folder. From a shell:

```bash
git clone https://github.com/Sentinel6Systems/fieldmap-android.git
cd fieldmap-android
echo "sdk.dir=<path to your Android SDK>" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # the unit tests
```

### Release builds

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

## Use

### At home: connect and save

1. Join the network the box is on (its own Wi-Fi, or your home network).
2. Open the app. The first time it asks for the server: press **Find on this network**, or type the address
   you open the box's Maps page at (for example `192.168.1.10`) and press **Connect**. The app remembers it.
3. Move the map to the area you want. **Menu › Saved areas › Save a new area**: move and zoom until the area is
   inside the frame, give it a name, read the size, press **Download**.
4. Leave the app open until the download has finished (the strip at the top shows "Saving 42%", and the screen
   stays on). If it is interrupted, it carries on the next time the app is open and the box is in reach.

**Menu › Map style** changes the style. An area is saved in the style that is showing. The tiles are stored
once, so saving the same area again in a second style adds only that style's own small files.

### In the field

Open the app. The strip at the top says **Offline** and the map draws from what is saved.

| Button | What it does |
|---|---|
| **Follow** | Shows where you are and keeps the map on you, north up. Press again: the direction you are heading is up (by the direction of travel when driving, by the compass when walking). Drag the map to let go. |
| **+ −** | Zoom. Pinching and double-tapping work too. |
| **Menu** | Map style, saved areas, markers, server. |

**To mark a spot, press and hold it on the map for two seconds.** A ring fills around your finger; when it is
full the marker is there and its panel opens. It gets the next name (P1, P2, ...) and the colour you used last;
change them in the panel, or just close it. To mark where you are, hold the blue dot: the marker is put at your
position exactly.

Tap a marker to rename it, change its colour, move it to the crosshair, delete it, or read its grid reference
and how far and in which direction it is from you. The screen stays on while the map follows you.

### Away from home: the remote link

If the box's owner has set up remote access (fieldmap-server's `scripts/remote.sh`), the Server panel shows
**Away from this network** while you are on the box's network:

1. **Menu › Server › Set up remote access.** One press; if the box asks for a code, type it first.
2. That is all. Away from the box's network the strip at the top reads **Remote** instead of **Connected**, and
   the map loads as it does at home, only slower. With no internet it reads **Offline** and the saved areas work
   as before.

What happens: the app makes a key in the phone's key store, where it cannot be read out again, and the box gives
it a certificate for that key. Away from home the app connects to the box's gateway with it and trusts only the
box's own certificate there. The box is told the phone's make and model, so that its owner can tell the phones
on the list apart. The owner can remove a phone at any time; the app then says so, and it can be set up again
on the box's network. **Remove from this phone** forgets the link on the phone.

This is not a system VPN: nothing else on the phone uses it, no VPN icon appears, and another VPN on the phone
keeps working. The remote link goes through a relay with a bandwidth limit, so save areas at home.

### Markers and the print tool

**Menu › Markers › Save to a file** writes the markers as a GeoJSON file. The print tool of fieldmap-server
reads it (**Load drawings**), and so does other map software (QGIS, geojson.io, TAK tools). **Load from a
file** adds the markers of a file the print tool saved; its arrows and shapes are left out, because the app
shows markers only.

## How saving works, and its limits

- A saved area holds every zoom level from the whole world down to zoom 14, which is as deep as the vector
  tiles go; closer in, the map is drawn from the zoom 14 tiles. Hillshade is saved as deep as the box has it, up
  to zoom 14. A small area of 1 m hillshade above that (fieldmap-server's `hillshade-detail`) is not saved.
- The size shown before a download is an estimate: the app counts the tiles and measures a sample of them on
  the box. Towns are heavier than empty country, so the real size differs somewhat.
- A download that would not leave 500 MB free on the phone is refused, and so is an area of more than 200,000
  tiles (save it as several areas). Above 1 GB the app says that the download is large.
- Maps you only looked at are kept too (up to 200 MB, the oldest dropped first), so the neighbourhood of what
  you browsed at home often works without having been saved. Do not rely on it: save what you need.
- **Update all saved areas** downloads every saved area again, for after the maps on the box were rebuilt.
  (One area cannot be refreshed alone: areas share their files, and a file that is already on the phone counts
  as done.) Deleting an area frees its space, and also drops the maps that were only looked at.
- Saved areas remember the address they came from. If the box gets another address, they still work, and
  new areas are saved from the new address.
- The download runs while the app is open. There is no background service yet.

## Permissions

| Permission | Why |
|---|---|
| Location | To show where you are. Asked the first time you press Follow. The position stays on the phone. |
| Network | To reach the tile server you set. Plain `http` is allowed, because a box on a home network has no certificate. |

A box with its own Wi-Fi often has no internet behind it, and Android then keeps sending the apps' traffic over
mobile data, where the box cannot be found. When the server does not answer the usual way and the phone is on
such a network, the app talks to it over that network directly. Nothing has to be set for this.

**Find on this network** asks each of the up to 254 neighbouring addresses of the phone's Wi-Fi network whether
a tile server answers there (web port, then port 8080). It runs only when you press the button, and only on a
private network.

## What was tested

Version 0.1.0 was tested on an Android 14 emulator against a fieldmap-server tile server (TileServer GL 5.7.0)
with sample data of a small country:

- finding the server on the network and connecting by a typed address; the styles
- saving areas of up to 27,800 tiles, checked in the phone's database: the tile counts matched the app's count
  exactly, and for the larger areas the size was within a quarter of the estimate
- a download cut off by closing the app, which carried on at the next start; deleting; updating all areas
- the map with the network switched off and the server stopped
- the position from a simulated GPS, following, heading-up, upright and on its side, light and dark
- markers, and the markers file read by the code of fieldmap-server's print tool

It was then put on a Google Pixel phone (Android 16) and checked by hand and over adb:

- positions came from the satellite receiver once a second, the first about five seconds after the network's
  rough one; the app let go of the receiver when it left the screen
- following, heading-up by the compass, and marking by holding the map
- connecting to a fieldmap-server box behind Internet-in-a-Box (`/maps/`) over Wi-Fi, and saving an area from it
  in the `topo-usgs` style: the 120 tiles the app counted were the 120 in the phone's database, from all five
  tile sets of the style

The remote link was tested on the emulator against fieldmap-server's gateway reached directly: setting up with
one press, the switch to **Remote** when the home address went away, new tiles fetched with the phone's
certificate and stored under the home address, a phone removed on the box being told so, and setting it up again.
Not tested yet: the same through Tailscale Funnel, and on a real phone.

The unit tests cover the grid references (against the print tool's own results), the markers file, address
handling and tile counting.

Not tested yet: a box whose Wi-Fi has no internet, a download of several gigabytes, a long drive or hike, and
phones without Google services.

## Layout of the code

| Path | What it is |
|---|---|
| `app/src/main/java/.../ui/` | The one screen (`MainActivity`), its panels (`Panels`), and the pieces they are built from (`Ui`, `Views`) |
| `.../server/` | Talking to the tile server: its styles, its tile sets, finding it on the network, and which way requests travel (`Http`) |
| `.../remote/` | The remote link: the phone's key, being let in by the box, the connection to its gateway |
| `.../offline/` | Saved areas on top of MapLibre's offline regions; counting tiles and estimating sizes |
| `.../data/` | Markers and their file format |
| `.../geo/` | UTM and MGRS; the GPS source |
| `app/src/main/res/values*/colors.xml` | The colours of fieldmap-server's `ui/fieldmap.css`, light and dark |
| `app/src/test/` | Unit tests |

## Shared with fieldmap-server

Both projects are by the same authors and under the same license. The app takes from the server project: the
colours and control shapes of `ui/fieldmap.css`, the mark in the header (`ui/nav.js`), the marker palette
(`print/src/palette.js`), the UTM/MGRS formulas (`print/src/utm.js`, rewritten in Kotlin and checked against
it), and the drawings file format (`print/src/drawings.js`).

The way the position button steps through follow modes, and saving a framed area after seeing its size, are
common in offline map apps (OsmAnd, Organic Maps and others were looked at for how they behave). No code or
artwork was taken from them.

## Attribution and licenses

- Map data: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL); tile schema:
  [OpenMapTiles](https://openmaptiles.org/). The app shows this line on the map.
- Map drawing and offline storage: [MapLibre Native](https://github.com/maplibre/maplibre-native) (BSD 2-Clause)
- AndroidX libraries, Kotlin coroutines and [OkHttp](https://github.com/square/okhttp) (Apache License 2.0)

The code in this repository is under the MIT license. See [LICENSE](LICENSE).
