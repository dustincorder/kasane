//! YouTube Music account sign-in: a separate login webview captures the Google
//! session cookies, the session is validated against InnerTube, and only then
//! persisted — exclusively in the OS Secret Service (ksecretd here). When the
//! Secret Service is unavailable the session lives in memory only and the UI
//! reports it. The cookie is never written to disk, logged, or put on the
//! Kasane Connect wire.

use std::path::PathBuf;
use std::sync::Mutex;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};
use kasane_providers::{AccountIdentity, AccountSession, YouTubeMusicProvider};

use crate::AppState;

const KEYRING_SERVICE: &str = "cord.kasane.desktop";
const KEYRING_ACCOUNT: &str = "youtube-session";
pub const LOGIN_LABEL: &str = "google-login";
const LOGIN_URL: &str = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F";
const COOKIE_URL: &str = "https://music.youtube.com";

pub struct AccountRuntime {
    identity: Mutex<Option<AccountIdentity>>,
    /// "keyring" when the Secret Service accepted our probe, else "memory".
    persistence: Mutex<&'static str>,
}

impl AccountRuntime {
    pub fn new() -> Self {
        Self {
            identity: Mutex::new(None),
            persistence: Mutex::new("keyring"),
        }
    }

    fn set_identity(&self, identity: Option<AccountIdentity>) {
        if let Ok(mut guard) = self.identity.lock() {
            *guard = identity;
        }
    }

    fn persistence(&self) -> &'static str {
        self.persistence.lock().map(|p| *p).unwrap_or("memory")
    }

    fn mark_memory_only(&self) {
        if let Ok(mut p) = self.persistence.lock() {
            *p = "memory";
        }
    }
}

#[derive(Clone, Serialize)]
pub struct AccountStatus {
    signed_in: bool,
    name: Option<String>,
    avatar: Option<String>,
    channel_id: Option<String>,
    persistence: &'static str,
}

fn status_of(state: &AppState) -> AccountStatus {
    let identity = state
        .account
        .identity
        .lock()
        .ok()
        .and_then(|guard| guard.clone());
    AccountStatus {
        signed_in: state.yt_provider.session().is_some(),
        name: identity.as_ref().map(|i| i.name.clone()),
        avatar: identity.as_ref().and_then(|i| i.avatar.clone()),
        channel_id: identity.and_then(|i| i.channel_id.clone()),
        persistence: state.account.persistence(),
    }
}

fn emit_status(app: &AppHandle, state: &AppState) {
    let _ = app.emit("account://update", status_of(state));
}

fn keyring_entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_ACCOUNT).map_err(|e| e.to_string())
}

/// Reads the stored session JSON. Returns Ok(None) when no entry exists;
/// Err when the Secret Service itself is unreachable.
fn keyring_load() -> Result<Option<AccountSession>, String> {
    let entry = keyring_entry()?;
    match entry.get_password() {
        Ok(raw) => serde_json::from_str(&raw)
            .map(Some)
            .map_err(|e| format!("stored session is unreadable: {e}")),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

fn keyring_store(session: &AccountSession) -> Result<(), String> {
    let raw = serde_json::to_string(session).map_err(|e| e.to_string())?;
    keyring_entry()?.set_password(&raw).map_err(|e| e.to_string())
}

fn keyring_delete() {
    if let Ok(entry) = keyring_entry() {
        let _ = entry.delete_credential();
    }
}

/// Called once during app setup: restores a stored session into the provider.
/// Persistence availability is probed here so Settings can report it.
pub fn restore(app: AppHandle, provider: std::sync::Arc<YouTubeMusicProvider>) {
    tauri::async_runtime::spawn(async move {
        let stored = tauri::async_runtime::spawn_blocking(keyring_load)
            .await
            .unwrap_or_else(|e| Err(e.to_string()));
        let session = match stored {
            Ok(Some(session)) => session,
            Ok(None) => return,
            Err(e) => {
                tracing::warn!("Secret Service unavailable, session will be memory-only: {e}");
                if let Some(state) = app.try_state::<AppState>() {
                    state.account.mark_memory_only();
                }
                return;
            }
        };
        // Validate before activating: an expired cookie must not sign the user in.
        match provider.validate_session(&session).await {
            Ok(identity) => {
                provider.set_session(Some(session));
                if let Some(state) = app.try_state::<AppState>() {
                    state.account.set_identity(Some(identity));
                    emit_status(&app, &state);
                }
            }
            Err(e) => {
                tracing::warn!("Stored YouTube session is no longer valid: {e}");
                tauri::async_runtime::spawn_blocking(keyring_delete);
            }
        }
    });
}

fn login_data_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("login-webview");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

pub fn wipe_login_data(app: &AppHandle) {
    if let Ok(dir) = login_data_dir(app) {
        let _ = std::fs::remove_dir_all(&dir);
    }
}

/// Builds the `a=v; b=v` header from webview cookies; requires a SAPISID-family
/// cookie or returns None (navigation reached YouTube but not signed in).
fn cookie_header(cookies: &[tauri::webview::Cookie]) -> Option<String> {
    let header = cookies
        .iter()
        .map(|c| format!("{}={}", c.name(), c.value()))
        .collect::<Vec<_>>()
        .join("; ");
    let has_sapisid = cookies.iter().any(|c| {
        matches!(
            c.name(),
            "SAPISID" | "__Secure-3PAPISID" | "__Secure-1PAPISID"
        )
    });
    has_sapisid.then_some(header)
}

/// After navigation lands on music.youtube.com: pull cookies, validate the
/// session against account/account_menu, persist to the Secret Service, then
/// close the login window and wipe its data directory.
async fn capture_login(app: AppHandle) -> Result<(), String> {
    let Some(window) = app.get_webview_window(LOGIN_LABEL) else {
        return Err("login window already closed".into());
    };
    let url = tauri::Url::parse(COOKIE_URL).map_err(|e| e.to_string())?;
    let cookies = window
        .cookies_for_url(url)
        .map_err(|e| e.to_string())?;
    let Some(cookie) = cookie_header(&cookies) else {
        return Err("signed-in cookies not present yet".into());
    };
    let mut session = AccountSession {
        cookie,
        auth_user: 0,
        channel_id: None,
        visitor_data: cookies
            .iter()
            .find(|c| c.name() == "VISITOR_DATA")
            .map(|c| c.value().to_string()),
    };
    let state = app
        .try_state::<AppState>()
        .ok_or("app state unavailable")?;
    // Validate BEFORE persisting: reject a dead or incomplete login.
    let identity = state
        .yt_provider
        .validate_session(&session)
        .await
        .map_err(|e| e.to_string())?;
    if session.channel_id.is_none() {
        session.channel_id = identity.channel_id.clone();
    }
    let stored = tauri::async_runtime::spawn_blocking({
        let session = session.clone();
        move || keyring_store(&session)
    })
    .await
    .unwrap_or_else(|e| Err(e.to_string()));
    match stored {
        Ok(()) => {}
        Err(e) => {
            tracing::warn!("Secret Service write failed, session is memory-only: {e}");
            state.account.mark_memory_only();
        }
    }
    state.yt_provider.set_session(Some(session));
    state.account.set_identity(Some(identity));
    emit_status(&app, &state);
    if let Some(window) = app.get_webview_window(LOGIN_LABEL) {
        let _ = window.close();
    }
    wipe_login_data(&app);
    Ok(())
}

pub mod commands {
    use super::*;

    #[tauri::command]
    pub async fn account_status(state: State<'_, AppState>) -> Result<AccountStatus, String> {
        Ok(status_of(&state))
    }

    /// Opens the dedicated Google sign-in window. The capture runs when a
    /// navigation lands on music.youtube.com; closing the window without
    /// signing in leaves the user signed out.
    #[tauri::command]
    pub async fn account_sign_in(app: AppHandle) -> Result<(), String> {
        if app.get_webview_window(LOGIN_LABEL).is_some() {
            return Ok(()); // already open — a second click just focuses it
        }
        let dir = login_data_dir(&app)?;
        let (nav_tx, nav_rx) = std::sync::mpsc::channel::<tauri::Url>();
        WebviewWindowBuilder::new(
            &app,
            LOGIN_LABEL,
            WebviewUrl::External(
                LOGIN_URL
                    .parse()
                    .map_err(|_| "invalid login URL".to_string())?,
            ),
        )
        .title("Sign in to YouTube Music")
        .inner_size(460.0, 780.0)
        .data_directory(dir)
        .on_navigation(move |url| {
            let _ = nav_tx.send(url.clone());
            true
        })
        .build()
        .map_err(|e| e.to_string())?;
        tauri::async_runtime::spawn(async move {
            // Each navigation event is consumed off the async worker; a landing
            // on music.youtube.com triggers capture, and the watcher exits when
            // the window is destroyed or the session is captured.
            let nav_rx = std::sync::Arc::new(std::sync::Mutex::new(nav_rx));
            loop {
                if app.get_webview_window(LOGIN_LABEL).is_none() {
                    break;
                }
                let url = tauri::async_runtime::spawn_blocking({
                    let nav_rx = nav_rx.clone();
                    move || nav_rx.lock().ok().and_then(|rx| rx.recv().ok())
                })
                .await
                .ok()
                .flatten();
                let Some(url) = url else { break };
                if url.host_str() != Some("music.youtube.com") {
                    continue;
                }
                match capture_login(app.clone()).await {
                    Ok(()) => break,
                    Err(e) => {
                        tracing::warn!("YouTube sign-in capture failed: {e}");
                        // Keep watching: the user may still be mid-flow.
                    }
                }
            }
        });
        Ok(())
    }

    #[tauri::command]
    pub async fn account_sign_out(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
        state.yt_provider.set_session(None);
        state.account.set_identity(None);
        tauri::async_runtime::spawn_blocking(keyring_delete);
        emit_status(&app, &state);
        Ok(())
    }

    #[tauri::command]
    pub async fn account_liked_music(state: State<'_, AppState>) -> Result<Vec<kasane_core::model::Track>, String> {
        state
            .yt_provider
            .account_liked_music()
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn account_playlists(
        state: State<'_, AppState>,
    ) -> Result<Vec<kasane_providers::LibraryPlaylist>, String> {
        state
            .yt_provider
            .account_playlists()
            .await
            .map_err(|e| e.to_string())
    }

    #[tauri::command]
    pub async fn account_playlist_tracks(
        state: State<'_, AppState>,
        playlist_id: String,
    ) -> Result<Vec<kasane_core::model::Track>, String> {
        state
            .yt_provider
            .account_playlist_tracks(&playlist_id)
            .await
            .map_err(|e| e.to_string())
    }

    #[derive(Serialize)]
    pub struct StorageInfo {
        app_data_dir: String,
        database_path: String,
        database_bytes: u64,
        connect_bytes: u64,
    }

    fn dir_size(dir: &std::path::Path) -> u64 {
        fn walk(dir: &std::path::Path) -> u64 {
            let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
            entries
                .filter_map(|e| e.ok())
                .map(|e| {
                    let path = e.path();
                    if path.is_dir() {
                        walk(&path)
                    } else {
                        e.metadata().map(|m| m.len()).unwrap_or(0)
                    }
                })
                .sum()
        }
        walk(dir)
    }

    /// Real, existing storage only — no placeholders.
    #[tauri::command]
    pub async fn storage_info(app: AppHandle) -> Result<StorageInfo, String> {
        tauri::async_runtime::spawn_blocking(move || {
            let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
            let db_path = dir.join("kasane.db");
            let database_bytes = std::fs::metadata(&db_path)
                .map(|m| m.len())
                .unwrap_or(0);
            Ok(StorageInfo {
                app_data_dir: dir.to_string_lossy().into(),
                database_path: db_path.to_string_lossy().into(),
                database_bytes,
                connect_bytes: dir_size(&dir.join("connect")),
            })
        })
        .await
        .map_err(|e| e.to_string())?
    }
}
