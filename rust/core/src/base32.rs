//! Crockford base32: no I, L, O or U, and decoding folds the ambiguous glyphs.

const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

fn decode_value(c: char) -> Option<u32> {
    match c {
        'I' | 'i' | 'L' | 'l' => Some(1),
        'O' | 'o' | 'U' | 'u' => Some(0),
        other => ALPHABET
            .iter()
            .position(|&b| b as char == other)
            .map(|index| index as u32),
    }
}

pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity((bytes.len() * 8 + 4) / 5);
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;
    for &byte in bytes {
        buffer = (buffer << 8) | byte as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(ALPHABET[((buffer >> bits) & 0x1F) as usize] as char);
        }
    }
    if bits > 0 {
        out.push(ALPHABET[((buffer << (5 - bits)) & 0x1F) as usize] as char);
    }
    out
}

#[derive(Debug, PartialEq, Eq)]
pub struct Base32Error(pub char);

impl std::fmt::Display for Base32Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "invalid character: '{}'", self.0)
    }
}

impl std::error::Error for Base32Error {}

pub fn decode(text: &str) -> Result<Vec<u8>, Base32Error> {
    let mut out = Vec::with_capacity(text.len() * 5 / 8);
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;
    for c in text.chars() {
        let value = decode_value(c).ok_or(Base32Error(c))?;
        buffer = (buffer << 5) | value;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push(((buffer >> bits) & 0xFF) as u8);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fifteen_bytes_become_twenty_four_characters() {
        let bytes: Vec<u8> = (0u8..15).collect();
        assert_eq!(encode(&bytes).len(), 24);
    }

    #[test]
    fn round_trip_is_lossless() {
        for size in 1..64usize {
            let bytes: Vec<u8> = (0..size).map(|i| (i * 37 % 251) as u8).collect();
            assert_eq!(decode(&encode(&bytes)).unwrap(), bytes);
        }
    }

    #[test]
    fn decoding_folds_ambiguous_glyphs() {
        // Same value, four ways of writing it.
        assert_eq!(decode("I").unwrap(), decode("1").unwrap());
        assert_eq!(decode("l").unwrap(), decode("1").unwrap());
        assert_eq!(decode("o").unwrap(), decode("0").unwrap());
        assert_eq!(decode("u").unwrap(), decode("0").unwrap());
        assert!(decode("$").is_err());
    }
}
