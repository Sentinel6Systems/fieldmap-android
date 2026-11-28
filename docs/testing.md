# What was tested

The tests run on this app so far, and what has not been tested yet. Unit tests: `./gradlew testDebugUnitTest`.

## Maps, offline areas and markers

Version 0.1.0 was tested on an Android 14 emulator against a fieldmap-server tile server (TileServer GL 5.7.0)
with sample data of a small country:

- finding the server on the network and connecting by a typed address; the styles
- saving areas of up to 27,800 tiles, checked in the device's database: the tile counts matched the app's count
  exactly, and for the larger areas the size was within a quarter of the estimate
- a download cut off by closing the app, which carried on at the next start; deleting; updating all areas
- the map with the network switched off and the server stopped
- the position from a simulated GPS, following, heading-up, upright and on its side, light and dark
- markers, and the markers file read by the code of fieldmap-server's print tool

It was then put on an Android 16 phone and checked by hand and over adb:

- positions came from the satellite receiver once a second, the first about five seconds after the network's
  rough one; the app let go of the receiver when it left the screen
- following, heading-up by the compass, and marking by holding the map
- connecting to a fieldmap-server behind Internet-in-a-Box (`/maps/`) over Wi-Fi, and saving an area from it
  in the `topo-usgs` style: the 120 tiles the app counted were the 120 in the device's database, from all five
  tile sets of the style

## Pairing and remote access

Pairing and the remote link were tested on the emulator against fieldmap-server's gateway: pairing refused while
closed and done in one press once opened on the server's Devices page, the switch to the internet (the status then read **Connected**) when the home address
went away, a device removed on the server being told so, remote access switched off and on again (the device stays
paired). The remote link was later confirmed by hand from outside, through a home router, with that phone and a
server behind Internet-in-a-Box.

## Sharing

Sharing was tested on the emulator against fieldmap-server's sharing service, on its home address and through
its gateway, with a second device played by a script:

- switching the position on (the name, the tag in the strip) and off (gone from the server at once), closing the
  app for good (gone at once), leaving the app (the position stays and ages), opening it again (still on)
- the others on the map and in the list, tapping one, a position turning grey and going after two minutes
- sharing a marker, renaming it (one message to the server after the typing stops), changes and a delete made by
  the other device arriving, also while the marker's panel was open; taking a marker back; deleting a shared one
- out of reach: a shared marker renamed, one deleted and a new one shared, all sent when the server answered again;
  the same marker changed on both sides, where the later change stayed, either way round
- sharing switched off on the server (the app says so and asks again once a minute) and on again
- all of it again through the gateway, with the home address cut off (the status then read **Connected**)

Sharing was then tried by hand with two phones (Android 16 and Android 13) on a
fieldmap-server behind Internet-in-a-Box, on the local network: each showed the other, and a marker shared on
one arrived on the other.

Sharing in the background was tested on the emulator:

- the pace by the server's log: every 20 seconds after leaving the app or switching the screen off, every 60
  seconds after a minute in one place, back to 20 when the position jumped, and the app's own pace again once it
  was back on the screen; no questions to the server from the background, only positions
- Back on the map leaving the app with sharing going on; swiping the app away, **Close Fieldmap** and **Stop
  sharing** on the notification each taking the position off the server at once, with nothing left asking for
  positions afterwards
- the notification, and Android's question about notifications when sharing is switched on
- in the background, the home address going away (sharing moved to the gateway by itself) and coming back
  while the gateway did not answer (it moved home again)
- on another device's map: a device at rest still shown as live after 48 seconds without news, one on the move
  shown grey

Battery on a real device: a recent Android phone shared its position in the background for about an hour,
partly while driving, and its battery went from 75 % to 72 %, about 3 % per hour for the phone as a whole.

Not tested yet for sharing: a night with the device lying still, the motion sensor on a real device, older
devices and the device makers' own battery savers, a link that comes and goes, and many devices at once.

## Unit tests

The unit tests cover the grid references (against the print tool's own results), the markers file, address
handling, tile counting, and sharing: what is sent and read, bringing shared markers together (changed since
the last answer, the whole list, removed ones, the last change winning), how a position ages, the pace in the
background and telling rest from movement.

## NetBird

Remote access through a NetBird network was tested on the emulator, against a fieldmap-server in a
container and an own NetBird server (v0.83.1) on the same computer:

- pairing gives the app the NetBird block of the server; the setup key is stored encrypted
- with the local network gone and no direct address: **Connected** through NetBird, 13 seconds after the app
  started; the server showed that a device came in through NetBird; the connection was direct, not through the relay
- through the NetBird port of the app, the gateway of the server refused a request with no certificate and a
  request with a certificate from a different authority, and it showed only the certificate of the server
- the NetBird client stopped: on the local network (6 seconds), one minute after the app left the screen, at once
  with **Close Fieldmap**, and when the server removed the device
- sharing in the background through NetBird: the server got a position every 20 seconds, and the client ran on
  while sharing was on
- NetBird switched off on the server: the app deleted the setup key and its NetBird key. Switched on again: the app
  got the block on the local network and connected from outside again
- a device removed on the server: the app dropped the pairing, the setup key and its NetBird key

It was then checked by hand on a real Android device against a real fieldmap-server that uses NetBird's cloud
service:

- with the device on a different Wi-Fi network, away from the server's local network: **Connected** through
  NetBird, and the server's maps loaded
- offline areas downloaded from the server; with no network at all, the map kept drawing them

Not tested yet for NetBird: a real Starlink connection,
a long time on a real device (the battery, a night in the pocket, the battery savers of device makers), a 32-bit device, a
connection through the relay over a real distance, a new setup key on the server, and a server that gets a new address
in the NetBird network.

The unit tests for NetBird cover: reading the block of the server, the answers of the gateway, what a changed block
means, the order of the ways (the local network, the direct addresses, NetBird), the wait after a start that
failed, the rules that stop the client, and that network isolation on the server changes only the key (no new
join, no group chosen by the app). Network isolation itself is the server's: see fieldmap-server's tests.

## Route Options, groups, shapes and the grid (0.2.0)

187 unit tests pass (one more needs a real routing file and is skipped). They check that Route Options, the
avoid areas of a group and the Way become exactly the settings the server's map sends, that BRouter applies them
in all three profiles, the routes' options in files, shared state and mesh packets, groups (list order, export,
import, rename, delete), shape sizes, the MGRS grid (against the server map's own grid tests) and arrow heads.

On an Android emulator: every Route Options control, groups (move, new group, hide, fold, rename, delete,
export, import), shared shapes and their panel, the MGRS grid lines, arrow heads at three zooms, and the
"Which One?" chooser. On an Android 12 phone (a release-signed build over 0.1.2, data kept): the grid at
100 km, 10 km and 1 km with its numbers and square names, the grouped Markers list and Route Options in Edit
Route. Not yet tried: routes with options on real routing data, the "Working out routes" note (on the emulator
legs failed at once, without routing data), and Rename and Delete Group with shapes shared by a real server.

0.2.1: the unit tests pass, including that BRouter's walking profile reads Use Any Road or Trail
(shortest_way). The longer limit per leg (60 seconds) and the new option are not yet tried on a device.

## Not tested yet

A server whose Wi-Fi has no internet, a download of several gigabytes, a long drive or hike, and
devices without Google services.
