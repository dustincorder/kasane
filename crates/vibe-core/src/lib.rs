pub mod error;
pub mod model;
pub mod traits;

pub use error::{VibeError, Result};
pub use model::*;
pub use traits::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_track_serialization() {
        let track = Track {
            id: "yt-123".to_string(),
            title: "Test Track".to_string(),
            artist: "Test Artist".to_string(),
            artists: vec![Artist {
                id: "art-1".to_string(),
                name: "Test Artist".to_string(),
            }],
            album: Some(Album {
                id: "alb-1".to_string(),
                title: "Test Album".to_string(),
                cover_url: None,
            }),
            duration_secs: 180,
            thumbnail_url: None,
            provider: ProviderType::YouTubeMusic,
            uri: None,
        };

        let json = serde_json::to_string(&track).expect("Serialize track");
        let parsed: Track = serde_json::from_str(&json).expect("Deserialize track");
        assert_eq!(track.id, parsed.id);
        assert_eq!(track.title, parsed.title);
    }
}
