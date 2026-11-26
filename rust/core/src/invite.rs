//! Invite code: 15 bytes, 24 Crockford characters, six groups of four.
//!
//! ```text
//! byte  0      version (high nibble) | kind (low nibble)
//! bytes 1..2   expiry day since 2024-01-01, big endian, 0xFFFF = never
//! bytes 3..4   CRC-16/CCITT over all 15 bytes with this field zeroed
//! bytes 5..14  rendezvous secret (80 bits)
//! ```

use crate::base32;
use crate::crc16;
use crate::expiry;
use crate::hex;
use rand::RngCore;

pub const VERSION: u8 = 1;
pub const PAYLOAD_SIZE: usize = 15;
pub const SECRET_SIZE: usize = 10;
pub const GROUP_SIZE: usize = 4;
pub const URL_PREFIX: &str = "shutchat://join/";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InviteKind {
    Direct,
    Group,
}

impl InviteKind {
    pub fn wire_value(self) -> u8 {
        match self {
            InviteKind::Direct => 1,
            InviteKind::Group => 2,
        }
    }

    pub fn from_wire_value(value: u8) -> Option<Self> {
        match value {
            1 => Some(InviteKind::Direct),
            2 => Some(InviteKind::Group),
            _ => None,
        }
    }
}

/// A parsed invite code plus the canonical text it reads back as.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InviteCode {
    pub text: String,
    pub kind: InviteKind,
    pub expiry_day: Option<i32>,
    pub secret: [u8; SECRET_SIZE],
    pub room_id: [u8; 16],
}

#[derive(Debug, PartialEq, Eq)]
pub enum InviteError {
    Empty,
    BadCharacter(char),
    WrongLength(usize),
    UnsupportedVersion(u8),
    UnknownKind(u8),
    Checksum,
}

impl std::fmt::Display for InviteError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            InviteError::Empty => write!(f, "Enter a code"),
            InviteError::BadCharacter(_) => write!(f, "The code contains invalid characters"),
            InviteError::WrongLength(_) => write!(f, "The code must be 24 characters long"),
            InviteError::UnsupportedVersion(_) => {
                write!(f, "Code created by a newer version of ShutChat")
            }
            InviteError::UnknownKind(_) => write!(f, "Unknown code kind"),
            InviteError::Checksum => write!(f, "Invalid code: check that you copied all of it"),
        }
    }
}

impl std::error::Error for InviteError {}

pub fn new_secret() -> [u8; SECRET_SIZE] {
    let mut secret = [0u8; SECRET_SIZE];
    rand::thread_rng().fill_bytes(&mut secret);
    secret
}

pub fn create(kind: InviteKind, expiry_day: Option<i32>, secret: [u8; SECRET_SIZE]) -> InviteCode {
    let room_id = crate::crypto::room_id(&secret);
    InviteCode {
        text: encode_payload(kind, expiry_day, &secret),
        kind,
        expiry_day,
        secret,
        room_id,
    }
}

pub fn encode_payload(kind: InviteKind, expiry_day: Option<i32>, secret: &[u8]) -> String {
    let mut bytes = [0u8; PAYLOAD_SIZE];
    bytes[0] = (VERSION << 4) | kind.wire_value();
    let day = expiry_day.unwrap_or(expiry::NEVER) as u16;
    bytes[1] = (day >> 8) as u8;
    bytes[2] = day as u8;
    bytes[5..15].copy_from_slice(&secret[..SECRET_SIZE]);
    let crc = crc16::compute(&bytes);
    bytes[3] = (crc >> 8) as u8;
    bytes[4] = crc as u8;
    group(&base32::encode(&bytes))
}

/// Parses user input, tolerating spaces, dashes, lower case and URLs.
pub fn parse(input: &str) -> Result<InviteCode, InviteError> {
    let raw = normalize(input);
    if raw.is_empty() {
        return Err(InviteError::Empty);
    }
    let mut bytes = base32::decode(&raw).map_err(|e| InviteError::BadCharacter(e.0))?;
    if bytes.len() != PAYLOAD_SIZE {
        return Err(InviteError::WrongLength(bytes.len()));
    }
    let version = bytes[0] >> 4;
    if version != VERSION {
        return Err(InviteError::UnsupportedVersion(version));
    }
    let kind = InviteKind::from_wire_value(bytes[0] & 0x0F)
        .ok_or(InviteError::UnknownKind(bytes[0] & 0x0F))?;

    let stored = ((bytes[3] as u16) << 8) | bytes[4] as u16;
    bytes[3] = 0;
    bytes[4] = 0;
    if crc16::compute(&bytes) != stored {
        return Err(InviteError::Checksum);
    }

    let day_raw = ((bytes[1] as u16) << 8) | bytes[2] as u16;
    let expiry_day = if day_raw == expiry::NEVER as u16 {
        None
    } else {
        Some(day_raw as i32)
    };
    let mut secret = [0u8; SECRET_SIZE];
    secret.copy_from_slice(&bytes[5..15]);

    Ok(InviteCode {
        text: encode_payload(kind, expiry_day, &secret),
        kind,
        expiry_day,
        secret,
        room_id: crate::crypto::room_id(&secret),
    })
}

/// Value to hand to a QR code or the share sheet.
pub fn to_uri(code: &InviteCode) -> String {
    format!("{}{}", URL_PREFIX, code.text.replace('-', ""))
}

pub fn room_id_hex(code: &InviteCode) -> String {
    hex::to_hex(&code.room_id)
}

fn normalize(input: &str) -> String {
    let trimmed = input.trim();
    let without_prefix = if trimmed.len() >= URL_PREFIX.len()
        && trimmed[..URL_PREFIX.len()].eq_ignore_ascii_case(URL_PREFIX)
    {
        &trimmed[URL_PREFIX.len()..]
    } else {
        trimmed
    };
    without_prefix
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

fn group(compact: &str) -> String {
    compact
        .as_bytes()
        .chunks(GROUP_SIZE)
        .map(|chunk| std::str::from_utf8(chunk).unwrap_or("").to_string())
        .collect::<Vec<_>>()
        .join("-")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Produced by the Kotlin implementation with secret A0..A9.
    fn golden_secret() -> [u8; SECRET_SIZE] {
        let mut secret = [0u8; SECRET_SIZE];
        for (index, byte) in secret.iter_mut().enumerate() {
            *byte = 0xA0 + index as u8;
        }
        secret
    }

    #[test]
    fn matches_the_kotlin_code_for_the_same_secret() {
        let code = create(InviteKind::Direct, Some(400), golden_secret());
        assert_eq!(code.text, "240S-1SRV-M2GT-58X4-MPKA-FA59");
        assert_eq!(room_id_hex(&code), "6408123eb588c66ab444867409075cde");
        assert_eq!(
            to_uri(&code),
            "shutchat://join/240S1SRVM2GT58X4MPKAFA59"
        );
    }

    #[test]
    fn a_permanent_group_code_matches_kotlin_too() {
        let code = create(InviteKind::Group, None, golden_secret());
        assert_eq!(code.text, "2BZZ-YX91-M2GT-58X4-MPKA-FA59");
        let parsed = parse(&code.text).unwrap();
        assert_eq!(parsed.kind, InviteKind::Group);
        assert_eq!(parsed.expiry_day, None);
    }

    #[test]
    fn round_trip_preserves_every_part() {
        let original = create(InviteKind::Group, Some(1234), golden_secret());
        let parsed = parse(&original.text).unwrap();
        assert_eq!(parsed, original);
        assert_eq!(parsed.text, original.text);
    }

    #[test]
    fn parsing_tolerates_case_spaces_dashes_and_uris() {
        let code = create(InviteKind::Direct, Some(90), golden_secret());
        let compact = code.text.replace('-', "");
        assert_eq!(parse(&compact.to_lowercase()).unwrap().room_id, code.room_id);
        assert_eq!(parse(&format!("  {}  ", code.text)).unwrap().room_id, code.room_id);
        assert_eq!(parse(&to_uri(&code)).unwrap().room_id, code.room_id);
    }

    #[test]
    fn same_secret_always_maps_to_the_same_room() {
        let a = create(InviteKind::Direct, Some(10), golden_secret());
        let b = create(InviteKind::Group, Some(20), golden_secret());
        assert_eq!(a.room_id, b.room_id);
    }

    #[test]
    fn different_secrets_map_to_different_rooms() {
        let a = create(InviteKind::Direct, Some(10), new_secret());
        let b = create(InviteKind::Direct, Some(10), new_secret());
        assert_ne!(a.room_id, b.room_id);
    }

    #[test]
    fn a_corrupted_character_is_caught_by_the_checksum() {
        let code = create(InviteKind::Direct, Some(500), golden_secret());
        let mut chars: Vec<char> = code.text.replace('-', "").chars().collect();
        chars[20] = if chars[20] == 'A' { 'B' } else { 'A' };
        let corrupted: String = chars.into_iter().collect();
        assert_eq!(parse(&corrupted), Err(InviteError::Checksum));
    }

    #[test]
    fn a_short_code_is_rejected_by_length_not_by_checksum() {
        assert!(matches!(parse("ABCD-EFGH"), Err(InviteError::WrongLength(_))));
        assert_eq!(parse("   "), Err(InviteError::Empty));
    }
}
