//! YouTube Music account sessions (SAPISID cookies), authenticated InnerTube
//! requests and account library browsing.
//!
//! The cookie is only ever sent to *.youtube.com InnerTube endpoints. It is
//! never logged and never crosses the Kasane Connect wire.

use std::sync::{Arc, RwLock};

use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION, COOKIE, ORIGIN, REFERER};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};

use kasane_core::error::{Result, KasaneError};
use kasane_core::model::{Album, Artist, ProviderType, Track};

pub const YOUTUBE_ORIGIN: &str = "https://music.youtube.com";
pub const LIKED_MUSIC_BROWSE_ID: &str = "FEmusic_liked_videos";
pub const PLAYLISTS_BROWSE_ID: &str = "FEmusic_liked_playlists";
/// InnerTube browse ids are prefixed with VL to read a playlist's contents.
const PLAYLIST_PREFIX: &str = "VL";

fn provider_error(message: impl Into<String>) -> KasaneError {
    KasaneError::Provider {
        provider: "YouTubeMusic".into(),
        message: message.into(),
    }
}

/// The signed-in YouTube Music account's session material. Persisted as JSON
/// in the OS secret store — never written to disk by the app itself.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountSession {
    pub cookie: String,
    pub auth_user: u32,
    #[serde(default)]
    pub channel_id: Option<String>,
    #[serde(default)]
    pub visitor_data: Option<String>,
}

impl AccountSession {
    /// First SAPISID-family cookie value; `__Secure-3PAPISID` counts too.
    fn sapisid(&self) -> Option<&str> {
        self.cookie.split(';').map(str::trim).find_map(|pair| {
            let (name, value) = pair.split_once('=')?;
            matches!(name, "SAPISID" | "__Secure-3PAPISID" | "__Secure-1PAPISID")
                .then_some(value)
        })
    }
}

/// Display identity resolved from the account menu (name/avatar/channel).
#[derive(Debug, Clone, Serialize)]
pub struct AccountIdentity {
    pub name: String,
    pub avatar: Option<String>,
    pub channel_id: Option<String>,
}

/// Shared, swappable session holder: the app sets/clears it on sign-in/out and
/// the provider reads it lazily on every request.
pub type SessionStore = Arc<RwLock<Option<AccountSession>>>;

pub fn session_store() -> SessionStore {
    Arc::new(RwLock::new(None))
}

pub fn read_session(store: &SessionStore) -> Option<AccountSession> {
    store.read().ok().and_then(|guard| guard.clone())
}

pub fn write_session(store: &SessionStore, session: Option<AccountSession>) {
    if let Ok(mut guard) = store.write() {
        *guard = session;
    }
}

/// `SAPISIDHASH <ts>_<sha1("<ts> <sapisid> <origin>")>` — the InnerTube
/// authorization header required by authenticated WEB_REMIX requests.
pub fn sapisidhash(sapisid: &str, timestamp: u64, origin: &str) -> String {
    let mut sha = Sha1::new();
    sha.update(format!("{timestamp} {sapisid} {origin}").as_bytes());
    format!("{timestamp}_{:x}", sha.finalize())
}

/// Headers mirroring the Kotlin InnerTubeClient authenticated request:
/// Cookie, SAPISIDHASH Authorization, X-Goog-AuthUser, optional
/// X-Goog-Visitor-Id / X-Goog-PageId, Origin and Referer.
pub fn auth_headers(session: &AccountSession) -> Result<HeaderMap> {
    let sapisid = session
        .sapisid()
        .ok_or_else(|| provider_error("session cookie has no SAPISID"))?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| provider_error("system clock before epoch"))?
        .as_secs();
    let auth = format!("SAPISIDHASH {}", sapisidhash(sapisid, now, YOUTUBE_ORIGIN));
    let mut headers = HeaderMap::new();
    let mut push = |name: reqwest::header::HeaderName, value: String| {
        if let Ok(v) = HeaderValue::from_str(&value) {
            headers.insert(name, v);
        }
    };
    push(COOKIE, session.cookie.clone());
    push(AUTHORIZATION, auth);
    push(
        reqwest::header::HeaderName::from_static("x-goog-authuser"),
        session.auth_user.to_string(),
    );
    if let Some(visitor) = session
        .visitor_data
        .as_ref()
        .filter(|data| !data.is_empty())
    {
        push(
            reqwest::header::HeaderName::from_static("x-goog-visitor-id"),
            visitor.clone(),
        );
    }
    if let Some(channel) = session
        .channel_id
        .as_ref()
        .filter(|channel| !channel.is_empty())
    {
        push(
            reqwest::header::HeaderName::from_static("x-goog-pageid"),
            channel.clone(),
        );
    }
    push(ORIGIN, YOUTUBE_ORIGIN.into());
    push(REFERER, YOUTUBE_ORIGIN.into());
    Ok(headers)
}

/// First `key` renderer anywhere in a (deeply nested) InnerTube response —
/// the same recursive search the Kotlin parsers use.
fn find_renderer<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    match value {
        Value::Object(map) => map
            .get(key)
            .or_else(|| map.values().find_map(|v| find_renderer(v, key))),
        Value::Array(items) => items.iter().find_map(|v| find_renderer(v, key)),
        _ => None,
    }
}

fn text_of(value: Option<&Value>) -> String {
    let Some(value) = value else { return String::new() };
    if let Some(runs) = value.get("runs").and_then(Value::as_array) {
        return runs
            .iter()
            .filter_map(|run| run.get("text").and_then(Value::as_str))
            .collect();
    }
    value
        .get("simpleText")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn first_thumbnail(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(|obj| obj.get("thumbnails"))
        .and_then(Value::as_array)
        .and_then(|list| list.first())
        .and_then(|thumb| thumb.get("url"))
        .and_then(Value::as_str)
        .map(str::to_string)
}

/// Parses the account identity out of an `account/account_menu` response,
/// following `activeAccountHeaderRenderer` like the Kotlin identity parser.
pub fn parse_account_identity(body: &Value, channel_id: Option<&str>) -> Option<AccountIdentity> {
    let renderer = find_renderer(body, "activeAccountHeaderRenderer")?;
    let name = text_of(renderer.get("accountName"));
    if name.is_empty() {
        return None;
    }
    Some(AccountIdentity {
        name,
        avatar: first_thumbnail(renderer, "accountPhoto"),
        channel_id: channel_id.map(str::to_string),
    })
}

/// A user playlist entry in the library grid (`musicTwoRowItemRenderer`).
#[derive(Debug, Clone, Serialize)]
pub struct LibraryPlaylist {
    pub id: String,
    pub name: String,
    pub thumbnail_url: Option<String>,
    pub track_count: u64,
}

fn playlist_row(renderer: &Value) -> Option<LibraryPlaylist> {
    let id = renderer
        .pointer("/title/runs/0/navigationEndpoint/browseEndpoint/browseId")
        .and_then(Value::as_str)?
        .strip_prefix(PLAYLIST_PREFIX)
        .unwrap_or_default()
        .to_string();
    let name = text_of(renderer.get("title"));
    if id.is_empty() || name.is_empty() {
        return None;
    }
    // Last subtitle run holds "N canciones"/"N songs".
    let track_count = renderer
        .get("subtitle")
        .and_then(|s| s.get("runs"))
        .and_then(Value::as_array)
        .and_then(|runs| runs.last())
        .and_then(|run| run.get("text"))
        .and_then(Value::as_str)
        .and_then(|text| text.split_whitespace().next()?.parse::<u64>().ok())
        .unwrap_or(0);
    Some(LibraryPlaylist {
        id,
        name,
        thumbnail_url: first_thumbnail(renderer, "thumbnailRenderer")
            .or_else(|| first_thumbnail(renderer, "musicThumbnailRenderer")),
        track_count,
    })
}

fn parse_duration(text: &str) -> Option<u64> {
    let parts: Vec<&str> = text.trim().split(':').collect();
    if !(2..=3).contains(&parts.len()) || parts.iter().any(|p| p.is_empty()) {
        return None;
    }
    parts.iter().try_fold(0u64, |acc, part| {
        acc.checked_mul(60)?.checked_add(part.trim().parse().ok()?)
    })
}

/// A track row inside a playlist/library shelf
/// (`musicResponsiveListItemRenderer`: title run + metadata runs).
fn track_row(renderer: &Value) -> Option<Track> {
    let columns = renderer.get("flexColumns")?.as_array()?;
    if columns.len() < 2 {
        return None;
    }
    let first_run = columns[0]
        .pointer("/musicResponsiveListItemFlexColumnRenderer/text/runs/0")?;
    let title = first_run.get("text")?.as_str()?.to_string();
    let id = first_run
        .pointer("/navigationEndpoint/watchEndpoint/videoId")
        .and_then(Value::as_str)?
        .to_string();
    let detail_runs = columns[1]
        .pointer("/musicResponsiveListItemFlexColumnRenderer/text/runs")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let texts: Vec<&str> = detail_runs
        .iter()
        .filter_map(|run| run.get("text").and_then(Value::as_str))
        .filter(|text| text.trim() != "•")
        .collect();
    let artist = texts.first().copied().unwrap_or("Unknown Artist").to_string();
    let album_title = texts.get(1).filter(|_| texts.len() > 2).map(|t| t.to_string());
    let duration_secs = texts.iter().find_map(|t| parse_duration(t)).unwrap_or(0);
    let artist_id = detail_runs
        .first()
        .and_then(|run| run.pointer("/navigationEndpoint/browseEndpoint/browseId"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let thumbnail = renderer
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .and_then(Value::as_array)
        .and_then(|list| list.last())
        .and_then(|thumb| thumb.get("url"))
        .and_then(Value::as_str)
        .map(str::to_string);
    Some(Track {
        id,
        title,
        artist,
        artists: vec![Artist {
            id: artist_id,
            name: texts.first().copied().unwrap_or_default().to_string(),
        }],
        album: album_title.map(|title| Album {
            id: String::new(),
            title,
            cover_url: thumbnail.clone(),
        }),
        duration_secs,
        thumbnail_url: thumbnail,
        provider: ProviderType::YouTubeMusic,
        uri: None,
    })
}

fn collect_renderers<'a>(value: &'a Value, key: &str, out: &mut Vec<&'a Value>) {
    match value {
        Value::Object(map) => {
            if let Some(found) = map.get(key) {
                out.push(found);
            }
            for v in map.values() {
                collect_renderers(v, key, out);
            }
        }
        Value::Array(items) => {
            for v in items {
                collect_renderers(v, key, out);
            }
        }
        _ => (),
    }
}

fn continuation_token(body: &Value) -> Option<String> {
    let renderer = find_renderer(body, "continuationCommand")?;
    renderer
        .get("token")
        .and_then(Value::as_str)
        .map(str::to_string)
}

/// A page of the signed-in account library.
#[derive(Debug, Default)]
pub struct LibraryPage {
    pub playlists: Vec<LibraryPlaylist>,
    pub tracks: Vec<Track>,
    pub continuation: Option<String>,
}

pub fn parse_library_page(body: &Value) -> LibraryPage {
    let mut rows = Vec::new();
    collect_renderers(body, "musicTwoRowItemRenderer", &mut rows);
    let playlists = rows.iter().filter_map(|r| playlist_row(r)).collect();
    let mut track_rows = Vec::new();
    collect_renderers(body, "musicResponsiveListItemRenderer", &mut track_rows);
    let tracks = track_rows.iter().filter_map(|r| track_row(r)).collect();
    LibraryPage {
        playlists,
        tracks,
        continuation: continuation_token(body),
    }
}

/// Authenticated InnerTube client for account/library endpoints (WEB_REMIX).
pub struct AccountClient {
    http: reqwest::Client,
    base_url: String,
    client_version: String,
}

impl AccountClient {
    pub fn new(http: reqwest::Client) -> Self {
        Self {
            http,
            base_url: "https://music.youtube.com".into(),
            client_version: "1.20240101.01.00".into(),
        }
    }

    pub fn with_base_url(mut self, base_url: String) -> Self {
        self.base_url = base_url;
        self
    }

    async fn innertube(&self, endpoint: &str, extra: Value, session: &AccountSession) -> Result<Value> {
        let url = format!("{}/youtubei/v1/{endpoint}?prettyPrint=false", self.base_url);
        let mut body = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": self.client_version,
                    "hl": "en", "gl": "US"
                }
            }
        });
        if let Some(channel) = session.channel_id.as_deref().filter(|c| !c.is_empty()) {
            body["context"]["client"]["user"] = json!({"onBehalfOfUser": channel});
        }
        if let (Value::Object(a), Value::Object(b)) = (&mut body, extra) {
            for (k, v) in b {
                a.insert(k, v);
            }
        }
        let response = self
            .http
            .post(&url)
            .headers(auth_headers(session)?)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", &self.client_version)
            .json(&body)
            .send()
            .await
            .map_err(|e| provider_error(format!("InnerTube {endpoint} failed: {e}")))?;
        let status = response.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Err(provider_error(format!(
                "session rejected by YouTube (HTTP {status})"
            )));
        }
        if !status.is_success() {
            return Err(provider_error(format!("InnerTube {endpoint}: HTTP {status}")));
        }
        response
            .json()
            .await
            .map_err(|e| provider_error(format!("invalid {endpoint} JSON: {e}")))
    }

    /// Validates the session against `account/account_menu` and returns the
    /// resolved identity. Fails on 401 or a menu without account identity.
    pub async fn identity(&self, session: &AccountSession) -> Result<AccountIdentity> {
        let body = self.innertube("account/account_menu", json!({}), session).await?;
        parse_account_identity(&body, session.channel_id.as_deref())
            .ok_or_else(|| provider_error("account menu returned no identity"))
    }

    pub async fn library_page(
        &self,
        browse_id: &str,
        continuation: Option<&str>,
        session: &AccountSession,
    ) -> Result<LibraryPage> {
        let extra = match continuation {
            Some(ctoken) => json!({"continuation": ctoken, "ctoken": ctoken}),
            None => json!({"browseId": browse_id}),
        };
        let body = self.innertube("browse", extra, session).await?;
        Ok(parse_library_page(&body))
    }

    /// First page of the signed-in account's liked-music tracks.
    pub async fn liked_music(&self, session: &AccountSession) -> Result<Vec<Track>> {
        Ok(self.library_page(LIKED_MUSIC_BROWSE_ID, None, session).await?.tracks)
    }

    /// The signed-in account's user playlists.
    pub async fn playlists(&self, session: &AccountSession) -> Result<Vec<LibraryPlaylist>> {
        Ok(self.library_page(PLAYLISTS_BROWSE_ID, None, session).await?.playlists)
    }

    /// Tracks of one user playlist (first page).
    pub async fn playlist_tracks(&self, playlist_id: &str, session: &AccountSession) -> Result<Vec<Track>> {
        Ok(self
            .library_page(&format!("{PLAYLIST_PREFIX}{playlist_id}"), None, session)
            .await?
            .tracks)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sapisidhash_matches_fixed_vector() {
        // Independently computed: sha1("1234567890 sapisid_secret https://music.youtube.com")
        assert_eq!(
            sapisidhash("sapisid_secret", 1_234_567_890, "https://music.youtube.com"),
            "1234567890_f0bd76b269dc2c19be0e18c35dfddad8fb94afa9"
        );
    }

    #[test]
    fn identity_parses_reduced_account_menu_fixture() {
        // Reduced/scrubbed shape of a real account_menu response.
        let body = json!({
            "actions": [{
                "openPopupAction": {
                    "popup": {"multiPageMenuRenderer": {
                        "header": {"accountHeaderRenderer": {}},
                        "sections": [{"accountSectionListRenderer": {}}]
                    }}
                }
            }],
            "activeAccountHeaderRenderer": {
                "accountName": {"runs": [{"text": "Test User"}]},
                "accountPhoto": {"thumbnails": [{"url": "https://example.test/avatar.jpg"}]}
            }
        });
        let identity = parse_account_identity(&body, Some("UC000")).expect("identity");
        assert_eq!(identity.name, "Test User");
        assert_eq!(identity.avatar.as_deref(), Some("https://example.test/avatar.jpg"));
        assert_eq!(identity.channel_id.as_deref(), Some("UC000"));

        // Identity missing when the renderer or its name is absent.
        assert!(parse_account_identity(&json!({}), None).is_none());
        assert!(parse_account_identity(
            &json!({"activeAccountHeaderRenderer": {"accountName": {"runs": []}}}),
            None
        )
        .is_none());
    }
}
