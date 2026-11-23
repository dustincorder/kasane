//! Hidden WebKitGTK webview that hosts `verifier/dist/kasane-verifier.js` on
//! the app's own origin. It makes no network requests; the Rust verifier
//! passes data in and receives results through the `verifier_reply` command.

use async_trait::async_trait;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::webview::PageLoadEvent;
use tauri::{AppHandle, State, Webview, WebviewUrl, WebviewWindow, WebviewWindowBuilder};
use tokio::sync::{oneshot, OnceCell};
use kasane_core::error::{Result, KasaneError};
use kasane_providers::VerificationRuntime;

pub const LABEL: &str = "kasane-verifier";
const BUNDLE: &str = include_str!("../../verifier/dist/kasane-verifier.js");
const BRIDGE: &str = "globalThis.__kasaneReply = (id, payload) => \
    window.__TAURI_INTERNALS__.invoke('verifier_reply', { id, payload });";
const LOAD_TIMEOUT: Duration = Duration::from_secs(20);
const CALL_TIMEOUT: Duration = Duration::from_secs(45);

fn runtime_error(message: &str) -> KasaneError {
    KasaneError::Provider {
        provider: "YouTubeMusic".into(),
        message: format!("YouTube playback verification failed: {message}"),
    }
}

#[derive(Clone, Default)]
pub struct Replies(Arc<Mutex<HashMap<u64, oneshot::Sender<String>>>>);

pub struct WebviewRuntime {
    app: AppHandle,
    replies: Replies,
    next_id: AtomicU64,
    window: OnceCell<WebviewWindow>,
}

impl WebviewRuntime {
    pub fn new(app: AppHandle, replies: Replies) -> Self {
        Self {
            app,
            replies,
            next_id: AtomicU64::new(1),
            window: OnceCell::new(),
        }
    }

    async fn window(&self) -> Result<&WebviewWindow> {
        self.window
            .get_or_try_init(|| async {
                let (loaded, on_load) = oneshot::channel();
                let loaded = Mutex::new(Some(loaded));
                let window =
                    WebviewWindowBuilder::new(&self.app, LABEL, WebviewUrl::App("verifier.html".into()))
                        .title("Kasane verification")
                        .visible(false)
                        .skip_taskbar(true)
                        .on_page_load(move |_, payload| {
                            if payload.event() == PageLoadEvent::Finished {
                                if let Some(sender) = loaded.lock().ok().and_then(|mut s| s.take()) {
                                    let _ = sender.send(());
                                }
                            }
                        })
                        .build()
                        .map_err(|_| runtime_error("could not start the verification webview"))?;
                tokio::time::timeout(LOAD_TIMEOUT, on_load)
                    .await
                    .map_err(|_| runtime_error("verification webview did not load"))?
                    .map_err(|_| runtime_error("verification webview did not load"))?;
                window
                    .eval(BRIDGE)
                    .and_then(|_| window.eval(BUNDLE))
                    .map_err(|_| runtime_error("could not load the verification runtime"))?;
                Ok(window)
            })
            .await
    }
}

#[async_trait]
impl VerificationRuntime for WebviewRuntime {
    async fn call(&self, method: &str, args: Vec<Value>) -> Result<Value> {
        let window = self.window().await?;
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (sender, reply) = oneshot::channel();
        self.replies
            .0
            .lock()
            .map_err(|_| runtime_error("verification state is unavailable"))?
            .insert(id, sender);
        let script = format!(
            "globalThis.KasaneVerifier ? KasaneVerifier.run({id}, {}, {}) : \
             __kasaneReply({id}, JSON.stringify({{ ok: false, error: 'verification-runtime-missing' }}));",
            Value::from(method),
            Value::from(args),
        );
        let outcome = match window.eval(&script) {
            Ok(()) => tokio::time::timeout(CALL_TIMEOUT, reply).await.ok().and_then(|r| r.ok()),
            Err(_) => None,
        };
        if let Ok(mut pending) = self.replies.0.lock() {
            pending.remove(&id);
        }
        let payload = outcome.ok_or_else(|| runtime_error("verification runtime did not respond"))?;
        let reply: Value = serde_json::from_str(&payload)
            .map_err(|_| runtime_error("invalid verification runtime reply"))?;
        if reply.get("ok").and_then(Value::as_bool) == Some(true) {
            Ok(reply.get("value").cloned().unwrap_or(Value::Null))
        } else {
            let code = reply.get("error").and_then(Value::as_str).unwrap_or("unknown error");
            Err(runtime_error(code))
        }
    }
}

#[tauri::command]
pub fn verifier_reply(
    webview: Webview,
    replies: State<'_, Replies>,
    id: u64,
    payload: String,
) -> std::result::Result<(), String> {
    if webview.label() != LABEL {
        return Err("Not permitted".into());
    }
    let sender = replies
        .0
        .lock()
        .map_err(|_| "Verification state is unavailable")?
        .remove(&id);
    if let Some(sender) = sender {
        let _ = sender.send(payload);
    }
    Ok(())
}
