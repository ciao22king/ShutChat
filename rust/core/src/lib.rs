//! ShutChat wire protocol.
//!
//! Register-format, crypto and framing are the contract between the Android
//! client and this one, so every piece here is ported from the Kotlin
//! implementation and pinned by the vectors it produces.

pub mod base32;
pub mod beacon;
pub mod crc16;
pub mod crypto;
pub mod envelope;
pub mod expiry;
pub mod framing;
pub mod hex;
pub mod invite;

pub use envelope::{Envelope, Protocol, WireAttachment, WireMessage};
pub use invite::{InviteCode, InviteKind};
