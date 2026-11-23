use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use async_trait::async_trait;
use kasane_connect::adapter::{CmdError, PlayerAdapter, PlayerSnapshot};
use kasane_connect::protocol::{AlbumMeta, ArtistMeta, HandoffRequest, TrackMeta};
use serde_json::{Map, Value};
use tokio::sync::watch;
use kasane_audio::PlayerManager;
use kasane_core::model::{Album, Artist, PlaybackState, ProviderType, RepeatMode, Track};
use kasane_db::Database;

/// Bridges the Connect protocol session to the desktop PlayerManager.
pub struct DesktopPlayerAdapter {
    player: Arc<PlayerManager>,
    db: Arc<Database>,
    changed_tx: watch::Sender<u64>,
    changed_seq: AtomicU64,
}

impl DesktopPlayerAdapter {
    pub fn new(player: Arc<PlayerManager>, db: Arc<Database>) -> Self {
        let (changed_tx, _) = watch::channel(0u64);
        Self {
            player,
            db,
            changed_tx,
            changed_seq: AtomicU64::new(0),
        }
    }

    fn notify(&self) {
        self.changed_tx
            .send_replace(self.changed_seq.fetch_add(1, Ordering::SeqCst) + 1);
    }

    fn record(&self, track: Option<Track>) {
        if let Some(track) = track {
            if let Err(error) = self.db.log_history(&track, 0.0) {
                tracing::warn!("Could not update listening history: {error}");
            }
        }
    }
}

fn track_to_meta(track: &Track) -> TrackMeta {
    TrackMeta {
        id: track.id.clone(),
        title: track.title.clone(),
        artist: track.artist.clone(),
        artists: track
            .artists
            .iter()
            .map(|a| ArtistMeta {
                name: a.name.clone(),
                id: Some(a.id.clone()),
            })
            .collect(),
        album: track.album.as_ref().map(|a| AlbumMeta {
            title: a.title.clone(),
            id: Some(a.id.clone()),
        }),
        duration_ms: track.duration_secs * 1000,
        artwork_url: track.thumbnail_url.clone(),
        source: match track.provider {
            ProviderType::LocalLibrary => "local",
            _ => "youtube",
        }
        .into(),
    }
}

fn meta_to_track(meta: &TrackMeta) -> Track {
    Track {
        id: meta.id.clone(),
        title: meta.title.clone(),
        artist: meta.artist.clone(),
        artists: meta
            .artists
            .iter()
            .map(|a| Artist {
                id: a.id.clone().unwrap_or_default(),
                name: a.name.clone(),
            })
            .collect(),
        album: meta.album.as_ref().map(|a| Album {
            id: a.id.clone().unwrap_or_default(),
            title: a.title.clone(),
            cover_url: meta.artwork_url.clone(),
        }),
        duration_secs: meta.duration_ms / 1000,
        thumbnail_url: meta.artwork_url.clone(),
        provider: if meta.source == "local" {
            ProviderType::LocalLibrary
        } else {
            ProviderType::YouTubeMusic
        },
        // Stream URLs never cross the wire; the provider resolves on demand.
        uri: None,
    }
}

fn playback_name(state: &PlaybackState) -> &'static str {
    match state {
        PlaybackState::Playing => "playing",
        PlaybackState::Paused => "paused",
        PlaybackState::Stopped => "idle",
        PlaybackState::Ended => "ended",
        PlaybackState::Buffering => "buffering",
        PlaybackState::Error(_) => "error",
    }
}

fn repeat_name(mode: RepeatMode) -> &'static str {
    match mode {
        RepeatMode::Off => "off",
        RepeatMode::All => "all",
        RepeatMode::One => "one",
    }
}

fn arg_u64(args: &Map<String, Value>, key: &str) -> Result<u64, CmdError> {
    args.get(key)
        .and_then(Value::as_u64)
        .ok_or_else(|| CmdError::new("invalid_args", format!("missing or bad {key}")))
}

fn arg_usize(args: &Map<String, Value>, key: &str) -> Result<usize, CmdError> {
    usize::try_from(arg_u64(args, key)?)
        .map_err(|_| CmdError::new("invalid_args", format!("{key} out of range")))
}

#[async_trait]
impl PlayerAdapter for DesktopPlayerAdapter {
    async fn snapshot(&self) -> PlayerSnapshot {
        // A remote-controlled machine must keep advancing even when no
        // frontend is polling get_playback_state.
        let finished = self.player.advance_if_finished().await.ok().flatten();
        self.record(finished);
        let progress = self.player.get_progress().await.ok();
        let queue = self.player.get_queue().await;
        let volume = self.player.volume().await;
        let mut snap = PlayerSnapshot {
            volume: Some(volume),
            shuffle: queue.shuffle,
            repeat: repeat_name(queue.repeat_mode).into(),
            queue_index: queue.current_index.map(|i| i as i64).unwrap_or(-1),
            queue: queue.items.iter().map(track_to_meta).collect(),
            ..PlayerSnapshot::default()
        };
        if let Some(progress) = progress {
            snap.playback = playback_name(&progress.state).into();
            snap.position_ms = (progress.position_secs * 1000.0) as u64;
            snap.duration_ms = (progress.duration_secs * 1000.0) as u64;
            snap.track = progress.current_track.as_ref().map(track_to_meta);
            if let PlaybackState::Error(message) = &progress.state {
                snap.error = Some(message.clone());
            }
        }
        snap
    }

    fn changed(&self) -> watch::Receiver<u64> {
        self.changed_tx.subscribe()
    }

    async fn execute(&self, action: &str, args: &Map<String, Value>) -> Result<(), CmdError> {
        match action {
            "play" => self.player.play().await.map_err(CmdError::failed)?,
            "pause" => self.player.pause().await.map_err(CmdError::failed)?,
            "toggle" => {
                let track = self
                    .player
                    .toggle_play_pause()
                    .await
                    .map_err(CmdError::failed)?;
                self.record(track);
            }
            "next" => self.record(self.player.next().await.map_err(CmdError::failed)?),
            "previous" => self.record(self.player.previous().await.map_err(CmdError::failed)?),
            "seek" => {
                let ms = arg_u64(args, "positionMs")?;
                self.player
                    .seek(ms as f64 / 1000.0)
                    .await
                    .map_err(CmdError::failed)?;
            }
            "setVolume" => {
                let volume = args
                    .get("volume")
                    .and_then(Value::as_f64)
                    .ok_or_else(|| CmdError::new("invalid_args", "missing or bad volume"))?;
                self.player
                    .set_volume(volume as f32)
                    .await
                    .map_err(CmdError::failed)?;
            }
            "setShuffle" => {
                return Err(CmdError::new(
                    "unsupported",
                    "queue shuffle is not implemented",
                ))
            }
            "setRepeat" => {
                let mode = match args.get("mode").and_then(Value::as_str) {
                    Some("off") => RepeatMode::Off,
                    Some("all") => RepeatMode::All,
                    Some("one") => RepeatMode::One,
                    _ => return Err(CmdError::new("invalid_args", "missing or bad mode")),
                };
                self.player.set_repeat(mode).await;
            }
            "playQueueIndex" => {
                let index = arg_usize(args, "index")?;
                let track = self.player.play_index(index).await.map_err(|e| {
                    CmdError::new(
                        if e.to_string().contains("out of bounds") {
                            "invalid_args"
                        } else {
                            "failed"
                        },
                        e.to_string(),
                    )
                })?;
                self.record(Some(track));
            }
            "removeQueueIndex" => {
                let index = arg_usize(args, "index")?;
                self.player.remove_at(index).await.map_err(CmdError::failed)?;
            }
            "moveQueue" => {
                let from = arg_usize(args, "from")?;
                let to = arg_usize(args, "to")?;
                self.player.move_item(from, to).await.map_err(CmdError::failed)?;
            }
            "playTrack" => {
                let meta: TrackMeta = serde_json::from_value(
                    args.get("track")
                        .cloned()
                        .ok_or_else(|| CmdError::new("invalid_args", "missing track"))?,
                )
                .map_err(|e| CmdError::new("invalid_args", e.to_string()))?;
                let track = meta_to_track(&meta);
                if track.provider == ProviderType::LocalLibrary {
                    return Err(CmdError::new(
                        "unsupported_media",
                        "local tracks cannot be resolved remotely",
                    ));
                }
                let played = self
                    .player
                    .play_track_now(track)
                    .await
                    .map_err(CmdError::failed)?;
                self.record(Some(played));
            }
            "addToQueue" => {
                let meta: TrackMeta = serde_json::from_value(
                    args.get("track")
                        .cloned()
                        .ok_or_else(|| CmdError::new("invalid_args", "missing track"))?,
                )
                .map_err(|e| CmdError::new("invalid_args", e.to_string()))?;
                self.player.add_to_queue(meta_to_track(&meta)).await;
            }
            "playNext" => {
                let meta: TrackMeta = serde_json::from_value(
                    args.get("track")
                        .cloned()
                        .ok_or_else(|| CmdError::new("invalid_args", "missing track"))?,
                )
                .map_err(|e| CmdError::new("invalid_args", e.to_string()))?;
                self.player.insert_next(meta_to_track(&meta)).await;
            }
            _ => return Err(CmdError::new("unsupported", format!("unknown action {action}"))),
        }
        self.notify();
        Ok(())
    }

    async fn handoff(&self, request: &HandoffRequest) -> Result<(), CmdError> {
        let tracks: Vec<Track> = request.queue.iter().map(meta_to_track).collect();
        if tracks.iter().any(|t| t.provider == ProviderType::LocalLibrary) {
            return Err(CmdError::new(
                "unsupported_media",
                "local tracks cannot be resolved remotely",
            ));
        }
        let repeat = match request.repeat.as_str() {
            "all" => RepeatMode::All,
            "one" => RepeatMode::One,
            _ => RepeatMode::Off,
        };
        let track = self
            .player
            .handoff_queue(
                tracks,
                request.index,
                request.position_ms as f64 / 1000.0,
                request.playing,
                request.shuffle,
                repeat,
            )
            .await
            .map_err(|e| {
                let code = if e.to_string().contains("out of bounds") {
                    "invalid_args"
                } else {
                    "failed"
                };
                CmdError::new(code, e.to_string())
            })?;
        self.record(Some(track));
        self.notify();
        Ok(())
    }

    fn capabilities(&self) -> Vec<String> {
        vec!["seek".into(), "volume".into(), "queue".into(), "handoff".into()]
    }
}

#[derive(serde::Serialize)]
pub struct LocalDevice {
    id: String,
    name: String,
    platform: String,
    port: u16,
    fingerprint: String,
}

#[derive(Clone, serde::Serialize)]
struct ConnectUpdate {
    devices: Vec<kasane_connect::service::DeviceView>,
    prompts: Vec<kasane_connect::service::PairingPrompt>,
}

/// Push device/prompt changes to the frontend as `connect://update`.
pub fn spawn_update_forwarder(
    app: tauri::AppHandle,
    service: kasane_connect::ConnectService,
) {
    use tauri::Emitter;
    let mut devices = service.devices();
    let mut prompts = service.pairing_prompts();
    tauri::async_runtime::spawn(async move {
        loop {
            let update = ConnectUpdate {
                devices: devices.borrow().clone(),
                prompts: prompts.borrow().clone(),
            };
            if let Err(error) = app.emit("connect://update", update) {
                tracing::warn!("Could not emit connect update: {error}");
            }
            tokio::select! {
                changed = devices.changed() => if changed.is_err() { break },
                changed = prompts.changed() => if changed.is_err() { break },
                _ = service.wait_shutdown() => break,
            }
        }
    });
}

pub mod commands {
    use super::*;
    use crate::AppState;
    use kasane_connect::protocol::{HandoffRequest, StateFrame};
    use kasane_connect::service::{DeviceView, PairingPrompt};
    use tauri::State;

    #[tauri::command]
    pub async fn connect_local(state: State<'_, AppState>) -> Result<LocalDevice, String> {
        let device = state.connect.read().await.local_device();
        Ok(LocalDevice {
            id: device.id,
            name: device.name,
            platform: device.platform,
            port: state.connect.read().await.port(),
            fingerprint: state.connect.read().await.fingerprint(),
        })
    }

    #[tauri::command]
    pub async fn connect_devices(state: State<'_, AppState>) -> Result<Vec<DeviceView>, String> {
        Ok(state.connect.read().await.devices().borrow().clone())
    }

    #[tauri::command]
    pub async fn connect_prompts(
        state: State<'_, AppState>,
    ) -> Result<Vec<PairingPrompt>, String> {
        Ok(state.connect.read().await.pairing_prompts().borrow().clone())
    }

    #[tauri::command]
    pub async fn connect_pair(
        state: State<'_, AppState>,
        target: String,
    ) -> Result<Option<String>, String> {
        state
            .connect.read().await
            .pair(&target)
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn connect_confirm(
        state: State<'_, AppState>,
        prompt_id: String,
        accepted: bool,
    ) -> Result<(), String> {
        state.connect.read().await.confirm_pairing(&prompt_id, accepted);
        Ok(())
    }

    #[tauri::command]
    pub async fn connect_command(
        state: State<'_, AppState>,
        device_id: String,
        action: String,
        args: Option<Map<String, Value>>,
    ) -> Result<(), String> {
        state
            .connect.read().await
            .send_command(&device_id, &action, args.unwrap_or_default())
            .await
            .map_err(|e| format!("{}: {}", e.code, e.message))
    }

    #[tauri::command]
    pub async fn connect_handoff(
        state: State<'_, AppState>,
        device_id: String,
        request: HandoffRequest,
    ) -> Result<(), String> {
        state
            .connect.read().await
            .handoff(&device_id, request)
            .await
            .map_err(|e| format!("{}: {}", e.code, e.message))
    }

    #[tauri::command]
    pub async fn connect_remote_state(
        state: State<'_, AppState>,
        device_id: String,
    ) -> Result<Option<StateFrame>, String> {
        Ok(state
            .connect.read().await
            .remote_state(&device_id)
            .and_then(|rx| rx.borrow().clone()))
    }

    /// Pull the remote device's current queue into the local player.
    #[tauri::command]
    pub async fn connect_pull(
        state: State<'_, AppState>,
        device_id: String,
    ) -> Result<(), String> {
        let frame = state
            .connect.read().await
            .remote_state(&device_id)
            .and_then(|rx| rx.borrow().clone())
            .ok_or("the device is not connected")?;
        let queue = frame.queue.unwrap_or_default();
        let tracks: Vec<Track> = queue.iter().map(meta_to_track).collect();
        if tracks.iter().any(|t| t.provider == ProviderType::LocalLibrary) {
            return Err("the remote queue contains local files this device cannot resolve".into());
        }
        let repeat = match frame.repeat.as_str() {
            "all" => RepeatMode::All,
            "one" => RepeatMode::One,
            _ => RepeatMode::Off,
        };
        let index = usize::try_from(frame.queue_index.max(0))
            .map_err(|_| "bad queue index".to_string())?;
        let track = state
            .player
            .handoff_queue(
                tracks,
                index,
                frame.position_ms as f64 / 1000.0,
                frame.playback == "playing",
                frame.shuffle,
                repeat,
            )
            .await
            .map_err(|e| e.to_string())?;
        if let Err(error) = state.db.log_history(&track, 0.0) {
            tracing::warn!("Could not update listening history: {error}");
        }
        Ok(())
    }

    #[tauri::command]
    pub async fn connect_unpair(state: State<'_, AppState>, device_id: String) -> Result<(), String> {
        state.connect.read().await.unpair(&device_id).await;
        Ok(())
    }

    #[derive(serde::Serialize)]
    pub struct ConnectStatus {
        enabled: bool,
        device_name: String,
        device_id: String,
        port: u16,
    }

    /// Connect on/off + this device's name — for the Settings view. The name
    /// stays available while the service is stopped.
    #[tauri::command]
    pub async fn connect_status(state: State<'_, AppState>) -> Result<ConnectStatus, String> {
        let svc = state.connect.read().await;
        let device = svc.local_device();
        Ok(ConnectStatus {
            enabled: svc.is_running(),
            device_name: device.name,
            device_id: device.id,
            port: svc.port(),
        })
    }

    /// Settings toggle: stops the running service (listener, mDNS, sessions)
    /// or starts a fresh one from the saved config.
    #[tauri::command]
    pub async fn connect_set_enabled(
        app: tauri::AppHandle,
        state: State<'_, AppState>,
        enabled: bool,
    ) -> Result<ConnectStatus, String> {
        let mut guard = state.connect.write().await;
        if enabled == guard.is_running() {
            let device = guard.local_device();
            return Ok(ConnectStatus {
                enabled: guard.is_running(),
                device_name: device.name,
                device_id: device.id,
                port: guard.port(),
            });
        }
        if !enabled {
            guard.stop();
        } else {
            let cfg = &state.connect_cfg;
            let service = kasane_connect::ConnectService::start(
                &cfg.data_dir,
                &cfg.device_name,
                cfg.platform,
                cfg.app_version,
                cfg.adapter.clone(),
                true,
            )
            .await
            .map_err(|e| format!("Kasane Connect could not start: {e}"))?;
            spawn_update_forwarder(app.clone(), service.clone());
            *guard = service;
        }
        let device = guard.local_device();
        Ok(ConnectStatus {
            enabled: guard.is_running(),
            device_name: device.name,
            device_id: device.id,
            port: guard.port(),
        })
    }
}
