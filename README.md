# Tablo TV

Tablo TV is a native Android TV client for Tablo 4th Gen. It is designed for
Android TV and Amazon Fire TV: navigation is D-pad-first, playback uses the
native Media3 HLS player, and the stream wall can play **1, 2, 3, or 4 live
channels at once**.

This app replaces the browser frontend from
[tablo-web](https://github.com/trevor-viljoen/tablo-web). It intentionally keeps
the original Tablo visual language while using the physical Tablo's local REST
API for discovery, guide data, and live HLS sessions.

## Architecture

```text
Android TV / Fire TV app  --HTTP/HLS-->  Tablo device
```

The APK does not embed a browser or require a web server for its UI.

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
2. Enter the Tablo account credentials. The Kotlin login screen automatically
   discovers the physical Tablo using the Tablo UDP discovery protocol, the
   Tablo association service, and a local `/server/info` verification request.
   No server IP entry is required. Discovered devices appear as selectable
   cards, matching the Multiview login functionality.
3. Use the `PASTE` button beside either credential field when entering text
   from a Fire TV remote, phone, or clipboard. `COPY` is also available for
   selected field contents.
4. Select the desired stream count.
5. Select channels from the live guide. Selecting a channel fills the next
   available tile; selecting a playing tile stops it.

Cleartext LAN HTTP is enabled in the manifest because the Tablo local API uses
port `8885`. Do not expose the Tablo device directly to the public internet.

## UI and implementation

The app is implemented in Kotlin. Its login behavior is based on the
Multiview project's device discovery and selection flow, while its visual
language follows the `tablo-web` client: dark surfaces, blue accents, Tablo
branding, rounded cards, Live TV/Guide/Library navigation, and an ON AIR NOW
live guide. The UI is native Android and does not embed the referenced web app.

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
