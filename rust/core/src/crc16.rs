//! CRC-16/CCITT-FALSE, as used by the invite code checksum.

pub fn compute(bytes: &[u8]) -> u16 {
    let mut crc: u16 = 0xFFFF;
    for &byte in bytes {
        crc ^= (byte as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 {
                (crc << 1) ^ 0x1021
            } else {
                crc << 1
            };
        }
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_the_ccitt_false_check_value() {
        // Standard check value for "123456789".
        assert_eq!(compute(b"123456789"), 0x29B1);
    }
}
