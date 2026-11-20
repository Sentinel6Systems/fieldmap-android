# Fieldmap user guide

Fieldmap shows the maps of a [fieldmap-server](https://github.com/Sentinel6Systems/fieldmap-server) on an Android
phone or tablet, and keeps offline areas on the device for use with no network. In this guide, the **server** is
the fieldmap-server machine, and the **local network** is the Wi-Fi or home network the server is on.

## Contents

1. [Install the app](#install-the-app)
2. [Connect to the server](#connect-to-the-server)
3. [Download offline areas](#download-offline-areas)
4. [Use the map offline](#use-the-map-offline)
5. [Markers](#markers)
6. [Remote access: pair the device](#remote-access-pair-the-device)
7. [Location sharing](#location-sharing)
8. [NetBird VPN](#netbird-vpn)
9. [Permissions and privacy](#permissions-and-privacy)
10. [Troubleshooting](#troubleshooting)

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
| **Menu** | Map Style, Offline Areas, Markers, Location Sharing, Server, Settings, and **Exit**. |

**Menu** › **Settings** › **Coordinate Format** sets how positions are shown: **MGRS**, **UTM**, **Decimal Degrees
(DD)** or **Degrees Minutes Seconds (DMS)**.

## Markers

- **Add a marker:** touch and hold the map for 2 seconds, until the ring around your finger fills. To mark your own
  location, touch and hold the blue dot.
- **Edit a marker:** tap it. The panel lets you change the **Name** and **Color** (12 colors) and shows its
  **Location**, distance and bearing. **Delete** removes it.
- **Move a marker:** press **Move** in its panel, then tap the new place (or tap the blue dot for your location).
  Back keeps it where it was.
- **Import / Export:** **Menu** › **Markers** › **Export** writes a GeoJSON file. The server's **Print Map** page can
  import it with **Import Drawings**, and so can QGIS and other map software. **Import** reads markers from a file
  (arrows and shapes in it are skipped).

## Remote access: pair the device

If the server owner has enabled **Remote Connectivity**, a paired device can use the server over the internet. Pair
once, on the local network:

1. The server owner enables **Remote Connectivity** under **Admin** › **Remote Access**, or presses **Start
   Pairing** on the **Devices** page. Pairing mode then stays active for 10 minutes.
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
first enable **Location Sharing** on the server's **Devices** page; otherwise the app shows "Location sharing is
disabled on the server."

**Share your location:**

1. Go to **Menu** › **Location Sharing**.
2. Enter a **Display Name** for others to see (up to 24 characters).
3. Press **Start Sharing**.

A **Sharing** tag shows at the top: amber when your location reaches the server, gray when it doesn't (no
connection or no GPS fix). Sharing continues in the background and with the screen off, with a **Location Sharing
Active** notification that has a **Stop Sharing** button. While sharing, Back leaves the app without closing it.

To stop, press **Stop Sharing**, or close the app with **Menu** › **Exit** or by swiping it away. Your location
disappears from the other devices at once. The app remembers the setting: if you were sharing when you closed the
app, it starts sharing again the next time you open it.

**Other Devices** show as light blue diamonds with names. A diamond turns gray and fades when that device stops
sending. Tap one to see its **Shared Location**: name, last update, position, distance and bearing. **Menu** ›
**Location Sharing** lists them. You see the others even when you're not sharing yourself.

**Share a marker:** tap it and tick **Share with All Devices**. Every device then has it and can change or delete
it; the last change wins. Untick to keep it on your device only. Deleting a shared marker asks first (**Delete
Shared Marker?**) and removes it everywhere. Shared markers stay on the device when you're offline, and changes you
make offline are sent when the connection returns.

**Battery:** in the background the app sends your location about three times a minute when you move and once a
minute when you don't, and turns the GPS off in between.

Not available yet: groups (one server is one group), messages, and connecting ATAK or other TAK apps.

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
| Notifications | For the **Location Sharing Active** notification (Android 13 and newer). Sharing also works without it. |
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
| The app says location sharing is disabled on the server. | The server owner must enable Location Sharing on the server's Devices page. |
| Nobody sees my location. | Check the **Sharing** tag is amber. The others must start sharing too. |
| Sharing stops in the background. | Some phones' battery savers stop background apps. Open the app again, or exclude Fieldmap from battery saving. |
