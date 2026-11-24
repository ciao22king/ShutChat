![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)

![Linux](https://img.shields.io/badge/Linux-FCC624?style=for-the-badge&logo=linux&logoColor=black)


# ShutChat

A peer-to-peer messenger for Android and Linux. Messages and calls travel
directly between devices: there is no server, no account, and nothing to sign up
for.

Conversations are opened with a short invite code that the other person pastes
or scans. The code carries its own expiry day, or can be set to never expire.

## How it works

The whole access model is the invite code. It is 15 bytes rendered as 24
characters of Base32 (Crockford alphabet, so `1`/`l`/`I` and `0`/`O` cannot be
confused when read off a screen):

| Bytes  | Contents |
| ------ | -------- |
| `0`    | version and chat kind |
| `1..2` | expiry day, big endian, `0xFFFF` = never |
| `3..4` | CRC-16, so a mistyped character is caught |
| `5..14`| 80-bit rendezvous secret |

The expiry is stored as a **day**, not a timestamp, so every device in a room
enforces the same UTC deadline no matter its timezone.

The room id is derived from the secret with HKDF and travels in the clear in
every frame header. That is deliberate: it lets a device forward a frame it
cannot decrypt, which is what makes relaying possible. The message key and the
file-transfer key are separate derivations of the same secret, so a file chunk
cannot be replayed as a message.

Transport is chosen at runtime:

- **LAN** — UDP multicast to find peers on the same network, TCP for the data.
- **Nearby Connections** — Bluetooth and Wi-Fi Direct, for devices that are close
  but not on a shared network.
- **Public relay** — MQTT over TLS, for devices on different networks. On by
  default, because a messenger that only works on one network is not one; it can
  be switched off from settings. The broker relays sealed frames on a topic derived from the
  invite secret, so it sees neither the message nor who is talking; it cannot
  read a frame any more than a LAN peer could.

The relay uses QoS 1 with a persistent session keyed on the node id, so a frame
published while the other phone is asleep is queued by the broker and delivered
when it reconnects. It fails over between `broker.hivemq.com`, `broker.emqx.io`
and `test.mosquitto.org`, and reconnects with a backoff that resets on success.

Calls ride the same mesh: the offer, the answer and the ICE candidates are
ordinary envelopes, so they take whichever transport is available and never need
a second rendezvous mechanism. Media itself is WebRTC peer to peer (opus audio,
VP8 video); the signalling is the only part that ever touches a broker, and it
is sealed like everything else.

Two devices behind mobile carriers have no reachable address, so something has to
be reachable for long-distance chat to work at all. The relay is that something,
and it is deliberately the only third party involved.

A foreground service keeps the mesh listening when the app is closed, because
with no push server a message can only arrive if something is awake to receive it.

## Modules

| Module    | Contents |
| --------- | -------- |
| `shared`  | Protocol, crypto, invite codec, mesh engine, transports, call state machine. Plain Kotlin/JVM, no Android types. |
| `app`     | Android client: Compose UI, foreground service, Nearby Connections, WebRTC media. |
| `desktop` | Linux client: Compose for Desktop window, JSON file store, WebRTC media via `webrtc-java`. |

Android-specific pieces (multicast lock, Nearby Connections) sit behind small
interfaces in `shared`, so the same mesh runs on a JVM without an emulator.

## Features

- Direct and group chats, both created from a single code
- Photos, audio and files, transferred in chunks with progress
- Replies, edits, deletes, emoji reactions
- Read receipts and typing indicators, sent as explicit envelopes
- A retry queue: a message for an offline peer is re-sent until it is acknowledged
- Online/offline presence per member
- Message search, pin, mute, archive, drafts
- QR code sharing for an invite
- Automatic expiry and rotation of invite codes
- Notifications with inline reply
- Audio and video calls, with mute, speaker and a ring timeout
- Light and dark theme with Material You dynamic colour
- Settings with a check for updates against the release feed

## Build

You need JDK 17 or newer and the Android SDK with platform 35 and build-tools
35.0.0. Point `local.properties` at your SDK:

```properties
sdk.dir=/path/to/android-sdk
```

Then:

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified release APK
./gradlew :app:testDebugUnitTest # Android unit tests
./gradlew :shared:test :desktop:test   # shared and desktop unit tests
```

### Linux packages

```bash
./gradlew :desktop:packageDeb && desktop/package-deb.sh
desktop/package-appimage.sh
```

- `.deb` → `desktop/build/compose/binaries/main/deb/shutchat_<version>_amd64.deb`.
  Installs to `/opt/shutchat`, with a menu entry and an icon. `package-deb.sh`
  adds the `.desktop` file and the icon, which `jpackage` does not emit here.
- AppImage → `desktop/build/compose/binaries/main/appimage/ShutChat-<version>-x86_64.AppImage`.
  One file, no install, no root. `package-appimage.sh` downloads `appimagetool`
  on first use and runs it with `--appimage-extract-and-run`, so FUSE is not
  needed to build it.

Both wrap a jlink runtime, so the target machine does not need Java.

### Integration test against a real broker

The relay is the one part that cannot be proven offline. There is an opt-in test
that publishes a frame through a public broker and reads it back on a second
client:

```bash
./gradlew :desktop:test --tests '*RelayRoundTripIT*' -Dshutchat.it=1
```

The release build is signed with the debug key so the APK can be sideloaded
without a Play account. For a real release, replace the `signingConfig` in
`app/build.gradle.kts` with your own keystore.

## Requirements

Android 8.0 (API 26) or newer. The app asks for Bluetooth, camera and
microphone permissions; location is requested only on Android 11 and below,
where the platform requires it for Wi-Fi scanning.

The desktop client needs a Linux x86-64 machine with glibc and an X11 or Wayland
session. Calls need a microphone; video calls need a camera.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
