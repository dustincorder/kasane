use async_trait::async_trait;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{Album, Artist, ProviderType, StreamSource, Track};
use vibe_core::traits::MusicProvider;

pub struct MockProvider {
    tracks: Vec<Track>,
}

impl MockProvider {
    pub fn new() -> Self {
        let tracks = vec![
            Track {
                id: "mock-1".to_string(),
                title: "Midnight Drive".to_string(),
                artist: "Synthwave Collective".to_string(),
                artists: vec![Artist {
                    id: "art-1".to_string(),
                    name: "Synthwave Collective".to_string(),
                }],
                album: Some(Album {
                    id: "alb-1".to_string(),
                    title: "Neon Horizon".to_string(),
                    cover_url: Some("https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400".to_string()),
                }),
                duration_secs: 215,
                thumbnail_url: Some("https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400".to_string()),
                provider: ProviderType::Mock,
                uri: None,
            },
            Track {
                id: "mock-2".to_string(),
                title: "Deep Thought".to_string(),
                artist: "Aura Minimal".to_string(),
                artists: vec![Artist {
                    id: "art-2".to_string(),
                    name: "Aura Minimal".to_string(),
                }],
                album: Some(Album {
                    id: "alb-2".to_string(),
                    title: "Quiet Pulse".to_string(),
                    cover_url: Some("https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400".to_string()),
                }),
                duration_secs: 184,
                thumbnail_url: Some("https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400".to_string()),
                provider: ProviderType::Mock,
                uri: None,
            },
            Track {
                id: "mock-3".to_string(),
                title: "Nordic Frost".to_string(),
                artist: "Acoustic Tides".to_string(),
                artists: vec![Artist {
                    id: "art-3".to_string(),
                    name: "Acoustic Tides".to_string(),
                }],
                album: Some(Album {
                    id: "alb-3".to_string(),
                    title: "Solitude".to_string(),
                    cover_url: Some("https://images.unsplash.com/photo-1445985543470-41fdd6ce388d?w=400".to_string()),
                }),
                duration_secs: 248,
                thumbnail_url: Some("https://images.unsplash.com/photo-1445985543470-41fdd6ce388d?w=400".to_string()),
                provider: ProviderType::Mock,
                uri: None,
            },
        ];

        Self { tracks }
    }
}

impl Default for MockProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl MusicProvider for MockProvider {
    fn provider_name(&self) -> &'static str {
        "Mock"
    }

    async fn search(&self, query: &str, limit: usize) -> Result<Vec<Track>> {
        let q = query.to_lowercase();
        let matches: Vec<Track> = self
            .tracks
            .iter()
            .filter(|t| t.title.to_lowercase().contains(&q) || t.artist.to_lowercase().contains(&q))
            .take(limit)
            .cloned()
            .collect();
        if matches.is_empty() {
            Ok(self.tracks.iter().take(limit).cloned().collect())
        } else {
            Ok(matches)
        }
    }

    async fn get_track_details(&self, track_id: &str) -> Result<Track> {
        self.tracks
            .iter()
            .find(|t| t.id == track_id)
            .cloned()
            .ok_or_else(|| VibeError::TrackNotFound(track_id.to_string()))
    }

    async fn resolve_stream(&self, track: &Track) -> Result<StreamSource> {
        Ok(StreamSource {
            url: format!("mock://stream/{}", track.id),
            mime_type: "audio/webm".to_string(),
            bitrate_kbps: Some(160),
            expires_at: None,
        })
    }
}
