use thiserror::Error;

#[derive(Error, Debug)]
pub enum VibeError {
    #[error("Audio engine error: {0}")]
    Audio(String),

    #[error("Provider error ({provider}): {message}")]
    Provider {
        provider: String,
        message: String,
    },

    #[error("Database error: {0}")]
    Database(String),

    #[error("Playback error: {0}")]
    Playback(String),

    #[error("Intelligence error: {0}")]
    Intelligence(String),

    #[error("Network error: {0}")]
    Network(String),

    #[error("Track not found: {0}")]
    TrackNotFound(String),

    #[error("Not supported on this platform: {0}")]
    UnsupportedPlatform(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, VibeError>;
