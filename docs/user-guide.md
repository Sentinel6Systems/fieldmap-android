# Fieldmap user guide

Fieldmap shows the maps of a [fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server) on an Android
phone or tablet, and keeps offline areas on the device for use with no network. In this guide, the **server** is
the fieldmap-server machine, and the **local network** is the Wi-Fi or home network the server is on.

## Contents

1. [Install the app](#install-the-app)
2. [Connect to the server](#connect-to-the-server)
3. [Download offline areas](#download-offline-areas)
4. [Use the map offline](#use-the-map-offline)
5. [Markers and arrows](#markers-and-arrows)
6. [Routes](#routes)
7. [Track recording](#track-recording)
8. [Remote access: pair the device](#remote-access-pair-the-device)
9. [Location sharing](#location-sharing)
10. [Messages](#messages)
11. [Mesh radio](#mesh-radio)
12. [NetBird VPN](#netbird-vpn)
13. [Permissions and privacy](#permissions-and-privacy)
14. [Troubleshooting](#troubleshooting)

## Install the app

The easiest way is from the server, with no app store or account:

1. Connect the device to the local network.
2. In the device's browser, open the server and go to **Admin** › **Devices**.
3. Under **Android App**, press **Download App** and open the file. Android asks once whether the browser may
   install apps: allow it.

To update, download and install it again over the old one. Your offline areas and markers stay. You can also
[build the app yourself](building.md). Fieldmap needs Android 8.0 or newer.

## Connect to the server

1. Connect the device to the local network.
2. Open the app. The first time, it opens the **Server** panel (later: **Menu** › **Server**).
3. Press **Find Server**, or type the **Server Address** (for example `192.168.1.10`) and press **Connect**.

The app remembers the server. **Menu** › **Map Style** changes the style.

## Download offline areas

An offline area is part of the map that the app keeps on the device. Away from the server, the app shows your
offline areas. Download the areas you need before you leave the local network.

1. Move the map to the area you want.
2. Go to **Menu** › **Offline Areas** › **Download Area**.
3. Move and zoom until the area is in the frame, and enter an **Area Name**.
4. Choose a **Detail Level** from 11 to 14. Level 14 has every detail of the map; each level lower has about a
   quarter of the tiles.
5. Check the number of tiles and the download size, and press **Download**.

Keep the app open until the download finishes; the bar at the top shows progress and the screen stays on. If the
download stops, it carries on the next time the app is open on the local network.

The app downloads the area in the style on screen. Downloading the same area in a second style only adds that
style's small files, because tiles are shared.

The download includes the server's routing data for the area, for [routes](#routes) along roads and trails
offline. The size estimate shows it separately.

**Downloaded Areas** lists what is on the device. **Update All** downloads every area again; use it after the
server gets new maps. **Delete** removes an area and frees its space.

Limits: the app won't start a download that leaves less than 500 MB free, or one of more than 200,000 tiles (choose
a lower detail level or split the area). The size shown is an estimate. The app also keeps up to 200 MB of maps you
only looked at, but don't rely on that: download the areas you need.

## Use the map offline

With no connection, the top of the screen shows **Offline** and the map shows your offline areas. The top strip
always shows your GPS position and accuracy (for example **GPS ±5 m**), or **Acquiring GPS…** until the first fix.

| Button | What it does |
|---|---|
| **Follow** | Shows your location and keeps the map on it, **North Up**. Press again for **Heading Up** (direction of travel when driving, compass when walking). Move the map to stop following. |
| **+** / **−** | Zoom. You can also pinch or double-tap. |
| **Menu** | Map Style, Offline Areas, Markers, Track Recording, Location Sharing, Messages, Mesh Radio, Server, Settings, and **Exit**. |

**Menu** › **Settings** › **Coordinate Format** sets how positions are shown: **MGRS**, **UTM**, **Decimal Degrees
(DD)** or **Degrees Minutes Seconds (DMS)**.

## Markers and arrows

- **Add a marker:** touch and hold the map for 2 seconds, until the ring around your finger fills. To mark your own
  location, touch and hold the blue dot.
- **Draw an arrow:** touch and hold for 2 seconds, keep holding and drag, then lift your finger where the arrow
  should point. Its panel shows **Length and Bearing**, **Start** and **Head**; **Move Start** and **Move End**
  move its ends.
- **Edit an item:** tap it. The panel lets you change the **Name** and **Color** (12 colors) and shows its
  **Location**, distance and bearing. **Delete** removes it.
- **Move a marker:** press **Move** in its panel, then tap the new place (or tap the blue dot for your location).
  Back keeps it where it was.
- **Import / Export:** **Menu** › **Markers** › **Export** writes a GeoJSON file. The server's map can import it
  with **Import Drawings**, and so can QGIS and other map software. **Import** reads markers, arrows and routes
  from a file (shapes are skipped).

## Routes

A route is a line through up to 30 points, along roads and trails or straight between the points. The app works
it out on the device, so it also works offline.

1. Go to **Menu** › **Markers** › **New Route**.
2. Tap the map to add each point, in order. Drag a point to move it. Drag the dot between two points to add a point
   there. Tap a point to select it, then **Remove Point** to take it out. **Undo** reverses the last change.
3. Tap **Route Type** to choose **Driving**, **Walking** or **Straight Line**.
4. Press **Done**.

Tap the route to see its panel: **Route Type**, **Distance and Time** (set the **Speed** for the time estimate),
**Legs** with each leg's distance and bearing, **Start** and **End**. **Edit Route** changes it again; **Delete**
removes it. Leaving the editor without **Done** asks **Discard Changes?**.

Legs along roads and trails are solid. Points stay where you put them: a point off a road or trail gets a line
along the road to the nearest place the route can reach, then a dashed straight line to the point. The panel counts
the legs that end more than about 100 m off the road. A leg the app can't work out is straight and dashed, and the
panel says why: no route was found there, or the device has no routing data for the area. Routes look the same on
the server's map in the browser.

**Routing data** comes with your offline areas: when you download an area, the app also downloads the server's
routing data for it. **Menu** › **Offline Areas** shows its state under **Routing Data**. The server owner has to
build the **Routing Data** layer on the server first.

> **Tip: use more points, closer together.** The app works out each leg between two points on its own, with a time
> limit of 30 seconds per leg. A short leg is found quickly and follows the road you mean. A very long leg has far
> more roads to search: it can run out of time and come out as a dashed straight line, or take a road you didn't
> intend. Put a point at each turn you care about and every few miles along the way, rather than a few points far
> apart. The route is then quicker to work out, easier to fix by moving or adding one point, and comes out the same
> when another device recreates it from the shared points.

**Menu** › **Settings** › **Routes** chooses what a route shows: **Direction Indicators**, **Leg Distances on
Map**, **Total Distance and Time** and **Leg Details**.

To share a route, tick **Share with All Devices** in its panel (see [Location sharing](#location-sharing)).

## Track recording

A track is the path you actually travel, recorded from the GPS.

1. Go to **Menu** › **Track Recording**.
2. Choose the **Point Interval** (15 s, 30 s, 1 min or 5 min) and press **Start Recording**.
3. **Pause** and **Resume** as needed; press **Stop** to save.

While recording, the top of the screen shows **Recording** (or **Paused**). A saved track appears in the
**Markers** list with its distance and time. Its panel shows **Distance and Time**, moving and stopped time,
**Elevation** and when it was **Recorded**. **Export** sends it as a GPX file to another app. Tracks are not
shared with other devices.

## Remote access: pair the device

If the server owner has enabled **Remote Connectivity**, a paired device can use the server over the internet. Pair
once, on the local network:

1. The server owner enables **Remote Connectivity** on the server's **Admin** › **Devices** page (step 3), or
   presses **Start Pairing** there. Pairing mode then stays active for 10 minutes.
2. In the app, go to **Menu** › **Server** and press **Pair Device**.

Away from the local network the app connects through the internet by itself. The top still shows **Connected**,
and **Menu** › **Server** shows **Connected (remote)**. Maps load more slowly than on the local network. With no
internet, the app shows **Offline** and uses your offline areas.

The connection is encrypted, and the server only lets in devices it paired. This is not a system VPN: there's no
VPN icon, and other apps and VPNs aren't affected. If the server owner removes the device, the app tells you the
next time it tries from the internet; pair again on the local network. **Unpair Device** removes the pairing from
the device. If the owner disables remote connectivity, the app says so and the device stays paired.

If a paired device can't reach the server from the internet, bring it to the local network once: the app then
learns the server's current addresses.

## Location sharing

Devices connected to the same server can see each other's locations and share markers. The server owner must
first enable **Location Sharing and Messages** on the server's **Admin** › **Devices** page (step 2); otherwise
the app shows "Location sharing and messages are disabled on the server."

**Share your location:**

1. Go to **Menu** › **Location Sharing**.
2. Enter a **Display Name** for others to see (up to 24 characters).
3. Press **Start Sharing**.

A **Sharing** tag shows at the top: amber when your location reaches the server, gray when it doesn't (no
connection or no GPS fix). Sharing continues in the background and with the screen off, with a **Location Sharing
Active** notification that has a **Stop Sharing** button. While sharing, Back leaves the app without closing it.

To stop, press **Stop Sharing**, or close the app with **Menu** › **Exit**. Leaving the app or swiping it away
does not stop sharing. When you stop, your location disappears from the other devices at once. The app remembers the setting: if you were sharing when you closed the
app, it starts sharing again the next time you open it.

**Other Devices** show as light blue diamonds with names. A diamond turns gray and fades when that device stops
sending. Tap one to see its **Shared Location**: name, last update, position, distance and bearing. **Menu** ›
**Location Sharing** lists them. You see the others even when you're not sharing yourself.

**Share a marker, arrow or route:** tap it and tick **Share with All Devices**. Every device (and browser on the
server's map) then has it and can change or delete it; the last change wins. Untick to keep it on your device only.
Deleting a shared item asks first and removes it everywhere. Shared items stay on the device when you're offline,
and changes you make offline are sent when the connection returns. With a paired mesh radio, they also go over the
mesh.

**Battery:** in the background the app sends your location about three times a minute when you move and once a
minute when you don't, and turns the GPS off in between.

Not available yet: groups within a server, and connecting ATAK or other TAK apps.

## Messages

Short text messages between the devices and browsers on your server, and over the mesh radio when the server
can't be reached. Messages through the server need **Location Sharing and Messages** enabled on the server's
**Admin** › **Devices** page (step 2); you don't have to share your own location.

1. Go to **Menu** › **Messages**, or press **Send Message** on someone's **Shared Location** panel.
2. Choose a conversation: **Group (Private)** goes to all devices of this server; **Direct Messages** lists the
   people you can write to directly; **Group (Public)** is the public mesh channel (only with a paired radio).
3. Type up to 140 characters under **New Message** and press **Send**, or the keyboard's Enter.

A line under the field says how the message will go (through the server, over the mesh, or waiting for either).
Each message shows **Waiting**, **Sent (Server)**, **Sent (Mesh)**, **Delivered**, or for a group **Delivered to
N**. A mesh message that isn't confirmed also goes through the server after a minute. The app keeps the last 1,000
messages. New messages arrive in the background with a notification until you **Exit**.

## Mesh radio

With a [Meshtastic](https://meshtastic.org) radio paired over Bluetooth, the app keeps sharing locations, markers,
routes and messages with nearby devices when nobody can reach the server.

**Pair a radio:**

1. Turn on the radio and the device's Bluetooth.
2. Go to **Menu** › **Mesh Radio** and press **Pair Radio**. Allow **Nearby Devices** when Android asks.
3. Tap your radio under **Radios Nearby**.
4. Enter the radio's PIN when Android asks (radios with a screen show it there; the factory PIN is often 123456).
   Fieldmap then sets a new PIN and shows it under **Bluetooth PIN**: note it to pair the radio again later.
5. If the status says **Region Required**, choose your **Region**.

The app sets the radio up as a **Client** and gives it the server's private channel. It gets that channel the first
time it connects to the server with location sharing enabled; until then the status says **Waiting for Server
Channel**. Locations and markers only travel on that private channel. **Read Only** keeps the radio quiet on the
public channel.

Over the mesh, your location goes out about once a minute while you move and every ten minutes while you don't.

**Nodes Nearby** lists other Meshtastic radios heard in the last two hours. **Show on Map** draws those that report a
location as green triangles. Tap one for its details; **Trace Route** shows the hops to it and back, with signal
strength.

**Details** shows the radio's **Model**, **Firmware**, **Battery**, **Region** and **Channel**. **Forget Radio**
unpairs it.

## NetBird VPN

Some internet connections, such as Starlink, don't let the server be reached through a router port. For those, the
server owner can choose **NetBird VPN** as the remote connectivity method. **There is nothing to set up in the
app.** The device gets what it needs when you pair it, or, if it was paired before, the next time it is on the
local network.

Away from the local network, the app tries the server's direct addresses first and uses NetBird only if none
answers. The top shows **Connected** either way, and **Menu** › **Server** says when the connection goes through
NetBird.

What to expect:

- Connecting takes a few seconds longer.
- It uses more battery while connected, because the NetBird client keeps a connection open. The client stops on
  the local network, when you exit the app, and one minute after the app leaves the screen (unless you're sharing).
- The map may load more slowly if the connection goes through a NetBird relay.
- The NetBird client is built into the app, not a system VPN, and adds about 65 MB to the app's size.

Download your offline areas on the local network, not over NetBird, when you can.

If the owner runs several servers in one NetBird account, each server can keep its devices apart from the others
(**Network Isolation** on the server). The app does the same either way: a device reaches only the server it is
paired with.

The server owner can see the device (as "Fieldmap" with its make and model) in their NetBird dashboard. The setup
key is stored encrypted on the device and never shown. **Unpair Device** deletes it.

## Permissions and privacy

| Permission | Why |
|---|---|
| Location | To show your location. Asked the first time you press **Follow**, "while the app is in use" only. Your location stays on the device unless you start sharing. |
| Notifications | For the notification shown while sharing, and for new messages (Android 13 and newer). Sharing also works without it. |
| Nearby Devices (Bluetooth) | To find, pair and talk to a Meshtastic radio. Asked when you press **Pair Radio** or **Connect** under Mesh Radio. |
| Background location | To keep sharing when the app is not on screen, only while sharing. |
| Keep awake | For the few seconds it takes to send one location from the background. |
| Network | To reach your server. Plain `http` is allowed because a server on a local network has no certificate. |

There is no account, no advertising and no analytics. The app only connects to the server you set, and to the
server's NetBird network if the owner set one up.

**Find Server** checks up to 254 neighboring addresses on the Wi-Fi network for a server, on the web port and then
on port 8080. It only runs when you press the button, and only on a private network.

If the server's Wi-Fi has no internet, Android may send app traffic over mobile data instead. When the server
doesn't answer, the app reaches it over the Wi-Fi directly; there's nothing to set.

## Troubleshooting

| Problem | What to do |
|---|---|
| The top shows **No Server**. | Go to **Menu** › **Server** and connect. |
| **Find Server** finds nothing. | Check the device is on the local network, then type the **Server Address** and press **Connect**. |
| The top shows **Offline**. | There's no connection to the server. Go back to the local network, or (if paired) to a place with internet. Your offline areas still work. |
| The map is blank offline. | That area isn't downloaded. Download offline areas on the local network before you go. |
| An area shows **Incomplete**. | Open the app on the local network; the download carries on. |
| **Pair Device** fails. | Ask the server owner to enable Remote Connectivity or press **Start Pairing**, then pair within 10 minutes. |
| A paired device can't connect from the internet. | Bring it to the local network once so it learns the server's current addresses. |
| The app says location sharing and messages are disabled on the server. | The server owner must enable **Location Sharing and Messages** on the server's **Admin** › **Devices** page (step 2). |
| Nobody sees my location. | Check the **Sharing** tag is amber. The others must start sharing too. |
| A route has dashed straight legs. | No way was found, or there is no routing data there. Download an offline area covering the route while connected to the server, and add points closer together (see [Routes](#routes)). |
| The mesh radio says **Waiting for Server Channel**. | Connect to the server once while Location Sharing and Messages is enabled on it. |
| The mesh radio can't be found or connect. | Turn on Bluetooth and the radio, allow **Nearby Devices**, and keep the radio close. To start over, press **Forget Radio** and pair again. |
| Sharing stops in the background. | Some phones' battery savers stop background apps. Open the app again, or exclude Fieldmap from battery saving. |
