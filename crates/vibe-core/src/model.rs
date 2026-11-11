use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProviderType {
    YouTubeMusic,
    LocalLibrary,
    Mock,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Artist {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Album {
    pub id: String,
    pub title: String,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub artists: Vec<Artist>,
    pub album: Option<Album>,
    pub duration_secs: u64,
    pub thumbnail_url: Option<String>,
    pub provider: ProviderType,
    pub uri: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AudioFeatures {
    pub bpm: Option<f32>,
    pub key: Option<String>,
    pub energy: Option<f32>,
    pub danceability: Option<f32>,
    pub loudness_lufs: Option<f32>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StreamSource {
    pub url: String,
    pub mime_type: String,
    pub bitrate_kbps: Option<u32>,
    pub expires_at: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PlaybackState {
    Stopped,
    Playing,
    Paused,
    Buffering,
    Error(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueState {
    pub items: Vec<Track>,
    pub current_index: Option<usize>,
    pub shuffle: bool,
    pub repeat_mode: RepeatMode,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RepeatMode {
    Off,
    All,
    One,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlaybackProgress {
    pub position_secs: f64,
    pub duration_secs: f64,
    pub state: PlaybackState,
    pub current_track: Option<Track>,
}
