//! LAN discovery beacon: what a device shouts into the multicast group.
//!
//! ```text
//! 0..4     magic 'S' 'C' 'B' 1
//! 4..20    room id (16)
//! 20..36   node id (16)
//! 36..38   TCP port, big endian
//! 38..46   room tag (8 bytes of the derived beacon tag)
//! 46       name length
//! 47..     display name
//! ```

use crate::hex;

pub const MAGIC: [u8; 4] = [b'S', b'C', b'B', 1];
pub const FIXED_SIZE: usize = 4 + 16 + 16 + 2 + 8 + 1;
pub const MAX_NAME_BYTES: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Beacon {
    pub room_id: [u8; 16],
    pub node_id: [u8; 16],
    pub tcp_port: u16,
    pub room_tag: [u8; 8],
    pub display_name: String,
}

impl Beacon {
    pub fn encode(&self) -> Vec<u8> {
        let mut name = self.display_name.as_bytes().to_vec();
        name.truncate(MAX_NAME_BYTES);
        let mut out = Vec::with_capacity(FIXED_SIZE + name.len());
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&self.room_id);
        out.extend_from_slice(&self.node_id);
        out.extend_from_slice(&self.tcp_port.to_be_bytes());
        out.extend_from_slice(&self.room_tag);
        out.push(name.len() as u8);
        out.extend_from_slice(&name);
        out
    }

    pub fn decode(bytes: &[u8]) -> Option<Beacon> {
        if bytes.len() < FIXED_SIZE || bytes[..4] != MAGIC {
            return None;
        }
        let name_length = bytes[46] as usize;
        if bytes.len() < FIXED_SIZE + name_length {
            return None;
        }
        let mut room_id = [0u8; 16];
        room_id.copy_from_slice(&bytes[4..20]);
        let mut node_id = [0u8; 16];
        node_id.copy_from_slice(&bytes[20..36]);
        let tcp_port = u16::from_be_bytes([bytes[36], bytes[37]]);
        let mut room_tag = [0u8; 8];
        room_tag.copy_from_slice(&bytes[38..46]);
        let display_name = String::from_utf8_lossy(&bytes[47..47 + name_length]).to_string();
        Some(Beacon {
            room_id,
            node_id,
            tcp_port,
            room_tag,
            display_name,
        })
    }

    pub fn node_id_hex(&self) -> String {
        hex::to_hex(&self.node_id)
    }

    pub fn room_id_hex(&self) -> String {
        hex::to_hex(&self.room_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Beacon {
        Beacon {
            room_id: [0x11; 16],
            node_id: [0x22; 16],
            tcp_port: 40001,
            room_tag: [0x33; 8],
            display_name: "Luca".into(),
        }
    }

    #[test]
    fn round_trips_every_field() {
        let beacon = sample();
        assert_eq!(Beacon::decode(&beacon.encode()), Some(beacon));
    }

    #[test]
    fn refuses_foreign_magic_and_short_packets() {
        let mut raw = sample().encode();
        raw[0] = b'X';
        assert_eq!(Beacon::decode(&raw), None);
        // A 47-byte packet that claims no name is valid; one that claims a
        // name it does not carry is not.
        assert_eq!(Beacon::decode(&sample().encode()[..FIXED_SIZE]), None);
        let mut nameless = sample().encode();
        nameless.truncate(FIXED_SIZE);
        nameless[46] = 0;
        assert_eq!(
            Beacon::decode(&nameless),
            Some(Beacon {
                display_name: String::new(),
                ..sample()
            })
        );
        assert_eq!(Beacon::decode(&[0u8; 10]), None);
    }
}
