//! Headless benchmark driver for the Tauri desktop stack.
//!
//! Mirrors the Compose desktop `--smoke`/`--play` modes so `bench/run.py` can
//! measure both engines under identical scenarios. Uses the same
//! `YouTubeMusicProvider` + `PlayerManager` + `DesktopAudioBackend` as the app,
//! minus the Tauri runtime, windows and the verification webview.
//!
//!   kasane-bench --smoke [query]          search -> play -> seek -> pause -> next
//!   kasane-bench --play <query> <secs>    play first result, print position every 5 s
//!   kasane-bench --idle <secs>            build engine, sleep (idle baseline)
//!
//! Prints `EVT <name> <t_ms> [detail]` markers for timing. Never prints URLs.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use kasane_audio::{DesktopAudioBackend, PlayerManager};
use kasane_core::error::{KasaneError, Result};
use kasane_core::traits::MusicProvider;
use kasane_providers::verification::{VerificationRuntime, WebPlaybackVerifier};
use kasane_providers::YouTubeMusicProvider;
use serde_json::Value;

/// VerificationRuntime backed by the Compose desktop `--verifier` helper
/// (`KASANE_VERIFIER_HELPER` = path to the kasane-compose launcher).
///
/// The Tauri app verifies tracks through a hidden WebKitGTK webview that only
/// exists when the UI runs. Headless, the same system WebKitGTK is reachable
/// through the standalone JSON-lines helper the Compose client already ships.
/// Spawned lazily on the first call, like the Compose client's own helper —
/// the helper's JVM+WebKit processes then live inside this process's tree and
/// are counted by the sampler, matching how Compose is measured.
struct HelperRuntime {
    inner: tokio::sync::OnceCell<std::result::Result<Arc<Helper>, String>>,
    path: String,
}

struct Helper {
    stdin: Mutex<ChildStdin>,
    pending: Mutex<HashMap<u64, tokio::sync::oneshot::Sender<Value>>>,
    next: AtomicU64,
    _child: Mutex<Child>,
}

impl HelperRuntime {
    fn lazy(path: String) -> Arc<Self> {
        Arc::new(Self {
            inner: tokio::sync::OnceCell::new(),
            path,
        })
    }

    async fn helper(&self) -> std::result::Result<Arc<Helper>, String> {
        self.inner
            .get_or_init(|| async { Helper::spawn(&self.path) })
            .await
            .clone()
    }
}

impl Helper {
    fn spawn(path: &str) -> std::result::Result<Arc<Helper>, String> {
        let mut child = Command::new(path)
            .arg("--verifier")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("verifier helper spawn: {e}"))?;
        let stdout = child.stdout.take().ok_or("verifier helper: no stdout")?;
        let stdin = child.stdin.take().ok_or("verifier helper: no stdin")?;
        let mut reader = BufReader::new(stdout);
        let mut first = String::new();
        reader
            .read_line(&mut first)
            .map_err(|e| format!("verifier helper ready: {e}"))?;
        let ready: Value = serde_json::from_str(&first).unwrap_or_default();
        if !ready["ready"].as_bool().unwrap_or(false) {
            let _ = child.kill();
            return Err(format!(
                "verifier helper not ready: {}",
                ready["error"].as_str().unwrap_or("unknown")
            ));
        }
        let helper = Arc::new(Helper {
            stdin: Mutex::new(stdin),
            pending: Mutex::new(HashMap::new()),
            next: AtomicU64::new(1),
            _child: Mutex::new(child),
        });
        let pending_target = helper.clone();
        std::thread::spawn(move || {
            for line in reader.lines() {
                let Ok(line) = line else { break };
                let Ok(reply) = serde_json::from_str::<Value>(&line) else {
                    continue;
                };
                if let Some(id) = reply["id"].as_u64() {
                    if let Some(tx) = pending_target
                        .pending
                        .lock()
                        .ok()
                        .and_then(|mut p| p.remove(&id))
                    {
                        let _ = tx.send(reply);
                    }
                }
            }
        });
        Ok(helper)
    }
}

#[async_trait::async_trait]
impl VerificationRuntime for HelperRuntime {
    async fn call(&self, method: &str, args: Vec<Value>) -> Result<Value> {
        let helper = self
            .helper()
            .await
            .map_err(|e| KasaneError::Provider { provider: "bench".into(), message: e })?;
        let id = helper.next.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = tokio::sync::oneshot::channel();
        helper
            .pending
            .lock()
            .map_err(|_| KasaneError::Provider { provider: "bench".into(), message: "verifier helper lock".into() })?
            .insert(id, tx);
        let line = serde_json::json!({ "id": id, "method": method, "args": args });
        {
            let mut stdin = helper
                .stdin
                .lock()
                .map_err(|_| KasaneError::Provider { provider: "bench".into(), message: "verifier helper lock".into() })?;
            writeln!(stdin, "{line}")
                .and_then(|()| stdin.flush())
                .map_err(|e| KasaneError::Provider { provider: "bench".into(), message: format!("verifier helper io: {e}") })?;
        }
        let reply = tokio::time::timeout(std::time::Duration::from_secs(50), rx)
            .await
            .map_err(|_| KasaneError::Provider { provider: "bench".into(), message: "verifier helper timeout".into() })?
            .map_err(|_| KasaneError::Provider { provider: "bench".into(), message: "verifier helper exited".into() })?;
        if reply["ok"].as_bool().unwrap_or(false) {
            Ok(reply["value"].clone())
        } else {
            Err(KasaneError::Provider {
                provider: "bench".into(),
                message: format!("verification: {}", reply["error"].as_str().unwrap_or("unknown")),
            })
        }
    }
}

const DEFAULT_QUERY: &str = "Daft Punk Around the World";

fn evt(t0: Instant, name: &str, detail: &str) {
    println!("EVT {name} {} {detail}", t0.elapsed().as_millis());
}

async fn engine() -> Result<(Arc<PlayerManager>, Arc<YouTubeMusicProvider>)> {
    let mut provider = YouTubeMusicProvider::new();
    if let Ok(helper) = std::env::var("KASANE_VERIFIER_HELPER") {
        provider = provider.with_verifier(Arc::new(WebPlaybackVerifier::new(
            HelperRuntime::lazy(helper),
        )));
    }
    let provider = Arc::new(provider);
    let player = Arc::new(PlayerManager::new(
        Box::new(DesktopAudioBackend::new()?),
        provider.clone(),
    ));
    let _ = player.set_volume(0.0).await;
    Ok((player, provider))
}

/// The bench has no verification webview, so a track that requires attestation
/// cannot be resolved. Try results in order until one resolves — the same
/// fallback the app would hit if its verifier were unavailable. Emits
/// `skipped` events for rejected candidates so the asymmetry is visible.
async fn play_first_resolvable(
    player: &PlayerManager,
    t0: Instant,
    tracks: &[kasane_core::model::Track],
) -> Option<()> {
    for (i, track) in tracks.iter().enumerate() {
        match player.play_track_now(track.clone()).await {
            Ok(_) => {
                evt(t0, "play_ok", &format!("index={i} title={}", track.title));
                return Some(());
            }
            Err(e) => evt(t0, "skipped", &format!("index={i} {e}")),
        }
    }
    evt(t0, "fail", "no resolvable track");
    None
}

async fn wait_position(player: &PlayerManager, timeout_ms: u64) -> f64 {
    let deadline = Instant::now() + std::time::Duration::from_millis(timeout_ms);
    loop {
        if let Ok(p) = player.get_progress().await {
            if p.position_secs > 0.5 {
                return p.position_secs;
            }
        }
        if Instant::now() > deadline {
            return -1.0;
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}

#[tokio::main]
async fn main() {
    let t0 = Instant::now();
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("--smoke");

    match mode {
        "--idle" => {
            let secs: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(60);
            match engine().await {
                Ok(_) => {
                    evt(t0, "ready", "");
                    tokio::time::sleep(std::time::Duration::from_secs(secs)).await;
                }
                Err(e) => {
                    evt(t0, "fail", &format!("engine: {e}"));
                    std::process::exit(1);
                }
            }
        }
        "--play" => {
            let secs: u64 = args
                .last()
                .and_then(|s| s.parse().ok())
                .unwrap_or(60);
            let query = args[2..args.len() - 1].join(" ");
            let query = if query.is_empty() {
                DEFAULT_QUERY.to_string()
            } else {
                query
            };
            let (player, provider) = match engine().await {
                Ok(e) => e,
                Err(e) => {
                    evt(t0, "fail", &format!("engine: {e}"));
                    std::process::exit(1);
                }
            };
            evt(t0, "ready", "");
            let tracks = match provider.search(&query, 20).await {
                Ok(t) if !t.is_empty() => t,
                _ => {
                    evt(t0, "fail", "no results");
                    std::process::exit(1);
                }
            };
            evt(t0, "search_done", &format!("n={}", tracks.len()));
            if play_first_resolvable(&player, t0, &tracks).await.is_none() {
                std::process::exit(1);
            }
            let pos = wait_position(&player, 25_000).await;
            if pos < 0.0 {
                evt(t0, "fail", "playback timeout");
                std::process::exit(1);
            }
            evt(t0, "audible", &format!("pos={pos:.1}"));
            let rounds = secs / 5;
            for i in 0..rounds {
                tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                if let Ok(p) = player.get_progress().await {
                    evt(
                        t0,
                        "tick",
                        &format!("t={}s pos={:.1}", (i + 1) * 5, p.position_secs),
                    );
                }
            }
        }
        _ => {
            let query = args.get(2).cloned().unwrap_or_else(|| DEFAULT_QUERY.into());
            let (player, provider) = match engine().await {
                Ok(e) => e,
                Err(e) => {
                    evt(t0, "fail", &format!("engine: {e}"));
                    std::process::exit(1);
                }
            };
            evt(t0, "ready", "");
            match provider.search(&query, 20).await {
                Ok(tracks) if !tracks.is_empty() => {
                    evt(t0, "search_done", &format!("n={}", tracks.len()));
                    if play_first_resolvable(&player, t0, &tracks).await.is_none() {
                        std::process::exit(1);
                    }
                    let pos = wait_position(&player, 25_000).await;
                    if pos < 0.0 {
                        evt(t0, "fail", "playback timeout");
                        std::process::exit(1);
                    }
                    evt(t0, "audible", &format!("pos={pos:.1}"));
                    if let Err(e) = player.seek(60.0).await {
                        evt(t0, "warn", &format!("seek: {e}"));
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
                    let _ = player.toggle_play_pause().await;
                    tokio::time::sleep(std::time::Duration::from_millis(800)).await;
                    let _ = player.toggle_play_pause().await;
                    match player.next().await {
                        Ok(Some(t)) => evt(t0, "next_done", &t.title),
                        Ok(None) => evt(t0, "warn", "next: queue end"),
                        Err(e) => evt(t0, "warn", &format!("next: {e}")),
                    }
                    let pos2 = wait_position(&player, 25_000).await;
                    evt(
                        t0,
                        if pos2 > 0.0 { "next_audible" } else { "warn" },
                        &format!("pos={pos2:.1}"),
                    );
                }
                _ => {
                    evt(t0, "fail", "no results");
                    std::process::exit(1);
                }
            }
        }
    }
}
