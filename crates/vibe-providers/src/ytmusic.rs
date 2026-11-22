use async_trait::async_trait;
use reqwest::header::{HeaderMap, HeaderValue, USER_AGENT};
use serde_json::Value;
use std::time::Duration;
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
            .timeout(Duration::from_secs(15))
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

        if !resp.status().is_success() {
            return Err(VibeError::Provider {
                provider: "YouTubeMusic".into(),
                message: format!("Player request returned HTTP {}", resp.status()),
            });
        }

        let json: Value = resp.json().await.map_err(|e| VibeError::Provider {
            provider: "YouTubeMusic".into(),
            message: format!("Player JSON parsing failed: {e}"),
        })?;

        direct_audio_source(&json).ok_or_else(|| VibeError::Provider {
            provider: "YouTubeMusic".into(),
            message: "No desktop-compatible audio stream is available for this track".into(),
        })
    }
}

fn direct_audio_source(json: &Value) -> Option<StreamSource> {
    json.pointer("/streamingData/adaptiveFormats")?
        .as_array()?
        .iter()
        .filter_map(|format| {
            let mime = format.get("mimeType")?.as_str()?;
            let url = reqwest::Url::parse(format.get("url")?.as_str()?).ok()?;
            // Symphonia can decode AAC/MP4, but not the provider's WebM/Opus tracks.
            if !mime.starts_with("audio/mp4;")
                || !mime.contains("mp4a.40.")
                || url.scheme() != "https"
            {
                return None;
            }
            Some(StreamSource {
                url: url.into(),
                mime_type: mime.into(),
                bitrate_kbps: format
                    .get("bitrate")
                    .and_then(Value::as_u64)
                    .map(|bitrate| (bitrate / 1000) as u32),
                expires_at: None,
            })
        })
        .max_by_key(|source| source.bitrate_kbps)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn selects_direct_aac_over_unsupported_opus() {
        let player = json!({ "streamingData": { "adaptiveFormats": [
            { "mimeType": "audio/webm; codecs=\"opus\"", "url": "https://example.com/opus" },
            { "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "url": "https://example.com/aac" }
        ]}});
        assert_eq!(
            direct_audio_source(&player).unwrap().url,
            "https://example.com/aac"
        );
    }

    #[test]
    fn unsupported_transport_does_not_become_a_watch_url() {
        assert!(direct_audio_source(&json!({ "streamingData": {
            "serverAbrStreamingUrl": "https://example.com/sabr",
            "hlsManifestUrl": "https://example.com/manifest.m3u8"
        }}))
        .is_none());
    }
}
