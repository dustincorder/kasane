//! Legitimate YouTube playback verification for the anonymous WEB_REMIX client.
//!
//! The native side performs every network request. A [`VerificationRuntime`]
//! (a real browser engine owned by the app: WebKitGTK on Linux, the System
//! WebView on Android) runs Google's BotGuard challenge and YouTube's own
//! player script from `verifier/dist/kasane-verifier.js`. The resulting
//! integrity state and player functions live only in memory; nothing is
//! persisted or logged. Any failure is returned as an explicit error.

use async_trait::async_trait;
use reqwest::Url;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;
use vibe_core::error::{Result, VibeError};
use vibe_core::model::StreamSource;

/// A browser engine that has loaded `kasane-verifier.js` and answers
/// `KasaneVerifier.run(id, method, args)` calls.
#[async_trait]
pub trait VerificationRuntime: Send + Sync {
    async fn call(&self, method: &str, args: Vec<Value>) -> Result<Value>;
}

pub const RUNTIME_PROTOCOL: u64 = 1;
pub const WEB_REMIX_VERSION: &str = "1.20250310.01.00";
const REQUEST_KEY: &str = "O43z0dpjhgX20SCx4KAo";
const WAA_API_KEY: &str = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
const AUDIO_ITAG: u64 = 140;
const PLAYER_REFRESH: Duration = Duration::from_secs(6 * 60 * 60);
const INTEGRITY_MARGIN: Duration = Duration::from_secs(10 * 60);
const MAX_PLAYER_JS_BYTES: usize = 8 * 1024 * 1024;

fn verification_error(message: impl Into<String>) -> VibeError {
    VibeError::Provider {
        provider: "YouTubeMusic".into(),
        message: format!("YouTube playback verification failed: {}", message.into()),
    }
}

#[derive(Default)]
struct State {
    protocol_checked: bool,
    integrity_expires: Option<Instant>,
    player: Option<LoadedPlayer>,
}

struct LoadedPlayer {
    id: String,
    signature_timestamp: u64,
    loaded: Instant,
}

pub struct WebPlaybackVerifier {
    runtime: Arc<dyn VerificationRuntime>,
    client: reqwest::Client,
    youtube_base: String,
    music_base: String,
    state: Mutex<State>,
}

impl WebPlaybackVerifier {
    pub fn new(runtime: Arc<dyn VerificationRuntime>) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(20))
            .build()
            .unwrap_or_default();
        Self::with_endpoints(
            runtime,
            client,
            "https://www.youtube.com".into(),
            "https://music.youtube.com".into(),
        )
    }

    pub fn with_endpoints(
        runtime: Arc<dyn VerificationRuntime>,
        client: reqwest::Client,
        youtube_base: String,
        music_base: String,
    ) -> Self {
        Self {
            runtime,
            client,
            youtube_base,
            music_base,
            state: Mutex::new(State::default()),
        }
    }

    /// Resolves a verified, directly playable AAC (itag 140) URL for `video_id`.
    pub async fn resolve(&self, video_id: &str) -> Result<StreamSource> {
        let mut state = self.state.lock().await;
        if !state.protocol_checked {
            let protocol = self.runtime.call("protocol", vec![]).await?;
            if protocol.as_u64() != Some(RUNTIME_PROTOCOL) {
                return Err(verification_error("verification runtime version mismatch"));
            }
            state.protocol_checked = true;
        }
        let signature_timestamp = self.ensure_player(&mut state).await?;
        self.ensure_integrity(&mut state).await?;
        let root = self.player_response(video_id, signature_timestamp).await?;
        let format = select_audio_format(&root, video_id)?;
        let n = format.url.query_pairs().find(|(k, _)| k == "n").map(|(_, v)| v.into_owned());
        let prepared = match (&n, &format.signature) {
            (None, None) => Value::Null,
            _ => {
                self.runtime
                    .call(
                        "decipher",
                        vec![
                            json!(n),
                            json!(format.signature_param),
                            json!(format.signature),
                        ],
                    )
                    .await?
            }
        };
        let token = self
            .runtime
            .call("mint", vec![json!(video_id)])
            .await?
            .as_str()
            .filter(|token| !token.is_empty())
            .map(str::to_owned)
            .ok_or_else(|| verification_error("BotGuard returned no proof"))?;
        let url = apply_verification(&format, &prepared, &token)?;
        Ok(StreamSource {
            expires_at: url
                .query_pairs()
                .find(|(k, _)| k == "expire")
                .and_then(|(_, v)| v.parse().ok()),
            url: url.into(),
            mime_type: format.mime_type,
            bitrate_kbps: format.bitrate_kbps,
        })
    }

    async fn ensure_player(&self, state: &mut State) -> Result<u64> {
        if let Some(player) = &state.player {
            if player.loaded.elapsed() < PLAYER_REFRESH {
                return Ok(player.signature_timestamp);
            }
        }
        let api = self
            .get_text(&format!("{}/iframe_api", self.youtube_base), 1024 * 1024)
            .await?;
        let id = player_id(&api).ok_or_else(|| verification_error("player version not found"))?;
        if state.player.as_ref().is_some_and(|player| player.id == id) {
            if let Some(player) = state.player.as_mut() {
                player.loaded = Instant::now();
                return Ok(player.signature_timestamp);
            }
        }
        let source = self
            .get_text(
                &format!("{}/s/player/{id}/player_es6.vflset/en_US/base.js", self.youtube_base),
                MAX_PLAYER_JS_BYTES,
            )
            .await?;
        let signature_timestamp = signature_timestamp(&source)
            .ok_or_else(|| verification_error("player script is not supported"))?;
        self.runtime.call("loadPlayer", vec![json!(source)]).await?;
        state.player = Some(LoadedPlayer {
            id,
            signature_timestamp,
            loaded: Instant::now(),
        });
        Ok(signature_timestamp)
    }

    async fn ensure_integrity(&self, state: &mut State) -> Result<()> {
        if state.integrity_expires.is_some_and(|expires| Instant::now() < expires) {
            return Ok(());
        }
        state.integrity_expires = None;
        let challenge = self.waa("Create", json!([REQUEST_KEY])).await?;
        let snapshot = self.runtime.call("attest", vec![challenge]).await?;
        let snapshot = snapshot
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or_else(|| verification_error("BotGuard produced no response"))?;
        let issued = self.waa("GenerateIT", json!([REQUEST_KEY, snapshot])).await?;
        let token = issued
            .get(0)
            .and_then(Value::as_str)
            .filter(|t| !t.is_empty())
            .ok_or_else(|| verification_error("integrity token was not issued"))?;
        let ttl = issued.get(1).and_then(Value::as_u64).unwrap_or(0);
        if ttl <= INTEGRITY_MARGIN.as_secs() {
            return Err(verification_error("integrity token expires too soon"));
        }
        self.runtime.call("integrity", vec![json!(token)]).await?;
        state.integrity_expires = Some(Instant::now() + Duration::from_secs(ttl) - INTEGRITY_MARGIN);
        Ok(())
    }

    async fn waa(&self, method: &str, body: Value) -> Result<Value> {
        let response = self
            .client
            .post(format!("{}/api/jnn/v1/{method}", self.youtube_base))
            .header("Content-Type", "application/json+protobuf")
            .header("X-Goog-Api-Key", WAA_API_KEY)
            .header("X-User-Agent", "grpc-web-javascript/0.1")
            .body(body.to_string())
            .send()
            .await
            .map_err(|_| verification_error("could not reach the BotGuard service"))?;
        if !response.status().is_success() {
            return Err(verification_error(format!(
                "BotGuard {method} returned HTTP {}",
                response.status().as_u16()
            )));
        }
        response
            .json()
            .await
            .map_err(|_| verification_error(format!("invalid BotGuard {method} response")))
    }

    async fn player_response(&self, video_id: &str, signature_timestamp: u64) -> Result<Value> {
        let body = json!({
            "videoId": video_id,
            "context": {"client": {
                "clientName": "WEB_REMIX", "clientVersion": WEB_REMIX_VERSION,
                "hl": "en", "gl": "US"
            }},
            "playbackContext": {"contentPlaybackContext": {
                "signatureTimestamp": signature_timestamp
            }}
        });
        let response = self
            .client
            .post(format!("{}/youtubei/v1/player?prettyPrint=false", self.music_base))
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            .header("Origin", "https://music.youtube.com")
            .json(&body)
            .send()
            .await
            .map_err(|_| verification_error("player request failed"))?;
        if !response.status().is_success() {
            return Err(verification_error(format!(
                "player request returned HTTP {}",
                response.status().as_u16()
            )));
        }
        response
            .json()
            .await
            .map_err(|_| verification_error("invalid player response"))
    }

    async fn get_text(&self, url: &str, limit: usize) -> Result<String> {
        let mut response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|_| verification_error("could not download the YouTube player"))?;
        if !response.status().is_success() {
            return Err(verification_error(format!(
                "YouTube player download returned HTTP {}",
                response.status().as_u16()
            )));
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| verification_error("could not download the YouTube player"))?
        {
            if bytes.len() + chunk.len() > limit {
                return Err(verification_error("YouTube player is too large"));
            }
            bytes.extend_from_slice(&chunk);
        }
        String::from_utf8(bytes).map_err(|_| verification_error("YouTube player is not text"))
    }
}

#[derive(Debug)]
struct AudioFormat {
    url: Url,
    signature: Option<String>,
    signature_param: String,
    mime_type: String,
    bitrate_kbps: Option<u32>,
}

fn player_id(iframe_api: &str) -> Option<String> {
    let start = iframe_api.find("player\\/").map(|i| i + 8).or_else(|| iframe_api.find("player/").map(|i| i + 7))?;
    let id: String = iframe_api[start..].chars().take_while(char::is_ascii_hexdigit).collect();
    (id.len() == 8).then_some(id)
}

fn signature_timestamp(source: &str) -> Option<u64> {
    ["signatureTimestamp:", "signatureTimestamp=", "\"signatureTimestamp\":"]
        .iter()
        .find_map(|key| {
            let start = source.find(key)? + key.len();
            let digits: String = source[start..].chars().take_while(char::is_ascii_digit).collect();
            digits.parse().ok()
        })
}

fn select_audio_format(root: &Value, video_id: &str) -> Result<AudioFormat> {
    if root
        .pointer("/videoDetails/videoId")
        .and_then(Value::as_str)
        .is_some_and(|id| id != video_id)
    {
        return Err(verification_error("player returned a different video"));
    }
    let status = root.pointer("/playabilityStatus/status").and_then(Value::as_str);
    if status != Some("OK") {
        let reason = root
            .pointer("/playabilityStatus/reason")
            .and_then(Value::as_str)
            .unwrap_or("no reason given");
        return Err(VibeError::Provider {
            provider: "YouTubeMusic".into(),
            message: format!("Unplayable ({}): {reason}", status.unwrap_or("UNKNOWN")),
        });
    }
    let format = root
        .pointer("/streamingData/adaptiveFormats")
        .and_then(Value::as_array)
        .and_then(|formats| {
            formats
                .iter()
                .find(|f| f.get("itag").and_then(Value::as_u64) == Some(AUDIO_ITAG))
        })
        .ok_or_else(|| verification_error("no AAC audio format in the player response"))?;
    let (raw_url, signature, signature_param) = match format.get("url").and_then(Value::as_str) {
        Some(url) => (url.to_owned(), None, "sig".to_owned()),
        None => {
            let cipher = format
                .get("signatureCipher")
                .or_else(|| format.get("cipher"))
                .and_then(Value::as_str)
                .ok_or_else(|| verification_error("audio format has no URL"))?;
            let fields = Url::parse(&format!("https://cipher.invalid/?{cipher}"))
                .map_err(|_| verification_error("invalid signature cipher"))?;
            let get = |key: &str| {
                fields
                    .query_pairs()
                    .find(|(k, _)| k == key)
                    .map(|(_, v)| v.into_owned())
            };
            (
                get("url").ok_or_else(|| verification_error("invalid signature cipher"))?,
                Some(get("s").ok_or_else(|| verification_error("invalid signature cipher"))?),
                get("sp").unwrap_or_else(|| "signature".into()),
            )
        }
    };
    let url = Url::parse(&raw_url).map_err(|_| verification_error("invalid audio URL"))?;
    if url.scheme() != "https"
        || !url
            .host_str()
            .is_some_and(|host| host.ends_with(".googlevideo.com"))
    {
        return Err(verification_error("unexpected audio host"));
    }
    Ok(AudioFormat {
        url,
        signature,
        signature_param,
        mime_type: format
            .get("mimeType")
            .and_then(Value::as_str)
            .and_then(|mime| mime.split(';').next())
            .unwrap_or("audio/mp4")
            .to_owned(),
        bitrate_kbps: format
            .get("bitrate")
            .and_then(Value::as_u64)
            .and_then(|bps| u32::try_from(bps / 1000).ok()),
    })
}

fn apply_verification(format: &AudioFormat, prepared: &Value, token: &str) -> Result<Url> {
    let mut replacements: Vec<(String, String)> = Vec::new();
    if format.signature.is_some() {
        let sig = prepared
            .get("sig")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| verification_error("player script did not produce a signature"))?;
        replacements.push((format.signature_param.clone(), sig.to_owned()));
    }
    if format.url.query_pairs().any(|(k, _)| k == "n") {
        let n = prepared
            .get("n")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .ok_or_else(|| verification_error("player script did not transform the URL"))?;
        replacements.push(("n".into(), n.to_owned()));
    }
    replacements.push(("pot".into(), token.to_owned()));
    let kept: Vec<(String, String)> = format
        .url
        .query_pairs()
        .filter(|(k, _)| !replacements.iter().any(|(r, _)| r == k))
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    let mut url = format.url.clone();
    url.query_pairs_mut().clear().extend_pairs(kept).extend_pairs(replacements);
    Ok(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[derive(Default)]
    struct FakeRuntime {
        calls: StdMutex<Vec<(String, Vec<Value>)>>,
    }

    #[async_trait]
    impl VerificationRuntime for FakeRuntime {
        async fn call(&self, method: &str, args: Vec<Value>) -> Result<Value> {
            self.calls.lock().unwrap().push((method.into(), args));
            Ok(match method {
                "protocol" => json!(RUNTIME_PROTOCOL),
                "attest" => json!("snapshot"),
                "integrity" | "loadPlayer" => json!(true),
                "decipher" => json!({"sig": "SIGNED", "n": "fast"}),
                "mint" => json!("video-proof"),
                _ => return Err(verification_error("unknown")),
            })
        }
    }

    const PLAYER: &str = r#"{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"JALbemLw3G4"},
        "streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://rr1.googlevideo.com/videoplayback?itag=251"},
        {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,
        "signatureCipher":"s=SCRAMBLED&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fexpire%3D1900000000%26itag%3D140%26n%3Dslow"}]}}"#;

    async fn serve() -> (String, tokio::task::JoinHandle<Vec<(String, String)>>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let mut seen = Vec::new();
            while seen.len() < 5 {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut bytes = Vec::new();
                let (head, body) = loop {
                    let mut buf = [0; 8192];
                    let count = socket.read(&mut buf).await.unwrap();
                    bytes.extend_from_slice(&buf[..count]);
                    if let Some(end) = bytes.windows(4).position(|p| p == b"\r\n\r\n") {
                        let head = String::from_utf8_lossy(&bytes[..end]).to_lowercase();
                        let length: usize = head
                            .lines()
                            .find_map(|l| l.strip_prefix("content-length: "))
                            .map_or(0, |v| v.parse().unwrap());
                        while bytes.len() < end + 4 + length {
                            let count = socket.read(&mut buf).await.unwrap();
                            bytes.extend_from_slice(&buf[..count]);
                        }
                        break (head, String::from_utf8_lossy(&bytes[end + 4..]).into_owned());
                    }
                };
                let path = head.split_whitespace().nth(1).unwrap().to_owned();
                let reply = if path.starts_with("/iframe_api") {
                    r#"var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/6b3d0364\/www-widgetapi.vflset\/www-widgetapi.js';"#
                } else if path.starts_with("/s/player/6b3d0364/") {
                    "var a={signatureTimestamp:20774};"
                } else if path.ends_with("/create") {
                    r#"["challenge"]"#
                } else if path.ends_with("/generateit") {
                    r#"["integrity-token",43200]"#
                } else {
                    PLAYER
                };
                socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}", reply.len()).as_bytes()).await.unwrap();
                seen.push((path, body));
            }
            seen
        });
        (base, task)
    }

    #[tokio::test]
    async fn resolves_a_deciphered_url_bound_to_the_video() {
        let (base, server) = serve().await;
        let runtime = Arc::new(FakeRuntime::default());
        let verifier = WebPlaybackVerifier::with_endpoints(
            runtime.clone(),
            reqwest::Client::new(),
            base.clone(),
            base,
        );
        let source = verifier.resolve("JALbemLw3G4").await.unwrap();
        let url = Url::parse(&source.url).unwrap();
        let query: Vec<(String, String)> = url.query_pairs().map(|(k, v)| (k.into(), v.into())).collect();
        assert_eq!(url.host_str(), Some("rr1.googlevideo.com"));
        assert!(query.contains(&("sig".into(), "SIGNED".into())));
        assert!(query.contains(&("n".into(), "fast".into())));
        assert!(query.contains(&("pot".into(), "video-proof".into())));
        assert!(!query.iter().any(|(_, v)| v == "slow"));
        assert_eq!(source.mime_type, "audio/mp4");
        assert_eq!(source.expires_at, Some(1_900_000_000));

        let requests = server.await.unwrap();
        let player = requests.iter().find(|(p, _)| p.starts_with("/youtubei/v1/player")).unwrap();
        let body: Value = serde_json::from_str(&player.1).unwrap();
        assert_eq!(body["context"]["client"]["clientName"], "WEB_REMIX");
        assert_eq!(body["playbackContext"]["contentPlaybackContext"]["signatureTimestamp"], 20774);
        let generate = requests.iter().find(|(p, _)| p.ends_with("/generateit")).unwrap();
        assert_eq!(generate.1, json!([REQUEST_KEY, "snapshot"]).to_string());

        let calls = runtime.calls.lock().unwrap();
        let methods: Vec<&str> = calls.iter().map(|(m, _)| m.as_str()).collect();
        assert_eq!(methods, ["protocol", "loadPlayer", "attest", "integrity", "decipher", "mint"]);
        assert_eq!(calls[2].1, vec![json!(["challenge"])]);
        assert_eq!(calls[4].1, vec![json!("slow"), json!("sig"), json!("SCRAMBLED")]);
        assert_eq!(calls[5].1, vec![json!("JALbemLw3G4")]);
    }

    #[test]
    fn parses_player_version_and_signature_timestamp() {
        assert_eq!(player_id(r"s\/player\/6b3d0364\/www").as_deref(), Some("6b3d0364"));
        assert_eq!(player_id("/s/player/abcdef12/x").as_deref(), Some("abcdef12"));
        assert_eq!(player_id("/s/player/xyz/"), None);
        assert_eq!(signature_timestamp("x={signatureTimestamp:20774,y}"), Some(20774));
        assert_eq!(signature_timestamp("nothing"), None);
    }

    #[test]
    fn rejects_unplayable_foreign_and_untrusted_responses() {
        let unplayable = json!({"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}});
        assert!(select_audio_format(&unplayable, "JALbemLw3G4").unwrap_err().to_string().contains("LOGIN_REQUIRED"));
        let other = json!({"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"kJQP7kiw5Fk"}});
        assert!(select_audio_format(&other, "JALbemLw3G4").is_err());
        let no_aac = json!({"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":251,"url":"https://a.googlevideo.com/v"}]}});
        assert!(select_audio_format(&no_aac, "JALbemLw3G4").unwrap_err().to_string().contains("no AAC"));
        let foreign = json!({"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"itag":140,"url":"https://evil.example/v"}]}});
        assert!(select_audio_format(&foreign, "JALbemLw3G4").unwrap_err().to_string().contains("unexpected audio host"));
    }

    #[test]
    fn missing_player_output_is_an_explicit_error() {
        let format = AudioFormat {
            url: Url::parse("https://a.googlevideo.com/videoplayback?n=slow").unwrap(),
            signature: None,
            signature_param: "sig".into(),
            mime_type: "audio/mp4".into(),
            bitrate_kbps: None,
        };
        assert!(apply_verification(&format, &json!({"n": null}), "t").unwrap_err().to_string().contains("did not transform"));
        let url = apply_verification(&format, &json!({"n": "fast"}), "t").unwrap();
        assert_eq!(url.query(), Some("n=fast&pot=t"));
    }
}
