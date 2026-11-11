use std::sync::Arc;
use tauri::{Manager, State};
use vibe_audio::{DesktopAudioBackend, PlayerManager};
use vibe_core::model::{PlaybackProgress, QueueState, Track};
use vibe_db::Database;
use vibe_providers::{MockProvider, YouTubeMusicProvider};

pub struct AppState {
    pub player: Arc<PlayerManager>,
    pub db: Arc<Database>,
    pub yt_provider: Arc<YouTubeMusicProvider>,
    pub mock_provider: Arc<MockProvider>,
}

mod commands {
    use super::*;

    #[tauri::command]
    pub async fn search_tracks(
        state: State<'_, AppState>,
        query: String,
        use_mock: bool,
    ) -> Result<Vec<Track>, String> {
        if use_mock {
            use vibe_core::traits::MusicProvider;
            state
                .mock_provider
                .search(&query, 20)
                .await
                .map_err(|e| e.to_string())
        } else {
            use vibe_core::traits::MusicProvider;
            match state.yt_provider.search(&query, 20).await {
                Ok(results) if !results.is_empty() => Ok(results),
                _ => state
                    .mock_provider
                    .search(&query, 20)
                    .await
                    .map_err(|e| e.to_string()),
            }
        }
    }

    #[tauri::command]
    pub async fn play_track(state: State<'_, AppState>, track: Track) -> Result<(), String> {
        let _ = state.db.log_history(&track, 0.0);
        state
            .player
            .play_track_now(track)
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn add_to_queue(state: State<'_, AppState>, track: Track) -> Result<(), String> {
        state.player.add_to_queue(track).await;
        Ok(())
    }

    #[tauri::command]
    pub async fn toggle_play_pause(state: State<'_, AppState>) -> Result<(), String> {
        state
            .player
            .toggle_play_pause()
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn next_track(state: State<'_, AppState>) -> Result<(), String> {
        state.player.next().await.map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn previous_track(state: State<'_, AppState>) -> Result<(), String> {
        state.player.previous().await.map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn seek(state: State<'_, AppState>, position_secs: f64) -> Result<(), String> {
        state.player.seek(position_secs).await.map_err(|e| e.to_string())
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
    pub async fn get_playback_state(state: State<'_, AppState>) -> Result<PlaybackProgress, String> {
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
    pub async fn get_history(state: State<'_, AppState>, limit: usize) -> Result<Vec<Track>, String> {
        state.db.get_recent_history(limit).map_err(|e| e.to_string())
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let yt_provider = Arc::new(YouTubeMusicProvider::new());
    let mock_provider = Arc::new(MockProvider::new());

    let backend = match DesktopAudioBackend::new() {
        Ok(b) => Box::new(b),
        Err(e) => {
            eprintln!("Failed to init audio backend: {e}");
            Box::new(DesktopAudioBackend::new().unwrap())
        }
    };

    let player = Arc::new(PlayerManager::new(backend, mock_provider.clone()));

    tauri::Builder::default()
        .setup(|app| {
            let db_path = match app.path().app_data_dir() {
                Ok(mut p) => {
                    let _ = std::fs::create_dir_all(&p);
                    p.push("vibeplayer.db");
                    p.to_string_lossy().to_string()
                }
                Err(_) => "vibeplayer.db".to_string(),
            };

            let db = Arc::new(
                Database::open(&db_path).unwrap_or_else(|_| Database::in_memory().unwrap()),
            );

            let app_state = AppState {
                player,
                db,
                yt_provider,
                mock_provider,
            };

            app.manage(app_state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::search_tracks,
            commands::play_track,
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
