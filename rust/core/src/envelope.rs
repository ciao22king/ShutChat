//! Control envelopes. JSON, internally tagged on `t`, camelCase fields.
//!
//! Unknown fields are ignored on purpose: that is what lets a newer Android
//! build add a field without the Linux client dropping the conversation.

use serde::{Deserialize, Serialize};

pub struct Protocol;

impl Protocol {
    pub const VERSION: i32 = 1;
    pub const MAX_CONTROL_BYTES: usize = 512 * 1024;
    pub const CHUNK_SIZE: usize = 32 * 1024;
    pub const MAX_FRAME_BYTES: usize = 2 * 1024 * 1024;
    pub const MAX_HOPS: i32 = 4;
    pub const CALL_RING_TIMEOUT_MILLIS: u64 = 45_000;
    pub const CALL_CONNECT_TIMEOUT_MILLIS: u64 = 30_000;
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WireAttachment {
    pub id: String,
    pub file_name: String,
    pub mime_type: String,
    pub size_bytes: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_millis: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WireMember {
    pub node_id: String,
    pub display_name: String,
    pub avatar_seed: String,
    pub is_owner: bool,
    pub joined_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "t", rename_all = "kebab-case", rename_all_fields = "camelCase")]
pub enum Envelope {
    Hello {
        room_id: String,
        node_id: String,
        display_name: String,
        avatar_seed: String,
        #[serde(default)]
        known_room_ids: Vec<String>,
        protocol_version: i32,
        app_version: String,
    },
    HelloAck {
        room_id: String,
        node_id: String,
        display_name: String,
        avatar_seed: String,
        #[serde(default)]
        known_room_ids: Vec<String>,
        protocol_version: i32,
        app_version: String,
    },
    #[serde(rename = "message")]
    WireMessage(WireMessage),
    Receipt {
        room_id: String,
        message_ids: Vec<String>,
        state: String,
        node_id: String,
    },
    Typing {
        room_id: String,
        chat_id: String,
        node_id: String,
        display_name: String,
        typing: bool,
    },
    #[serde(rename = "reaction")]
    ReactionUpdate {
        room_id: String,
        message_id: String,
        node_id: String,
        emoji: String,
        removed: bool,
    },
    #[serde(rename = "edit")]
    EditMessage {
        room_id: String,
        message_id: String,
        node_id: String,
        new_body: String,
        edited_at: i64,
    },
    #[serde(rename = "delete")]
    DeleteMessage {
        room_id: String,
        message_id: String,
        node_id: String,
    },
    #[serde(rename = "members")]
    MemberRoster {
        room_id: String,
        chat_id: String,
        members: Vec<WireMember>,
    },
    #[serde(rename = "join")]
    JoinNotice {
        room_id: String,
        chat_id: String,
        node_id: String,
        display_name: String,
        avatar_seed: String,
    },
    #[serde(rename = "leave")]
    LeaveNotice {
        room_id: String,
        chat_id: String,
        node_id: String,
    },
    #[serde(rename = "sync")]
    SyncRequest {
        room_id: String,
        chat_id: String,
        node_id: String,
        since_millis: i64,
    },
    Ping {
        room_id: String,
        node_id: String,
        sent_at: i64,
    },
    Pong {
        room_id: String,
        node_id: String,
        sent_at: i64,
        received_at: i64,
    },
    TransferStart {
        room_id: String,
        transfer_id: String,
        attachment_id: String,
        message_id: String,
        file_name: String,
        mime_type: String,
        total_chunks: i32,
        total_bytes: i64,
    },
    TransferComplete {
        room_id: String,
        transfer_id: String,
        attachment_id: String,
        message_id: String,
    },
    TransferCancel {
        room_id: String,
        transfer_id: String,
        reason: String,
    },
    CallOffer {
        room_id: String,
        call_id: String,
        from_node_id: String,
        from_name: String,
        media: String,
        sdp: String,
        created_at: i64,
        expires_at: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        to_node_id: Option<String>,
    },
    CallRinging {
        room_id: String,
        call_id: String,
        from_node_id: String,
        to_node_id: String,
    },
    CallAnswer {
        room_id: String,
        call_id: String,
        from_node_id: String,
        to_node_id: String,
        sdp: String,
    },
    CallIce {
        room_id: String,
        call_id: String,
        from_node_id: String,
        to_node_id: String,
        candidate: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        sdp_mid: Option<String>,
        #[serde(default)]
        sdp_m_line_index: i32,
        from_caller: bool,
    },
    CallDecline {
        room_id: String,
        call_id: String,
        from_node_id: String,
        to_node_id: String,
        #[serde(default)]
        reason: String,
    },
    CallHangup {
        room_id: String,
        call_id: String,
        from_node_id: String,
        to_node_id: String,
        #[serde(default)]
        reason: String,
    },
}

/// A message, in the shape the local store wants too.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WireMessage {
    pub room_id: String,
    pub message_id: String,
    pub chat_id: String,
    pub sender_node_id: String,
    pub sender_name: String,
    pub kind: String,
    pub body: String,
    pub created_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reply_to_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reply_to_preview: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attachment: Option<WireAttachment>,
    #[serde(default)]
    pub seen_by: Vec<String>,
    #[serde(default)]
    pub hops: i32,
}

impl Envelope {
    pub fn room_id(&self) -> &str {
        match self {
            Envelope::Hello { room_id, .. }
            | Envelope::HelloAck { room_id, .. }
            | Envelope::Receipt { room_id, .. }
            | Envelope::Typing { room_id, .. }
            | Envelope::ReactionUpdate { room_id, .. }
            | Envelope::EditMessage { room_id, .. }
            | Envelope::DeleteMessage { room_id, .. }
            | Envelope::MemberRoster { room_id, .. }
            | Envelope::JoinNotice { room_id, .. }
            | Envelope::LeaveNotice { room_id, .. }
            | Envelope::SyncRequest { room_id, .. }
            | Envelope::Ping { room_id, .. }
            | Envelope::Pong { room_id, .. }
            | Envelope::TransferStart { room_id, .. }
            | Envelope::TransferComplete { room_id, .. }
            | Envelope::TransferCancel { room_id, .. }
            | Envelope::CallOffer { room_id, .. }
            | Envelope::CallRinging { room_id, .. }
            | Envelope::CallAnswer { room_id, .. }
            | Envelope::CallIce { room_id, .. }
            | Envelope::CallDecline { room_id, .. }
            | Envelope::CallHangup { room_id, .. } => room_id,
            Envelope::WireMessage(message) => &message.room_id,
        }
    }

    /// Identity used to collapse duplicate deliveries.
    pub fn dedupe_key(&self) -> String {
        match self {
            Envelope::WireMessage(m) => format!("m:{}", m.message_id),
            Envelope::Receipt { node_id, message_ids, .. } => {
                format!("r:{}:{}", node_id, message_ids.join(","))
            }
            Envelope::Typing { node_id, chat_id, typing, .. } => {
                format!("t:{}:{}:{}", node_id, chat_id, typing)
            }
            Envelope::ReactionUpdate { message_id, node_id, emoji, removed, .. } => {
                format!("x:{}:{}:{}:{}", message_id, node_id, emoji, removed)
            }
            Envelope::EditMessage { message_id, edited_at, .. } => {
                format!("e:{}:{}", message_id, edited_at)
            }
            Envelope::DeleteMessage { message_id, .. } => format!("d:{}", message_id),
            Envelope::Ping { node_id, sent_at, .. } => format!("p:{}:{}", node_id, sent_at),
            Envelope::Pong { node_id, sent_at, .. } => format!("o:{}:{}", node_id, sent_at),
            Envelope::JoinNotice { node_id, .. } => format!("j:{}", node_id),
            Envelope::LeaveNotice { node_id, .. } => format!("l:{}", node_id),
            Envelope::MemberRoster { chat_id, members, .. } => {
                format!("ro:{}:{}", chat_id, members.len())
            }
            Envelope::SyncRequest { node_id, since_millis, .. } => {
                format!("s:{}:{}", node_id, since_millis)
            }
            Envelope::Hello { node_id, .. } => format!("h:{}", node_id),
            Envelope::HelloAck { node_id, .. } => format!("ha:{}", node_id),
            Envelope::TransferStart { transfer_id, .. } => format!("ts:{}", transfer_id),
            Envelope::TransferComplete { transfer_id, .. } => format!("tc:{}", transfer_id),
            Envelope::TransferCancel { transfer_id, .. } => format!("tx:{}", transfer_id),
            Envelope::CallOffer { call_id, from_node_id, .. } => {
                format!("co:{}:{}", call_id, from_node_id)
            }
            Envelope::CallRinging { call_id, from_node_id, .. } => {
                format!("cr:{}:{}", call_id, from_node_id)
            }
            Envelope::CallAnswer { call_id, from_node_id, .. } => {
                format!("ca:{}:{}", call_id, from_node_id)
            }
            Envelope::CallIce { call_id, from_node_id, sdp_m_line_index, candidate, .. } => {
                format!("ci:{}:{}:{}:{}", call_id, from_node_id, sdp_m_line_index, candidate)
            }
            Envelope::CallDecline { call_id, from_node_id, .. } => {
                format!("cd:{}:{}", call_id, from_node_id)
            }
            Envelope::CallHangup { call_id, from_node_id, .. } => {
                format!("ch:{}:{}", call_id, from_node_id)
            }
        }
    }

    /// The node that sent this envelope, when it names one.
    pub fn sender_node_id(&self) -> &str {
        match self {
            Envelope::WireMessage(m) => &m.sender_node_id,
            Envelope::Hello { node_id, .. }
            | Envelope::HelloAck { node_id, .. }
            | Envelope::Receipt { node_id, .. }
            | Envelope::Typing { node_id, .. }
            | Envelope::ReactionUpdate { node_id, .. }
            | Envelope::EditMessage { node_id, .. }
            | Envelope::DeleteMessage { node_id, .. }
            | Envelope::JoinNotice { node_id, .. }
            | Envelope::LeaveNotice { node_id, .. }
            | Envelope::SyncRequest { node_id, .. }
            | Envelope::Ping { node_id, .. }
            | Envelope::Pong { node_id, .. } => node_id,
            Envelope::CallOffer { from_node_id, .. }
            | Envelope::CallRinging { from_node_id, .. }
            | Envelope::CallAnswer { from_node_id, .. }
            | Envelope::CallIce { from_node_id, .. }
            | Envelope::CallDecline { from_node_id, .. }
            | Envelope::CallHangup { from_node_id, .. } => from_node_id,
            _ => "",
        }
    }

    pub fn is_call_signal(&self) -> bool {
        matches!(
            self,
            Envelope::CallOffer { .. }
                | Envelope::CallRinging { .. }
                | Envelope::CallAnswer { .. }
                | Envelope::CallIce { .. }
                | Envelope::CallDecline { .. }
                | Envelope::CallHangup { .. }
        )
    }

    pub fn encode(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("envelopes are plain data")
    }

    pub fn decode(bytes: &[u8]) -> Option<Envelope> {
        serde_json::from_slice(bytes).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message() -> Envelope {
        Envelope::WireMessage(WireMessage {
            room_id: "abc".into(),
            message_id: "m1".into(),
            chat_id: "c1".into(),
            sender_node_id: "n1".into(),
            sender_name: "Luca".into(),
            kind: "TEXT".into(),
            body: "ciao".into(),
            created_at: 1_700_000_000_000,
            reply_to_id: None,
            reply_to_preview: None,
            attachment: None,
            seen_by: vec![],
            hops: 0,
        })
    }

    #[test]
    fn the_tag_is_t_and_fields_are_camel_case() {
        let json = String::from_utf8(message().encode()).unwrap();
        assert!(json.contains("\"t\":\"message\""), "{json}");
        assert!(json.contains("\"senderNodeId\":\"n1\""), "{json}");
        assert!(json.contains("\"seenBy\":[]"), "{json}");
        // Kotlin omits nulls, so a null must never appear.
        assert!(!json.contains("null"), "{json}");
    }

    #[test]
    fn hello_ack_uses_the_kotlin_tag() {
        let ack = Envelope::HelloAck {
            room_id: "abc".into(),
            node_id: "n1".into(),
            display_name: "Luca".into(),
            avatar_seed: "n1".into(),
            known_room_ids: vec![],
            protocol_version: 1,
            app_version: "1.4.0".into(),
        };
        let json = String::from_utf8(ack.encode()).unwrap();
        assert!(json.contains("\"t\":\"hello-ack\""), "{json}");
    }

    #[test]
    fn round_trip_ignores_fields_it_does_not_know() {
        let json = r#"{"t":"message","roomId":"r","messageId":"m","chatId":"c",
            "senderNodeId":"n","senderName":"L","kind":"TEXT","body":"b",
            "createdAt":1,"seenBy":[],"hops":0,"someFutureField":42}"#;
        let decoded = Envelope::decode(json.as_bytes()).expect("decodes");
        assert_eq!(decoded, message_with_ids());
    }

    fn message_with_ids() -> Envelope {
        Envelope::WireMessage(WireMessage {
            room_id: "r".into(),
            message_id: "m".into(),
            chat_id: "c".into(),
            sender_node_id: "n".into(),
            sender_name: "L".into(),
            kind: "TEXT".into(),
            body: "b".into(),
            created_at: 1,
            reply_to_id: None,
            reply_to_preview: None,
            attachment: None,
            seen_by: vec![],
            hops: 0,
        })
    }

    #[test]
    fn dedupe_and_sender_agree_with_the_android_keys() {
        assert_eq!(message().dedupe_key(), "m:m1");
        assert_eq!(message().sender_node_id(), "n1");
        let order = Envelope::CallOffer {
            room_id: "r".into(),
            call_id: "c".into(),
            from_node_id: "n".into(),
            from_name: "L".into(),
            media: "audio".into(),
            sdp: "v=0".into(),
            created_at: 1,
            expires_at: 2,
            to_node_id: None,
        };
        assert_eq!(order.dedupe_key(), "co:c:n");
        assert!(order.is_call_signal());
    }
}
