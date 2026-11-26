//! Binary framing shared by every transport.
//!
//! ```text
//! byte 0        frame type: 1 = control, 2 = chunk
//! bytes 1..4    payload length, big endian
//! payload       roomId (16 raw bytes) || body
//! ```
//!
//! Chunk bodies are `transferId (16) | index (4) | total (4) | sealed bytes`.
//! The room id stays outside the ciphertext so a device can route a frame it
//! cannot read.

use crate::envelope::Protocol;

pub const TYPE_CONTROL: u8 = 1;
pub const TYPE_CHUNK: u8 = 2;

pub const LENGTH_HEADER_SIZE: usize = 5;
pub const ROOM_ID_SIZE: usize = 16;
pub const TRANSFER_HEADER_SIZE: usize = 24;

#[derive(Debug, PartialEq, Eq)]
pub enum Frame {
    Control {
        room_id: [u8; ROOM_ID_SIZE],
        body: Vec<u8>,
    },
    Chunk {
        room_id: [u8; ROOM_ID_SIZE],
        transfer_id: [u8; 16],
        index: u32,
        total: u32,
        body: Vec<u8>,
    },
}

impl Frame {
    pub fn room_id(&self) -> [u8; ROOM_ID_SIZE] {
        match self {
            Frame::Control { room_id, .. } | Frame::Chunk { room_id, .. } => *room_id,
        }
    }

    pub fn room_id_hex(&self) -> String {
        crate::hex::to_hex(&self.room_id())
    }
}

pub fn frame_control(room_id: &[u8; ROOM_ID_SIZE], body: &[u8]) -> Vec<u8> {
    let mut payload = Vec::with_capacity(ROOM_ID_SIZE + body.len());
    payload.extend_from_slice(room_id);
    payload.extend_from_slice(body);
    frame(TYPE_CONTROL, &payload)
}

pub fn frame_chunk(
    room_id: &[u8; ROOM_ID_SIZE],
    transfer_id: &[u8; 16],
    index: u32,
    total: u32,
    body: &[u8],
) -> Vec<u8> {
    let mut payload = Vec::with_capacity(ROOM_ID_SIZE + TRANSFER_HEADER_SIZE + body.len());
    payload.extend_from_slice(room_id);
    payload.extend_from_slice(transfer_id);
    payload.extend_from_slice(&index.to_be_bytes());
    payload.extend_from_slice(&total.to_be_bytes());
    payload.extend_from_slice(body);
    frame(TYPE_CHUNK, &payload)
}

fn frame(frame_type: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(LENGTH_HEADER_SIZE + payload.len());
    out.push(frame_type);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn parse(raw: &[u8]) -> Option<Frame> {
    if raw.len() < LENGTH_HEADER_SIZE {
        return None;
    }
    let length = u32::from_be_bytes([raw[1], raw[2], raw[3], raw[4]]) as usize;
    if length < ROOM_ID_SIZE || length > Protocol::MAX_FRAME_BYTES {
        return None;
    }
    if raw.len() < LENGTH_HEADER_SIZE + length {
        return None;
    }
    let payload = &raw[LENGTH_HEADER_SIZE..LENGTH_HEADER_SIZE + length];
    let mut room_id = [0u8; ROOM_ID_SIZE];
    room_id.copy_from_slice(&payload[..ROOM_ID_SIZE]);
    match raw[0] {
        TYPE_CHUNK => {
            if payload.len() < ROOM_ID_SIZE + TRANSFER_HEADER_SIZE {
                return None;
            }
            let mut transfer_id = [0u8; 16];
            transfer_id.copy_from_slice(&payload[ROOM_ID_SIZE..ROOM_ID_SIZE + 16]);
            let index = u32::from_be_bytes([
                payload[ROOM_ID_SIZE + 16],
                payload[ROOM_ID_SIZE + 17],
                payload[ROOM_ID_SIZE + 18],
                payload[ROOM_ID_SIZE + 19],
            ]);
            let total = u32::from_be_bytes([
                payload[ROOM_ID_SIZE + 20],
                payload[ROOM_ID_SIZE + 21],
                payload[ROOM_ID_SIZE + 22],
                payload[ROOM_ID_SIZE + 23],
            ]);
            Some(Frame::Chunk {
                room_id,
                transfer_id,
                index,
                total,
                body: payload[ROOM_ID_SIZE + TRANSFER_HEADER_SIZE..].to_vec(),
            })
        }
        _ => Some(Frame::Control {
            room_id,
            body: payload[ROOM_ID_SIZE..].to_vec(),
        }),
    }
}

/// Reads length-prefixed frames from a stream that may split them anywhere.
pub struct FrameReader<R> {
    inner: R,
    buffer: Vec<u8>,
}

impl<R: std::io::Read> FrameReader<R> {
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            buffer: Vec::new(),
        }
    }

    /// @return the next whole frame, or None at end of stream.
    pub fn read_frame(&mut self) -> Option<Vec<u8>> {
        while self.buffer.len() < LENGTH_HEADER_SIZE {
            if !self.fill()? {
                return None;
            }
        }
        let length = u32::from_be_bytes([
            self.buffer[1],
            self.buffer[2],
            self.buffer[3],
            self.buffer[4],
        ]) as usize;
        if length > Protocol::MAX_FRAME_BYTES {
            return None;
        }
        let total = LENGTH_HEADER_SIZE + length;
        while self.buffer.len() < total {
            if !self.fill()? {
                return None;
            }
        }
        let frame = self.buffer[..total].to_vec();
        self.buffer.drain(..total);
        Some(frame)
    }

    /// @return Some(false) at end of stream, None when the read failed.
    fn fill(&mut self) -> Option<bool> {
        let mut chunk = [0u8; 4096];
        match self.inner.read(&mut chunk) {
            Ok(0) => Some(false),
            Ok(n) => {
                self.buffer.extend_from_slice(&chunk[..n]);
                Some(true)
            }
            Err(_) => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn control_frames_round_trip() {
        let room = [7u8; 16];
        let raw = frame_control(&room, b"body");
        assert_eq!(raw[0], TYPE_CONTROL);
        assert_eq!(
            parse(&raw),
            Some(Frame::Control { room_id: room, body: b"body".to_vec() })
        );
    }

    #[test]
    fn chunk_frames_carry_the_transfer_header() {
        let room = [1u8; 16];
        let transfer = [2u8; 16];
        let raw = frame_chunk(&room, &transfer, 3, 9, b"abc");
        match parse(&raw) {
            Some(Frame::Chunk { transfer_id, index, total, body, .. }) => {
                assert_eq!(transfer_id, transfer);
                assert_eq!((index, total), (3, 9));
                assert_eq!(body, b"abc");
            }
            other => panic!("expected a chunk, got {other:?}"),
        }
    }

    #[test]
    fn truncated_and_oversized_frames_are_refused() {
        let room = [3u8; 16];
        let raw = frame_control(&room, b"hello");
        assert_eq!(parse(&raw[..4]), None);
        assert_eq!(parse(&raw[..raw.len() - 1]), None);
        let mut lying = raw.clone();
        lying[1..5].copy_from_slice(&(Protocol::MAX_FRAME_BYTES as u32 + 1).to_be_bytes());
        assert_eq!(parse(&lying), None);
    }

    #[test]
    fn the_reader_reassembles_split_frames() {
        let room = [4u8; 16];
        let first = frame_control(&room, b"one");
        let second = frame_control(&room, b"two");
        let mut stream = Vec::new();
        stream.extend_from_slice(&first);
        stream.extend_from_slice(&second);
        let mut reader = FrameReader::new(std::io::Cursor::new(stream));
        assert_eq!(reader.read_frame().unwrap(), first);
        assert_eq!(reader.read_frame().unwrap(), second);
        assert_eq!(reader.read_frame(), None);
    }
}
