# Studio Light

Turn an Android TV / Google TV into a solid-color light for video and photo shoots. Control it with the TV remote, or pair one or more Android phones on the same LAN. No cloud, browser, account, or frontend build step.

## Download

- **Android phone:** [Download Studio Light Control APK](https://github.com/Akash200307/studiolightTV/releases/download/v1.0.0/StudioLight-Phone-1.0.0-debug.apk) — Android 8.0 (API 26) or newer.
- **Android TV / Google TV:** [Download Studio Light TV APK](https://github.com/Akash200307/studiolightTV/releases/download/v1.0.0/StudioLight-TV-1.0.0-debug.apk) — Android 6.0 (API 23) or newer.

Both APKs are separate assets in the [1.0.0 preview release](https://github.com/Akash200307/studiolightTV/releases/tag/v1.0.0). Install Studio Light on your TV and Studio Light Control on your phone, then follow the pairing steps below.

The preview APKs are debug-signed development builds for sideloading. They are not Play Store releases. Future builds signed with a different key require uninstalling the existing app first, which clears its saved settings and pairing.

## Build

Requirements: JDK 21, Android SDK Platform 36 and Build Tools 35.0.0 or newer, and Internet access for the first Gradle dependency download. Android Studio can install the SDK. Set `ANDROID_HOME` to your SDK directory, or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/sdk`.

```sh
git clone https://github.com/Akash200307/studiolightTV.git
cd studiolightTV
./gradlew :core:test :tv:testDebugUnitTest
./gradlew :tv:assembleDebug :phone:assembleDebug
```

On Windows use `gradlew.bat`. The checked-in wrapper uses Gradle 8.13. Android Gradle Plugin 8.13.0, Kotlin 2.2.10, Compose, kotlinx.serialization and Ktor 3.3.3 are pinned in the Gradle files. Java/Kotlin bytecode targets Java 17; core-library desugaring supports older TVs.

Outputs:

- TV (Android 6.0 / API 23+): `tv/build/outputs/apk/debug/tv-debug.apk`
- Phone (Android 8.0 / API 26+): `phone/build/outputs/apk/debug/phone-debug.apk`

These are development APKs signed with the local Android debug key. Release distribution requires your own signing configuration.

## Install on Android TV / Google TV

1. Open Settings → System (or Device Preferences) → About. Select Android TV OS build / Build seven times to enable Developer options.
2. Open Developer options and enable USB debugging / ADB debugging. Some TVs also have a separate Network debugging toggle. Keep TV and computer on the same trusted WiFi or Ethernet LAN.
3. For TVs exposing classic network ADB, find the TV IP in Network settings, then run:

   ```sh
   adb connect <tv-ip>:5555
   adb install -r tv/build/outputs/apk/debug/tv-debug.apk
   ```

4. Accept the computer's debugging authorization prompt on the TV. Launch **Studio Light** from the TV app list.

Port 5555 is device-dependent: if it is closed, enable network debugging in the TV settings or first connect USB, authorize the computer, and run `adb tcpip 5555`. Newer Android devices using Wireless debugging instead show a pairing code and two ports:

```sh
adb pair <tv-ip>:<pairing-port>
adb connect <tv-ip>:<debugging-port>
```

Install with the same `adb install` command after connecting. ADB debugging is needed only for installation; disable it afterwards if desired. The app's controller server uses **8765**, independently of ADB.

## Install on the phone

Copy `phone/build/outputs/apk/debug/phone-debug.apk` to the phone, open it in Files, and allow that file manager to install unknown apps when prompted. Alternatively, enable phone Developer options and USB debugging, connect and authorize it, then:

```sh
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
```

Find the serial with `adb devices`. Open **Studio Light Control** and grant Nearby devices permission on Android 13+ when requested. Discovery uses Android NSD, not WiFi scanning. A denied permission on systems that gate local discovery may require using the permission settings before connecting.

## Pair and control

1. Open Studio Light on the TV. Its first-launch settings card shows the device name, local IP and port, and a four-digit PIN. The card hides after five seconds; press **Menu**, or hold **Back**, to reopen it.
2. Open the phone app and choose the discovered TV. If discovery is blocked, enter the TV's numeric IP in **Connect by IP**, for example `192.168.1.42:8765`.
3. Enter the TV PIN. Pairing is saved only after the connection succeeds. Next launch reconnects to the last TV, with exponential backoff if temporarily unreachable. Tap the TV name/status to change TVs or enter a changed IP.
4. Drag the hue, saturation, brightness, and opacity sliders. White mode replaces hue with a 2500–9000 K slider. Saturation is retained but inactive in White mode. Edits are merged and sent at about 30 messages per second during dragging; TV transitions are instant for these edits and 250 ms for other changes.
5. Choose one of ten preset swatches. **Save** stores the current light without active effects; **Saved** applies or deletes saved lights. Up to twelve are retained; saving a thirteenth replaces the oldest.
6. **Blackout** instantly sets brightness to zero and disables both effects. Raise brightness or select a preset to relight. Strobe supports 1–10 Hz. Cycle traverses hue at 3–30 degrees/second (speed 1–10), and switches to Color mode. On compact phone displays, tap **Speed** for effect settings. The controller itself has no scrolling; discovery and saved-light lists may scroll.

Multiple phones see the same authoritative TV state. Slider updates change only their named fields, so one phone adjusting brightness does not reset another phone's hue. While disconnected, control edits are disabled; reconnect fetches the TV state instead of replaying stale edits. The TV keeps its current light if a phone disconnects.

### TV remote

- **Left / Right**: hue, in 5° increments; selects Color mode.
- **Up / Down**: brightness, in 5% increments.
- **OK**: toggle Color / White. **Hold OK** for 650 ms: instant blackout.
- **Menu / hold Back**: settings and pairing card. **Back**: dismiss settings, or exit the app.
- In settings: **Up / Down** selects a row, **Left / Right** adjusts. **OK** toggles effects, applies/advances a preset, or regenerates the PIN. For strobe and cycle, Left / Right changes the effect speed.
- Settings hide five seconds after the last remote interaction. Regenerating the PIN disconnects every paired controller; enter the new PIN on each phone.

The TV draws the color layer over black; opacity multiplies the light output, and brightness scales RGB values. Neither setting changes the TV hardware backlight. White is an RGB approximation of blackbody color, not calibrated colorimetry. Strobe timing is limited by the TV refresh rate. Both screens stay awake while their activities are visible. Fullscreen uses native Android immersive mode, with no browser fullscreen or Wake Lock permission restrictions; system gestures may temporarily reveal system bars.

## LAN, lifecycle, and storage

The TV starts a Ktor CIO WebSocket server on `0.0.0.0:8765` in a `connectedDevice` foreground service while the TV activity is open. It advertises `_studiolight._tcp.` using NSD/mDNS. Returning Home or exiting stops the service; relaunching restores the last light and starts it again. No boot receiver or background auto-start is installed. Android DataStore persists the TV state with a 180 ms debounce and stores a generated PIN; abrupt process termination within that window can lose the latest change. Phone pairings and saved lights also use DataStore. Backups are disabled to keep pairing credentials local.

Cleartext `ws://` is used only for the local controller link. [Android's static network-security configuration](https://developer.android.com/privacy-and-security/security-config) cannot whitelist arbitrary private IP subnets. The code therefore validates numeric RFC1918 IPv4, link-local, loopback, and IPv6 ULA addresses before opening a phone connection, and rejects non-local TV peers. It never resolves user-entered public hostnames or opens public endpoints. The XML enables the transport required for dynamic private IPs. PINs travel in the WebSocket HTTP upgrade header (`X-Studio-Pin`), never in URLs. They are unencrypted on the LAN: use a trusted network. Five failed PIN attempts from one address within a minute block further attempts for that minute; regenerate the PIN to reset this. Up to eight controllers can connect.

No analytics, Internet API requests, or remote control endpoints are included. The apps target API 36; future Android releases may add local-network permission requirements, which should be reviewed before increasing the target SDK.

## Protocol

WebSocket endpoint: `/light`, port 8765, header `X-Studio-Pin: 1234`. Authentication precedes the upgrade and any state delivery. A successful connection receives `hello`, then full `state`, then broadcasts as state changes. An application `ping` responds with current state, in addition to WebSocket-level heartbeats.

```json
{"type":"hello","deviceName":"Living room TV","appVersion":"1.0.0"}
{"type":"set","state":{"hue":220,"brightness":75},"instant":true}
{"type":"blackout"}
{"type":"ping"}
{"type":"state","state":{"hue":220,"saturation":75,"brightness":75,"opacity":100,"mode":"COLOR","kelvin":4500,"strobe":false,"strobeHz":2,"cycle":false,"cycleSpeed":1},"instant":true}
```

All fields are validated at the TV before any mutation. Ranges: hue 0–360; saturation, brightness and opacity 0–100; kelvin 2500–9000; strobeHz and cycleSpeed 1–10. Unknown message types/fields, non-finite numbers, out-of-range values, binary messages, and messages over 4096 bytes are rejected. Invalid JSON closes only the offending controller; existing light state is unchanged. `instant` defaults to false and is transport metadata, not persisted light state.

## Troubleshooting

- **No TVs found:** verify both apps are open, on the same WiFi/LAN, without guest-network segregation or AP/client isolation. VPNs can also interfere. Allow multicast/mDNS (UDP 5353). Use **Connect by IP** if mDNS is blocked.
- **Cannot connect by IP:** check the pairing card IP and port; allow inbound TCP 8765 through network/router firewalls. The TV must remain in Studio Light. If DHCP changes the TV address, select its new discovery entry or enter its new IP. A router DHCP reservation avoids this.
- **PIN rejected:** reopen the TV card. Leading zeroes are significant. After five wrong attempts wait one minute, or regenerate the TV PIN. All phones need the new PIN after regeneration.
- **Reconnecting:** wake/reopen the TV app and check WiFi. The phone retries at roughly 0.5, 1, 2, 4, then 8 seconds, with jitter. The phone closes its socket when backgrounded and reconnects when reopened.
- **Black screen:** raise brightness and opacity, turn off strobe, or choose a preset. A disconnected phone does not black out the TV.
- **Server unavailable:** reopen settings for the error; another process could already own port 8765. NSD failure does not stop IP-based control.
- **Installation fails:** verify the app's minimum Android version, authorize ADB, and specify a serial if more than one device is attached. A different signing key may require uninstalling an older build, which clears saved state/pairing.

## Project layout and verification

Both debug APKs build successfully. Automated verification includes 26 passing tests (20 core tests and 6 real WebSocket integration tests), plus Android lint with zero errors. Lint retains dependency-version notices and warnings for intentional TV / LAN configuration. The apps support TV minSdk 23 and phone minSdk 26; the TV app includes a leanback launcher.

Device testing covered installation, startup, pairing screens, local-network permissions, and discovery on an Android 11 TV and Android 17 phone. The project owner confirmed that paired TV/phone control works as expected. TV settings and phone controller contrast, plus the Saved button label layout, were corrected during device testing. This is a preview, not an exhaustive compatibility or lighting-calibration certification.

- `core`: JVM-only state/partial-update validation, strict JSON protocol, Kelvin/HSV math, LAN endpoint validation, and built-in presets.
- `tv`: native immersive Compose renderer, remote controls/settings, DataStore, foreground service, Ktor CIO server, and NSD advertising.
- `phone`: Material 3 controller, serial NSD resolution, saved pairing/presets, Ktor CIO client, throttled partial edits and reconnect backoff.
- `core/src/test`: color reference values/bounds, state validation, protocol round trips/rejections, persistence JSON round trip, LAN restrictions.
- `tv/src/test`: real loopback WebSocket integration tests for missing/wrong/revoked PINs, rate limiting, initial state, two-phone broadcast, blackout/ping, malformed messages, and PIN-regeneration disconnects.

Before using for a shoot, verify on your TV/phone: remote short/long presses, fullscreen output, slider response, White endpoints, blackout, Home/reopen, process restart persistence, two phones, WiFi disconnect/reconnect, and small-screen layout. JVM tests and APK compilation do not prove physical panel output, TV firmware key handling, mDNS behavior on a particular router, or device UI layout.

## License

Licensed under the [Apache License 2.0](LICENSE).
