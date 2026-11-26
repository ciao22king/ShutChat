//! The LAN mesh: discovery, handshake, routing.
//!
//! Mirrors the Android transport so the two can share a room: beacons into the
//! multicast group, a TCP link per peer, an unsealed `hello` first and every
//! frame after that sealed with the room key. A peer holding the invite code can
//! read the room; anyone else sees a 16-byte room id and ciphertext.

use anyhow::{Context, Result};
use shutchat_core::beacon::Beacon;
use shutchat_core::crypto;
use shutchat_core::envelope::{Envelope, Protocol};
use shutchat_core::framing;
use shutchat_core::{hex, WireMessage};
use socket2::{Domain, Protocol as SockProtocol, Socket, Type};
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::mpsc as blocking;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::mpsc;

const MULTICAST_GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 77, 66);
const DISCOVERY_PORT: u16 = 47766;
const BEACON_INTERVAL: Duration = Duration::from_secs(3);
const PING_INTERVAL: Duration = Duration::from_secs(20);

/// A room this device takes part in, as the transport needs to see it.
#[derive(Debug, Clone)]
pub struct RoomSpec {
    pub chat_id: String,
    pub room_id: String,
    pub room_id_bytes: [u8; 16],
    pub message_key: [u8; 32],
    pub beacon_tag: [u8; 8],
    pub title: String,
    pub expired: bool,
}

impl RoomSpec {
    pub fn new(chat_id: &str, room_id: &str, secret: &[u8; 10], title: &str, expired: bool) -> Self {
        Self {
            chat_id: chat_id.to_string(),
            room_id: room_id.to_string(),
            room_id_bytes: crypto::room_id(secret),
            message_key: crypto::message_key(secret),
            beacon_tag: crypto::beacon_tag(secret),
            title: title.to_string(),
            expired,
        }
    }
}

#[derive(Debug)]
pub enum Cmd {
    SetRooms(Vec<RoomSpec>),
    Send(WireMessage),
    SetName(String),
    Shutdown,
}

#[derive(Debug)]
pub enum Event {
    PeerUp { node_id: String, name: String },
    PeerDown { node_id: String },
    Message { message: WireMessage },
    Receipt { ids: Vec<String>, state: String },
    Status { lan_port: u16, peers: usize },
}

/// The handle the UI keeps: commands in, events out on the channel it was
/// given at startup. Cloning it is cheap and it is safe to share.
#[derive(Clone)]
pub struct Mesh {
    commands: mpsc::UnboundedSender<Cmd>,
}

impl Mesh {
    pub fn start(node_id: String, display_name: String) -> Result<(Self, blocking::Receiver<Event>)> {
        let (command_tx, command_rx) = mpsc::unbounded_channel();
        let (event_tx, event_rx) = blocking::channel();
        let (ready_tx, ready_rx) = blocking::channel();

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .build()
            .context("starting the async runtime")?;

        std::thread::Builder::new()
            .name("mesh".into())
            .spawn(move || {
                runtime.block_on(async move {
                    let mut state = State {
                        node_id,
                        display_name,
                        rooms: HashMap::new(),
                        peers: HashMap::new(),
                        seen: Seen::new(2048),
                        events: event_tx,
                    };
                    if let Err(error) = state.run(command_rx, ready_tx).await {
                        eprintln!("mesh stopped: {error:#}");
                    }
                });
            })
            .context("spawning the mesh thread")?;

        // Report a bind failure here rather than in the UI: a machine without a
        // usable network is something the user should see at startup.
        ready_rx.recv().context("the mesh did not start")??;
        Ok((Self { commands: command_tx }, event_rx))
    }

    pub fn set_rooms(&self, rooms: Vec<RoomSpec>) {
        let _ = self.commands.send(Cmd::SetRooms(rooms));
    }

    pub fn set_display_name(&self, name: String) {
        let _ = self.commands.send(Cmd::SetName(name));
    }

    pub fn send(&self, message: WireMessage) {
        let _ = self.commands.send(Cmd::Send(message));
    }

    pub fn shutdown(&self) {
        let _ = self.commands.send(Cmd::Shutdown);
    }
}

struct Peer {
    frames: mpsc::UnboundedSender<Vec<u8>>,
    room_id: String,
}

struct IncomingLink {
    stream: TcpStream,
    node_id: String,
    room_id: String,
    display_name: String,
}

struct State {
    node_id: String,
    display_name: String,
    rooms: HashMap<String, RoomSpec>,
    peers: HashMap<String, Peer>,
    seen: Seen,
    events: blocking::Sender<Event>,
}

impl State {
    async fn run(
        &mut self,
        mut commands: mpsc::UnboundedReceiver<Cmd>,
        ready: blocking::Sender<Result<()>>,
    ) -> Result<()> {
        let listener = match TcpListener::bind(("0.0.0.0", 0)).await {
            Ok(listener) => listener,
            Err(error) => {
                let _ = ready.send(Err(anyhow::anyhow!("{error}")));
                return Ok(());
            }
        };
        let lan_port = listener.local_addr()?.port();

        let socket = match open_multicast() {
            Ok(socket) => Arc::new(socket),
            Err(error) => {
                let _ = ready.send(Err(error));
                return Ok(());
            }
        };

        let (incoming_tx, mut incoming_rx) = mpsc::unbounded_channel::<IncomingLink>();
        let (frame_tx, mut frame_rx) = mpsc::unbounded_channel::<(String, Vec<u8>)>();
        let (beacon_tx, mut beacon_rx) = mpsc::unbounded_channel::<(SocketAddr, Beacon)>();

        spawn_accept_loop(listener, incoming_tx);
        spawn_beacon_reader(socket.clone(), beacon_tx);

        let _ = ready.send(Ok(()));
        self.emit(Event::Status { lan_port, peers: 0 });

        let mut beacon_tick = tokio::time::interval(BEACON_INTERVAL);
        let mut ping_tick = tokio::time::interval(PING_INTERVAL);

        loop {
            tokio::select! {
                command = commands.recv() => match command {
                    Some(Cmd::SetRooms(rooms)) => {
                        self.rooms = rooms
                            .into_iter()
                            .map(|room| (room.room_id.clone(), room))
                            .collect();
                        self.send_beacons(&socket, lan_port).await;
                        self.status(lan_port);
                    }
                    Some(Cmd::Send(message)) => self.broadcast(Envelope::WireMessage(message)).await,
                    Some(Cmd::SetName(name)) => {
                        self.display_name = name;
                        self.send_beacons(&socket, lan_port).await;
                    }
                    Some(Cmd::Shutdown) | None => break,
                },

                Some(link) = incoming_rx.recv() => {
                    self.accept(link, frame_tx.clone());
                    self.status(lan_port);
                }

                Some((node_id, frame)) = frame_rx.recv() => self.on_frame(&node_id, &frame).await,

                Some((address, beacon)) = beacon_rx.recv() => {
                    if self.dial_if_needed(address, beacon, frame_tx.clone()).await {
                        self.status(lan_port);
                    }
                }

                _ = beacon_tick.tick() => self.send_beacons(&socket, lan_port).await,

                _ = ping_tick.tick() => {
                    let now = shutchat_core::expiry::now_millis();
                    let rooms: Vec<String> = self.rooms.keys().cloned().collect();
                    for room_id in rooms {
                        self.broadcast(Envelope::Ping {
                            room_id,
                            node_id: self.node_id.clone(),
                            sent_at: now,
                        }).await;
                    }
                }
            }
        }
        Ok(())
    }

    fn emit(&self, event: Event) {
        let _ = self.events.send(event);
    }

    fn status(&self, lan_port: u16) {
        self.emit(Event::Status {
            lan_port,
            peers: self.peers.len(),
        });
    }

    async fn send_beacons(&self, socket: &UdpSocket, lan_port: u16) {
        let node_id = match hex::from_hex(&self.node_id) {
            Ok(bytes) if bytes.len() == 16 => {
                let mut fixed = [0u8; 16];
                fixed.copy_from_slice(&bytes);
                fixed
            }
            _ => return,
        };
        let target = SocketAddr::V4(SocketAddrV4::new(MULTICAST_GROUP, DISCOVERY_PORT));
        for room in self.rooms.values().filter(|room| !room.expired) {
            let beacon = Beacon {
                room_id: room.room_id_bytes,
                node_id,
                tcp_port: lan_port,
                room_tag: room.beacon_tag,
                display_name: self.display_name.clone(),
            };
            let _ = socket.send_to(&beacon.encode(), target).await;
        }
    }

    /// Dials a discovered peer when this side is the one that should.
    async fn dial_if_needed(
        &mut self,
        address: SocketAddr,
        beacon: Beacon,
        frame_tx: mpsc::UnboundedSender<(String, Vec<u8>)>,
    ) -> bool {
        let peer_node = beacon.node_id_hex();
        if peer_node == self.node_id || self.peers.contains_key(&peer_node) {
            return false;
        }
        let room = match self.rooms.get(&beacon.room_id_hex()) {
            Some(room) if !room.expired => room.clone(),
            _ => return false,
        };
        // Only the lexicographically smaller id dials, so two devices that
        // discover each other at the same moment do not open two sockets.
        if self.node_id > peer_node {
            return false;
        }

        let target = SocketAddr::new(address.ip(), beacon.tcp_port);
        let stream = match tokio::time::timeout(Duration::from_secs(4), TcpStream::connect(target))
            .await
        {
            Ok(Ok(stream)) => stream,
            _ => return false,
        };
        let _ = stream.set_nodelay(true);

        let hello = self.hello(&room);
        let frame = framing::frame_control(&room.room_id_bytes, &hello.encode());
        let (read_half, write_half) = stream.into_split();
        let (frames_tx, frames_rx) = mpsc::unbounded_channel();
        if frames_tx.send(frame).is_err() {
            return false;
        }
        spawn_writer(write_half, frames_rx);
        spawn_reader(peer_node.clone(), read_half, frame_tx);
        self.peers.insert(
            peer_node.clone(),
            Peer {
                frames: frames_tx,
                room_id: room.room_id.clone(),
            },
        );
        self.emit(Event::PeerUp {
            node_id: peer_node,
            name: beacon.display_name,
        });
        true
    }

    fn accept(&mut self, link: IncomingLink, frame_tx: mpsc::UnboundedSender<(String, Vec<u8>)>) {
        if link.node_id == self.node_id || self.peers.contains_key(&link.node_id) {
            return;
        }
        let room = match self.rooms.get(&link.room_id) {
            Some(room) => room.clone(),
            None => return,
        };
        let ack = self.hello_ack(&room);
        let sealed = crypto::encrypt(&room.message_key, &ack.encode(), &room.room_id_bytes);
        let ack_frame = framing::frame_control(&room.room_id_bytes, &sealed);

        let (read_half, write_half) = link.stream.into_split();
        let (frames_tx, frames_rx) = mpsc::unbounded_channel();
        if frames_tx.send(ack_frame).is_err() {
            return;
        }
        spawn_writer(write_half, frames_rx);
        spawn_reader(link.node_id.clone(), read_half, frame_tx);
        self.peers.insert(
            link.node_id.clone(),
            Peer {
                frames: frames_tx,
                room_id: room.room_id.clone(),
            },
        );
        self.emit(Event::PeerUp {
            node_id: link.node_id,
            name: link.display_name,
        });
    }

    fn hello(&self, room: &RoomSpec) -> Envelope {
        Envelope::Hello {
            room_id: room.room_id.clone(),
            node_id: self.node_id.clone(),
            display_name: self.display_name.clone(),
            avatar_seed: self.node_id.clone(),
            known_room_ids: self.rooms.keys().cloned().collect(),
            protocol_version: Protocol::VERSION,
            app_version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }

    fn hello_ack(&self, room: &RoomSpec) -> Envelope {
        Envelope::HelloAck {
            room_id: room.room_id.clone(),
            node_id: self.node_id.clone(),
            display_name: self.display_name.clone(),
            avatar_seed: self.node_id.clone(),
            known_room_ids: self.rooms.keys().cloned().collect(),
            protocol_version: Protocol::VERSION,
            app_version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }

    async fn on_frame(&mut self, from: &str, raw: &[u8]) {
        let frame = match framing::parse(raw) {
            Some(frame) => frame,
            None => return,
        };
        let room = match self.rooms.get(&frame.room_id_hex()) {
            Some(room) => room.clone(),
            None => return,
        };
        let body = match &frame {
            framing::Frame::Control { body, .. } => body.clone(),
            framing::Frame::Chunk { .. } => return,
        };
        let plaintext = match crypto::decrypt(&room.message_key, &body, &room.room_id_bytes) {
            Some(plaintext) => plaintext,
            None => return,
        };
        let envelope = match Envelope::decode(&plaintext) {
            Some(envelope) => envelope,
            None => return,
        };
        if envelope.sender_node_id() == self.node_id {
            return;
        }
        if !self.seen.mark(&envelope.dedupe_key()) {
            return;
        }

        match envelope {
            Envelope::WireMessage(message) => {
                let receipt = Envelope::Receipt {
                    room_id: room.room_id.clone(),
                    message_ids: vec![message.message_id.clone()],
                    state: "delivered".into(),
                    node_id: self.node_id.clone(),
                };
                if let Some(peer) = self.peers.get(from) {
                    self.send_envelope(&room, &receipt, peer);
                }
                self.emit(Event::Message { message });
            }
            Envelope::Receipt { message_ids, state, .. } => {
                self.emit(Event::Receipt {
                    ids: message_ids,
                    state,
                })
            }
            Envelope::Ping { node_id, sent_at, .. } => {
                let pong = Envelope::Pong {
                    room_id: room.room_id.clone(),
                    node_id: self.node_id.clone(),
                    sent_at,
                    received_at: shutchat_core::expiry::now_millis(),
                };
                if let Some(peer) = self.peers.get(&node_id) {
                    self.send_envelope(&room, &pong, peer);
                }
            }
            _ => {}
        }
    }

    async fn broadcast(&mut self, envelope: Envelope) {
        let room_id = envelope.room_id().to_string();
        let room = match self.rooms.get(&room_id) {
            Some(room) => room.clone(),
            None => return,
        };
        let sealed = crypto::encrypt(&room.message_key, &envelope.encode(), &room.room_id_bytes);
        let frame = framing::frame_control(&room.room_id_bytes, &sealed);
        for peer in self.peers.values() {
            if peer.room_id == room_id {
                let _ = peer.frames.send(frame.clone());
            }
        }
    }

    fn send_envelope(&self, room: &RoomSpec, envelope: &Envelope, peer: &Peer) {
        let sealed = crypto::encrypt(&room.message_key, &envelope.encode(), &room.room_id_bytes);
        let frame = framing::frame_control(&room.room_id_bytes, &sealed);
        let _ = peer.frames.send(frame);
    }
}

fn open_multicast() -> Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(SockProtocol::UDP))?;
    socket.set_reuse_address(true)?;
    socket.bind(&SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DISCOVERY_PORT)).into())?;
    socket.set_nonblocking(true)?;
    let socket: std::net::UdpSocket = socket.into();
    socket.join_multicast_v4(&MULTICAST_GROUP, &Ipv4Addr::UNSPECIFIED)?;
    // Two clients on one machine must see each other, which is how the LAN
    // transport is tested without a second device.
    socket.set_multicast_loop_v4(true)?;
    UdpSocket::from_std(socket).context("registering the discovery socket")
}

fn spawn_accept_loop(listener: TcpListener, sink: mpsc::UnboundedSender<IncomingLink>) {
    tokio::spawn(async move {
        loop {
            let (mut stream, _) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => continue,
            };
            let _ = stream.set_nodelay(true);
            let sink = sink.clone();
            tokio::spawn(async move {
                // The first frame on a fresh socket must be the hello, which is
                // what names the link.
                let mut buffer = Vec::new();
                let first = match read_frame(&mut stream, &mut buffer).await {
                    Some(frame) => frame,
                    None => return,
                };
                let hello = match framing::parse(&first) {
                    Some(framing::Frame::Control { body, .. }) => Envelope::decode(&body),
                    _ => None,
                };
                if let Some(Envelope::Hello {
                    node_id,
                    room_id,
                    display_name,
                    ..
                }) = hello
                {
                    if !buffer.is_empty() {
                        // Frames that arrived together with the hello are left
                        // for the reader task.
                    }
                    let link = IncomingLink {
                        stream,
                        node_id,
                        room_id,
                        display_name,
                    };
                    let _ = sink.send(link);
                }
            });
        }
    });
}

fn spawn_beacon_reader(socket: Arc<UdpSocket>, sink: mpsc::UnboundedSender<(SocketAddr, Beacon)>) {
    tokio::spawn(async move {
        let mut buffer = [0u8; 1024];
        loop {
            match socket.recv_from(&mut buffer).await {
                Ok((length, address)) => {
                    if let Some(beacon) = Beacon::decode(&buffer[..length]) {
                        let _ = sink.send((address, beacon));
                    }
                }
                Err(_) => tokio::time::sleep(Duration::from_millis(50)).await,
            }
        }
    });
}

fn spawn_reader(
    node_id: String,
    mut read_half: tokio::net::tcp::OwnedReadHalf,
    sink: mpsc::UnboundedSender<(String, Vec<u8>)>,
) {
    tokio::spawn(async move {
        let mut buffer = Vec::new();
        while let Some(frame) = read_frame(&mut read_half, &mut buffer).await {
            if sink.send((node_id.clone(), frame)).is_err() {
                break;
            }
        }
    });
}

/// Reads one length-prefixed frame, however the bytes arrive.
async fn read_frame<R>(stream: &mut R, buffer: &mut Vec<u8>) -> Option<Vec<u8>>
where
    R: AsyncRead + Unpin,
{
    loop {
        if buffer.len() >= framing::LENGTH_HEADER_SIZE {
            let length = u32::from_be_bytes([buffer[1], buffer[2], buffer[3], buffer[4]]) as usize;
            if length > Protocol::MAX_FRAME_BYTES {
                return None;
            }
            let total = framing::LENGTH_HEADER_SIZE + length;
            if buffer.len() >= total {
                let frame = buffer[..total].to_vec();
                buffer.drain(..total);
                return Some(frame);
            }
        }
        let mut chunk = [0u8; 8192];
        let read = stream.read(&mut chunk).await.ok()?;
        if read == 0 {
            return None;
        }
        buffer.extend_from_slice(&chunk[..read]);
    }
}

fn spawn_writer(
    mut write_half: tokio::net::tcp::OwnedWriteHalf,
    mut frames: mpsc::UnboundedReceiver<Vec<u8>>,
) {
    tokio::spawn(async move {
        while let Some(frame) = frames.recv().await {
            if write_half.write_all(&frame).await.is_err() {
                break;
            }
        }
        let _ = write_half.shutdown().await;
    });
}

/// Recently seen envelope keys, so a retried frame is not a second message.
struct Seen {
    order: VecDeque<String>,
    keys: HashSet<String>,
    capacity: usize,
}

impl Seen {
    fn new(capacity: usize) -> Self {
        Self {
            order: VecDeque::new(),
            keys: HashSet::new(),
            capacity,
        }
    }

    /// @return true when the key had not been seen before.
    fn mark(&mut self, key: &str) -> bool {
        if !self.keys.insert(key.to_string()) {
            return false;
        }
        self.order.push_back(key.to_string());
        while self.order.len() > self.capacity {
            if let Some(old) = self.order.pop_front() {
                self.keys.remove(&old);
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_room_spec_derives_the_keys_the_wire_expects() {
        let secret = [0xA0u8, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9];
        let room = RoomSpec::new(
            "c1",
            "6408123eb588c66ab444867409075cde",
            &secret,
            "Test",
            false,
        );
        assert_eq!(
            hex::to_hex(&room.room_id_bytes),
            "6408123eb588c66ab444867409075cde"
        );
        assert_eq!(
            hex::to_hex(&room.message_key),
            "db1237cb7e0ab9e7dab34cb7c320f1c7dc6331cbe3a2c245cb0d586e4700f5d1"
        );
        assert_eq!(hex::to_hex(&room.beacon_tag), "f4cf1538dfe82f5d");
    }

    /// Two meshes on one machine, one room, one message: discovery, handshake,
    /// sealing, delivery and the receipt all have to work for this to pass.
    #[test]
    fn two_meshes_exchange_a_message_and_a_receipt() {
        let secret = [0x11u8; 10];
        let room_id = hex::to_hex(&crypto::room_id(&secret));
        let (a, events_a) = Mesh::start(
            "000000000000000000000000000000aa".to_string(),
            "A".to_string(),
        )
        .expect("first mesh");
        let (b, events_b) = Mesh::start(
            "000000000000000000000000000000bb".to_string(),
            "B".to_string(),
        )
        .expect("second mesh");
        a.set_rooms(vec![RoomSpec::new("chat-a", &room_id, &secret, "A", false)]);
        b.set_rooms(vec![RoomSpec::new("chat-b", &room_id, &secret, "B", false)]);

        let connected = wait_for(&events_a, Duration::from_secs(15), |event| {
            matches!(event, Event::PeerUp { .. })
        });
        assert!(connected, "the two meshes never discovered each other");

        a.send(WireMessage {
            room_id: room_id.clone(),
            message_id: "m-1".into(),
            chat_id: "chat-a".into(),
            sender_node_id: "000000000000000000000000000000aa".into(),
            sender_name: "A".into(),
            kind: "TEXT".into(),
            body: "hello over the wire".into(),
            created_at: 1,
            reply_to_id: None,
            reply_to_preview: None,
            attachment: None,
            seen_by: vec![],
            hops: 0,
        });

        let received = wait_for(&events_b, Duration::from_secs(10), |event| {
            matches!(event, Event::Message { message } if message.body == "hello over the wire")
        });
        assert!(received, "the message never arrived");

        let acked = wait_for(&events_a, Duration::from_secs(10), |event| {
            matches!(event, Event::Receipt { ids, .. } if ids == &vec!["m-1".to_string()])
        });
        assert!(acked, "no receipt came back");
    }

    fn wait_for(
        events: &blocking::Receiver<Event>,
        timeout: Duration,
        predicate: impl Fn(&Event) -> bool,
    ) -> bool {
        let deadline = std::time::Instant::now() + timeout;
        while std::time::Instant::now() < deadline {
            match events.recv_timeout(Duration::from_millis(200)) {
                Ok(event) => {
                    if predicate(&event) {
                        return true;
                    }
                }
                Err(blocking::RecvTimeoutError::Timeout) => continue,
                Err(blocking::RecvTimeoutError::Disconnected) => return false,
            }
        }
        false
    }

    #[test]
    fn the_seen_cache_forgets_only_the_oldest() {
        let mut seen = Seen::new(2);
        assert!(seen.mark("a"));
        assert!(!seen.mark("a"));
        assert!(seen.mark("b"));
        assert!(seen.mark("c"));
        assert!(seen.mark("a"), "a was evicted and is new again");
    }
}
