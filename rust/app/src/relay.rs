//! Long-distance transport: public MQTT brokers.
//!
//! This is what makes the client work when the two devices share no network.
//! It carries only frames that are already sealed with the room key, on a topic
//! derived from the invite secret, so the broker forwards bytes it cannot read
//! and cannot tell who is talking.
//!
//! Three properties matter and each one costs something:
//!
//! * **Persistence.** The session is not clean, so a frame published while the
//!   other device is asleep is kept by the broker and delivered on reconnect.
//!   That is the only reason a message reaches a closed laptop at all.
//! * **Failover.** Three independent public brokers are tried in turn, and each
//!   failure walks to the next one with an exponential backoff capped at a
//!   minute, so a dead broker costs one timeout and not a battery.
//! * **Honesty.** Up and down are reported to the mesh, so the UI can say the
//!   relay is off rather than pretend a message was sent.

use rumqttc::{AsyncClient, Event as MqttEvent, MqttOptions, Packet, QoS, Transport};
use std::time::Duration;
use tokio::sync::mpsc;

/// Brokers tried in order. All public and account-free: a broker that wants a
/// signup would tie a room to a person.
pub const DEFAULT_ENDPOINTS: &[(&str, u16)] = &[
    ("broker.hivemq.com", 8883),
    ("broker.emqx.io", 8883),
    ("test.mosquitto.org", 8883),
];

const KEEP_ALIVE: Duration = Duration::from_secs(30);
const RETRY_MIN_MILLIS: u64 = 2_000;
const RETRY_MAX_MILLIS: u64 = 60_000;

pub enum Cmd {
    SetTopics(Vec<String>),
    Send { topic: String, frame: Vec<u8> },
    Shutdown,
}

#[derive(Debug)]
pub enum Event {
    Up,
    Down,
    Frame(Vec<u8>),
}

/// Handle to the relay task: cheap to clone, safe to share.
#[derive(Clone)]
pub struct Relay {
    commands: mpsc::UnboundedSender<Cmd>,
}

impl Relay {
    /// Starts the relay on the current tokio runtime.
    pub fn spawn(node_id: &str, events: mpsc::UnboundedSender<Event>) -> Self {
        let (commands, receiver) = mpsc::unbounded_channel();
        let client_id = format!("shutchat-{node_id}");
        tokio::spawn(async move { run(client_id, events, receiver).await });
        Self { commands }
    }

    pub fn set_topics(&self, topics: Vec<String>) {
        let _ = self.commands.send(Cmd::SetTopics(topics));
    }

    pub fn send(&self, topic: &str, frame: Vec<u8>) {
        let _ = self.commands.send(Cmd::Send {
            topic: topic.to_string(),
            frame,
        });
    }

    pub fn shutdown(&self) {
        let _ = self.commands.send(Cmd::Shutdown);
    }
}

/// Exponential backoff capped at a minute, so a dead broker stays cheap.
fn backoff(attempt: u32) -> Duration {
    let shift = attempt.min(5);
    Duration::from_millis((RETRY_MIN_MILLIS << shift).min(RETRY_MAX_MILLIS))
}

async fn run(
    client_id: String,
    events: mpsc::UnboundedSender<Event>,
    mut commands: mpsc::UnboundedReceiver<Cmd>,
) {
    let mut topics: Vec<String> = Vec::new();
    let mut endpoint = 0usize;
    let mut attempt = 0u32;

    'broker: loop {
        let (host, port) = DEFAULT_ENDPOINTS[endpoint % DEFAULT_ENDPOINTS.len()];
        let mut options = MqttOptions::new(client_id.clone(), host, port);
        options.set_keep_alive(KEEP_ALIVE);
        // Not a clean session: the broker keeps queued QoS 1 frames for this id.
        options.set_clean_session(false);
        options.set_transport(Transport::tls_with_default_config());
        let (client, mut eventloop) = AsyncClient::new(options, 64);

        loop {
            match eventloop.poll().await {
                Ok(MqttEvent::Incoming(Packet::ConnAck(_))) => break,
                Ok(_) => {}
                Err(error) => {
                    eprintln!("relay: {host}:{port} unreachable ({error})");
                    let _ = events.send(Event::Down);
                    endpoint += 1;
                    attempt = attempt.saturating_add(1);
                    tokio::time::sleep(backoff(attempt)).await;
                    continue 'broker;
                }
            }
        }

        for topic in &topics {
            let _ = client.subscribe(topic, QoS::AtLeastOnce).await;
        }
        let _ = events.send(Event::Up);
        attempt = 0;

        loop {
            tokio::select! {
                command = commands.recv() => match command {
                    Some(Cmd::SetTopics(next)) => {
                        for topic in topics.iter().filter(|topic| !next.contains(topic)) {
                            let _ = client.unsubscribe(topic).await;
                        }
                        for topic in next.iter().filter(|topic| !topics.contains(topic)) {
                            let _ = client.subscribe(topic, QoS::AtLeastOnce).await;
                        }
                        topics = next;
                    }
                    Some(Cmd::Send { topic, frame }) => {
                        let _ = client.publish(topic, QoS::AtLeastOnce, false, frame).await;
                    }
                    Some(Cmd::Shutdown) | None => return,
                },
                event = eventloop.poll() => match event {
                    Ok(MqttEvent::Incoming(Packet::Publish(publish))) => {
                        let _ = events.send(Event::Frame(publish.payload.to_vec()));
                    }
                    Ok(_) => {}
                    Err(error) => {
                        eprintln!("relay: {host}:{port} dropped ({error})");
                        let _ = events.send(Event::Down);
                        endpoint += 1;
                        attempt = attempt.saturating_add(1);
                        tokio::time::sleep(backoff(attempt)).await;
                        continue 'broker;
                    }
                },
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_and_stops_at_a_minute() {
        assert_eq!(backoff(0), Duration::from_secs(2));
        assert_eq!(backoff(1), Duration::from_secs(4));
        assert_eq!(backoff(2), Duration::from_secs(8));
        assert_eq!(backoff(10), Duration::from_secs(60));
    }

    /// A real round trip through a public broker. Ignored by default: it needs
    /// the network and borrows infrastructure nobody here controls.
    #[tokio::test]
    #[ignore = "needs a public MQTT broker"]
    async fn two_relays_exchange_a_frame_through_the_broker() {
        let topic = format!(
            "shutchat/v1/test-{}",
            shutchat_core::hex::to_hex(&[std::process::id() as u8; 8])
        );
        let (a_tx, mut a_rx) = mpsc::unbounded_channel();
        let (b_tx, mut b_rx) = mpsc::unbounded_channel();
        let a = Relay::spawn("test-a", a_tx);
        let b = Relay::spawn("test-b", b_tx);
        a.set_topics(vec![topic.clone()]);
        b.set_topics(vec![topic.clone()]);

        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        loop {
            if std::time::Instant::now() > deadline {
                panic!("the relay never came up");
            }
            let up_a = matches!(a_rx.try_recv(), Ok(Event::Up));
            let up_b = matches!(b_rx.try_recv(), Ok(Event::Up));
            if up_a && up_b {
                break;
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        }

        a.send(&topic, b"sealed-frame".to_vec());
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            if std::time::Instant::now() > deadline {
                panic!("the frame never arrived");
            }
            if let Ok(Event::Frame(frame)) = b_rx.try_recv() {
                assert_eq!(frame, b"sealed-frame");
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        a.shutdown();
        b.shutdown();
    }
}
