use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// Wire-level device description sent in `hello` and mDNS TXT hints.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    #[serde(default)]
    pub app: String,
    #[serde(default, rename = "appVersion")]
    pub app_version: String,
}

/// Metadata-only track projection. Stream URLs and local paths never cross the wire.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TrackMeta {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub artists: Vec<ArtistMeta>,
    #[serde(default)]
    pub album: Option<AlbumMeta>,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default = "default_source")]
    pub source: String,
}

fn default_source() -> String {
    "youtube".into()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ArtistMeta {
    pub name: String,
    #[serde(default)]
    pub id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AlbumMeta {
    pub title: String,
    #[serde(default)]
    pub id: Option<String>,
}

/// Playback snapshot broadcast by the player.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StateFrame {
    pub rev: u64,
    pub queue_rev: u64,
    pub playback: String,
    #[serde(default)]
    pub track: Option<TrackMeta>,
    #[serde(default)]
    pub position_ms: u64,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub volume: Option<f32>,
    #[serde(default)]
    pub shuffle: bool,
    #[serde(default)]
    pub repeat: String,
    #[serde(default)]
    pub queue_index: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub queue: Option<Vec<TrackMeta>>,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HandoffRequest {
    pub transfer_id: String,
    #[serde(default)]
    pub queue: Vec<TrackMeta>,
    #[serde(default)]
    pub index: usize,
    #[serde(default)]
    pub position_ms: u64,
    #[serde(default)]
    pub playing: bool,
    #[serde(default)]
    pub shuffle: bool,
    #[serde(default)]
    pub repeat: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FrameError {
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

/// One line-delimited protocol frame. Unknown `type` values are rejected
/// before this enum is parsed so the session can answer `unknown_type`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Frame {
    #[serde(rename_all = "camelCase")]
    Hello {
        protocol: u32,
        min_protocol: u32,
        device: DeviceInfo,
    },
    AuthOk,
    AuthRequired,
    Error {
        code: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        message: Option<String>,
    },
    Ping,
    Pong,
    PairRequest {
        commit: String,
    },
    PairNonce {
        ns: String,
    },
    PairReveal {
        nc: String,
    },
    PairConfirm {
        accepted: bool,
    },
    PairResult {
        accepted: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        reason: Option<String>,
    },
    Unpair,
    Subscribe,
    Sync,
    State(Box<StateFrame>),
    Command {
        id: String,
        seq: u64,
        action: String,
        #[serde(flatten)]
        args: Map<String, Value>,
    },
    Ack {
        id: String,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<FrameError>,
    },
    Handoff(Box<HandoffRequest>),
    HandoffResult {
        #[serde(rename = "transferId")]
        transfer_id: String,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<FrameError>,
    },
}

/// The tag the parser needs before dispatching to a concrete variant.
#[derive(Deserialize)]
struct TypeProbe {
    #[serde(rename = "type")]
    kind: String,
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum RawFrame {
    Hello {
        protocol: u32,
        #[serde(rename = "minProtocol")]
        min_protocol: u32,
        device: DeviceInfo,
    },
    AuthOk,
    AuthRequired,
    Error {
        code: String,
        message: Option<String>,
    },
    Ping,
    Pong,
    PairRequest {
        commit: String,
    },
    PairNonce {
        ns: String,
    },
    PairReveal {
        nc: String,
    },
    PairConfirm {
        accepted: bool,
    },
    PairResult {
        accepted: bool,
        reason: Option<String>,
    },
    Unpair,
    Subscribe,
    Sync,
    State(Box<StateFrame>),
    Command {
        id: String,
        seq: u64,
        action: String,
        #[serde(flatten)]
        args: Map<String, Value>,
    },
    Ack {
        id: String,
        ok: bool,
        error: Option<FrameError>,
    },
    Handoff(Box<HandoffRequest>),
    HandoffResult {
        #[serde(rename = "transferId")]
        transfer_id: String,
        ok: bool,
        error: Option<FrameError>,
    },
}

/// Outcome of decoding one line: a frame, an unknown type the peer must be
/// told about, or malformed JSON for a declared type.
#[derive(Debug)]
pub enum Decoded {
    Frame(Frame),
    UnknownType(String),
    Malformed(String),
}

pub fn decode(line: &str) -> Decoded {
    let probe: TypeProbe = match serde_json::from_str(line) {
        Ok(p) => p,
        Err(e) => return Decoded::Malformed(e.to_string()),
    };
    let raw: RawFrame = match serde_json::from_str(line) {
        Ok(f) => f,
        Err(e) => {
            return if e.to_string().contains("unknown variant") {
                Decoded::UnknownType(probe.kind)
            } else {
                Decoded::Malformed(format!("{}: {}", probe.kind, e))
            };
        }
    };
    Decoded::Frame(match raw {
        RawFrame::Hello {
            protocol,
            min_protocol,
            device,
        } => Frame::Hello {
            protocol,
            min_protocol,
            device,
        },
        RawFrame::AuthOk => Frame::AuthOk,
        RawFrame::AuthRequired => Frame::AuthRequired,
        RawFrame::Error { code, message } => Frame::Error { code, message },
        RawFrame::Ping => Frame::Ping,
        RawFrame::Pong => Frame::Pong,
        RawFrame::PairRequest { commit } => Frame::PairRequest { commit },
        RawFrame::PairNonce { ns } => Frame::PairNonce { ns },
        RawFrame::PairReveal { nc } => Frame::PairReveal { nc },
        RawFrame::PairConfirm { accepted } => Frame::PairConfirm { accepted },
        RawFrame::PairResult { accepted, reason } => Frame::PairResult { accepted, reason },
        RawFrame::Unpair => Frame::Unpair,
        RawFrame::Subscribe => Frame::Subscribe,
        RawFrame::Sync => Frame::Sync,
        RawFrame::State(s) => Frame::State(s),
        RawFrame::Command {
            id,
            seq,
            action,
            args,
        } => Frame::Command {
            id,
            seq,
            action,
            args,
        },
        RawFrame::Ack { id, ok, error } => Frame::Ack { id, ok, error },
        RawFrame::Handoff(h) => Frame::Handoff(h),
        RawFrame::HandoffResult {
            transfer_id,
            ok,
            error,
        } => Frame::HandoffResult {
            transfer_id,
            ok,
            error,
        },
    })
}

pub fn encode(frame: &Frame) -> String {
    serde_json::to_string(frame).expect("protocol frames are serializable")
}

impl Frame {
    pub fn error(code: &str) -> Frame {
        Frame::Error {
            code: code.into(),
            message: None,
        }
    }
}
