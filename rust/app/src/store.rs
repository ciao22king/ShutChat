//! Local storage: SQLite in the XDG data directory.
//!
//! The schema carries a version in `PRAGMA user_version`. A version this build
//! does not know is a hard error, never a silent wipe: chats are not re-sent by
//! peers, so dropping the file loses the conversation for good.

use anyhow::{bail, Context, Result};
use rusqlite::{params, Connection};
use shutchat_core::hex;
use std::path::Path;
use std::sync::Mutex;

/// Bump this only together with a migration below.
const SCHEMA_VERSION: i32 = 1;

#[derive(Debug, Clone)]
pub struct Chat {
    pub id: String,
    pub room_id: String,
    pub title: String,
    pub kind: String,
    pub invite_code: String,
    pub invite_expiry_day: Option<i32>,
    pub created_at: i64,
    pub last_activity_at: i64,
    pub last_message_preview: String,
    pub last_message_sender: String,
    pub unread: i32,
    pub secret_hex: String,
}

#[derive(Debug, Clone)]
pub struct Message {
    pub id: String,
    pub chat_id: String,
    pub sender_node_id: String,
    pub sender_name: String,
    pub kind: String,
    pub body: String,
    pub created_at: i64,
    pub mine: bool,
    pub status: String,
}

/// What the mesh needs about a room.
#[derive(Debug, Clone)]
pub struct Room {
    pub chat_id: String,
    pub room_id: String,
    pub secret: [u8; 10],
    pub title: String,
    pub expired: bool,
}

pub struct Store {
    conn: Mutex<Connection>,
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).with_context(|| format!("creating {parent:?}"))?;
        }
        let conn = Connection::open(path).with_context(|| format!("opening {path:?}"))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        migrate(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn identity(&self) -> Result<(String, String)> {
        let conn = self.conn.lock().unwrap();
        let node_id = setting(&conn, "node_id")?.unwrap_or_else(|| {
            let id = hex::to_hex(&shutchat_core::invite::new_secret().repeat(2)[..16]);
            let _ = set_setting(&conn, "node_id", &id);
            id
        });
        let name = setting(&conn, "display_name")?.unwrap_or_else(|| "You".to_string());
        Ok((node_id, name))
    }

    pub fn set_display_name(&self, name: &str) -> Result<()> {
        set_setting(&*self.conn.lock().unwrap(), "display_name", name)
    }

    pub fn chats(&self) -> Result<Vec<Chat>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, room_id, title, kind, invite_code, invite_expiry_day, created_at,
                    last_activity_at, last_message_preview, last_message_sender, unread, secret_hex
             FROM chats ORDER BY last_activity_at DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Chat {
                id: row.get(0)?,
                room_id: row.get(1)?,
                title: row.get(2)?,
                kind: row.get(3)?,
                invite_code: row.get(4)?,
                invite_expiry_day: row.get(5)?,
                created_at: row.get(6)?,
                last_activity_at: row.get(7)?,
                last_message_preview: row.get(8)?,
                last_message_sender: row.get(9)?,
                unread: row.get(10)?,
                secret_hex: row.get(11)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn rooms(&self) -> Result<Vec<Room>> {
        Ok(self
            .chats()?
            .into_iter()
            .filter_map(|chat| {
                let secret = hex::from_hex(&chat.secret_hex).ok()?;
                if secret.len() != 10 {
                    return None;
                }
                let mut fixed = [0u8; 10];
                fixed.copy_from_slice(&secret);
                let expired = shutchat_core::expiry::is_expired(chat.invite_expiry_day);
                Some(Room {
                    chat_id: chat.id,
                    room_id: chat.room_id,
                    secret: fixed,
                    title: chat.title,
                    expired,
                })
            })
            .collect())
    }

    pub fn insert_chat(&self, chat: &Chat) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO chats (id, room_id, title, kind, invite_code, invite_expiry_day, created_at,
                                last_activity_at, last_message_preview, last_message_sender, unread, secret_hex)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,'','',0,?9)",
            params![
                chat.id,
                chat.room_id,
                chat.title,
                chat.kind,
                chat.invite_code,
                chat.invite_expiry_day,
                chat.created_at,
                chat.last_activity_at,
                chat.secret_hex
            ],
        )?;
        Ok(())
    }

    pub fn chat_by_room(&self, room_id: &str) -> Result<Option<Chat>> {
        Ok(self.chats()?.into_iter().find(|chat| chat.room_id == room_id))
    }

    pub fn chat_by_id(&self, chat_id: &str) -> Result<Option<Chat>> {
        Ok(self.chats()?.into_iter().find(|chat| chat.id == chat_id))
    }

    pub fn messages(&self, chat_id: &str) -> Result<Vec<Message>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, chat_id, sender_node_id, sender_name, kind, body, created_at, mine, status
             FROM messages WHERE chat_id = ?1 ORDER BY created_at ASC",
        )?;
        let rows = stmt.query_map(params![chat_id], |row| {
            Ok(Message {
                id: row.get(0)?,
                chat_id: row.get(1)?,
                sender_node_id: row.get(2)?,
                sender_name: row.get(3)?,
                kind: row.get(4)?,
                body: row.get(5)?,
                created_at: row.get(6)?,
                mine: row.get::<_, i32>(7)? != 0,
                status: row.get(8)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Stores a message once; a repeated delivery is not a new row.
    pub fn insert_message(&self, message: &Message) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let inserted = conn.execute(
            "INSERT OR IGNORE INTO messages
                (id, chat_id, sender_node_id, sender_name, kind, body, created_at, mine, status)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
            params![
                message.id,
                message.chat_id,
                message.sender_node_id,
                message.sender_name,
                message.kind,
                message.body,
                message.created_at,
                message.mine as i32,
                message.status
            ],
        )?;
        if inserted > 0 {
            conn.execute(
                "UPDATE chats SET last_activity_at = ?2, last_message_preview = ?3,
                        last_message_sender = ?4,
                        unread = unread + ?5
                 WHERE id = ?1",
                params![
                    message.chat_id,
                    message.created_at,
                    preview(&message.body),
                    message.sender_name,
                    if message.mine { 0 } else { 1 }
                ],
            )?;
        }
        Ok(inserted > 0)
    }

    pub fn set_message_status(&self, ids: &[String], status: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        for id in ids {
            conn.execute(
                "UPDATE messages SET status = ?2 WHERE id = ?1 AND status != 'DELIVERED'",
                params![id, status],
            )?;
        }
        Ok(())
    }

    pub fn mark_read(&self, chat_id: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("UPDATE chats SET unread = 0 WHERE id = ?1", params![chat_id])?;
        Ok(())
    }
}

fn preview(body: &str) -> String {
    let trimmed = body.trim();
    if trimmed.chars().count() > 80 {
        let head: String = trimmed.chars().take(80).collect();
        format!("{head}...")
    } else {
        trimmed.to_string()
    }
}

fn setting(conn: &Connection, key: &str) -> Result<Option<String>> {
    let mut stmt = conn.prepare("SELECT value FROM settings WHERE key = ?1")?;
    let mut rows = stmt.query(params![key])?;
    Ok(rows.next()?.map(|row| row.get::<_, String>(0)).transpose()?)
}

fn set_setting(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO settings (key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

fn migrate(conn: &Connection) -> Result<()> {
    let version: i32 = conn.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if version > SCHEMA_VERSION {
        bail!(
            "this database was written by a newer ShutChat (schema {version}, this build knows {SCHEMA_VERSION}); refusing to touch it"
        );
    }
    if version == SCHEMA_VERSION {
        return Ok(());
    }
    if version == 0 {
        conn.execute_batch(
            "BEGIN;
             CREATE TABLE IF NOT EXISTS chats (
                 id TEXT PRIMARY KEY,
                 room_id TEXT NOT NULL UNIQUE,
                 title TEXT NOT NULL,
                 kind TEXT NOT NULL,
                 invite_code TEXT NOT NULL,
                 invite_expiry_day INTEGER,
                 created_at INTEGER NOT NULL,
                 last_activity_at INTEGER NOT NULL,
                 last_message_preview TEXT NOT NULL DEFAULT '',
                 last_message_sender TEXT NOT NULL DEFAULT '',
                 unread INTEGER NOT NULL DEFAULT 0,
                 secret_hex TEXT NOT NULL
             );
             CREATE TABLE IF NOT EXISTS messages (
                 id TEXT PRIMARY KEY,
                 chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                 sender_node_id TEXT NOT NULL,
                 sender_name TEXT NOT NULL,
                 kind TEXT NOT NULL,
                 body TEXT NOT NULL,
                 created_at INTEGER NOT NULL,
                 mine INTEGER NOT NULL DEFAULT 0,
                 status TEXT NOT NULL
             );
             CREATE INDEX IF NOT EXISTS messages_by_chat ON messages(chat_id, created_at);
             CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
             PRAGMA user_version = 1;
             COMMIT;",
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> Store {
        let dir = std::env::temp_dir().join(format!("shutchat-test-{}", rand::random::<u32>()));
        Store::open(&dir.join("shutchat.db")).unwrap()
    }

    fn sample_chat(id: &str, room: &str) -> Chat {
        Chat {
            id: id.into(),
            room_id: room.into(),
            title: "Test".into(),
            kind: "DIRECT".into(),
            invite_code: "CODE".into(),
            invite_expiry_day: None,
            created_at: 1,
            last_activity_at: 1,
            last_message_preview: String::new(),
            last_message_sender: String::new(),
            unread: 0,
            secret_hex: "a0a1a2a3a4a5a6a7a8a9".into(),
        }
    }

    #[test]
    fn an_identity_is_created_once_and_kept() {
        let store = store();
        let (first, name) = store.identity().unwrap();
        assert_eq!(first.len(), 32);
        assert_eq!(name, "You");
        let (second, _) = store.identity().unwrap();
        assert_eq!(first, second);
    }

    #[test]
    fn a_message_is_stored_once_and_updates_the_chat_row() {
        let store = store();
        store.insert_chat(&sample_chat("c1", "r1")).unwrap();
        let message = Message {
            id: "m1".into(),
            chat_id: "c1".into(),
            sender_node_id: "n1".into(),
            sender_name: "Luca".into(),
            kind: "TEXT".into(),
            body: "ciao".into(),
            created_at: 10,
            mine: false,
            status: "DELIVERED".into(),
        };
        assert!(store.insert_message(&message).unwrap());
        assert!(!store.insert_message(&message).unwrap());
        assert_eq!(store.messages("c1").unwrap().len(), 1);
        let chat = store.chat_by_id("c1").unwrap().unwrap();
        assert_eq!(chat.last_message_preview, "ciao");
        assert_eq!(chat.unread, 1);
    }

    #[test]
    fn reopening_keeps_the_data_and_a_newer_schema_is_refused() {
        let dir = std::env::temp_dir().join(format!("shutchat-test-{}", rand::random::<u32>()));
        let path = dir.join("shutchat.db");
        {
            let store = Store::open(&path).unwrap();
            store.insert_chat(&sample_chat("c1", "r1")).unwrap();
        }
        let reopened = Store::open(&path).unwrap();
        assert_eq!(reopened.chats().unwrap().len(), 1);

        let conn = Connection::open(&path).unwrap();
        conn.pragma_update(None, "user_version", 99).unwrap();
        drop(conn);
        assert!(Store::open(&path).is_err());
        // And the row is still there: refusing is not wiping.
        let conn = Connection::open(&path).unwrap();
        let count: i32 = conn
            .query_row("SELECT COUNT(*) FROM chats", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 1);
    }

    #[test]
    fn rooms_carry_the_secret_the_mesh_needs() {
        let store = store();
        store.insert_chat(&sample_chat("c1", "r1")).unwrap();
        let rooms = store.rooms().unwrap();
        assert_eq!(rooms.len(), 1);
        assert_eq!(rooms[0].secret[0], 0xA0);
        assert_eq!(rooms[0].chat_id, "c1");
    }
}
