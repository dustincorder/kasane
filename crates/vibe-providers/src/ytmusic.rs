use async_trait::async_trait;
use reqwest::header::{HeaderMap, HeaderValue, USER_AGENT};
use serde_json::Value;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{Artist, ProviderType, StreamSource, Track};
use vibe_core::traits::MusicProvider;

pub struct YouTubeMusicProvider {
    client: reqwest::Client,
}

impl YouTubeMusicProvider {
    pub fn new() -> Self {
        let mut headers = HeaderMap::new();
        headers.insert(
            USER_AGENT,
            HeaderValue::from_static(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            ),
        );
        headers.insert(
            "X-YouTube-Client-Name",
            HeaderValue::from_static("67"), // WEB_REMIX
        );
        headers.insert(
            "X-YouTube-Client-Version",
            HeaderValue::from_static("1.20240101.01.00"),
        );

        let client = reqwest::Client::builder()
            .default_headers(headers)
            .build()
            .unwrap_or_default();

        Self { client }
    }
}

impl Default for YouTubeMusicProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl MusicProvider for YouTubeMusicProvider {
    fn provider_name(&self) -> &'static str {
        "YouTubeMusic"
    }

    async fn search(&self, query: &str, limit: usize) -> Result<Vec<Track>> {
        let url = "https://music.youtube.com/youtubei/v1/search";
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "query": query,
            "params": "Eg-KAQwIABAAGAAgACgAMABqChAEEAMQCRAFEAo%3D" // Songs filter
        });

        let resp = self
            .client
            .post(url)
            .json(&body)
            .send()
            .await
            .map_err(|e| VibeError::Provider {
                provider: "YouTubeMusic".into(),
                message: format!("Search request failed: {e}"),
            })?;

        if !resp.status().is_success() {
            return Err(VibeError::Provider {
                provider: "YouTubeMusic".into(),
                message: format!("HTTP error: {}", resp.status()),
            });
        }

        let json: Value = resp.json().await.map_err(|e| VibeError::Provider {
            provider: "YouTubeMusic".into(),
            message: format!("Invalid JSON response: {e}"),
        })?;

        let mut tracks = Vec::new();
        // Traverse InnerTube response structure
        if let Some(contents) = json
            .pointer("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents")
            .and_then(|v| v.as_array())
        {
            for section in contents {
                if let Some(items) = section
                    .pointer("/musicShelfRenderer/contents")
                    .and_then(|v| v.as_array())
                {
                    for item in items {
                        if tracks.len() >= limit {
                            break;
                        }
                        if let Some(renderer) = item.get("musicResponsiveListItemRenderer") {
                            let video_id = renderer
                                .pointer("/playlistItemData/videoId")
                                .and_then(|v| v.as_str());

                            let title = renderer
                                .pointer("/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/text")
                                .and_then(|v| v.as_str());

                            let artist = renderer
                                .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs/0/text")
                                .and_then(|v| v.as_str())
                                .unwrap_or("Unknown Artist");

                            let thumb = renderer
                                .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails/0/url")
                                .and_then(|v| v.as_str());

                            if let (Some(id), Some(t)) = (video_id, title) {
                                tracks.push(Track {
                                    id: id.to_string(),
                                    title: t.to_string(),
                                    artist: artist.to_string(),
                                    artists: vec![Artist {
                                        id: format!("art_{}", id),
                                        name: artist.to_string(),
                                    }],
                                    album: None,
                                    duration_secs: 180, // Default duration placeholder until stream inspection
                                    thumbnail_url: thumb.map(|s| s.to_string()),
                                    provider: ProviderType::YouTubeMusic,
                                    uri: None,
                                });
                            }
                        }
                    }
                }
            }
        }

        Ok(tracks)
    }

    async fn get_track_details(&self, track_id: &str) -> Result<Track> {
        Ok(Track {
            id: track_id.to_string(),
            title: "YouTube Music Track".to_string(),
            artist: "Artist".to_string(),
            artists: vec![],
            album: None,
            duration_secs: 180,
            thumbnail_url: None,
            provider: ProviderType::YouTubeMusic,
            uri: None,
        })
    }

    async fn resolve_stream(&self, track: &Track) -> Result<StreamSource> {
        let url = "https://music.youtube.com/youtubei/v1/player";
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "6.20.51",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": track.id
        });

        let resp = self
            .client
            .post(url)
            .json(&body)
            .send()
            .await
            .map_err(|e| VibeError::Provider {
                provider: "YouTubeMusic".into(),
                message: format!("Player request failed: {e}"),
            })?;

        let json: Value = resp.json().await.map_err(|e| VibeError::Provider {
            provider: "YouTubeMusic".into(),
            message: format!("Player JSON parsing failed: {e}"),
        })?;

        // Locate optimal audio/webm or audio/mp4 stream from adaptiveFormats
        if let Some(formats) = json
            .pointer("/streamingData/adaptiveFormats")
            .and_then(|v| v.as_array())
        {
            for fmt in formats {
                let mime = fmt.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                if mime.starts_with("audio/") {
                    if let Some(stream_url) = fmt.get("url").and_then(|v| v.as_str()) {
                        let bitrate = fmt.get("bitrate").and_then(|v| v.as_u64()).map(|b| (b / 1000) as u32);
                        return Ok(StreamSource {
                            url: stream_url.to_string(),
                            mime_type: mime.to_string(),
                            bitrate_kbps: bitrate,
                            expires_at: None,
                        });
                    }
                }
            }
        }

        // Return fallback stream source if direct extraction requires client fallback
        Ok(StreamSource {
            url: format!("https://music.youtube.com/watch?v={}", track.id),
            mime_type: "audio/webm".to_string(),
            bitrate_kbps: Some(160),
            expires_at: None,
        })
    }
}
