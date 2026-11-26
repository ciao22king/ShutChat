# Linux client in Rust

The Linux client is going to be Rust, and it will do exactly what the Android
client does: same screens, same features, same wire protocol. Nothing less,
nothing more. The Kotlin desktop client stays in the tree until the Rust one
reaches parity, then it goes.

## Why a real port and not a rewrite

The two clients have to talk to each other, so the protocol is the contract and
the Kotlin code is the reference. The Rust core is ported file by file and
proven against the vectors that the Kotlin tests already fix (`shared/src/test`).
A Rust core that passes the same vectors speaks the same protocol; anything else
is guesswork.

## Where the port stands

| Piece | State |
|---|---|
| invite code, envelope, crypto, framing | ported, pinned by the Kotlin vectors |
| LAN transport (UDP multicast discovery, TCP links) | ported, two instances exchange messages and receipts |
| SQLite store | chats, messages, receipts, per-chat unread |
| Slint UI | chat list, conversation with bubbles/timestamps/status, join and settings pages |
| notifications | `org.freedesktop.Notifications` through `notify-rust` |
| relay (MQTT) | ported: three public brokers with failover, persistent session, QoS 1, sealed frames on the topic derived from the invite secret (`app/src/relay.rs`) |
| attachments, voice notes | next |
| calls (`webrtc-rs`) | last |

## Layout

```
rust/
  Cargo.toml            workspace
  core/                 protocol, crypto, transports, call state machine
  ui/                   Slint screens
  app/                  wiring: stores, notifications, updater, entry point
  updater/              delta updater
```

Slint because the Android UI is Compose, and Slint is the Rust toolkit closest
to it: declarative, compiled into the binary, no web view in the middle. A
web view would mean owning the media path twice.

## Port order

Each step lands with its tests, and the wire format does not change.

| Step | Kotlin source | Lines | Rust target |
|---|---|---|---|
| 1 | `domain/codec/InviteCodec.kt` | 139 | `core/src/invite.rs` |
| 2 | `p2p/protocol/Envelope.kt`, `EnvelopeCodec.kt`, `EnvelopeKeys.kt` | 571 | `core/src/envelope.rs` |
| 3 | `crypto/RoomCrypto.kt` | 132 | `core/src/crypto.rs` (dalek + chacha20poly1305) |
| 4 | `p2p/protocol/Framing`, `PeerLink.kt` | 269 | `core/src/framing.rs` |
| 5 | `p2p/lan/LanTransport.kt` | 313 | `app/src/mesh.rs` (UDP multicast + TCP) |
| 6 | `p2p/relay/*.kt` | 761 | `app/src/relay.rs` (rumqttc, TLS, system roots) |
| 7 | `p2p/MeshEngine.kt`, `MeshModel.kt` | 854 | `app/src/mesh.rs` |
| 8 | `p2p/call/CallState.kt`, `CallController.kt` | 701 | `core/src/call/*` (webrtc-rs) |
| 9 | `domain/*`, `data/repo/*` | — | `app/src/store.rs` (SQLite via rusqlite) |
| 10 | `ui/screens/*` | — | `ui/src/*.slint` |

Steps 1 to 8 are the whole protocol; they are what makes a Linux client able to
join an existing chat. Steps 9 and 10 are the local side.

Two of the tests need the internet and are therefore ignored by default. Run
them on purpose:

```bash
cd rust
cargo test -p shutchat -- --ignored                    # both broker tests
cargo test -p shutchat -- --ignored a_message_crosses  # two clients, one message
```

`a_message_crosses_the_relay_between_two_clients` starts two real meshes with
`SHUTCHAT_NO_LAN` set, so the only path between them is the public broker. It is
the test that proves long distance works, and the one to run before a release.

## Parity checklist

Every item below exists on Android today and must exist on Linux:

- home with the chat list, unread counts, last message
- create a chat, show the invite as code and QR, expiry countdown
- join with a code, paste or scan
- conversation: text, attachments, voice notes, reactions, delivery state
- group chat
- audio call and video call, mute, speaker, hang up
- incoming call while the window is closed or behind another window
- settings: display name, notifications, theme, session control
- update check against the release feed

## Notifications and calls on Linux

There is no full screen intent on Linux, so the equivalent is a desktop
notification with actions (`org.freedesktop.Notifications`): `default` opens the
window, `answer` and `decline` are action keys that the app handles without
raising the window. `notify-rust` exposes both. The notification carries the
`phone` hint where the desktop supports it, and a ringtone plays on the media
player until the call is answered or times out.

## Updates without re-downloading

The updater fetches a manifest of files with their digests and only transfers
what changed:

1. `latest.json` lists every file of the release with its BLAKE3 digest.
2. Local files are hashed and compared.
3. Changed files are fetched as zstd patches against the previous release when
   the delta is smaller than the file.
4. `app/` is replaced only after every download verifies.

Chats live in `~/.local/share/shutchat/` and are never touched by an update.

## Updating without losing chats

Same rule as Android: the schema has a version, and every bump ships a
migration. The database is opened with `user_version` checks, and a version the
binary does not know is a hard error rather than a wipe.
