# fieldmap-android

Fieldmap is a small Android map app for the maps of a [fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server)
box. On the box's network, you look at the maps of the box and save the map areas that you need. Without a
connection, your phone or tablet shows these map areas with your GPS position. You can put coloured markers on
the map.

The app is for driving, hiking and scouting. It has one map and a few large buttons. You can read it in sunlight
and use it with gloves. It shows your position. It does not give turn-by-turn directions.

- **The maps of your box**: all styles of the box, for example topographic light and dark, and OpenStreetMap.
  The device draws the maps from vector tiles, so they stay sharp at all zoom levels.
- **Map areas**: put an area in the frame, choose the detail (zoom 11 to 14), read the number of tiles and the
  size of the download, and press **Download**. A bar shows the progress. A map area has all the map data: the
  tiles of each layer, the style, the fonts and the symbols.
- **Your position**: the GPS position of the device on the map. One button follows your position, with north up
  or with your direction up. The top of the screen always shows your GPS position and its accuracy. Choose
  the format in **Menu › Settings**: MGRS grid, decimal degrees, or degrees, minutes and seconds.
- **Markers**: touch and hold the map for 2 seconds to add a marker. Give it a name and one of 12
  colours. The **Print a map** page of the box uses the same markers. A file moves them between the two.
- **Remote access**: pair the device with the box one time, on the box's network. After that, the app connects to
  the box through the internet when the device is not on the box's network. The connection is encrypted, and only paired devices can
  use it. There is no account and no third party.
- **Sharing**: when you switch it on, the other devices on the box see your position with your name. This
  continues when the device is in your pocket. Tick a marker to share it with all devices. Sharing is off until
  you switch it on. There is no account.
- **Nothing more**: no account, no advertising, no analytics. The app connects only to the box that you set.

The app works with all [TileServer GL](https://github.com/maptiler/tileserver-gl) servers that have vector
styles. It was made for fieldmap-server and looks like its pages.

Status: first version, 0.1.0. Tested on an emulator and on two phones (see "What was tested"). It needs Android
8.0 or newer.

## Install

There is no published release yet. Build the app from this repository (next section) and install the file it
makes, with a cable:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

or copy the `.apk` file to the device and open it there (Android asks once whether to allow installing from
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

### On the box's network: connect and save

1. Connect the device to the box's network. This is the Wi-Fi of the box, or the network at home.
2. Open the app. The first time, the app shows the **Box** panel.
3. Press **Find the box**. Or type the address of the box, for example `192.168.1.10`, and press **Connect**.
   The app keeps the address.
4. Move the map to the area that you want.
5. Go to **Menu › Map areas › Save a map area**.
6. Move and zoom the map until the area is in the frame.
7. Give the map area a name.
8. Under **Detail**, choose a number from 11 to 14. These are zoom levels. 14 has all the details of the map. Each
   number less has about a quarter of the tiles. The app keeps your choice for the next map area.
9. Read the number of tiles and the size, and press **Download**.
10. Keep the app open until the download is complete. The top of the screen shows the progress (42%), and a
    bar under it shows the progress too. The screen stays on during the download.

If the download stops, it continues the next time that the app is open on the box's network.

**Menu › Map style** changes the style. The app saves a map area in the style that shows on the screen. The
app keeps each tile only one time. When you save the same area in a second style, the app adds only the small
files of that style.

### Without a connection

Open the app. The top of the screen shows **Offline**. The map shows the map areas on the device.

| Button | What it does |
|---|---|
| **Follow** | Shows your position and keeps the map on it, with north up. Press again to put your direction up. When you drive, the app uses the direction of travel. When you walk, it uses the compass. Move the map to stop. |
| **+ −** | Zoom. You can also pinch or double-tap. |
| **Menu** | Map style, map areas, markers, sharing, box, settings, and **Close Fieldmap**. |

**To add a marker, touch and hold the map for 2 seconds.** A ring fills around your finger. When the
ring is full, the app adds the marker and opens its panel. The marker gets the next name (P1, P2, ...) and the
last colour that you used. You can change them in the panel. To add a marker at your position, touch and hold
the blue dot.

Tap a marker to see its panel. In the panel you can change the name and the colour, and delete the marker. The
panel also shows the position in the format of **Menu › Settings**, the distance and the bearing. The screen stays on when the map follows your
position.

**To move a marker, press Move in its panel. Then tap the new place on the map.** You can move and zoom the
map before you tap. Tap the blue dot to move the marker to your position. To keep the marker where it is, press
the Back arrow.

### Remote access: pairing

A fieldmap-server box can let paired devices use its maps from outside. The README of the box tells more in
"Remote access for the Android app". Remote access is off until the owner of the box switches it on. Do these
steps on the box's network:

1. On the pages of the box, open **Admin › Remote access** and press **Switch on**. If remote access is on
   already, open **Devices** and press **Pair a device**. Pairing then opens for 10 minutes.
2. In the app, go to **Menu › Box** and press **Pair this device**.

When the device is not on the box's network, the app connects through the internet. The top of the screen shows
**Connected**, as on the box's network. **Menu › Box** shows "connected through the internet". The map loads as on
the box's network, but slower. Without the internet, the top shows **Offline**, and the map areas work as before.

How it works: the app makes a key in the key store of the device. Nothing can read the key out of the key store.
The box gives the app a certificate for this key and the addresses of the box on the internet. Outside the box's
network, the app tries these addresses. It tries the last address that worked first. The app trusts only the
certificate of the box. When the device is on the box's network again, the app gets the new addresses. The box
keeps the make and the model of the device, so that the owner can see which device is which.

The owner can remove a device on the **Devices** page of the box. The app then tells you this the next time that
it tries to connect from outside. You can pair the device again on the box's network. The owner can also switch
off remote access under **Admin › Remote access**. The app tells you this, and the device stays paired.
**Unpair this device** in the app removes the pairing from the device.

The router and the internet provider of the box decide if the box can be reached from outside. The **Box** panel
shows what the box says about it. This is not a VPN of the system. No other app on the device uses it, there is
no VPN icon, and a different VPN on the device continues to work.

### Sharing with other devices

Devices that connect to the same box can see the positions of the other devices. They can also share markers.
Sharing goes through the box: on the box's network, or from outside with pairing. There is no additional sign-in.
All devices that connect to the box see all that is shared on it.

Sharing is a function of the box. It is off until the owner of the box switches it on, on the **Devices** page of
the box. When it is off, the app shows "Sharing is off on the box".

**Your position.** The app does not share your position until you switch it on:

1. Go to **Menu › Sharing**.
2. Type the name that the other devices show for you. The name can have 24 characters.
3. Press **Share my position**.

The top of the screen then shows a **Sharing** tag. The tag is amber when your position gets to the box. It is
grey when your position does not get to the box, for example without a connection or without a GPS position.
**Stop sharing my position** removes your position from the maps of the other devices immediately. The app
keeps this setting. If sharing was on when you closed the app, it is on again when you open the app.

Sharing continues when you leave the app or when the screen goes off. A notification shows this
("Fieldmap shares your position"). It has a **Stop sharing** button. When sharing is on, the Back button on the
map leaves the app, as the Home button does. It does not close the app.

Sharing stops when you close the app with **Menu › Close Fieldmap**, or when you swipe the app away in the list
of recent apps. This also removes your position from the maps of the other devices immediately. **Stop sharing
my position**, in the **Sharing** panel or in the notification, stops sharing and keeps the app open.

**The battery.** When the app is on the screen, it sends your position every few seconds. When the app is in the
background, it does as little as possible:

- When you move, it sends a position approximately three times a minute. When you do not move, it sends one
  position each minute. Between the positions, Android switches off the GPS receiver.
- When you do not move, the motion sensor of the device tells the app when you move again. This sensor uses
  almost no power.
- It only sends your position. It does not get the other positions or the markers until you look at the map
  again.
- It keeps the device awake only for the few seconds that one message needs.

The other devices show a device that does not move as current for 90 seconds. They show a device that moves as
current for 30 seconds. After that time, its symbol turns grey. Android can stop the sharing itself, for example
when the device has too little memory, or after many hours without movement. Your position then turns grey on
the maps of the other devices, and goes after two minutes. Sharing starts again when you open the app.

**The other devices.** Each other device is a light blue diamond with its name. A marker is a round dot, and your
own position is the blue dot with the arrow. When a device does not send for some time, its diamond turns grey
and fades. When the box removes the position, the diamond goes from the map. Tap a diamond to see the name, the
time of the last update, the grid reference, the distance and the bearing. **Menu › Sharing** shows a list of
the other devices. You see the other devices also when you do not share your own position.

**Shared markers.** A marker is only on your device until you tick **Share with the other devices** in its
panel. Then all devices have it. All devices can change the name, the colour and the position, or delete it.
The last change stays. Untick it to remove the marker from the other devices and keep it on your device.
When you delete a shared marker, the app deletes it from all devices. The app asks first. The device keeps the
shared markers, so they show also without a connection. When you change a shared marker without a connection,
the app sends the change when the connection comes back. If a different device changed the same marker in the
meantime, the later change stays.

Not available yet (see "What is next"): groups (one box is one group), messages, and TAK apps.

### Markers in a file

**Menu › Markers › Save to a file** writes the markers to a GeoJSON file. The **Print a map** page of the box can
open this file with **Load drawings**. Other map software can also open it, for example QGIS, geojson.io and TAK
tools. **Add from a file** adds the markers of a file. The app does not show arrows and shapes from the file. It
shows only markers.

## Map areas: how they work, and their limits

- A map area has all zoom levels from the whole world down to the zoom that you choose (11 to 14). Zoom 14 is the
  deepest level of the vector tiles of the box. The styles show some layers only closer in (zoom 15 to 18), but
  they draw them from the zoom 14 tiles. Thus zoom 14 has all the map features. At zoom 11, 12 or 13, the map
  shows less detail when you zoom in past the chosen zoom.
- The app saves hillshade as deep as the box has it, to a maximum of the chosen zoom. The app does not save the
  1 m hillshade above zoom 14 (`hillshade-detail` of fieldmap-server).
- The size before a download is an estimate. The app counts the tiles and measures some of them on the box.
  Towns have more data than empty land, so the real size is a little different.
- The app does not start a download that leaves less than 500 MB free on the device. It also does not start a
  download of more than 200,000 tiles. Choose a lower zoom, or save a large area as two or more map areas. Above 1 GB, the app tells you
  that the download is large.
- The app also keeps the maps that you only looked at, to a maximum of 200 MB. It removes the oldest first.
  Thus the area around the maps that you looked at on the box's network often works without a connection. Do not
  rely on this. Save the map areas that you need.
- **Update all map areas** downloads all map areas again. Use it after a new build of the maps on the box. You
  cannot update one map area only, because the map areas share their files.
- When you delete a map area, the app removes its maps from the device. It also removes the maps that you only
  looked at.
- Each map area keeps the address of the box. If the box gets a different address, the map areas still work. The
  app saves new map areas from the new address.
- The download runs while the app is open. Downloads do not run in the background yet.

## Permissions

| Permission | Why |
|---|---|
| Location | To show your position. The app asks the first time that you press **Follow**. Your position stays on the device until you switch on sharing. Then the app sends it to your box, and the box shows it to the devices that connect to it, until you stop sharing or close the app. The app asks only for "while the app is in use". It does not ask for "all the time". |
| Notifications | For the notification that shows that the app shares your position. The app asks when you switch on sharing, and when you open the app with sharing on, until you allow it (Android 13 and newer). Sharing works also without it. Android then shows it only in the list of running apps. |
| Run in the background, with location | To continue sharing when the app is not on the screen. Only when sharing is on. |
| Keep the device awake | For the few seconds that one position needs to go to the box from the background. |
| Network | To connect to the box that you set. The app can use plain `http`, because a box on a home network has no certificate. |

A box with its own Wi-Fi often has no internet. Android then sends the traffic of the apps over mobile data, and
the box is not there. When the box does not answer, and the device is on such a network, the app connects to the
box over that network directly. You do not have to set anything for this.

**Find the box** asks each of the up to 254 neighbouring addresses of the Wi-Fi network if a box answers there.
It asks on the web port first, then on port 8080. It runs only when you press the button, and only on a private
network.

## What was tested

Version 0.1.0 was tested on an Android 14 emulator against a fieldmap-server tile server (TileServer GL 5.7.0)
with sample data of a small country:

- finding the server on the network and connecting by a typed address; the styles
- saving areas of up to 27,800 tiles, checked in the device's database: the tile counts matched the app's count
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
  in the `topo-usgs` style: the 120 tiles the app counted were the 120 in the device's database, from all five
  tile sets of the style

Pairing and the remote link were tested on the emulator against fieldmap-server's gateway: pairing refused while
closed and done in one press once opened on the box's Devices page, the switch to the internet (the strip then read **Outside**, now **Connected**) when the home address
went away, a device removed on the box being told so, remote access switched off and on again (the device stays
paired). The remote link was later confirmed by hand from outside, through a home router, with the Pixel and a
box behind Internet-in-a-Box.

Sharing was tested on the emulator against fieldmap-server's sharing service, on its home address and through
its gateway, with a second device played by a script:

- switching the position on (the name, the tag in the strip) and off (gone from the box at once), closing the
  app for good (gone at once), leaving the app (the position stays and ages), opening it again (still on)
- the others on the map and in the list, tapping one, a position turning grey and going after two minutes
- sharing a marker, renaming it (one message to the box after the typing stops), changes and a delete made by
  the other device arriving, also while the marker's panel was open; taking a marker back; deleting a shared one
- out of reach: a shared marker renamed, one deleted and a new one shared, all sent when the box answered again;
  the same marker changed on both sides, where the later change stayed, either way round
- sharing switched off on the box (the app says so and asks again once a minute) and on again
- all of it again through the gateway, with the home address cut off (the strip then read **Outside**, now **Connected**)

Sharing was then tried by hand with two phones (a Google Pixel on Android 16 and a rugged Android 13 phone) on a
fieldmap-server box behind Internet-in-a-Box, on the box's network: each showed the other, and a marker shared on
one arrived on the other.

Sharing in the background was tested on the emulator:

- the pace by the box's log: every 20 seconds after leaving the app or switching the screen off, every 60
  seconds after a minute in one place, back to 20 when the position jumped, and the app's own pace again once it
  was back on the screen; no questions to the box from the background, only positions
- Back on the map leaving the app with sharing going on; swiping the app away, **Close Fieldmap** and **Stop
  sharing** on the notification each taking the position off the box at once, with nothing left asking for
  positions afterwards
- the notification, and Android's question about notifications when sharing is switched on
- in the background, the home address going away (sharing moved to the gateway by itself) and coming back
  while the gateway did not answer (it moved home again)
- on another device's map: a device at rest still shown as live after 48 seconds without news, one on the move
  shown grey

Not tested yet for sharing: the background on a real device (the battery it really costs, the motion sensor, a
night with the device lying still, the device makers' own battery savers), a link that comes and goes, and many
devices at once.

The unit tests cover the grid references (against the print tool's own results), the markers file, address
handling, tile counting, and sharing: what is sent and read, bringing shared markers together (changed since
the last answer, the whole list, removed ones, the last change winning), how a position ages, the pace in the
background and telling rest from movement.

Not tested yet: a box whose Wi-Fi has no internet, a download of several gigabytes, a long drive or hike, and
devices without Google services.

## What is next

Not built yet, in no fixed order:

- **Sharing that starts by itself** after the device was restarted, or after Android ended it. Today you open the
  app once. Starting from nothing needs Android's permission for location "all the time".
- **A position that says when the next one is due.** Today the others tell a resting device by the speed it
  reports; the box's interface could carry it outright.
- **Downloads in the background.**
- **Groups.** One box is one room: everyone who reaches it sees everyone.
- **Messages** between the devices.
- **TAK compatibility** (CoT). The app and the box talk their own small JSON; ATAK and other TAK apps cannot
  join in.

## Layout of the code

| Path | What it is |
|---|---|
| `app/src/main/java/.../ui/` | The one screen (`MainActivity`), its panels (`Panels`), the rounds of live sharing (`Sharing`), and the pieces they are built from (`Ui`, `Views`) |
| `.../server/` | Talking to the tile server: its styles, its tile sets, finding it on the network, and which way requests travel (`Http`) |
| `.../remote/` | Pairing: the device's key, being let in by the box, its addresses, the connection to its gateway |
| `.../offline/` | Map areas on top of MapLibre's offline regions; counting tiles and estimating sizes |
| `.../data/` | Markers and their file format |
| `.../share/` | Live sharing: what is sent to and read from the box's `/mapshare/` service, bringing shared markers together (last change wins), how a position ages, and the service that keeps the position shared in the background (`ShareService`) |
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
