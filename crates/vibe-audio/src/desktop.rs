use async_trait::async_trait;
use std::io::Cursor;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio::sync::mpsc;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{PlaybackProgress, PlaybackState, StreamSource, Track};
use vibe_core::traits::PlaybackBackend;

enum AudioCommand {
    Play(Vec<u8>),
    Pause,
    Resume,
    Stop,
    SetVolume(f32),
}

pub struct DesktopAudioBackend {
    cmd_tx: mpsc::UnboundedSender<AudioCommand>,
    current_state: Arc<Mutex<PlaybackState>>,
    current_track: Arc<Mutex<Option<Track>>>,
    start_time: Arc<Mutex<Option<Instant>>>,
    paused_at_secs: Arc<Mutex<f64>>,
    duration_secs: Arc<Mutex<f64>>,
    volume: Arc<Mutex<f32>>,
}

// Explicitly implement Send and Sync since cmd_tx and Arcs are all Send + Sync
unsafe impl Send for DesktopAudioBackend {}
unsafe impl Sync for DesktopAudioBackend {}

impl DesktopAudioBackend {
    pub fn new() -> Result<Self> {
        let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<AudioCommand>();

        // Spawn dedicated audio thread where non-Send rodio OutputStream lives
        std::thread::spawn(move || {
            let stream_result = rodio::OutputStream::try_default();
            let (_stream, stream_handle) = match stream_result {
                Ok((s, h)) => (Some(s), Some(h)),
                Err(e) => {
                    tracing::warn!("Audio thread: failed to init default audio output: {e}");
                    (None, None)
                }
            };

            let mut sink: Option<rodio::Sink> = None;

            while let Some(cmd) = cmd_rx.blocking_recv() {
                match cmd {
                    AudioCommand::Play(bytes) => {
                        if let Some(ref handle) = stream_handle {
                            if !bytes.is_empty() {
                                let cursor = Cursor::new(bytes);
                                if let Ok(decoder) = rodio::Decoder::new(cursor) {
                                    if let Ok(new_sink) = rodio::Sink::try_new(handle) {
                                        new_sink.append(decoder);
                                        new_sink.play();
                                        sink = Some(new_sink);
                                    }
                                }
                            }
                        }
                    }
                    AudioCommand::Pause => {
                        if let Some(ref s) = sink {
                            s.pause();
                        }
                    }
                    AudioCommand::Resume => {
                        if let Some(ref s) = sink {
                            s.play();
                        }
                    }
                    AudioCommand::Stop => {
                        if let Some(s) = sink.take() {
                            s.stop();
                        }
                    }
                    AudioCommand::SetVolume(vol) => {
                        if let Some(ref s) = sink {
                            s.set_volume(vol);
                        }
                    }
                }
            }
        });

        Ok(Self {
            cmd_tx,
            current_state: Arc::new(Mutex::new(PlaybackState::Stopped)),
            current_track: Arc::new(Mutex::new(None)),
            start_time: Arc::new(Mutex::new(None)),
            paused_at_secs: Arc::new(Mutex::new(0.0)),
            duration_secs: Arc::new(Mutex::new(0.0)),
            volume: Arc::new(Mutex::new(1.0)),
        })
    }

    pub fn set_current_track(&mut self, track: Track) {
        *self.duration_secs.lock().unwrap() = track.duration_secs as f64;
        *self.current_track.lock().unwrap() = Some(track);
    }
}

#[async_trait]
impl PlaybackBackend for DesktopAudioBackend {
    async fn play_stream(&mut self, source: &StreamSource) -> Result<()> {
        let url = source.url.clone();
        *self.current_state.lock().unwrap() = PlaybackState::Buffering;

        let bytes = if url.starts_with("http://") || url.starts_with("https://") {
            let resp = reqwest::get(&url)
                .await
                .map_err(|e| VibeError::Playback(format!("HTTP fetch error: {e}")))?;
            let raw = resp
                .bytes()
                .await
                .map_err(|e| VibeError::Playback(format!("Stream read error: {e}")))?;
            raw.to_vec()
        } else {
            std::fs::read(&url).unwrap_or_default()
        };

        let _ = self.cmd_tx.send(AudioCommand::Play(bytes));
        *self.start_time.lock().unwrap() = Some(Instant::now());
        *self.paused_at_secs.lock().unwrap() = 0.0;
        *self.current_state.lock().unwrap() = PlaybackState::Playing;
        Ok(())
    }

    async fn pause(&mut self) -> Result<()> {
        let _ = self.cmd_tx.send(AudioCommand::Pause);
        if let Some(start) = *self.start_time.lock().unwrap() {
            let elapsed = start.elapsed().as_secs_f64();
            let mut paused = self.paused_at_secs.lock().unwrap();
            *paused += elapsed;
        }
        *self.start_time.lock().unwrap() = None;
        *self.current_state.lock().unwrap() = PlaybackState::Paused;
        Ok(())
    }

    async fn resume(&mut self) -> Result<()> {
        let _ = self.cmd_tx.send(AudioCommand::Resume);
        *self.start_time.lock().unwrap() = Some(Instant::now());
        *self.current_state.lock().unwrap() = PlaybackState::Playing;
        Ok(())
    }

    async fn stop(&mut self) -> Result<()> {
        let _ = self.cmd_tx.send(AudioCommand::Stop);
        *self.start_time.lock().unwrap() = None;
        *self.paused_at_secs.lock().unwrap() = 0.0;
        *self.current_state.lock().unwrap() = PlaybackState::Stopped;
        Ok(())
    }

    async fn seek(&mut self, position_secs: f64) -> Result<()> {
        *self.paused_at_secs.lock().unwrap() = position_secs;
        *self.start_time.lock().unwrap() = Some(Instant::now());
        Ok(())
    }

    async fn set_volume(&mut self, volume: f32) -> Result<()> {
        let clamped = volume.clamp(0.0, 1.0);
        *self.volume.lock().unwrap() = clamped;
        let _ = self.cmd_tx.send(AudioCommand::SetVolume(clamped));
        Ok(())
    }

    async fn get_progress(&self) -> Result<PlaybackProgress> {
        let state = self.current_state.lock().unwrap().clone();
        let track = self.current_track.lock().unwrap().clone();
        let duration = *self.duration_secs.lock().unwrap();

        let position = match state {
            PlaybackState::Playing => {
                let start = *self.start_time.lock().unwrap();
                let base = *self.paused_at_secs.lock().unwrap();
                if let Some(st) = start {
                    base + st.elapsed().as_secs_f64()
                } else {
                    base
                }
            }
            PlaybackState::Paused => *self.paused_at_secs.lock().unwrap(),
            _ => 0.0,
        };

        Ok(PlaybackProgress {
            position_secs: position.min(duration.max(position)),
            duration_secs: duration,
            state,
            current_track: track,
        })
    }

    async fn get_state(&self) -> Result<PlaybackState> {
        Ok(self.current_state.lock().unwrap().clone())
    }
}
