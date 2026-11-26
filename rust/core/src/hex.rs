//! Lower-case hex, matching `ByteArray.toHex()` on the Kotlin side.

use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub struct HexError(pub usize);

impl fmt::Display for HexError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "invalid hex digit at byte {}", self.0)
    }
}

impl std::error::Error for HexError {}

pub fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0F) as usize] as char);
    }
    out
}

pub fn from_hex(text: &str) -> Result<Vec<u8>, HexError> {
    let bytes = text.as_bytes();
    if bytes.len() % 2 != 0 {
        return Err(HexError(bytes.len() / 2));
    }
    let mut out = Vec::with_capacity(bytes.len() / 2);
    for (index, pair) in bytes.chunks(2).enumerate() {
        let hi = digit(pair[0]).ok_or(HexError(index))?;
        let lo = digit(pair[1]).ok_or(HexError(index))?;
        out.push((hi << 4) | lo);
    }
    Ok(out)
}

fn digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_rejects_bad_input() {
        assert_eq!(to_hex(&[0x00, 0xA5, 0xFF]), "00a5ff");
        assert_eq!(from_hex("00A5ff").unwrap(), vec![0x00, 0xA5, 0xFF]);
        assert!(from_hex("abc").is_err());
        assert!(from_hex("zz").is_err());
    }
}
