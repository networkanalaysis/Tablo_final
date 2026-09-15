# Tablo TV

Tablo TV is a native Android TV client for Tablo 4th Gen. It is designed for
Android TV and Amazon Fire TV: navigation is D-pad-first, playback uses the
native Media3 HLS player, and the stream wall can play **1, 2, 3, or 4 live
channels at once**.

This app replaces the browser frontend from
[tablo-web](https://github.com/trevor-viljoen/tablo-web). It intentionally keeps
the existing local backend as the service boundary because the backend handles
Tablo account authentication, device discovery, FFmpeg transcoding, and HLS
session cleanup.

## Architecture

```text
Android TV / Fire TV app  --HTTP/HLS-->  tablo-web backend  -->  Tablo device/cloud
```

The APK does not embed a browser or require a web server for its UI. A
`tablo-web` backend must be reachable on the same network as the TV.

## Run the backend

Use the backend from the referenced project on a computer or small server on
the same LAN:

```bash
git clone https://github.com/trevor-viljoen/tablo-web.git
cd tablo-web
docker compose up -d
```

The default address is `http://<server-ip>:7070`. The server must be reachable
from the TV; `localhost` on the TV is not the computer running Docker.

## Build and install

Open this repository in Android Studio (Ladybug or newer), let Gradle sync,
and run the `app` configuration on an Android TV emulator or a connected TV.
For a manually built APK:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch:

1. Connect the TV and the computer running `tablo-web` to the same Wi-Fi or
   wired LAN.
2. Enter the Tablo account credentials. The app scans the local network for
   the Tablo service automatically; no server IP entry is required.
3. Use the `PASTE` button beside either credential field when entering text
   from a Fire TV remote, phone, or clipboard. `COPY` is also available for
   selected field contents.
4. Select the desired stream count.
5. Select channels from the live guide. Selecting a channel fills the next
   available tile; selecting a playing tile stops it.

The backend must allow cleartext LAN HTTP, which is enabled in the manifest for
local deployments. Discovery probes the LAN for port `7070`, the default
`tablo-web` frontend port. Do not expose the backend directly to the public
internet.

## Fire TV notes

Fire TV uses the same Android APK format. Enable developer options and ADB
debugging on the Fire TV, find its IP address, then install with:

```bash
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Stream behavior

- OTA channels are requested through the backend's normal stream endpoint; the
  backend decides when FFmpeg transcoding is needed.
- Each tile owns an independent HLS session and is stopped when cleared or
  when the app exits.
- Stream count is capped at four to keep the TV decoder and network load
  predictable.

Tablo and the Tablo logo are trademarks of Nuvyyo Inc. This is an unofficial
client and is not affiliated with Nuvyyo Inc.
