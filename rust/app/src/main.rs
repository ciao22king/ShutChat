//! ShutChat for Linux.
//!
//! The Android client is the reference: this binary speaks the same protocol,
//! derives the same keys and keeps the same data. The UI is Slint, the network
//! is a LAN mesh, the store is SQLite in the XDG data directory.

mod mesh;
mod relay;
mod store;

use anyhow::{Context, Result};
use mesh::{Event, Mesh, RoomSpec};
use shutchat_core::expiry;
use shutchat_core::hex;
use shutchat_core::invite::{self, InviteKind};
use shutchat_core::WireMessage;
use slint::{ModelRc, Timer, TimerMode, VecModel};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use store::{Chat, Message, Store};

slint::include_modules!();

fn main() -> Result<()> {
    let data_dir = data_directory();
    let store = Arc::new(Store::open(&data_dir.join("shutchat.db"))?);
    let (node_id, display_name) = store.identity()?;
    let (mesh, events) = Mesh::start(node_id.clone(), display_name.clone())?;

    let ui = MainWindow::new().context("creating the window")?;
    ui.set_node_id(node_id.clone().into());
    ui.set_display_name(display_name.clone().into());
    ui.set_name_text(display_name.clone().into());
    ui.set_initial(initial_of(&display_name).into());

    // Relay state is restored before the first room is published, so the topics
    // are subscribed as soon as the client connects to a broker.
    let relay_on = store.relay_enabled().unwrap_or(true);
    mesh.set_relay(relay_on);
    ui.set_relay_on(relay_on);
    ui.set_relay_status(relay_status(relay_on, false).into());

    publish_rooms(&store, &mesh)?;

    // ---------------------------------------------------------------- create
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let mesh = mesh.clone();
        let node_id = node_id.clone();
        let display_name = display_name.clone();
        ui.unwrap().on_create_chat(move |group, days| {
            let ui = match ui.upgrade() {
                Some(ui) => ui,
                None => return,
            };
            let kind = if group { InviteKind::Group } else { InviteKind::Direct };
            let expiry_day = if days > 0 {
                Some(expiry::day_in(days as i64))
            } else {
                None
            };
            let code = invite::create(kind, expiry_day, invite::new_secret());
            let chat = Chat {
                id: hex::to_hex(&random_bytes(16)),
                room_id: invite::room_id_hex(&code),
                title: if group { "Group".into() } else { "Direct chat".into() },
                kind: if group { "GROUP".into() } else { "DIRECT".into() },
                invite_code: code.text.clone(),
                invite_expiry_day: code.expiry_day,
                created_at: expiry::now_millis(),
                last_activity_at: expiry::now_millis(),
                last_message_preview: String::new(),
                last_message_sender: String::new(),
                unread: 0,
                secret_hex: hex::to_hex(&code.secret),
            };
            if let Err(error) = store.insert_chat(&chat) {
                ui.set_notice(format!("{error}").into());
                return;
            }
            let _ = publish_rooms(&store, &mesh);
            ui.set_invite_code(code.text.clone().into());
            ui.set_invite_uri(invite::to_uri(&code).into());
            ui.set_notice("".into());
            let _ = display_name;
            let _ = node_id;
        });
    }

    // ------------------------------------------------------------------ join
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let mesh = mesh.clone();
        let node_id = node_id.clone();
        let display_name = display_name.clone();
        ui.unwrap().on_join(move |text| {
            let ui = match ui.upgrade() {
                Some(ui) => ui,
                None => return,
            };
            let code = match invite::parse(&text) {
                Ok(code) => code,
                Err(error) => {
                    ui.set_notice(format!("{}", error).into());
                    return;
                }
            };
            let room_id = invite::room_id_hex(&code);
            let existing = store.chat_by_room(&room_id).ok().flatten();
            let chat = match existing {
                Some(chat) => chat,
                None => {
                    let chat = Chat {
                        id: hex::to_hex(&random_bytes(16)),
                        room_id,
                        title: match code.kind {
                            InviteKind::Group => "Group".to_string(),
                            InviteKind::Direct => "Direct chat".to_string(),
                        },
                        kind: match code.kind {
                            InviteKind::Group => "GROUP".into(),
                            InviteKind::Direct => "DIRECT".into(),
                        },
                        invite_code: code.text.clone(),
                        invite_expiry_day: code.expiry_day,
                        created_at: expiry::now_millis(),
                        last_activity_at: expiry::now_millis(),
                        last_message_preview: String::new(),
                        last_message_sender: String::new(),
                        unread: 0,
                        secret_hex: hex::to_hex(&code.secret),
                    };
                    if let Err(error) = store.insert_chat(&chat) {
                        ui.set_notice(format!("{error}").into());
                        return;
                    }
                    chat
                }
            };
            let _ = publish_rooms(&store, &mesh);
            ui.set_notice("".into());
            ui.set_join_text("".into());
            open_chat(&ui, &store, &chat, &node_id, &display_name);
        });
    }

    // ------------------------------------------------------------ open a chat
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let node_id = node_id.clone();
        let display_name = display_name.clone();
        ui.unwrap().on_open_chat(move |chat_id| {
            let ui = match ui.upgrade() {
                Some(ui) => ui,
                None => return,
            };
            if let Ok(Some(chat)) = store.chat_by_id(&chat_id) {
                open_chat(&ui, &store, &chat, &node_id, &display_name);
            }
        });
    }

    // ---------------------------------------------------------------- send
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let mesh = mesh.clone();
        let node_id = node_id.clone();
        let display_name = display_name.clone();
        ui.unwrap().on_send(move |text| {
            let ui = match ui.upgrade() {
                Some(ui) => ui,
                None => return,
            };
            let body = text.trim().to_string();
            if body.is_empty() {
                return;
            }
            let Some(chat) = current_chat(&store, &ui) else {
                return;
            };
            let message = Message {
                id: hex::to_hex(&random_bytes(16)),
                chat_id: chat.id.clone(),
                sender_node_id: node_id.clone(),
                sender_name: display_name.clone(),
                kind: "TEXT".into(),
                body: body.clone(),
                created_at: expiry::now_millis(),
                mine: true,
                status: "SENDING".into(),
            };
            if store.insert_message(&message).is_err() {
                return;
            }
            mesh.send(WireMessage {
                room_id: chat.room_id.clone(),
                message_id: message.id.clone(),
                chat_id: chat.id.clone(),
                sender_node_id: node_id.clone(),
                sender_name: display_name.clone(),
                kind: "TEXT".into(),
                body,
                created_at: message.created_at,
                reply_to_id: None,
                reply_to_preview: None,
                attachment: None,
                seen_by: vec![],
                hops: 0,
            });
            ui.set_draft("".into());
            refresh(&ui, &store);
        });
    }

    // ------------------------------------------------------------------ back
    {
        let ui = ui.as_weak();
        ui.unwrap().on_back(move || {
            if let Some(ui) = ui.upgrade() {
                ui.set_notice("".into());
                ui.set_page("home".into());
                ui.set_invite_code("".into());
            }
        });
    }

    // ------------------------------------------------------------- save name
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let mesh = mesh.clone();
        ui.unwrap().on_save_name(move |name| {
            let name = name.trim().to_string();
            if name.is_empty() {
                return;
            }
            let _ = store.set_display_name(&name);
            mesh.set_display_name(name.clone());
            if let Some(ui) = ui.upgrade() {
                ui.set_initial(initial_of(&name).into());
                ui.set_display_name(name.into());
            }
        });
    }

    // ------------------------------------------------------------- clipboard
    {
        let ui = ui.as_weak();
        ui.unwrap().on_copy_code(move |text| {
            if let Some(ui) = ui.upgrade() {
                match arboard::Clipboard::new().and_then(|mut clipboard| clipboard.set_text(text.to_string())) {
                    Ok(()) => ui.set_notice("Copied".into()),
                    Err(error) => ui.set_notice(format!("Could not copy: {error}").into()),
                }
            }
        });
    }

    refresh(&ui, &store);

    // The mesh talks on its own thread; the UI drains what it has to say on the
    // event loop, which is the only place the models may change.
    // ------------------------------------------------------- long distance
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let mesh = mesh.clone();
        ui.unwrap().on_set_relay(move |enabled| {
            let Some(ui) = ui.upgrade() else {
                return;
            };
            let _ = store.set_relay_enabled(enabled);
            mesh.set_relay(enabled);
            ui.set_relay_on(enabled);
            ui.set_relay_up(false);
            ui.set_relay_status(relay_status(enabled, false).into());
        });
    }

    let timer = Timer::default();
    {
        let ui = ui.as_weak();
        let store = store.clone();
        let display_name = display_name.clone();
        timer.start(TimerMode::Repeated, Duration::from_millis(200), move || {
            let Some(ui) = ui.upgrade() else {
                return;
            };
            let mut touched = false;
            while let Ok(event) = events.try_recv() {
                match event {
                    Event::Message { message } => {
                        if let Ok(Some(chat)) = store.chat_by_room(&message.room_id) {
                            let stored = Message {
                                id: message.message_id.clone(),
                                chat_id: chat.id.clone(),
                                sender_node_id: message.sender_node_id.clone(),
                                sender_name: message.sender_name.clone(),
                                kind: message.kind.clone(),
                                body: message.body.clone(),
                                created_at: message.created_at,
                                mine: false,
                                status: "DELIVERED".into(),
                            };
                            let _ = store.insert_message(&stored);
                            notify(&ui, &message.sender_name, &message.body);
                            touched = true;
                        }
                    }
                    Event::Receipt { ids, state } => {
                        let status = if state == "read" { "READ" } else { "DELIVERED" };
                        let _ = store.set_message_status(&ids, status);
                        touched = true;
                    }
                    Event::PeerUp { name, .. } => {
                        ui.set_peer_status(format!("connected to {name}").into());
                    }
                    Event::PeerDown { .. } => {
                        ui.set_peer_status("no peers yet".into());
                    }
                    Event::Relay { up } => {
                        ui.set_relay_up(up);
                        ui.set_relay_status(relay_status(ui.get_relay_on(), up).into());
                    }
                    Event::Status { lan_port, peers } => {
                        ui.set_lan_active(peers > 0);
                        ui.set_lan_status(
                            if peers == 0 {
                                format!("Listening on port {lan_port}")
                            } else {
                                format!("Listening on port {lan_port}, {peers} connected")
                            }
                            .into(),
                        );
                    }
                }
            }
            if touched {
                refresh(&ui, &store);
            }
            let _ = &display_name;
        });
    }

    ui.run().context("running the window")?;
    mesh.shutdown();
    Ok(())
}

/// What the settings screen says about the relay. Three states, because "off"
/// and "not connected yet" mean different things to the person reading it.
fn relay_status(enabled: bool, up: bool) -> String {
    match (enabled, up) {
        (false, _) => "Long distance: off, same network only".to_string(),
        (true, true) => "Long distance: connected to a relay".to_string(),
        (true, false) => "Long distance: trying a relay…".to_string(),
    }
}

/// One letter for the avatar: the first character, upper case.
fn initial_of(name: &str) -> String {
    name.chars()
        .find(|c| !c.is_whitespace())
        .map(|c| c.to_uppercase().to_string())
        .unwrap_or_else(|| "?".to_string())
}

fn data_directory() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("shutchat")
}

fn random_bytes(size: usize) -> Vec<u8> {
    use rand::RngCore;
    let mut bytes = vec![0u8; size];
    rand::thread_rng().fill_bytes(&mut bytes);
    bytes
}

fn publish_rooms(store: &Store, mesh: &Mesh) -> Result<()> {
    let rooms = store
        .rooms()?
        .into_iter()
        .map(|room| {
            RoomSpec::new(
                &room.chat_id,
                &room.room_id,
                &room.secret,
                &room.title,
                room.expired,
            )
        })
        .collect();
    mesh.set_rooms(rooms);
    Ok(())
}

fn current_chat(store: &Store, ui: &MainWindow) -> Option<Chat> {
    store.chat_by_id(&ui.get_chat_id()).ok().flatten()
}

fn open_chat(
    ui: &MainWindow,
    store: &Store,
    chat: &Chat,
    node_id: &str,
    display_name: &str,
) {
    let _ = (node_id, display_name);
    ui.set_chat_id(chat.id.clone().into());
    ui.set_chat_title(chat.title.clone().into());
    ui.set_chat_status(room_status(chat).into());
    let _ = store.mark_read(&chat.id);
    ui.set_page("chat".into());
    ui.set_draft("".into());
    refresh(ui, store);
}

fn room_status(chat: &Chat) -> String {
    let expired = expiry::is_expired(chat.invite_expiry_day);
    if expired {
        return "The code for this room expired: history stays, new messages do not travel".into();
    }
    match expiry::expires_at_millis(chat.invite_expiry_day) {
        Some(deadline) => {
            let days = ((deadline - expiry::now_millis()) as f64 / 86_400_000.0).max(0.0);
            format!("Code valid for another {:.0} days", days.ceil())
        }
        None => "Code never expires".into(),
    }
}

fn refresh(ui: &MainWindow, store: &Store) {
    let chats = store.chats().unwrap_or_default();
    let rows: Vec<ChatRow> = chats
        .iter()
        .map(|chat| ChatRow {
            id: chat.id.clone().into(),
            title: chat.title.clone().into(),
            initial: initial_of(&chat.title).into(),
            preview: chat.last_message_preview.clone().into(),
            when: clock(chat.last_activity_at).into(),
            unread: chat.unread,
            kind: chat.kind.clone().into(),
        })
        .collect();
    ui.set_chats(ModelRc::new(VecModel::from(rows)));

    if ui.get_page() == "chat" {
        if let Some(chat) = current_chat(store, ui) {
            let messages = store.messages(&chat.id).unwrap_or_default();
            let rows: Vec<MessageRow> = messages
                .iter()
                .map(|message| MessageRow {
                    id: message.id.clone().into(),
                    body: message.body.clone().into(),
                    sender: message.sender_name.clone().into(),
                    when: clock(message.created_at).into(),
                    mine: message.mine,
                    status: message.status.clone().into(),
                })
                .collect();
            ui.set_messages(ModelRc::new(VecModel::from(rows)));
        }
    }
}

fn clock(millis: i64) -> String {
    if millis <= 0 {
        return String::new();
    }
    let seconds = (millis / 1000) % 86_400;
    format!("{:02}:{:02}", seconds / 3600, (seconds % 3_600) / 60)
}

fn notify(ui: &MainWindow, sender: &str, body: &str) {
    // Nothing to announce when the conversation is already on screen.
    if ui.get_page() == "chat" {
        return;
    }
    let _ = notify_rust::Notification::new()
        .summary(&format!("ShutChat: {sender}"))
        .body(body)
        .show();
}
