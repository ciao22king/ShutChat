# ShutChat

A peer-to-peer messenger for Android. Messages travel directly between devices:
there is no server, no account, and nothing to sign up for.

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
- **Public relay** — MQTT over TLS, for devices on different networks. Off by
  default and never enabled on its own, because it is the one path that leaves
  the local network. The broker relays sealed frames on a topic derived from the
  invite secret, so it sees neither the message nor who is talking; it cannot
  read a frame any more than a LAN peer could.

Two devices behind mobile carriers have no reachable address, so something has to
be reachable for long-distance chat to work at all. The relay is that something,
and it is deliberately the only third party involved.

A foreground service keeps the mesh listening when the app is closed, because
with no push server a message can only arrive if something is awake to receive it.

## Features

- Direct and group chats, both created from a single code
- Photos, audio and files, transferred in chunks with progress
- Replies, edits, deletes, emoji reactions
- Read receipts and typing indicators
- Online/offline presence per member
- Message search, pin, mute, archive, drafts
- QR code sharing for an invite
- Automatic expiry and rotation of invite codes
- Notifications with inline reply
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
./gradlew :app:assembleDebug     # debug build
./gradlew :app:assembleRelease   # minified release build
./gradlew :app:testDebugUnitTest # unit tests
```

The release build is signed with the debug key so the APK can be sideloaded
without a Play account. For a real release, replace the `signingConfig` in
`app/build.gradle.kts` with your own keystore.

## Requirements

Android 8.0 (API 26) or newer. The app asks for Bluetooth, camera and
microphone permissions; location is requested only on Android 11 and below,
where the platform requires it for Wi-Fi scanning.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
