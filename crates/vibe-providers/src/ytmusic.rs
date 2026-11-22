use async_trait::async_trait;
use reqwest::header::{HeaderMap, HeaderValue, USER_AGENT};
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{Album, Artist, ProviderType, StreamSource, Track};
use vibe_core::traits::MusicProvider;

use crate::verification::WebPlaybackVerifier;

pub struct YouTubeMusicProvider {
    client: reqwest::Client,
    player_url: String,
    verifier: Option<Arc<WebPlaybackVerifier>>,
}

const IOS_VERSION: &str = "21.26.4";
const IOS_USER_AGENT: &str =
    "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)";

fn provider_error(message: impl Into<String>) -> VibeError {
    VibeError::Provider {
        provider: "YouTubeMusic".into(),
        message: message.into(),
    }
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

        Self {
            client,
            player_url: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false".into(),
            verifier: None,
        }
    }

    /// Enables verified WEB_REMIX playback through an app-owned browser runtime.
    pub fn with_verifier(mut self, verifier: Arc<WebPlaybackVerifier>) -> Self {
        self.verifier = Some(verifier);
        self
    }

    async fn resolve_ios(&self, track: &Track) -> Result<StreamSource> {
        let body = serde_json::json!({
            "videoId": track.id, "context": {"client": {
                "clientName": "IOS", "clientVersion": IOS_VERSION,
                "deviceModel": "iPhone16,2", "osName": "iPhone",
                "osVersion": "18.5.0.22F76", "hl": "en", "gl": "US"
            }}
        });
        let response = self
            .client
            .post(&self.player_url)
            .header(USER_AGENT, IOS_USER_AGENT)
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", IOS_VERSION)
            .json(&body)
            .send()
            .await
            .map_err(|_| provider_error("Player request failed"))?;
        if !response.status().is_success() {
            return Err(provider_error(format!(
                "Player request returned HTTP {}",
                response.status()
            )));
        }
        let root: Value = response
            .json()
            .await
            .map_err(|_| provider_error("Invalid player JSON response"))?;
        if root
            .pointer("/videoDetails/videoId")
            .and_then(Value::as_str)
            .is_some_and(|id| id != track.id)
        {
            return Err(provider_error("Player returned a different video ID"));
        }
        parse_player(&root)
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
            "params": "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
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

        parse_search(&json, limit)
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
        if track.id.len() != 11
            || !track
                .id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            return Err(provider_error("Invalid YouTube video ID"));
        }
        let Some(verifier) = &self.verifier else {
            return self.resolve_ios(track).await;
        };
        let verified_error = match verifier.resolve(&track.id).await {
            Ok(source) => {
                tracing::info!("YouTube playback: verified WEB_REMIX AAC stream");
                return Ok(source);
            }
            Err(error) => error,
        };
        // A second real source (iOS HLS) is tried and both outcomes are reported;
        // no placeholder audio is ever substituted.
        tracing::warn!("Verified YouTube playback unavailable: {verified_error}");
        self.resolve_ios(track).await.map_err(|ios_error| {
            provider_error(format!("{verified_error}; iOS HLS: {ios_error}"))
        })
    }
}

fn parse_duration(text: &str) -> Option<u64> {
    let parts: Vec<_> = text.trim().split(':').collect();
    if !(2..=3).contains(&parts.len())
        || parts
            .iter()
            .any(|part| part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()))
        || parts[1..]
            .iter()
            .any(|part| part.len() != 2 || part.parse::<u64>().ok().is_none_or(|n| n >= 60))
    {
        return None;
    }
    parts.into_iter().try_fold(0u64, |total, part| {
        total
            .checked_mul(60)?
            .checked_add(part.parse::<u64>().ok()?)
    })
}

fn parse_search(json: &Value, limit: usize) -> Result<Vec<Track>> {
    let mut tracks = Vec::new();
    let mut supported = false;
    // Traverse InnerTube response structure
    if let Some(contents) = json
            .pointer("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents")
            .and_then(|v| v.as_array())
        {
            if contents.is_empty() { return Ok(tracks); }
            for section in contents {
                if let Some(items) = section
                    .pointer("/musicShelfRenderer/contents")
                    .and_then(|v| v.as_array())
                {
                    supported = true;
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

                            let runs = renderer
                                .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs")
                                .and_then(Value::as_array);
                            let duration_secs = runs.and_then(|runs| runs.iter().rev()
                                .filter_map(|run| run.get("text").and_then(Value::as_str))
                                .find_map(parse_duration)).unwrap_or(0);
                            let album = runs.and_then(|runs| runs.windows(3).find_map(|group| {
                                if group[0].get("text").and_then(Value::as_str)?.trim() != "•"
                                    || group[2].get("text").and_then(Value::as_str)?.trim() != "•" {
                                    return None;
                                }
                                let title = group[1].get("text")?.as_str()?.trim();
                                if title.is_empty() || parse_duration(title).is_some() { return None; }
                                Some(Album {
                                    id: group[1].pointer("/navigationEndpoint/browseEndpoint/browseId")
                                        .and_then(Value::as_str).unwrap_or_default().into(),
                                    title: title.into(),
                                    cover_url: None,
                                })
                            }));

                            if let (Some(id), Some(t)) = (video_id, title) {
                                tracks.push(Track {
                                    id: id.to_string(),
                                    title: t.to_string(),
                                    artist: artist.to_string(),
                                    artists: vec![Artist {
                                        id: format!("art_{}", id),
                                        name: artist.to_string(),
                                    }],
                                    album,
                                    duration_secs,
                                    thumbnail_url: thumb.map(|s| s.to_string()),
                                    provider: ProviderType::YouTubeMusic,
                                    uri: None,
                                });
                            } else {
                                return Err(provider_error("Unsupported search song row"));
                            }
                        } else {
                            return Err(provider_error("Unsupported search shelf item"));
                        }
                    }
                }
            }
        }

    if supported {
        Ok(tracks)
    } else {
        Err(provider_error("Unsupported search response shape"))
    }
}

fn parse_player(root: &Value) -> Result<StreamSource> {
    let reason = root
        .pointer("/playabilityStatus/reason")
        .and_then(Value::as_str)
        .unwrap_or("No reason supplied");
    match root
        .pointer("/playabilityStatus/status")
        .and_then(Value::as_str)
    {
        Some("OK") => {
            if let Some(url) = root
                .pointer("/streamingData/hlsManifestUrl")
                .and_then(Value::as_str)
            {
                let parsed = reqwest::Url::parse(url)
                    .map_err(|_| provider_error("Unsupported HLS manifest URL"))?;
                if parsed.scheme() != "https"
                    || parsed.host_str().is_none()
                    || !parsed.username().is_empty()
                {
                    return Err(provider_error("Unsupported HLS manifest URL"));
                }
                return Ok(StreamSource {
                    url: parsed.into(),
                    mime_type: "application/vnd.apple.mpegurl".into(),
                    bitrate_kbps: None,
                    expires_at: None,
                });
            }
            if root
                .pointer("/streamingData/serverAbrStreamingUrl")
                .and_then(Value::as_str)
                .is_some_and(|value| !value.is_empty())
            {
                return Err(provider_error("YouTube requires playback verification for this track (no attestation provider)"));
            }
            Err(provider_error("No compatible HLS audio stream"))
        }
        Some("LOGIN_REQUIRED") => Err(provider_error(format!("Sign-in required: {reason}"))),
        Some(status) => Err(provider_error(format!("Unplayable ({status}): {reason}"))),
        None => Err(provider_error(
            "Invalid player response: missing playability status",
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const HLS: &str = include_str!("../tests/fixtures/player-hls.json");
    const LOGIN: &str = include_str!("../tests/fixtures/player-login.json");
    const SABR: &str = include_str!("../tests/fixtures/player-sabr.json");
    const NO_AUDIO: &str = include_str!("../tests/fixtures/player-no-audio.json");

    #[test]
    fn player_fixtures_have_distinct_outcomes() {
        assert_eq!(
            parse_player(&serde_json::from_str(HLS).unwrap())
                .unwrap()
                .mime_type,
            "application/vnd.apple.mpegurl"
        );
        for (fixture, message) in [
            (LOGIN, "Sign-in required"),
            (SABR, "requires playback verification"),
            (NO_AUDIO, "No compatible HLS"),
        ] {
            assert!(parse_player(&serde_json::from_str(fixture).unwrap())
                .unwrap_err()
                .to_string()
                .contains(message));
        }
        assert!(parse_player(
            &json!({"playabilityStatus":{"status":"UNPLAYABLE","reason":"Unavailable here"}})
        )
        .unwrap_err()
        .to_string()
        .contains("Unplayable (UNPLAYABLE): Unavailable here"));
        assert!(parse_player(&json!({})).is_err());
        assert!(parse_player(&json!({"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"file:///bad"}})).is_err());
    }

    #[test]
    fn search_shelf_empty_and_unsupported_are_distinct() {
        let mut root: Value =
            serde_json::from_str(include_str!("../tests/fixtures/search-songs.json")).unwrap();
        let tracks = parse_search(&root, 20).unwrap();
        assert_eq!(tracks.len(), 1);
        assert_eq!(tracks[0].id, "lYBUbBu4W08");
        assert_eq!(tracks[0].title, "Never Gonna Give You Up");
        assert_eq!(tracks[0].artist, "Rick Astley");
        assert_eq!(tracks[0].duration_secs, 214);
        assert_eq!(
            tracks[0].album.as_ref().unwrap().title,
            "Whenever You Need Somebody"
        );
        assert_eq!(tracks[0].provider, ProviderType::YouTubeMusic);
        assert!(parse_search(&root, 0).unwrap().is_empty());
        let contents = root.pointer_mut("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents").unwrap();
        *contents = json!([{"musicShelfRenderer":{"contents":[]}}]);
        assert!(parse_search(&root, 20).unwrap().is_empty());
        *root.pointer_mut("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents").unwrap() = json!([{"itemSectionRenderer":{}}]);
        assert!(parse_search(&root, 20)
            .unwrap_err()
            .to_string()
            .contains("Unsupported search response shape"));
        assert!(parse_search(&json!({}), 20).is_err());
    }

    #[test]
    fn duration_formats_are_strict_and_overflow_is_unknown() {
        for (text, expected) in [
            ("3:34", 214),
            ("12:05", 725),
            ("1:02:03", 3723),
            (" 0:00 ", 0),
        ] {
            assert_eq!(parse_duration(text), Some(expected));
        }
        for text in [
            "",
            "Live",
            "3:4",
            "3:60",
            "1:60:00",
            "1:2:03",
            "-1:00",
            "1:02:03:04",
            "18446744073709551615:00",
        ] {
            assert_eq!(parse_duration(text), None, "{text}");
        }
    }

    #[test]
    fn search_duration_uses_last_matching_run_and_missing_is_unknown() {
        let mut root: Value =
            serde_json::from_str(include_str!("../tests/fixtures/search-songs.json")).unwrap();
        let path = "/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents/0/musicShelfRenderer/contents/0/musicResponsiveListItemRenderer/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs";
        root.pointer_mut(path)
            .unwrap()
            .as_array_mut()
            .unwrap()
            .extend([
                json!({"text":" • "}),
                json!({"text":"1:02:03"}),
                json!({"text":"extra metadata"}),
            ]);
        assert_eq!(parse_search(&root, 20).unwrap()[0].duration_secs, 3723);
        *root.pointer_mut(path).unwrap() =
            json!([{"text":"Rick Astley"},{"text":" • "},{"text":"Live"}]);
        let tracks = parse_search(&root, 20).unwrap();
        assert_eq!(tracks[0].duration_secs, 0);
        assert!(tracks[0].album.is_none());
        *root.pointer_mut(path).unwrap() = json!([]);
        assert_eq!(parse_search(&root, 20).unwrap()[0].duration_secs, 0);
    }

    async fn serve_player(
        responses: Vec<&'static str>,
    ) -> (String, tokio::task::JoinHandle<Vec<(String, Value)>>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let task = tokio::spawn(async move {
            let mut requests = Vec::new();
            for body in responses {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut bytes = Vec::new();
                let (headers, offset, length) = loop {
                    let mut buf = [0; 4096];
                    let count = socket.read(&mut buf).await.unwrap();
                    assert_ne!(count, 0);
                    bytes.extend_from_slice(&buf[..count]);
                    if let Some(end) = bytes.windows(4).position(|p| p == b"\r\n\r\n") {
                        let headers = String::from_utf8(bytes[..end].to_vec())
                            .unwrap()
                            .to_lowercase();
                        let length: usize = headers
                            .lines()
                            .find_map(|l| l.strip_prefix("content-length: "))
                            .unwrap()
                            .parse()
                            .unwrap();
                        break (headers, end + 4, length);
                    }
                };
                while bytes.len() < offset + length {
                    let mut buf = [0; 4096];
                    let count = socket.read(&mut buf).await.unwrap();
                    assert_ne!(count, 0);
                    bytes.extend_from_slice(&buf[..count]);
                }
                requests.push((
                    headers,
                    serde_json::from_slice(&bytes[offset..offset + length]).unwrap(),
                ));
                socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body).as_bytes()).await.unwrap();
            }
            requests
        });
        (format!("http://{address}/player"), task)
    }

    #[tokio::test]
    async fn ios_profile_matches_android_without_visitor_handshake() {
        assert_eq!(
            YouTubeMusicProvider::new().player_url,
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        );
        for fixture in [HLS, LOGIN, SABR] {
            let (url, server) = serve_player(vec![fixture]).await;
            let provider = YouTubeMusicProvider {
                player_url: url,
                ..YouTubeMusicProvider::new()
            };
            let track = provider.get_track_details("JALbemLw3G4").await.unwrap();
            let _ = provider.resolve_stream(&track).await;
            let requests = server.await.unwrap();
            assert_eq!(requests.len(), 1);
            let (headers, body) = &requests[0];
            assert!(headers.contains("x-youtube-client-name: 5\r\n"));
            assert!(headers.contains("x-youtube-client-version: 21.26.4\r\n"));
            assert!(headers.contains(&format!("user-agent: {}", IOS_USER_AGENT.to_lowercase())));
            assert!(!headers.contains("x-goog-visitor-id"));
            assert_eq!(
                body,
                &json!({"videoId":"JALbemLw3G4", "context":{"client":{
                    "clientName":"IOS","clientVersion":"21.26.4","deviceModel":"iPhone16,2",
                    "osName":"iPhone","osVersion":"18.5.0.22F76","hl":"en","gl":"US"
                }}})
            );
        }
    }
}
