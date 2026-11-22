use std::sync::Arc;
use tauri::{Manager, State, WebviewWindow};
use tauri_plugin_dialog::DialogExt;
use vibe_audio::{DesktopAudioBackend, PlayerManager};
use vibe_core::model::{PlaybackProgress, ProviderType, QueueState, Track};
use vibe_core::traits::MusicProvider;
use vibe_db::Database;
use vibe_providers::YouTubeMusicProvider;

pub struct AppState {
    pub player: Arc<PlayerManager>,
    pub db: Arc<Database>,
    pub yt_provider: Arc<YouTubeMusicProvider>,
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
        state
            .player
            .play_track_now(track.clone())
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let app_dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&app_dir)?;
            let db = Arc::new(Database::open(app_dir.join("vibeplayer.db"))?);
            let yt_provider = Arc::new(YouTubeMusicProvider::new());
            let player = Arc::new(PlayerManager::new(
                Box::new(DesktopAudioBackend::new()?),
                yt_provider.clone(),
            ));
            app.manage(AppState {
                player,
                db,
                yt_provider,
            });
            Ok(())
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
            commands::get_history
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
