use std::{path::PathBuf, sync::Arc};
use tauri::{Manager, State, WebviewWindow};
use tauri_plugin_dialog::DialogExt;
use kasane_audio::{DesktopAudioBackend, PlayerManager};
use kasane_core::error::KasaneError;
use kasane_core::model::{PlaybackProgress, ProviderType, QueueState, Track};
use kasane_core::traits::MusicProvider;
use kasane_db::Database;
use kasane_providers::{WebPlaybackVerifier, YouTubeMusicProvider};

mod account;
mod connect;
mod verifier;

/// Everything needed to re-create the ConnectService when the Settings
/// toggle switches it back on.
pub struct ConnectCfg {
    pub data_dir: PathBuf,
    pub device_name: String,
    pub platform: &'static str,
    pub app_version: &'static str,
    pub adapter: Arc<connect::DesktopPlayerAdapter>,
}

pub struct AppState {
    pub player: Arc<PlayerManager>,
    pub db: Arc<Database>,
    pub yt_provider: Arc<YouTubeMusicProvider>,
    /// The live service while Connect is on; a stopped service while off
    /// (`is_running()` false). Replaced on re-enable.
    pub connect: tokio::sync::RwLock<kasane_connect::ConnectService>,
    pub connect_cfg: ConnectCfg,
    pub account: account::AccountRuntime,
}

mod commands {
    use super::*;

    #[tauri::command]
    pub async fn search_tracks(
        state: State<'_, AppState>,
        query: String,
    ) -> Result<Vec<Track>, String> {
        state
            .yt_provider
            .search(&query, 20)
            .await
            .map_err(|e| e.to_string())
    }

    fn record_history(state: &AppState, track: Option<Track>) {
        if let Some(track) = track {
            if let Err(error) = state.db.log_history(&track, 0.0) {
                tracing::warn!("Could not update listening history: {error}");
            }
        }
    }

    #[tauri::command]
    pub async fn open_audio_file(
        window: WebviewWindow,
        state: State<'_, AppState>,
    ) -> Result<(), String> {
        let (reply, selected) = tokio::sync::oneshot::channel();
        window
            .dialog()
            .file()
            .set_parent(&window)
            .add_filter("Audio", &["mp3", "wav", "flac", "ogg", "m4a", "aac"])
            .pick_file(move |file| {
                let _ = reply.send(file);
            });
        let Some(file) = selected
            .await
            .map_err(|_| "Could not open the file picker")?
        else {
            return Ok(());
        };
        let path = file.into_path().map_err(|_| "Choose a local audio file")?;
        let path_string = path.to_str().ok_or("This file path is not valid UTF-8")?;
        let track = Track {
            id: format!("local:{path_string}"),
            title: path
                .file_stem()
                .and_then(|name| name.to_str())
                .unwrap_or("Local audio")
                .into(),
            artist: "Local file".into(),
            artists: Vec::new(),
            album: None,
            duration_secs: 0,
            thumbnail_url: None,
            provider: ProviderType::LocalLibrary,
            uri: Some(path_string.into()),
        };
        play_track(state, track).await
    }

    #[tauri::command]
    pub async fn play_track(state: State<'_, AppState>, track: Track) -> Result<(), String> {
        let track = state
            .player
            .play_track_now(track)
            .await
            .map_err(|e| e.to_string())?;
        record_history(&state, Some(track));
        Ok(())
    }

    #[tauri::command]
    pub async fn add_to_queue(state: State<'_, AppState>, track: Track) -> Result<(), String> {
        state.player.add_to_queue(track).await;
        Ok(())
    }

    #[tauri::command]
    pub async fn play_queue_item(state: State<'_, AppState>, index: usize) -> Result<(), String> {
        let track = state
            .player
            .play_index(index)
            .await
            .map_err(|e| e.to_string())?;
        record_history(&state, Some(track));
        Ok(())
    }

    #[tauri::command]
    pub async fn toggle_play_pause(state: State<'_, AppState>) -> Result<(), String> {
        let track = state
            .player
            .toggle_play_pause()
            .await
            .map_err(|e| e.to_string())?;
        record_history(&state, track);
        Ok(())
    }

    #[tauri::command]
    pub async fn next_track(state: State<'_, AppState>) -> Result<(), String> {
        let track = state.player.next().await.map_err(|e| e.to_string())?;
        record_history(&state, track);
        Ok(())
    }

    #[tauri::command]
    pub async fn previous_track(state: State<'_, AppState>) -> Result<(), String> {
        let track = state.player.previous().await.map_err(|e| e.to_string())?;
        record_history(&state, track);
        Ok(())
    }

    #[tauri::command]
    pub async fn seek(state: State<'_, AppState>, position_secs: f64) -> Result<(), String> {
        state
            .player
            .seek(position_secs)
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn set_volume(state: State<'_, AppState>, volume: f32) -> Result<(), String> {
        state
            .player
            .set_volume(volume)
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn get_playback_state(
        state: State<'_, AppState>,
    ) -> Result<PlaybackProgress, String> {
        let finished = state
            .player
            .advance_if_finished()
            .await
            .map_err(|e| e.to_string())?;
        record_history(&state, finished);
        state.player.get_progress().await.map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn get_queue(state: State<'_, AppState>) -> Result<QueueState, String> {
        Ok(state.player.get_queue().await)
    }

    #[tauri::command]
    pub async fn get_favorites(state: State<'_, AppState>) -> Result<Vec<Track>, String> {
        state.db.get_favorites().map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn toggle_favorite(state: State<'_, AppState>, track: Track) -> Result<bool, String> {
        let is_fav = state.db.is_favorite(&track.id).map_err(|e| e.to_string())?;
        if is_fav {
            state
                .db
                .remove_favorite(&track.id)
                .map_err(|e| e.to_string())?;
            Ok(false)
        } else {
            state.db.save_favorite(&track).map_err(|e| e.to_string())?;
            Ok(true)
        }
    }

    #[tauri::command]
    pub async fn get_history(
        state: State<'_, AppState>,
        limit: usize,
    ) -> Result<Vec<Track>, String> {
        state
            .db
            .get_recent_history(limit)
            .map_err(|e| e.to_string())
    }
}

fn open_library_database(app_dir: tauri::Result<PathBuf>) -> kasane_core::error::Result<Database> {
    let persistent = app_dir
        .map_err(|error| KasaneError::Database(error.to_string()))
        .and_then(|dir| {
            std::fs::create_dir_all(&dir)?;
            Database::open(dir.join("kasane.db"))
        });
    persistent.or_else(|error| {
        eprintln!("Kasane could not open the library database: {error}. Favorites and listening history will not be saved for this session.");
        Database::in_memory()
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = tracing_subscriber::fmt::try_init();
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let db = Arc::new(open_library_database(app.path().app_data_dir())?);
            let replies = verifier::Replies::default();
            app.manage(replies.clone());
            let runtime = Arc::new(verifier::WebviewRuntime::new(app.handle().clone(), replies));
            let yt_provider = Arc::new(
                YouTubeMusicProvider::new()
                    .with_verifier(Arc::new(WebPlaybackVerifier::new(runtime))),
            );
            let player = Arc::new(PlayerManager::new(
                Box::new(DesktopAudioBackend::new()?),
                yt_provider.clone(),
            ));
            let adapter = Arc::new(connect::DesktopPlayerAdapter::new(
                player.clone(),
                db.clone(),
            ));
            let connect_cfg = ConnectCfg {
                data_dir: app
                    .path()
                    .app_data_dir()
                    .map(|dir| dir.join("connect"))
                    .unwrap_or_else(|_| std::env::temp_dir().join("kasane-connect")),
                device_name: std::env::var("HOSTNAME")
                    .ok()
                    .filter(|name| !name.is_empty())
                    .or_else(|| {
                        std::fs::read_to_string("/etc/hostname")
                            .ok()
                            .map(|name| name.trim().to_string())
                            .filter(|name| !name.is_empty())
                    })
                    .unwrap_or_else(|| "Kasane PC".into()),
                platform: "linux",
                app_version: env!("CARGO_PKG_VERSION"),
                adapter,
            };
            let connect = match tauri::async_runtime::block_on(
                kasane_connect::ConnectService::start(
                    &connect_cfg.data_dir,
                    &connect_cfg.device_name,
                    connect_cfg.platform,
                    connect_cfg.app_version,
                    connect_cfg.adapter.clone(),
                    true,
                ),
            ) {
                Ok(service) => service,
                Err(error) => {
                    eprintln!("Kasane Connect could not start: {error}. Remote control is disabled for this session.");
                    tauri::async_runtime::block_on(kasane_connect::ConnectService::start(
                        &connect_cfg.data_dir,
                        &connect_cfg.device_name,
                        connect_cfg.platform,
                        connect_cfg.app_version,
                        connect_cfg.adapter.clone(),
                        false,
                    ))?
                }
            };
            connect::spawn_update_forwarder(app.handle().clone(), connect.clone());
            app.manage(AppState {
                player,
                db,
                yt_provider: yt_provider.clone(),
                connect: tokio::sync::RwLock::new(connect),
                connect_cfg,
                account: account::AccountRuntime::new(),
            });
            account::restore(app.handle().clone(), yt_provider);
            Ok(())
        })
        .on_window_event(|window, event| {
            // The hidden verification window and the sign-in popup are
            // auxiliary — they must not terminate the app on close.
            if window.label() == account::LOGIN_LABEL
                && matches!(event, tauri::WindowEvent::Destroyed)
            {
                account::wipe_login_data(window.app_handle());
                return;
            }
            if window.label() != verifier::LABEL
                && window.label() != account::LOGIN_LABEL
                && matches!(event, tauri::WindowEvent::Destroyed)
            {
                window.app_handle().exit(0);
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::search_tracks,
            commands::open_audio_file,
            commands::play_track,
            commands::play_queue_item,
            commands::add_to_queue,
            commands::toggle_play_pause,
            commands::next_track,
            commands::previous_track,
            commands::seek,
            commands::set_volume,
            commands::get_playback_state,
            commands::get_queue,
            commands::get_favorites,
            commands::toggle_favorite,
            commands::get_history,
            verifier::verifier_reply,
            connect::commands::connect_local,
            connect::commands::connect_devices,
            connect::commands::connect_prompts,
            connect::commands::connect_pair,
            connect::commands::connect_confirm,
            connect::commands::connect_command,
            connect::commands::connect_handoff,
            connect::commands::connect_remote_state,
            connect::commands::connect_remote_states,
            connect::commands::connect_disconnect,
            connect::commands::connect_pull,
            connect::commands::connect_unpair,
            connect::commands::connect_status,
            connect::commands::connect_set_enabled,
            account::commands::account_status,
            account::commands::account_sign_in,
            account::commands::account_sign_out,
            account::commands::account_liked_music,
            account::commands::account_playlists,
            account::commands::account_playlist_tracks,
            account::commands::storage_info
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn database_uses_memory_when_the_app_data_path_is_unavailable() {
        let missing_path = open_library_database(Err(tauri::Error::UnknownPath)).unwrap();
        assert!(missing_path.get_favorites().unwrap().is_empty());

        let file = std::env::current_exe().unwrap();
        let blocked_path = open_library_database(Ok(file.join("kasane"))).unwrap();
        assert!(blocked_path.get_recent_history(1).unwrap().is_empty());
    }

    #[test]
    fn database_fallback_leaves_an_invalid_persistent_file_untouched() {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir =
            std::env::temp_dir().join(format!("kasane-db-{}-{timestamp}", std::process::id()));
        let path = dir.join("kasane.db");
        drop(open_library_database(Ok(dir.clone())).unwrap());
        assert!(path.is_file());

        let invalid = b"not a SQLite database";
        std::fs::write(&path, invalid).unwrap();
        let db = open_library_database(Ok(dir.clone())).unwrap();
        let favorites = db.get_favorites();
        let data = std::fs::read(&path);
        std::fs::remove_dir_all(&dir).unwrap();

        assert!(favorites.unwrap().is_empty());
        assert_eq!(data.unwrap(), invalid);
    }
}
