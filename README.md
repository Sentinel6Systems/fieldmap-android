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
- **Away from home, while there is internet**: pair the phone with the box once, at home, with one button. From
  then on the app reaches the box from anywhere, through the box's own router, over an encrypted link that only
  paired phones can use. No account, nothing of anyone else's in between.
- **Sharing with the other phones of the box**: switch it on and the others see where you are, by the name you
  give; tick a marker and everyone has it. Off until you switch it on, and only between phones that reach the
  same box. No account for this either.
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
| **Menu** | Map style, saved areas, markers, sharing, server. |

**To mark a spot, press and hold it on the map for two seconds.** A ring fills around your finger; when it is
full the marker is there and its panel opens. It gets the next name (P1, P2, ...) and the colour you used last;
change them in the panel, or just close it. To mark where you are, hold the blue dot: the marker is put at your
position exactly.

Tap a marker to rename it, change its colour, move it to the crosshair, delete it, or read its grid reference
and how far and in which direction it is from you. The screen stays on while the map follows you.

### Away from home: pairing

A fieldmap-server box lets phones that were paired on its own network use its maps from anywhere (its README,
"Remote access for the Android app"). It is off until the box's owner switches it on. At home, on the box's
network:

1. On the box's **Phones** page (a tab in the header of its Maps page), press **Switch on**, or, if it is already
   on, **Pair a phone**. Either opens pairing for ten minutes.
2. In the app: **Menu › Server › Pair this phone**.

That is all. Away from the box's network the strip at the top reads **Remote** instead of **Connected**, and the
map loads as at home, only slower. With no internet it reads **Offline** and the saved areas work as before.

What happens: the app makes a key in the phone's key store, where it cannot be read out again, and the box gives
it a certificate for that key, the addresses it can be reached at from outside, and the name in its gateway's
certificate. Away from home the app tries those addresses, the one that answered last first, and trusts only the
box's own certificate there. Whenever the phone is home again it picks up any new address. The box is told the
phone's make and model, so that its owner can tell the phones on its list apart.

On the box's Phones page its owner can remove a phone (the app then says so the next time it tries from outside,
and it can be paired again at home), or switch remote access off altogether (the app says so, and stays paired
for when it is switched on again). **Unpair this phone** in the app forgets the pairing on the phone.

Whether the box can be reached from outside depends on its router and internet provider; the Server panel shows
what the box says about it. This is not a system VPN: nothing else on the phone uses it, no VPN icon appears, and
another VPN on the phone keeps working.

### Sharing with other phones

Phones that reach the same fieldmap-server box can show each other where they are and keep one set of markers
in common. It goes through the box: at home over its network, away from home through the pairing above. There
is nothing more to sign in to. Everyone who reaches the box sees everything that is shared on it.

Sharing is a feature of the box, and it is off there until the box's owner switches it on, on the box's
**Phones** page. While it is off the app says "Sharing is switched off on the box".

**Your position.** It is not shared until you switch that on: **Menu › Sharing**, type the name the others
should see you by (up to 24 characters), press **Share my position**. The strip at the top then shows a
**Sharing** tag for as long as it is on: amber while your position is getting through, grey while it is not
(the box out of reach, no GPS position yet). **Stop sharing my position**, in the same place, takes you off the
others' maps at once. The setting is remembered: if sharing was on when you closed the app, it is on again when
you open it, and the tag says so.

Your position is sent only while the app is open on the screen. When you close the app for good you are taken
off the others' maps at once; when the app is merely left, or the screen goes off, your last position stays
where it was, turns grey, fades, and is gone after about two minutes.

**The others.** Each is a light blue diamond with the name beside it, so that it cannot be taken for a marker (a
round dot) or for your own position. A diamond that has not been heard from for a while turns grey and fades;
when the box drops the position, it goes from the map. Tap a diamond for the name, how long ago the position was
updated, its grid reference, and how far and in which direction it is from you. **Menu › Sharing** lists
everyone who is there now. You see the others whether or not you share your own position.

**Shared markers.** A marker is on your phone only, until you tick **Share with everyone on this box** in its
panel. Then everyone has it, and anyone can rename, recolour, move or delete it: the last change wins.
Unticking takes it off the box for everyone and keeps it on your phone. Deleting a shared marker deletes it for
everyone (the app asks first). Shared markers are kept on the phone, so they are still on the map with no
network. Changes you make to them out of reach are sent when the box answers again; if somebody else changed the
same marker in the meantime, the later change stays.

What is not there yet: sharing your position with the screen off or the app in the background (it needs a
background service; see "What is next"), groups (one box is one room), messages, and working with TAK apps.

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
| Location | To show where you are. Asked the first time you press Follow. The position stays on the phone, unless you switch on sharing it: then it goes to your box, and from there to the phones that reach it, while the app is open. |
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

Pairing and the remote link were tested on the emulator against fieldmap-server's gateway: pairing refused while
closed and done in one press once opened on the box's Phones page, the switch to **Remote** when the home address
went away, a phone removed on the box being told so, remote access switched off and on again (the phone stays
paired). Not tested yet: through a real home router, and on a real phone.

Sharing was tested on the emulator against fieldmap-server's sharing service, on its home address and through
its gateway, with a second phone played by a script:

- switching the position on (the name, the tag in the strip) and off (gone from the box at once), closing the
  app for good (gone at once), leaving the app (the position stays and ages), opening it again (still on)
- the others on the map and in the list, tapping one, a position turning grey and going after two minutes
- sharing a marker, renaming it (one message to the box after the typing stops), changes and a delete made by
  the other phone arriving, also while the marker's panel was open; taking a marker back; deleting a shared one
- out of reach: a shared marker renamed, one deleted and a new one shared, all sent when the box answered again;
  the same marker changed on both sides, where the later change stayed, either way round
- sharing switched off on the box (the app says so and asks again once a minute) and on again
- all of it again through the gateway, with the home address cut off (the strip reads **Remote**)

Not tested yet for sharing: two real phones, a real box, a link that comes and goes, and many phones at once.

The unit tests cover the grid references (against the print tool's own results), the markers file, address
handling, tile counting, and sharing: what is sent and read, bringing shared markers together (changed since
the last answer, the whole list, removed ones, the last change winning) and how a position ages.

Not tested yet: a box whose Wi-Fi has no internet, a download of several gigabytes, a long drive or hike, and
phones without Google services.

## What is next

Not built yet, in no fixed order:

- **Sharing with the screen off.** Today a position is sent only while the app is open on the screen. Sending it
  from a pocket needs a background service with a lasting notification, and Android's permission for location
  in the background.
- **Downloads in the background**, for the same reason.
- **Groups.** One box is one room: everyone who reaches it sees everyone.
- **Messages** between the phones.
- **TAK compatibility** (CoT). The app and the box talk their own small JSON; ATAK and other TAK apps cannot
  join in.

## Layout of the code

| Path | What it is |
|---|---|
| `app/src/main/java/.../ui/` | The one screen (`MainActivity`), its panels (`Panels`), the rounds of live sharing (`Sharing`), and the pieces they are built from (`Ui`, `Views`) |
| `.../server/` | Talking to the tile server: its styles, its tile sets, finding it on the network, and which way requests travel (`Http`) |
| `.../remote/` | Pairing: the phone's key, being let in by the box, its addresses, the connection to its gateway |
| `.../offline/` | Saved areas on top of MapLibre's offline regions; counting tiles and estimating sizes |
| `.../data/` | Markers and their file format |
| `.../share/` | Live sharing: what is sent to and read from the box's `/mapshare/` service, bringing shared markers together (last change wins), and how a position ages |
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
