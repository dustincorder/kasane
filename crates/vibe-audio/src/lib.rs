pub mod desktop;
pub mod dsp;

pub use desktop::DesktopAudioBackend;
pub use dsp::NativeDspAnalyzer;

use std::sync::Arc;
use tokio::sync::Mutex;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{PlaybackProgress, PlaybackState, QueueState, RepeatMode, Track};
use vibe_core::traits::{MusicProvider, PlaybackBackend};

pub struct PlayerManager {
    backend: Arc<Mutex<Box<dyn PlaybackBackend>>>,
    queue: Arc<Mutex<QueueState>>,
    provider: Arc<dyn MusicProvider>,
}

impl PlayerManager {
    pub fn new(
        backend: Box<dyn PlaybackBackend>,
        provider: Arc<dyn MusicProvider>,
    ) -> Self {
        Self {
            backend: Arc::new(Mutex::new(backend)),
            queue: Arc::new(Mutex::new(QueueState {
                items: Vec::new(),
                current_index: None,
                shuffle: false,
                repeat_mode: RepeatMode::Off,
            })),
            provider,
        }
    }

    pub async fn add_to_queue(&self, track: Track) {
        let mut q = self.queue.lock().await;
        q.items.push(track);
        if q.current_index.is_none() && !q.items.is_empty() {
            q.current_index = Some(0);
        }
    }

    pub async fn get_queue(&self) -> QueueState {
        self.queue.lock().await.clone()
    }

    pub async fn play_index(&self, index: usize) -> Result<()> {
        let track = {
            let mut q = self.queue.lock().await;
            if index >= q.items.len() {
                return Err(VibeError::TrackNotFound(format!("Queue index {index} out of bounds")));
            }
            q.current_index = Some(index);
            q.items[index].clone()
        };

        let stream_source = self.provider.resolve_stream(&track).await?;
        let mut backend = self.backend.lock().await;
        backend.play_stream(&stream_source).await?;
        Ok(())
    }

    pub async fn play_track_now(&self, track: Track) -> Result<()> {
        {
            let mut q = self.queue.lock().await;
            q.items.insert(0, track.clone());
            q.current_index = Some(0);
        }
        let stream_source = self.provider.resolve_stream(&track).await?;
        let mut backend = self.backend.lock().await;
        backend.play_stream(&stream_source).await?;
        Ok(())
    }

    pub async fn next(&self) -> Result<()> {
        let next_idx = {
            let q = self.queue.lock().await;
            match q.current_index {
                Some(idx) if idx + 1 < q.items.len() => Some(idx + 1),
                Some(_) if q.repeat_mode == RepeatMode::All => Some(0),
                _ => None,
            }
        };

        if let Some(idx) = next_idx {
            self.play_index(idx).await?;
        } else {
            let mut backend = self.backend.lock().await;
            backend.stop().await?;
        }
        Ok(())
    }

    pub async fn previous(&self) -> Result<()> {
        let prev_idx = {
            let q = self.queue.lock().await;
            match q.current_index {
                Some(idx) if idx > 0 => Some(idx - 1),
                Some(_) if q.repeat_mode == RepeatMode::All && !q.items.is_empty() => {
                    Some(q.items.len() - 1)
                }
                _ => None,
            }
        };

        if let Some(idx) = prev_idx {
            self.play_index(idx).await?;
        }
        Ok(())
    }

    pub async fn toggle_play_pause(&self) -> Result<()> {
        let mut backend = self.backend.lock().await;
        let state = backend.get_state().await?;
        match state {
            PlaybackState::Playing => backend.pause().await?,
            PlaybackState::Paused => backend.resume().await?,
            PlaybackState::Stopped => {
                drop(backend);
                let current_idx = self.queue.lock().await.current_index;
                if let Some(idx) = current_idx {
                    self.play_index(idx).await?;
                }
            }
            _ => {}
        }
        Ok(())
    }

    pub async fn get_progress(&self) -> Result<PlaybackProgress> {
        let mut progress = self.backend.lock().await.get_progress().await?;
        let q = self.queue.lock().await;
        if let Some(idx) = q.current_index {
            if let Some(track) = q.items.get(idx) {
                progress.current_track = Some(track.clone());
                if progress.duration_secs == 0.0 {
                    progress.duration_secs = track.duration_secs as f64;
                }
            }
        }
        Ok(progress)
    }

    pub async fn seek(&self, position_secs: f64) -> Result<()> {
        self.backend.lock().await.seek(position_secs).await
    }

    pub async fn set_volume(&self, volume: f32) -> Result<()> {
        self.backend.lock().await.set_volume(volume).await
    }
}
