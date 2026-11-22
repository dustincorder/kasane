use async_trait::async_trait;
use reqwest::header::{HeaderMap, HeaderValue, USER_AGENT};
use serde_json::Value;
use std::time::Duration;
use tokio::sync::Mutex;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{Artist, ProviderType, StreamSource, Track};
use vibe_core::traits::MusicProvider;

pub struct YouTubeMusicProvider {
    client: reqwest::Client,
    player_url: String,
    visitor_data: Mutex<Option<String>>,
}

const VR_VERSION: &str = "1.65.10";
const VR_USER_AGENT: &str = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

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
            player_url: "https://music.youtube.com/youtubei/v1/player?prettyPrint=false".into(),
            visitor_data: Mutex::new(None),
        }
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
        let mut visitor = self.visitor_data.lock().await;
        for attempt in 0..2 {
            let mut body = serde_json::json!({
                "context": { "client": {
                    "clientName": "ANDROID_VR", "clientVersion": VR_VERSION,
                    "deviceMake": "Oculus", "deviceModel": "Quest 3",
                    "osName": "Android", "osVersion": "12L", "androidSdkVersion": 32,
                    "hl": "en", "gl": "US", "userAgent": VR_USER_AGENT
                }},
                "videoId": track.id,
                "playbackContext": { "contentPlaybackContext": { "html5Preference": "HTML5_PREF_WANTS" }}
            });
            let mut request = self
                .client
                .post(&self.player_url)
                .header(USER_AGENT, VR_USER_AGENT)
                .header("X-YouTube-Client-Name", "28")
                .header("X-YouTube-Client-Version", VR_VERSION);
            if let Some(value) = visitor.as_ref() {
                body["context"]["client"]["visitorData"] = Value::String(value.clone());
                request = request.header("X-Goog-Visitor-Id", value);
            }
            let response = request
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
            let issued = root
                .pointer("/responseContext/visitorData")
                .and_then(Value::as_str)
                .filter(|value| {
                    !value.is_empty() && value.bytes().all(|b| (33..=126).contains(&b))
                });
            let changed = issued.is_some_and(|value| visitor.as_deref() != Some(value));
            if let Some(value) = issued {
                *visitor = Some(value.into());
            }
            if attempt == 0 && is_bot_challenge(&root) && changed {
                continue;
            }
            if root
                .pointer("/videoDetails/videoId")
                .and_then(Value::as_str)
                .is_some_and(|id| id != track.id)
            {
                return Err(provider_error("Player returned a different video ID"));
            }
            return parse_player(&root);
        }
        Err(provider_error("Bot check not cleared"))
    }
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

fn is_bot_challenge(root: &Value) -> bool {
    let reason = root
        .pointer("/playabilityStatus/reason")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_lowercase();
    root.pointer("/playabilityStatus/status")
        .and_then(Value::as_str)
        == Some("LOGIN_REQUIRED")
        && reason.contains("confirm")
        && reason.contains("bot")
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
        Some("OK") => direct_audio_source(root)
            .ok_or_else(|| provider_error("No compatible direct AAC/MP4 audio format")),
        Some("LOGIN_REQUIRED") if is_bot_challenge(root) => {
            Err(provider_error("Bot check not cleared"))
        }
        Some("LOGIN_REQUIRED") => Err(provider_error(format!("Login required: {reason}"))),
        Some(status) => Err(provider_error(format!("Unplayable ({status}): {reason}"))),
        None => Err(provider_error(
            "Invalid player response: missing playability status",
        )),
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
                || url.host_str().is_none()
                || format
                    .get("drmTrackType")
                    .and_then(Value::as_str)
                    .is_some_and(|value| !value.is_empty())
                || format
                    .get("drmFamilies")
                    .and_then(Value::as_array)
                    .is_some_and(|value| !value.is_empty())
            {
                return None;
            }
            Some((
                format.get("itag").and_then(Value::as_u64) == Some(140),
                StreamSource {
                    url: url.into(),
                    mime_type: mime.into(),
                    bitrate_kbps: format
                        .get("bitrate")
                        .and_then(Value::as_u64)
                        .map(|bitrate| (bitrate / 1000) as u32),
                    expires_at: None,
                },
            ))
        })
        .max_by_key(|(preferred, source)| (*preferred, source.bitrate_kbps))
        .map(|(_, source)| source)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const DIRECT: &str = include_str!("../tests/fixtures/player-direct.json");
    const LOGIN: &str = include_str!("../tests/fixtures/player-login.json");
    const BOT: &str = include_str!("../tests/fixtures/player-bot.json");
    const NO_AUDIO: &str = include_str!("../tests/fixtures/player-no-audio.json");

    #[test]
    fn player_fixtures_have_distinct_outcomes() {
        assert_eq!(
            parse_player(&serde_json::from_str(DIRECT).unwrap())
                .unwrap()
                .url,
            "https://example.com/aac"
        );
        for (fixture, message) in [
            (LOGIN, "Login required"),
            (BOT, "Bot check not cleared"),
            (NO_AUDIO, "No compatible direct AAC/MP4"),
        ] {
            assert!(parse_player(&serde_json::from_str(fixture).unwrap())
                .unwrap_err()
                .to_string()
                .contains(message));
        }
        assert!(parse_player(&json!({"playabilityStatus":{"status":"UNPLAYABLE","reason":"Not available in your region"}})).unwrap_err().to_string().contains("Unplayable (UNPLAYABLE): Not available in your region"));
        assert!(parse_player(&json!({})).is_err());
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
    async fn bot_handshake_uses_server_visitor_once_and_matching_vr_profile() {
        let (url, server) = serve_player(vec![BOT, DIRECT]).await;
        let provider = YouTubeMusicProvider {
            player_url: url,
            ..YouTubeMusicProvider::new()
        };
        let track = provider.get_track_details("JALbemLw3G4").await.unwrap();
        assert_eq!(
            provider.resolve_stream(&track).await.unwrap().url,
            "https://example.com/aac"
        );
        let requests = server.await.unwrap();
        assert_eq!(requests.len(), 2);
        for (headers, body) in &requests {
            assert!(headers.contains("x-youtube-client-name: 28\r\n"));
            assert!(headers.contains("x-youtube-client-version: 1.65.10\r\n"));
            assert!(headers.contains(&format!("user-agent: {}", VR_USER_AGENT.to_lowercase())));
            assert_eq!(body["context"]["client"]["clientName"], "ANDROID_VR");
            assert_eq!(body["context"]["client"]["deviceModel"], "Quest 3");
            assert_eq!(
                body["playbackContext"]["contentPlaybackContext"]["html5Preference"],
                "HTML5_PREF_WANTS"
            );
        }
        assert!(requests[0].1["context"]["client"]["visitorData"].is_null());
        assert!(requests[1]
            .0
            .contains("x-goog-visitor-id: anonymous-fixture"));
        assert_eq!(
            requests[1].1["context"]["client"]["visitorData"],
            "anonymous-fixture"
        );
    }

    #[tokio::test]
    async fn login_does_not_retry_and_uncleared_bot_is_bounded() {
        for (responses, expected, count) in [
            (vec![LOGIN], "Login required", 1),
            (vec![BOT, BOT], "Bot check not cleared", 2),
        ] {
            let (url, server) = serve_player(responses).await;
            let provider = YouTubeMusicProvider {
                player_url: url,
                ..YouTubeMusicProvider::new()
            };
            let track = provider.get_track_details("JALbemLw3G4").await.unwrap();
            assert!(provider
                .resolve_stream(&track)
                .await
                .unwrap_err()
                .to_string()
                .contains(expected));
            assert_eq!(server.await.unwrap().len(), count);
        }
    }

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
