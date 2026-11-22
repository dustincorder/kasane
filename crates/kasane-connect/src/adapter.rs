use crate::protocol::{HandoffRequest, TrackMeta};
use async_trait::async_trait;
use serde_json::{Map, Value};
use tokio::sync::watch;

/// What the local player reports right now; the service assigns rev/queueRev.
#[derive(Debug, Clone, Default)]
pub struct PlayerSnapshot {
    /// "idle" | "buffering" | "playing" | "paused" | "ended" | "error"
    pub playback: String,
    pub track: Option<TrackMeta>,
    pub position_ms: u64,
    pub duration_ms: u64,
    pub volume: Option<f32>,
    pub shuffle: bool,
    /// "off" | "all" | "one"
    pub repeat: String,
    pub queue_index: i64,
    pub queue: Vec<TrackMeta>,
    pub error: Option<String>,
}

#[derive(Debug, Clone)]
pub struct CmdError {
    pub code: String,
    pub message: String,
}

impl CmdError {
    pub fn new(code: &str, message: impl Into<String>) -> CmdError {
        CmdError {
            code: code.into(),
            message: message.into(),
        }
    }
    pub fn failed(e: impl std::fmt::Display) -> CmdError {
        CmdError::new("failed", e.to_string())
    }
}

/// Bridge between the protocol session and the host player. The host
/// implements this; the crate never sees a concrete player type.
#[async_trait]
pub trait PlayerAdapter: Send + Sync {
    async fn snapshot(&self) -> PlayerSnapshot;
    /// Bumped whenever a non-position field may have changed; also used to
    /// trigger auto-advance evaluation by the state publisher.
    fn changed(&self) -> watch::Receiver<u64>;
    async fn execute(&self, action: &str, args: &Map<String, Value>) -> Result<(), CmdError>;
    async fn handoff(&self, request: &HandoffRequest) -> Result<(), CmdError>;
    fn capabilities(&self) -> Vec<String>;
}
