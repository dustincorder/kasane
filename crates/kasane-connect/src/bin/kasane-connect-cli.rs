use kasane_connect::adapter::PlayerAdapter;
use kasane_connect::fake::FakePlayer;
use kasane_connect::protocol::{Frame, HandoffRequest};
use kasane_connect::service::ConnectService;
use serde_json::{Map, Value};
use std::io::{BufRead, Write};
use std::sync::Arc;
use std::time::Duration;

/// Headless Kasane Connect controller/server for real-device testing.
///
/// Usage:
///   kasane-connect-cli [--data-dir DIR] serve            # fake in-memory player
///   kasane-connect-cli [--data-dir DIR] list [secs]
///   kasane-connect-cli [--data-dir DIR] pair <id|ip[:port]>
///   kasane-connect-cli [--data-dir DIR] state <id|ip[:port]>
///   kasane-connect-cli [--data-dir DIR] cmd <id|ip[:port]> <action> [json-args]
///   kasane-connect-cli [--data-dir DIR] handoff <id|ip[:port]> <videoId> <positionMs>
///   kasane-connect-cli [--data-dir DIR] unpair <id>

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "warn".into()),
        )
        .init();
    let mut args = std::env::args().skip(1).collect::<Vec<_>>();
    let mut data_dir = std::env::temp_dir().join("kasane-connect-cli");
    if args.first().map(String::as_str) == Some("--data-dir") {
        data_dir = args.get(1).map(Into::into).unwrap_or_else(|| data_dir.clone());
        args.drain(0..2);
    }
    let Some(command) = args.first().cloned() else {
        eprintln!("usage: kasane-connect-cli [--data-dir DIR] <serve|list|pair|state|cmd|handoff|unpair> ...");
        std::process::exit(2);
    };
    std::fs::create_dir_all(&data_dir).expect("data dir");

    let player: Arc<dyn PlayerAdapter> = Arc::new(FakePlayer::new());
    let mdns = std::env::var("KC_MDNS").map(|v| v != "0").unwrap_or(true);
    let svc = ConnectService::start(&data_dir, "kasane-cli", "linux", "cli", player, mdns)
        .await
        .expect("start connect service");
    let me = svc.local_device();
    eprintln!(
        "[cli] {} id={} port={} fp={}",
        me.name,
        me.id,
        svc.port(),
        hex::encode(kasane_connect::identity::Identity::load_or_create(&data_dir, "x")
            .unwrap()
            .fingerprint())
    );

    match command.as_str() {
        "serve" => {
            if args.get(1).map(String::as_str) == Some("--auto-accept") {
                let mut prompts = svc.pairing_prompts();
                let svc2 = svc.clone();
                tokio::spawn(async move {
                    while prompts.changed().await.is_ok() {
                        // Bind the clone first: a `for` keeps temporaries of its
                        // iterator expression alive for the whole loop, so
                        // `for p in prompts.borrow().clone()` would still hold the
                        // watch read lock while confirm_pairing() writes to it.
                        let pending = prompts.borrow().clone();
                        for p in pending {
                            if p.incoming {
                                eprintln!("[cli] auto-accepting pairing with {}", p.peer_name);
                                svc2.confirm_pairing(&p.prompt_id, true);
                            }
                        }
                    }
                });
            }
            eprintln!("[cli] serving fake player (no audio) — Ctrl-C to stop");
            loop {
                tokio::time::sleep(Duration::from_secs(3600)).await;
            }
        }
        "list" => {
            let secs: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(5);
            tokio::time::sleep(Duration::from_secs(secs)).await;
            for d in svc.devices().borrow().iter() {
                println!(
                    "{}\t{}\t{}\t{}\tonline={} trusted={} conn={}{}",
                    d.id.as_deref().unwrap_or("?"),
                    d.name,
                    d.platform.as_deref().unwrap_or("?"),
                    d.addr.as_deref().unwrap_or("?"),
                    d.online,
                    d.trusted,
                    d.connection,
                    d.last_error.as_deref().map(|e| format!(" err={e}")).unwrap_or_default()
                );
            }
        }
        "pair" => {
            let target = args.get(1).expect("pair <id|ip[:port]>");
            let stdin = std::io::stdin();
            // Auto-answer incoming prompts after printing, but pairing here is
            // outgoing: wait for our prompt, show the SAS, ask y/n.
            let rx = svc.pairing_prompts();
            let result = svc.pair(target).await;
            match result {
                Ok(None) => println!("already paired"),
                Ok(Some(_prompt)) => {
                    // Wait for the prompt to appear.
                    for _ in 0..200 {
                        if !rx.borrow().is_empty() {
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                    let prompts = rx.borrow().clone();
                    for p in prompts {
                        println!("Pair with {} — is the code {} shown there? [y/N]", p.peer_name, p.code);
                        print!("> ");
                        std::io::stdout().flush().unwrap();
                        let mut line = String::new();
                        stdin.lock().read_line(&mut line).unwrap();
                        let yes = line.trim().eq_ignore_ascii_case("y");
                        svc.confirm_pairing(&p.prompt_id, yes);
                        println!("{}", if yes { "confirmed" } else { "rejected" });
                    }
                    // The other side may take a while to confirm; keep the
                    // connection open until the pairing result arrives.
                    let deadline = std::time::Instant::now() + Duration::from_secs(120);
                    let paired = loop {
                        let trusted: Vec<String> = svc
                            .devices()
                            .borrow()
                            .iter()
                            .filter(|d| d.trusted)
                            .map(|d| format!("{} ({})", d.name, d.id.as_deref().unwrap_or("?")))
                            .collect();
                        if !trusted.is_empty() || std::time::Instant::now() > deadline {
                            break trusted;
                        }
                        tokio::time::sleep(Duration::from_millis(200)).await;
                    };
                    if paired.is_empty() {
                        eprintln!("not paired (rejected or timed out)");
                        std::process::exit(1);
                    }
                    for d in paired {
                        println!("paired: {d}");
                    }
                }
                Err(e) => {
                    eprintln!("pair failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "state" => {
            let target = args.get(1).expect("state <id|ip[:port]>");
            let conn = svc.connect(target).await.expect("connect");
            let id = conn.peer.id.clone();
            let mut rx = svc.remote_state(&id).expect("remote state");
            eprintln!("[cli] subscribed to {}", conn.peer.name);
            loop {
                if rx.changed().await.is_err() {
                    break;
                }
                if let Some(state) = &*rx.borrow() {
                    println!("{}", serde_json::to_string(&Frame::State(Box::new(state.clone()))).unwrap());
                }
            }
        }
        "cmd" => {
            let target = args.get(1).expect("cmd <id|ip[:port]> <action> [json-args]");
            let action = args.get(2).expect("cmd <target> <action>");
            let extra: Map<String, Value> = args
                .get(3)
                .and_then(|s| serde_json::from_str::<Value>(s).ok())
                .and_then(|v| v.as_object().cloned())
                .unwrap_or_default();
            let conn = svc.connect(target).await.expect("connect");
            match svc.send_command(&conn.peer.id, action, extra).await {
                Ok(()) => println!("ack ok"),
                Err(e) => {
                    println!("ack fail {}: {}", e.code, e.message);
                    std::process::exit(1);
                }
            }
        }
        "handoff" => {
            let target = args.get(1).expect("handoff <id|ip[:port]> <videoId> <positionMs>");
            let video_id = args.get(2).expect("videoId");
            let pos: u64 = args.get(3).and_then(|s| s.parse().ok()).expect("positionMs");
            let conn = svc.connect(target).await.expect("connect");
            let request = HandoffRequest {
                transfer_id: String::new(),
                queue: vec![kasane_connect::protocol::TrackMeta {
                    id: video_id.clone(),
                    title: video_id.clone(),
                    artist: String::new(),
                    artists: vec![],
                    album: None,
                    duration_ms: 0,
                    artwork_url: None,
                    source: "youtube".into(),
                }],
                index: 0,
                position_ms: pos,
                playing: true,
                shuffle: false,
                repeat: "off".into(),
            };
            match svc.handoff(&conn.peer.id, request).await {
                Ok(()) => println!("handoff ok"),
                Err(e) => {
                    println!("handoff fail {}: {}", e.code, e.message);
                    std::process::exit(1);
                }
            }
        }
        "unpair" => {
            let id = args.get(1).expect("unpair <id>");
            svc.unpair(id).await;
            println!("unpaired {id}");
        }
        other => {
            eprintln!("unknown command {other:?}");
            std::process::exit(2);
        }
    }
}
