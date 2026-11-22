pub mod desktop;
pub mod dsp;

pub use desktop::DesktopAudioBackend;
pub use dsp::NativeDspAnalyzer;

use std::sync::Arc;
use tokio::sync::Mutex;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{
    PlaybackProgress, PlaybackState, ProviderType, QueueState, RepeatMode, StreamSource, Track,
};
use vibe_core::traits::{MusicProvider, PlaybackBackend};

pub struct PlayerManager {
    backend: Mutex<Box<dyn PlaybackBackend>>,
    queue: Mutex<QueueState>,
    transition: Mutex<()>,
    provider: Arc<dyn MusicProvider>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use std::sync::atomic::{AtomicBool, Ordering};

    struct TestProvider;

    #[async_trait]
    impl MusicProvider for TestProvider {
        fn provider_name(&self) -> &'static str {
            "test"
        }
        async fn search(&self, _: &str, _: usize) -> Result<Vec<Track>> {
            unreachable!()
        }
        async fn get_track_details(&self, _: &str) -> Result<Track> {
            unreachable!()
        }
        async fn resolve_stream(&self, track: &Track) -> Result<StreamSource> {
            Ok(StreamSource {
                url: track.id.clone(),
                mime_type: String::new(),
                bitrate_kbps: None,
                expires_at: None,
            })
        }
    }

    struct TestBackend {
        state: PlaybackState,
        ended: Arc<AtomicBool>,
    }

    #[async_trait]
    impl PlaybackBackend for TestBackend {
        async fn play_stream(&mut self, source: &StreamSource) -> Result<()> {
            if source.url == "broken" {
                return Err(VibeError::Playback("Cannot decode".into()));
            }
            self.ended.store(false, Ordering::Relaxed);
            self.state = PlaybackState::Playing;
            Ok(())
        }
        async fn pause(&mut self) -> Result<()> {
            self.state = PlaybackState::Paused;
            Ok(())
        }
        async fn resume(&mut self) -> Result<()> {
            self.state = PlaybackState::Playing;
            Ok(())
        }
        async fn stop(&mut self) -> Result<()> {
            self.state = PlaybackState::Stopped;
            self.ended.store(false, Ordering::Relaxed);
            Ok(())
        }
        async fn seek(&mut self, _: f64) -> Result<()> {
            Ok(())
        }
        async fn set_volume(&mut self, _: f32) -> Result<()> {
            Ok(())
        }
        async fn get_progress(&self) -> Result<PlaybackProgress> {
            Ok(PlaybackProgress {
                position_secs: 0.0,
                duration_secs: 0.0,
                state: self.get_state().await?,
                current_track: None,
            })
        }
        async fn get_state(&self) -> Result<PlaybackState> {
            Ok(if self.ended.load(Ordering::Relaxed) {
                PlaybackState::Ended
            } else {
                self.state.clone()
            })
        }
    }

    fn track(id: &str) -> Track {
        Track {
            id: id.into(),
            title: id.into(),
            artist: String::new(),
            artists: vec![],
            album: None,
            duration_secs: 10,
            thumbnail_url: None,
            provider: ProviderType::YouTubeMusic,
            uri: None,
        }
    }

    fn player() -> (PlayerManager, Arc<AtomicBool>) {
        let ended = Arc::new(AtomicBool::new(false));
        (
            PlayerManager::new(
                Box::new(TestBackend {
                    state: PlaybackState::Stopped,
                    ended: ended.clone(),
                }),
                Arc::new(TestProvider),
            ),
            ended,
        )
    }

    #[tokio::test]
    async fn selecting_a_queued_track_keeps_order_and_does_not_duplicate_it() {
        let (player, _) = player();
        player.add_to_queue(track("first")).await;
        player.add_to_queue(track("second")).await;
        assert!(player.get_progress().await.unwrap().current_track.is_none());

        player.play_index(1).await.unwrap();
        player.play_track_now(track("second")).await.unwrap();
        let queue = player.get_queue().await;
        assert_eq!(queue.items, vec![track("first"), track("second")]);
        assert_eq!(queue.current_index, Some(1));
        assert!(player.next().await.unwrap().is_none());
        assert_eq!(
            player.get_progress().await.unwrap().state,
            PlaybackState::Playing
        );
    }

    #[tokio::test]
    async fn failed_selection_keeps_active_track_and_does_not_insert_failed_track() {
        let (player, _) = player();
        player.play_track_now(track("first")).await.unwrap();
        player.add_to_queue(track("broken")).await;
        assert!(player.play_index(1).await.is_err());
        assert_eq!(
            player.get_progress().await.unwrap().current_track,
            Some(track("first"))
        );
        assert!(player
            .play_track_now(Track {
                provider: ProviderType::Mock,
                ..track("demo")
            })
            .await
            .is_err());
        assert_eq!(
            player.get_queue().await.items,
            vec![track("first"), track("broken")]
        );
    }

    #[tokio::test]
    async fn advances_only_at_end_and_does_not_retry_a_failed_next_track() {
        let (player, ended) = player();
        player.add_to_queue(track("first")).await;
        player.add_to_queue(track("second")).await;
        player.add_to_queue(track("broken")).await;
        assert_eq!(
            player.toggle_play_pause().await.unwrap(),
            Some(track("first"))
        );
        assert!(player.advance_if_finished().await.unwrap().is_none());
        ended.store(true, Ordering::Relaxed);
        assert_eq!(
            player.advance_if_finished().await.unwrap(),
            Some(track("second"))
        );
        ended.store(true, Ordering::Relaxed);
        assert!(player.advance_if_finished().await.is_err());
        assert!(player.advance_if_finished().await.unwrap().is_none());
        assert_eq!(player.get_queue().await.current_index, Some(1));
    }
}

impl PlayerManager {
    pub fn new(backend: Box<dyn PlaybackBackend>, provider: Arc<dyn MusicProvider>) -> Self {
        Self {
            backend: Mutex::new(backend),
            queue: Mutex::new(QueueState {
                items: Vec::new(),
                current_index: None,
                shuffle: false,
                repeat_mode: RepeatMode::Off,
            }),
            transition: Mutex::new(()),
            provider,
        }
    }

    pub async fn add_to_queue(&self, track: Track) {
        self.queue.lock().await.items.push(track);
    }

    pub async fn get_queue(&self) -> QueueState {
        self.queue.lock().await.clone()
    }

    pub async fn play_index(&self, index: usize) -> Result<Track> {
        let _transition = self.transition.lock().await;
        self.select(index, None).await
    }

    pub async fn play_track_now(&self, track: Track) -> Result<()> {
        let _transition = self.transition.lock().await;
        let (index, insertion) = {
            let q = self.queue.lock().await;
            match q
                .items
                .iter()
                .position(|entry| entry.id == track.id && entry.provider == track.provider)
            {
                Some(index) => (index, None),
                None => (q.current_index.map_or(0, |i| i + 1), Some(track)),
            }
        };
        self.select(index, insertion).await?;
        Ok(())
    }

    async fn select(&self, index: usize, insertion: Option<Track>) -> Result<Track> {
        let track = match &insertion {
            Some(track) => track.clone(),
            None => self
                .queue
                .lock()
                .await
                .items
                .get(index)
                .cloned()
                .ok_or_else(|| {
                    VibeError::TrackNotFound(format!("Queue index {index} out of bounds"))
                })?,
        };
        let stream = match track.provider {
            ProviderType::YouTubeMusic => self.provider.resolve_stream(&track).await?,
            ProviderType::LocalLibrary => {
                let path = track
                    .uri
                    .as_ref()
                    .filter(|p| std::path::Path::new(p).is_absolute())
                    .ok_or_else(|| VibeError::Playback("The local audio file is missing".into()))?;
                StreamSource {
                    url: path.clone(),
                    mime_type: String::new(),
                    bitrate_kbps: None,
                    expires_at: None,
                }
            }
            ProviderType::Mock => {
                return Err(VibeError::Playback("Demo tracks have no audio".into()))
            }
        };

        let mut backend = self.backend.lock().await;
        backend.play_stream(&stream).await?;
        let mut q = self.queue.lock().await;
        if let Some(track) = insertion {
            q.items.insert(index, track);
        }
        q.current_index = Some(index);
        Ok(track)
    }

    async fn next_index(&self, automatic: bool) -> Option<usize> {
        let q = self.queue.lock().await;
        match q.current_index {
            Some(idx) if automatic && q.repeat_mode == RepeatMode::One => Some(idx),
            Some(idx) if idx + 1 < q.items.len() => Some(idx + 1),
            Some(_) if q.repeat_mode == RepeatMode::All => Some(0),
            None if !q.items.is_empty() => Some(0),
            _ => None,
        }
    }

    pub async fn next(&self) -> Result<Option<Track>> {
        let _transition = self.transition.lock().await;
        match self.next_index(false).await {
            Some(index) => self.select(index, None).await.map(Some),
            None => Ok(None),
        }
    }

    pub async fn advance_if_finished(&self) -> Result<Option<Track>> {
        let Ok(_transition) = self.transition.try_lock() else {
            return Ok(None);
        };
        if self.backend.lock().await.get_state().await? != PlaybackState::Ended {
            return Ok(None);
        }
        match self.next_index(true).await {
            Some(index) => match self.select(index, None).await {
                Ok(track) => Ok(Some(track)),
                Err(error) => {
                    // Do not retry an unavailable track on every poll.
                    self.backend.lock().await.stop().await?;
                    Err(error)
                }
            },
            None => Ok(None),
        }
    }

    pub async fn previous(&self) -> Result<Option<Track>> {
        let _transition = self.transition.lock().await;
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

        match prev_idx {
            Some(index) => self.select(index, None).await.map(Some),
            None => Ok(None),
        }
    }

    pub async fn toggle_play_pause(&self) -> Result<Option<Track>> {
        let _transition = self.transition.lock().await;
        let mut backend = self.backend.lock().await;
        let state = backend.get_state().await?;
        match state {
            PlaybackState::Playing => backend.pause().await?,
            PlaybackState::Paused => backend.resume().await?,
            PlaybackState::Stopped | PlaybackState::Ended => {
                drop(backend);
                let current_idx = {
                    let q = self.queue.lock().await;
                    q.current_index
                        .or_else(|| (!q.items.is_empty()).then_some(0))
                };
                if let Some(idx) = current_idx {
                    return self.select(idx, None).await.map(Some);
                }
            }
            _ => {}
        }
        Ok(None)
    }

    pub async fn get_progress(&self) -> Result<PlaybackProgress> {
        let backend = self.backend.lock().await;
        let mut progress = backend.get_progress().await?;
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
