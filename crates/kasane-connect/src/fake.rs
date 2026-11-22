use crate::adapter::{CmdError, PlayerAdapter, PlayerSnapshot};
use crate::protocol::{HandoffRequest, TrackMeta};
use async_trait::async_trait;
use serde_json::{Map, Value};
use std::sync::Mutex;
use tokio::sync::watch;

/// In-memory fake player for the CLI `serve` mode and loopback tests.
/// Labelled fake: it never touches audio hardware.
pub struct FakePlayer {
    state: Mutex<PlayerSnapshot>,
    changed_tx: watch::Sender<u64>,
    /// Command execution log, for tests.
    pub executed: Mutex<Vec<String>>,
}

impl Default for FakePlayer {
    fn default() -> Self {
        Self::new()
    }
}

impl FakePlayer {
    pub fn new() -> Self {
        let (changed_tx, _) = watch::channel(0u64);
        FakePlayer {
            state: Mutex::new(PlayerSnapshot {
                playback: "idle".into(),
                repeat: "off".into(),
                queue_index: -1,
                ..PlayerSnapshot::default()
            }),
            changed_tx,
            executed: Mutex::new(Vec::new()),
        }
    }

    fn bump(&self) {
        self.changed_tx.send_modify(|v| *v += 1);
    }
}

fn track_arg(args: &Map<String, Value>) -> Result<TrackMeta, CmdError> {
    let v = args.get("track").ok_or_else(|| CmdError::new("invalid_args", "missing track"))?;
    serde_json::from_value(v.clone()).map_err(|e| CmdError::new("invalid_args", e.to_string()))
}

#[async_trait]
impl PlayerAdapter for FakePlayer {
    async fn snapshot(&self) -> PlayerSnapshot {
        self.state.lock().unwrap().clone()
    }

    fn changed(&self) -> watch::Receiver<u64> {
        self.changed_tx.subscribe()
    }

    async fn execute(&self, action: &str, args: &Map<String, Value>) -> Result<(), CmdError> {
        self.executed.lock().unwrap().push(action.to_string());
        let mut s = self.state.lock().unwrap();
        match action {
            "play" => s.playback = "playing".into(),
            "pause" => s.playback = "paused".into(),
            "toggle" => {
                s.playback = if s.playback == "playing" { "paused" } else { "playing" }.into()
            }
            "next" => {
                if s.queue_index + 1 < s.queue.len() as i64 {
                    s.queue_index += 1;
                    s.track = s.queue.get(s.queue_index as usize).cloned();
                }
            }
            "previous" => {
                if s.queue_index > 0 {
                    s.queue_index -= 1;
                    s.track = s.queue.get(s.queue_index as usize).cloned();
                }
            }
            "seek" => {
                s.position_ms = args
                    .get("positionMs")
                    .and_then(Value::as_u64)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing positionMs"))?;
            }
            "setVolume" => {
                s.volume = Some(
                    args.get("volume")
                        .and_then(Value::as_f64)
                        .ok_or_else(|| CmdError::new("invalid_args", "missing volume"))?
                        as f32,
                );
            }
            "setShuffle" => {
                s.shuffle = args
                    .get("enabled")
                    .and_then(Value::as_bool)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing enabled"))?;
            }
            "setRepeat" => {
                s.repeat = args
                    .get("mode")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing mode"))?
                    .to_string();
            }
            "playQueueIndex" => {
                let i = args
                    .get("index")
                    .and_then(Value::as_i64)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing index"))?;
                if i < 0 || i >= s.queue.len() as i64 {
                    return Err(CmdError::new("invalid_args", "index out of bounds"));
                }
                s.queue_index = i;
                s.track = s.queue.get(i as usize).cloned();
                s.playback = "playing".into();
            }
            "removeQueueIndex" => {
                let i = args
                    .get("index")
                    .and_then(Value::as_i64)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing index"))?;
                if i < 0 || i >= s.queue.len() as i64 {
                    return Err(CmdError::new("invalid_args", "index out of bounds"));
                }
                s.queue.remove(i as usize);
                if s.queue_index >= s.queue.len() as i64 {
                    s.queue_index = s.queue.len() as i64 - 1;
                }
            }
            "moveQueue" => {
                let from = args.get("from").and_then(Value::as_i64).unwrap_or(-1);
                let to = args.get("to").and_then(Value::as_i64).unwrap_or(-1);
                let len = s.queue.len() as i64;
                if from < 0 || to < 0 || from >= len || to >= len {
                    return Err(CmdError::new("invalid_args", "index out of bounds"));
                }
                let item = s.queue.remove(from as usize);
                s.queue.insert(to as usize, item);
            }
            "playTrack" => {
                let t = track_arg(args)?;
                let at = ((s.queue_index + 1).max(0) as usize).min(s.queue.len());
                s.queue.insert(at, t.clone());
                s.queue_index += 1;
                s.track = Some(t);
                s.playback = "playing".into();
            }
            "addToQueue" => {
                s.queue.push(track_arg(args)?);
            }
            "playNext" => {
                let t = track_arg(args)?;
                let at = ((s.queue_index + 1).max(0) as usize).min(s.queue.len());
                s.queue.insert(at, t);
            }
            other => return Err(CmdError::new("unsupported", other)),
        }
        drop(s);
        self.bump();
        Ok(())
    }

    async fn handoff(&self, request: &HandoffRequest) -> Result<(), CmdError> {
        let track = request
            .queue
            .get(request.index)
            .cloned()
            .ok_or_else(|| CmdError::new("unsupported_media", "index out of queue"))?;
        if track.id.starts_with("local:") {
            return Err(CmdError::new("unsupported_media", "local tracks cannot transfer"));
        }
        let mut s = self.state.lock().unwrap();
        s.queue = request.queue.clone();
        s.queue_index = request.index as i64;
        s.track = Some(track);
        s.position_ms = request.position_ms;
        s.playing_set(request.playing);
        s.shuffle = request.shuffle;
        s.repeat = request.repeat.clone();
        drop(s);
        self.bump();
        Ok(())
    }

    fn capabilities(&self) -> Vec<String> {
        ["seek", "volume", "queue", "handoff"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    }
}

impl PlayerSnapshot {
    fn playing_set(&mut self, playing: bool) {
        self.playback = if playing { "playing" } else { "paused" }.into();
    }
}
