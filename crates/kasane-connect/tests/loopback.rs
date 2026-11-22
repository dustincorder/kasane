use kasane_connect::adapter::{PlayerAdapter, PlayerSnapshot};
use kasane_connect::fake::FakePlayer;
use kasane_connect::identity::Identity;
use kasane_connect::protocol::{Frame, HandoffRequest, TrackMeta};
use kasane_connect::raw::RawClient;
use kasane_connect::service::ConnectService;
use kasane_connect::tls::TlsConfigs;
use serde_json::{json, Map};
use std::sync::Arc;
use std::time::Duration;

fn track(id: &str) -> TrackMeta {
    TrackMeta {
        id: id.into(),
        title: id.into(),
        artist: "artist".into(),
        artists: vec![],
        album: None,
        duration_ms: 60_000,
        artwork_url: None,
        source: "youtube".into(),
    }
}

async fn service(dir: &tempfile::TempDir, name: &str) -> (ConnectService, Arc<FakePlayer>) {
    let player = Arc::new(FakePlayer::new());
    let svc = ConnectService::start(
        dir.path(),
        name,
        "linux",
        "test",
        player.clone(),
        false,
    )
    .await
    .unwrap();
    (svc, player)
}

fn confirm_prompts(svc: &ConnectService, accept: bool) {
    let mut rx = svc.pairing_prompts();
    let svc = svc.clone();
    tokio::spawn(async move {
        while rx.changed().await.is_ok() {
            let prompts = rx.borrow().clone();
            for p in prompts {
                svc.confirm_pairing(&p.prompt_id, accept);
            }
        }
    });
}

async fn wait_until<F: FnMut() -> bool>(mut f: F) -> bool {
    for _ in 0..100 {
        if f() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    false
}

/// Seed a fresh outbound connection's trust on the server side so a raw
/// client is authenticated on arrival.
fn pretrust(server_dir: &tempfile::TempDir, client_dir: &tempfile::TempDir) {
    let client_identity = Identity::load_or_create(client_dir.path(), "client").unwrap();
    let fp = hex::encode(client_identity.fingerprint());
    let trusted = serde_json::json!([{
        "deviceId": client_identity.device_id,
        "name": "client",
        "platform": "test",
        "fingerprint": fp,
        "pairedAt": 1,
        "endpoints": []
    }]);
    std::fs::write(server_dir.path().join("trusted.json"), trusted.to_string()).unwrap();
}

fn raw_identity(dir: &tempfile::TempDir) -> (Identity, TlsConfigs) {
    let id = Identity::load_or_create(dir.path(), "raw").unwrap();
    let tls = TlsConfigs::new(id.cert_der.clone(), id.key_der.clone()).unwrap();
    (id, tls)
}

#[tokio::test]
async fn pairing_success_then_authorized_command() {
    let _ = tracing_subscriber::fmt().with_env_filter("kasane_connect=debug").try_init();
    let da = tempfile::tempdir().unwrap();
    let db = tempfile::tempdir().unwrap();
    let (a, pa) = service(&da, "A").await;
    let (b, _pb) = service(&db, "B").await;
    confirm_prompts(&a, true);
    confirm_prompts(&b, true);

    let addr = format!("127.0.0.1:{}", a.port());
    let prompt = b.pair(&addr).await.unwrap();
    assert!(prompt.is_some(), "expected a pairing prompt");
    assert!(wait_until(|| b
        .devices()
        .borrow()
        .iter()
        .any(|d| d.trusted))
    .await);

    let device_id = b
        .devices()
        .borrow()
        .iter()
        .find(|d| d.trusted)
        .and_then(|d| d.id.clone())
        .unwrap();
    b.send_command(&device_id, "pause", Map::new()).await.unwrap();
    assert!(wait_until(|| pa.executed.lock().unwrap().contains(&"pause".to_string())).await);
}

#[tokio::test]
async fn pairing_rejected_when_one_side_declines() {
    let da = tempfile::tempdir().unwrap();
    let db = tempfile::tempdir().unwrap();
    let (a, pa) = service(&da, "A").await;
    let (b, _pb) = service(&db, "B").await;
    confirm_prompts(&a, false);
    confirm_prompts(&b, true);

    let addr = format!("127.0.0.1:{}", a.port());
    let _prompt = b.pair(&addr).await.unwrap();
    // Give the exchange a moment, then confirm nothing was trusted or executed.
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert!(!b.devices().borrow().iter().any(|d| d.trusted));
    assert!(!a.devices().borrow().iter().any(|d| d.trusted));
    assert!(pa.executed.lock().unwrap().is_empty());
}

#[tokio::test]
async fn commit_mismatch_rejects_pairing() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    let (a, _pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let mut raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    assert!(!raw.authed);
    let nc: [u8; 32] = rand::random();
    raw.send(Frame::PairRequest {
        commit: hex::encode(kasane_connect::sas::commit(&nc)),
    });
    // Server answers with its nonce.
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    let got_nonce = loop {
        match tokio::time::timeout(deadline - std::time::Instant::now(), raw.recv()).await {
            Ok(Some(Frame::PairNonce { .. })) => break true,
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(got_nonce);
    // Reveal a different nonce than committed.
    raw.send(Frame::PairReveal {
        nc: hex::encode([9u8; 32]),
    });
    let rejected = loop {
        match tokio::time::timeout(deadline - std::time::Instant::now(), raw.recv()).await {
            Ok(Some(Frame::PairResult { accepted, reason })) => break !accepted && reason.as_deref() == Some("commit_mismatch"),
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(rejected);
}

#[tokio::test]
async fn unauthorized_command_is_rejected_and_not_executed() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    let (a, pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let mut raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    raw.send(Frame::Command {
        id: "c1".into(),
        seq: 1,
        action: "pause".into(),
        args: Map::new(),
    });
    let denied = loop {
        match tokio::time::timeout(Duration::from_secs(5), raw.recv()).await {
            Ok(Some(Frame::Error { code, .. })) => break code == "unauthorized",
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(denied);
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(pa.executed.lock().unwrap().is_empty());
}

#[tokio::test]
async fn replayed_seq_and_duplicate_id() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    pretrust(&da, &dr);
    let (a, pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let mut raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    assert!(raw.authed);

    async fn next_ack(raw: &mut RawClient) -> (String, bool, Option<String>) {
        loop {
            match tokio::time::timeout(Duration::from_secs(5), raw.recv()).await {
                Ok(Some(Frame::Ack { id, ok, error })) => {
                    return (id, ok, error.map(|e| e.code));
                }
                Ok(Some(_)) => continue,
                _ => panic!("no ack"),
            }
        }
    }

    // First command executes.
    raw.send(Frame::Command { id: "x".into(), seq: 1, action: "pause".into(), args: Map::new() });
    assert_eq!(next_ack(&mut raw).await.1, true);
    // Replayed seq is rejected and not executed.
    raw.send(Frame::Command { id: "y".into(), seq: 1, action: "play".into(), args: Map::new() });
    let (_id, ok, err) = next_ack(&mut raw).await;
    assert!(!ok && err.as_deref() == Some("replayed"));
    // A duplicated command id is acked with the original result but not run again.
    raw.send(Frame::Command { id: "x".into(), seq: 2, action: "play".into(), args: Map::new() });
    let (id, ok, _err) = next_ack(&mut raw).await;
    assert!(ok && id == "x");
    tokio::time::sleep(Duration::from_millis(200)).await;
    let executed = pa.executed.lock().unwrap().clone();
    assert_eq!(executed, vec!["pause".to_string()]);
}

#[tokio::test]
async fn revoked_device_gets_auth_required() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    pretrust(&da, &dr);
    let (a, _pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    assert!(raw.authed);
    a.unpair(&identity.device_id).await;
    let raw2 = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    assert!(!raw2.authed);
}

#[tokio::test]
async fn index_command_with_stale_queue_rev_fails() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    pretrust(&da, &dr);
    let (a, pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let mut raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();
    let mut args = Map::new();
    args.insert("index".into(), json!(0));
    args.insert("queueRev".into(), json!(99));
    raw.send(Frame::Command { id: "s".into(), seq: 1, action: "playQueueIndex".into(), args });
    let failed = loop {
        match tokio::time::timeout(Duration::from_secs(5), raw.recv()).await {
            Ok(Some(Frame::Ack { ok, error, .. })) => {
                break !ok && error.map(|e| e.code == "stale_queue").unwrap_or(false)
            }
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(failed);
    assert!(pa.executed.lock().unwrap().is_empty());
}

#[tokio::test]
async fn handoff_starts_the_queue_and_failed_handoff_keeps_it() {
    let da = tempfile::tempdir().unwrap();
    let dr = tempfile::tempdir().unwrap();
    pretrust(&da, &dr);
    let (a, pa) = service(&da, "A").await;
    let (identity, tls) = raw_identity(&dr);
    let mut raw = RawClient::connect(&format!("127.0.0.1:{}", a.port()), &identity, &tls)
        .await
        .unwrap();

    // Failing handoff first: the previous (empty) queue must survive.
    let bad = HandoffRequest {
        transfer_id: "t-bad".into(),
        queue: vec![track("local:/music/a.flac")],
        index: 0,
        position_ms: 0,
        playing: true,
        shuffle: false,
        repeat: "off".into(),
    };
    raw.send(Frame::Handoff(Box::new(bad)));
    let failed = loop {
        match tokio::time::timeout(Duration::from_secs(5), raw.recv()).await {
            Ok(Some(Frame::HandoffResult { transfer_id, ok, error })) => {
                break transfer_id == "t-bad" && !ok && error.map(|e| e.code == "unsupported_media").unwrap_or(false)
            }
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(failed);
    let snap: PlayerSnapshot = pa.snapshot().await;
    assert!(snap.queue.is_empty());

    let good = HandoffRequest {
        transfer_id: "t-good".into(),
        queue: vec![track("vid1"), track("vid2")],
        index: 1,
        position_ms: 1234,
        playing: true,
        shuffle: false,
        repeat: "all".into(),
    };
    raw.send(Frame::Handoff(Box::new(good)));
    let done = loop {
        match tokio::time::timeout(Duration::from_secs(5), raw.recv()).await {
            Ok(Some(Frame::HandoffResult { transfer_id, ok, .. })) => break transfer_id == "t-good" && ok,
            Ok(Some(_)) => continue,
            _ => break false,
        }
    };
    assert!(done);
    let snap = pa.snapshot().await;
    assert_eq!(snap.queue.len(), 2);
    assert_eq!(snap.queue_index, 1);
    assert_eq!(snap.track.as_ref().unwrap().id, "vid2");
    assert_eq!(snap.playback, "playing");
    assert_eq!(snap.position_ms, 1234);
}
