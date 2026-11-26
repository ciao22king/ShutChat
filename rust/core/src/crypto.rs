//! Room keys, derived from the invite secret with HKDF-SHA256.
//!
//! `RoomCrypto` on the Kotlin side uses the same scheme, so the two clients
//! derive byte-identical keys from the same code — that is what makes a Linux
//! client able to read a room created on a phone.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

pub const NONCE_SIZE: usize = 12;
pub const KEY_SIZE: usize = 32;

pub fn room_id(secret: &[u8]) -> [u8; 16] {
    let mut out = [0u8; 16];
    out.copy_from_slice(&derive(secret, "shutchat/room", 16));
    out
}

pub fn message_key(secret: &[u8]) -> [u8; KEY_SIZE] {
    let mut out = [0u8; KEY_SIZE];
    out.copy_from_slice(&derive(secret, "shutchat/msg", KEY_SIZE));
    out
}

pub fn transfer_key(secret: &[u8]) -> [u8; KEY_SIZE] {
    let mut out = [0u8; KEY_SIZE];
    out.copy_from_slice(&derive(secret, "shutchat/file", KEY_SIZE));
    out
}

pub fn beacon_tag(secret: &[u8]) -> [u8; 8] {
    let mut out = [0u8; 8];
    out.copy_from_slice(&derive(secret, "shutchat/beacon", 8));
    out
}

/// Topic on the public relay: opaque to the broker, computable only by members.
pub fn relay_topic(secret: &[u8]) -> String {
    format!("shutchat/v1/{}", crate::hex::to_hex(&derive(secret, "shutchat/relay", 16)))
}

pub fn fingerprint(secret: &[u8]) -> String {
    crate::hex::to_hex(&derive(secret, "shutchat/fp", 8))
}

/// Eleven digits in three groups, compared out loud by two people.
pub fn safety_number(secret: &[u8]) -> String {
    let digest = derive(secret, "shutchat/sas", 5);
    let mut value: u64 = 0;
    for byte in digest {
        value = (value << 8) | byte as u64;
    }
    let digits = format!("{:011}", value % 100_000_000_000);
    digits
        .as_bytes()
        .chunks(4)
        .map(|chunk| std::str::from_utf8(chunk).unwrap_or("").to_string())
        .collect::<Vec<_>>()
        .join(" ")
}

/// AES-256-GCM. The nonce is prepended, exactly like `RoomCrypto.encrypt`.
pub fn encrypt(key: &[u8], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new_from_slice(key).expect("key size is fixed");
    let mut nonce_bytes = [0u8; NONCE_SIZE];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload { msg: plaintext, aad },
        )
        .expect("GCM encryption cannot fail on valid input");
    let mut out = Vec::with_capacity(NONCE_SIZE + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    out
}

/// @return the plaintext, or None when the payload was tampered with.
pub fn decrypt(key: &[u8], payload: &[u8], aad: &[u8]) -> Option<Vec<u8>> {
    if payload.len() <= NONCE_SIZE {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    cipher
        .decrypt(
            Nonce::from_slice(&payload[..NONCE_SIZE]),
            Payload {
                msg: &payload[NONCE_SIZE..],
                aad,
            },
        )
        .ok()
}

fn derive(input: &[u8], info: &str, length: usize) -> Vec<u8> {
    // HKDF-Extract with an all-zero salt, which is what the Kotlin version does
    // by keying HMAC with 32 zero bytes.
    let mut extract =
        <HmacSha256 as Mac>::new_from_slice(&[0u8; 32]).expect("any key length");
    extract.update(input);
    let prk = extract.finalize().into_bytes();

    let mut out = Vec::with_capacity(length);
    let mut previous: Vec<u8> = Vec::new();
    let mut counter: u8 = 1;
    while out.len() < length {
        let mut expand =
            <HmacSha256 as Mac>::new_from_slice(&prk).expect("any key length");
        expand.update(&previous);
        expand.update(info.as_bytes());
        expand.update(&[counter]);
        previous = expand.finalize().into_bytes().to_vec();
        out.extend_from_slice(&previous);
        counter = counter.wrapping_add(1);
    }
    out.truncate(length);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hex;

    fn golden_secret() -> [u8; 10] {
        let mut secret = [0u8; 10];
        for (index, byte) in secret.iter_mut().enumerate() {
            *byte = 0xA0 + index as u8;
        }
        secret
    }

    /// Every value below was printed by the Kotlin implementation.
    #[test]
    fn keys_match_the_kotlin_derivation() {
        let secret = golden_secret();
        assert_eq!(
            hex::to_hex(&message_key(&secret)),
            "db1237cb7e0ab9e7dab34cb7c320f1c7dc6331cbe3a2c245cb0d586e4700f5d1"
        );
        assert_eq!(
            hex::to_hex(&transfer_key(&secret)),
            "7852ac1b7cc78a93f292cd90ff895e2d6d04b1487e098a5ceed8ab7d933e61e7"
        );
        assert_eq!(hex::to_hex(&beacon_tag(&secret)), "f4cf1538dfe82f5d");
        assert_eq!(
            relay_topic(&secret),
            "shutchat/v1/254d258e13a4c51f16017e364005898b"
        );
        assert_eq!(safety_number(&secret), "7186 1440 492");
        assert_eq!(fingerprint(&secret), "36a76a942251f86a");
    }

    #[test]
    fn gcm_round_trips_and_rejects_a_changed_aad() {
        let key = message_key(&[7u8; 10]);
        let sealed = encrypt(&key, b"hello", b"room");
        assert_eq!(decrypt(&key, &sealed, b"room").unwrap(), b"hello");
        assert_eq!(decrypt(&key, &sealed, b"other"), None);
        assert_eq!(decrypt(&key, &sealed[..8], b"room"), None);
    }

    #[test]
    fn a_flipped_bit_does_not_decrypt() {
        let key = message_key(&[9u8; 10]);
        let mut sealed = encrypt(&key, b"tamper me", b"");
        let last = sealed.len() - 1;
        sealed[last] ^= 0x01;
        assert_eq!(decrypt(&key, &sealed, b""), None);
    }
}
