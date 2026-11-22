use crate::adapter::{CmdError, PlayerAdapter, PlayerSnapshot};
use crate::discovery::{Discovered, Discovery, DiscoveryEvent, ONLINE_TTL};
use crate::identity::Identity;
use crate::link::{spawn as link_spawn, Inbound, Link};
use crate::protocol::*;
use crate::sas;
use crate::tls::{cert_fingerprint, TlsConfigs};
use crate::trust::{TrustStore, TrustedDevice};
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, watch, Notify};
use tokio_rustls::{TlsAcceptor, TlsConnector};

pub const DEFAULT_PORT: u16 = 47650;
const PAIRING_TIMEOUT: Duration = Duration::from_secs(120);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const HANDOFF_TIMEOUT: Duration = Duration::from_secs(30);
const DEDUP_CAP: usize = 256;
const MAX_PAIR_FAILURES: usize = 3;
const PAIR_FAILURE_WINDOW: Duration = Duration::from_secs(600);
const PAIR_COOLDOWN: Duration = Duration::from_secs(300);

#[derive(Debug)]
pub enum ConnError {
    Io(std::io::Error),
    Tls(String),
    Protocol(String),
    Unauthorized,
    Unpaired,
    NotFound(String),
}

impl std::fmt::Display for ConnError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnError::Io(e) => write!(f, "{e}"),
            ConnError::Tls(e) => write!(f, "TLS failed: {e}"),
            ConnError::Protocol(e) => write!(f, "{e}"),
            ConnError::Unauthorized => write!(f, "peer rejected the command"),
            ConnError::Unpaired => write!(f, "device is not paired"),
            ConnError::NotFound(t) => write!(f, "no address known for {t}"),
        }
    }
}

impl std::error::Error for ConnError {}
impl From<std::io::Error> for ConnError {
    fn from(e: std::io::Error) -> ConnError {
        ConnError::Io(e)
    }
}
impl From<rustls::Error> for ConnError {
    fn from(e: rustls::Error) -> ConnError {
        ConnError::Tls(e.to_string())
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct DeviceView {
    pub id: Option<String>,
    pub name: String,
    pub platform: Option<String>,
    pub addr: Option<String>,
    pub online: bool,
    pub trusted: bool,
    /// "disconnected" | "connecting" | "connected" | "paired"
    pub connection: String,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct PairingPrompt {
    pub prompt_id: String,
    /// true = a remote device asked to pair with us.
    pub incoming: bool,
    pub peer_name: String,
    pub code: String,
    pub device_id: Option<String>,
}

#[derive(Debug, Clone)]
struct Published {
    snap: PlayerSnapshot,
    queue_rev: u64,
}

struct DeviceEntry {
    id: Option<String>,
    name: String,
    platform: Option<String>,
    addr: Option<String>,
    last_resolve: Option<Instant>,
    conn: &'static str,
    last_error: Option<String>,
}

/// Outbound controller session to one device.
pub struct ClientHandle {
    tx: mpsc::Sender<Frame>,
    /// The peer's `hello` device record.
    pub peer: DeviceInfo,
    peer_fp: [u8; 32],
    authed: AtomicBool,
    ready: watch::Sender<Option<bool>>,
    seq: AtomicU64,
    pending: Mutex<HashMap<String, oneshot::Sender<AckOutcome>>>,
    pair_tx: tokio::sync::broadcast::Sender<Frame>,
    pair_lock: tokio::sync::Mutex<()>,
    last_rev: AtomicU64,
    state_tx: watch::Sender<Option<StateFrame>>,
    dead: Notify,
}

struct Book {
    entries: HashMap<String, DeviceEntry>,
    sessions: HashMap<String, Arc<ClientHandle>>,
    wanted: HashSet<String>,
}

/// (ok, error) of an ack or handoffResult.
type AckOutcome = (bool, Option<FrameError>);
/// (command id, ok, error) for the replay cache.
type DedupEntry = (String, bool, Option<FrameError>);

struct PromptHandle {
    view: PairingPrompt,
    reply: PromptReply,
}

/// Where a user's pairing decision goes. Incoming prompts feed the server
/// session's own decision channel directly.
enum PromptReply {
    Outgoing(oneshot::Sender<bool>),
    Incoming(mpsc::UnboundedSender<bool>),
}

impl PromptReply {
    fn send(self, accepted: bool) -> bool {
        match self {
            PromptReply::Outgoing(tx) => tx.send(accepted).is_ok(),
            PromptReply::Incoming(tx) => tx.send(accepted).is_ok(),
        }
    }
}

struct Pairing {
    ns: [u8; 32],
    commit: String,
    prompt_id: String,
    /// Held for the whole exchange so a second pairing gets "busy".
    _slot: tokio::sync::OwnedMutexGuard<()>,
    local: Option<bool>,
    peer: Option<bool>,
    deadline: Instant,
    peer_id: String,
    peer_name: String,
    peer_fp: String,
}

#[derive(Clone)]
pub struct ConnectService {
    inner: Arc<Inner>,
}

struct Inner {
    identity: Identity,
    tls: TlsConfigs,
    adapter: Arc<dyn PlayerAdapter>,
    trust: Mutex<TrustStore>,
    book: Mutex<Book>,
    devices_tx: watch::Sender<Vec<DeviceView>>,
    prompts: Mutex<HashMap<String, PromptHandle>>,
    prompts_tx: watch::Sender<Vec<PairingPrompt>>,
    published: watch::Sender<Published>,
    dedup: Mutex<HashMap<String, VecDeque<DedupEntry>>>,
    pairing_failures: Mutex<VecDeque<Instant>>,
    pair_slot: Arc<tokio::sync::Mutex<()>>,
    mdns_trigger: mpsc::UnboundedSender<String>,
    /// Unauthenticated inbound sessions per source IP and total — admission
    /// control so unauthenticated peers can never starve paired devices.
    pending_auth: Mutex<(HashMap<std::net::IpAddr, u32>, u32)>,
    /// Set false by `stop()`: all service loops exit and no new work spawns.
    alive: AtomicBool,
    shutdown: Notify,
    discovery: Mutex<Option<Discovery>>,
    port: u16,
    app_version: String,
    platform: String,
}

impl ConnectService {
    pub async fn start(
        dir: &Path,
        device_name: &str,
        platform: &str,
        app_version: &str,
        adapter: Arc<dyn PlayerAdapter>,
        enable_mdns: bool,
    ) -> std::io::Result<ConnectService> {
        let identity = Identity::load_or_create(dir, device_name)?;
        let tls = TlsConfigs::new(identity.cert_der.clone(), identity.key_der.clone())
            .map_err(|e| std::io::Error::other(e.to_string()))?;
        let listener = match TcpListener::bind(("0.0.0.0", DEFAULT_PORT)).await {
            Ok(l) => l,
            Err(_) => TcpListener::bind(("0.0.0.0", 0)).await?,
        };
        let port = listener.local_addr()?.port();
        let trust = TrustStore::load(dir)?;
        let (devices_tx, _) = watch::channel(Vec::new());
        let (prompts_tx, _) = watch::channel(Vec::new());
        let (published_tx, _) = watch::channel(Published {
            snap: adapter.snapshot().await,
            queue_rev: 0,
        });
        let (mdns_tx, mdns_rx) = mpsc::unbounded_channel::<String>();
        let inner = Arc::new(Inner {
            identity,
            tls,
            adapter,
            trust: Mutex::new(trust),
            book: Mutex::new(Book {
                entries: HashMap::new(),
                sessions: HashMap::new(),
                wanted: HashSet::new(),
            }),
            devices_tx,
            prompts: Mutex::new(HashMap::new()),
            prompts_tx,
            published: published_tx,
            dedup: Mutex::new(HashMap::new()),
            pairing_failures: Mutex::new(VecDeque::new()),
            pair_slot: Arc::new(tokio::sync::Mutex::new(())),
            mdns_trigger: mdns_tx,
            pending_auth: Mutex::new((HashMap::new(), 0)),
            alive: AtomicBool::new(true),
            shutdown: Notify::new(),
            discovery: Mutex::new(None),
            port,
            app_version: app_version.into(),
            platform: platform.into(),
        });
        // Seed trusted devices into the listing.
        for device in inner.trust.lock().unwrap().all() {
            let key = device.device_id.clone();
            inner.book.lock().unwrap().entries.insert(
                key,
                DeviceEntry {
                    id: Some(device.device_id),
                    name: device.name,
                    platform: Some(device.platform),
                    addr: device.endpoints.first().cloned(),
                    last_resolve: None,
                    conn: "disconnected",
                    last_error: None,
                },
            );
        }
        inner.refresh_devices();

        tokio::spawn(publisher(inner.clone()));
        tokio::spawn(accept_loop(inner.clone(), listener));
        tokio::spawn(maintain(inner.clone(), mdns_rx));

        if enable_mdns {
            let fp = sas::fingerprint_hex(&inner.identity.fingerprint());
            let (tx, rx) = std::sync::mpsc::channel();
            match Discovery::start(
                &inner.identity.device_id,
                device_name,
                platform,
                port,
                &fp,
                tx,
            ) {
                Ok(discovery) => {
                    // Kept so stop() can unregister and shut the daemon down.
                    *inner.discovery.lock().unwrap() = Some(discovery);
                    tokio::task::spawn_blocking({
                        let inner = inner.clone();
                        move || mdns_events(inner, rx)
                    });
                }
                Err(e) => tracing::warn!("mDNS unavailable: {e}"),
            }
        }
        Ok(ConnectService { inner })
    }

    pub fn port(&self) -> u16 {
        self.inner.port
    }

    pub fn is_running(&self) -> bool {
        self.inner.alive.load(Ordering::SeqCst)
    }

    /// Resolves once `stop()` fires — lets forwarders/listeners wind down.
    pub async fn wait_shutdown(&self) {
        self.inner.shutdown.notified().await
    }

    /// Graceful shutdown: stops the listener and mDNS, closes every session
    /// and ends the background loops. The service object stays usable for
    /// reads (device list, identity) but accepts no new work.
    pub fn stop(&self) {
        if !self.inner.alive.swap(false, Ordering::SeqCst) {
            return;
        }
        if let Some(discovery) = self.inner.discovery.lock().unwrap().take() {
            discovery.stop();
        }
        {
            let mut book = self.inner.book.lock().unwrap();
            book.wanted.clear();
            book.sessions.clear();
            for entry in book.entries.values_mut() {
                entry.conn = "disconnected";
            }
        }
        self.inner.shutdown.notify_waiters();
        self.inner.refresh_devices();
    }

    /// Lowercase hex SHA-256 fingerprint of our leaf certificate.
    pub fn fingerprint(&self) -> String {
        hex::encode(self.inner.identity.fingerprint())
    }

    pub fn local_device(&self) -> DeviceInfo {
        DeviceInfo {
            id: self.inner.identity.device_id.clone(),
            name: self.inner.identity.name.clone(),
            platform: self.inner.platform.clone(),
            app: "Kasane".into(),
            app_version: self.inner.app_version.clone(),
        }
    }

    pub fn devices(&self) -> watch::Receiver<Vec<DeviceView>> {
        self.inner.devices_tx.subscribe()
    }

    pub fn pairing_prompts(&self) -> watch::Receiver<Vec<PairingPrompt>> {
        self.inner.prompts_tx.subscribe()
    }

    pub fn remote_state(&self, device_id: &str) -> Option<watch::Receiver<Option<StateFrame>>> {
        let book = self.inner.book.lock().unwrap();
        book.sessions
            .get(device_id)
            .map(|h| h.state_tx.subscribe())
    }

    /// Resolve a device id or `host[:port]` into a live, authenticated session,
    /// starting one if necessary.
    pub async fn connect(&self, target: &str) -> Result<Arc<ClientHandle>, ConnError> {
        let inner = &self.inner;
        if !inner.alive.load(Ordering::SeqCst) {
            return Err(ConnError::Protocol("service stopped".into()));
        }
        let (id_or_addr, addr_hint) = resolve_target(inner, target);
        if id_or_addr.is_none() {
            Self::validate_manual_target(target)?;
        }
        if let Some(id) = &id_or_addr {
            if let Some(h) = inner.book.lock().unwrap().sessions.get(id) {
                return Ok(h.clone());
            }
        }
        let addr = match addr_hint.or_else(|| {
            id_or_addr
                .as_ref()
                .and_then(|id| inner.book.lock().unwrap().entries.get(id).and_then(|e| e.addr.clone()))
        }) {
            Some(a) => a,
            None => return Err(ConnError::NotFound(target.into())),
        };
        let handle = self.dial(&addr, id_or_addr.as_deref()).await?;
        Ok(handle)
    }

    /// Begin pairing with `target` (device id or host[:port]). Returns the
    /// prompt id for `confirm_pairing`, or `None` when already paired.
    pub async fn pair(&self, target: &str) -> Result<Option<String>, ConnError> {
        let conn = self.connect(target).await?;
        if conn.authed.load(Ordering::SeqCst) {
            return Ok(None);
        }
        let _permit = conn.pair_lock.lock().await;
        if conn.authed.load(Ordering::SeqCst) {
            return Ok(None);
        }
        let mut rx = conn.pair_tx.subscribe();
        let nc: [u8; 32] = rand::random();
        let commit = hex::encode(sas::commit(&nc));
        conn.send(&Frame::PairRequest { commit: commit.clone() })
            .map_err(|_| ConnError::Protocol("connection closed".into()))?;
        let deadline = Instant::now() + PAIRING_TIMEOUT;
        let ns = loop {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(ConnError::Protocol("pairing timed out".into()));
            }
            match tokio::time::timeout(left, rx.recv()).await {
                Ok(Ok(Frame::PairNonce { ns })) => break ns,
                Ok(Ok(Frame::PairResult { reason, .. })) => {
                    return Err(ConnError::Protocol(format!(
                        "pairing refused: {}",
                        reason.unwrap_or_default()
                    )))
                }
                Ok(Ok(_)) | Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => continue,
                Ok(Err(_)) | Err(_) => {
                    return Err(ConnError::Protocol("pairing ended".into()))
                }
            }
        };
        let ns_bytes = parse_hex32(&ns).ok_or(ConnError::Protocol("bad nonce".into()))?;
        conn.send(&Frame::PairReveal { nc: hex::encode(nc) })
            .map_err(|_| ConnError::Protocol("connection closed".into()))?;
        let code = sas::code(
            &conn.peer_fp,
            &self.inner.identity.fingerprint(),
            &ns_bytes,
            &nc,
        );
        let prompt_id = uuid::Uuid::new_v4().to_string();
        let (reply_tx, reply_rx) = oneshot::channel();
        let view = PairingPrompt {
            prompt_id: prompt_id.clone(),
            incoming: false,
            peer_name: conn.peer.name.clone(),
            code,
            device_id: Some(conn.peer.id.clone()),
        };
        self.inner
            .prompts
            .lock()
            .unwrap()
            .insert(prompt_id.clone(), PromptHandle { view, reply: PromptReply::Outgoing(reply_tx) });
        self.push_prompts();
        // The caller resolves the prompt; a detached task completes the exchange.
        let inner = self.inner.clone();
        let conn2 = conn.clone();
        let _pid = prompt_id.clone();
        tokio::spawn(async move {
            // Subscribe before confirming so a fast pairResult cannot be missed.
            let mut rx = conn2.pair_tx.subscribe();
            let accepted = reply_rx.await.unwrap_or(false);
            let _ = conn2.send(&Frame::PairConfirm { accepted });
            let left = deadline.saturating_duration_since(Instant::now());
            let _ = tokio::time::timeout(left.max(Duration::from_secs(1)), async {
                while let Ok(frame) = rx.recv().await {
                    tracing::debug!("pair continuation got {:?}", frame);
                    match frame {
                        Frame::PairResult { accepted, .. } => {
                            if accepted {
                                let device = TrustedDevice {
                                    device_id: conn2.peer.id.clone(),
                                    name: conn2.peer.name.clone(),
                                    platform: conn2.peer.platform.clone(),
                                    fingerprint: hex::encode(conn2.peer_fp),
                                    paired_at: now_secs(),
                                    endpoints: Vec::new(),
                                };
                                let _ = inner.trust.lock().unwrap().put(device);
                                inner.refresh_devices();
                            }
                            return;
                        }
                        Frame::PairNonce { .. } | Frame::PairConfirm { .. } | Frame::AuthOk | Frame::AuthRequired => {}
                        _ => {}
                    }
                }
            })
            .await;
        });
        Ok(Some(prompt_id))
    }

    /// Answer a pairing prompt (incoming or outgoing).
    pub fn confirm_pairing(&self, prompt_id: &str, accepted: bool) {
        let handle = self.inner.prompts.lock().unwrap().remove(prompt_id);
        if let Some(p) = handle {
            p.reply.send(accepted);
            self.push_prompts();
        }
    }

    /// Send a command to a paired device; resolves to the ack.
    pub async fn send_command(
        &self,
        device_id: &str,
        action: &str,
        mut args: Map<String, Value>,
    ) -> Result<(), CmdError> {
        let conn = self
            .connect(device_id)
            .await
            .map_err(|e| CmdError::new("unreachable", e.to_string()))?;
        if !conn.authed.load(Ordering::SeqCst) {
            return Err(CmdError::new("unauthorized", "device is not paired"));
        }
        let id = uuid::Uuid::new_v4().to_string();
        let seq = conn.seq.fetch_add(1, Ordering::SeqCst) + 1;
        args.remove("id");
        args.remove("seq");
        args.remove("action");
        let (tx, rx) = oneshot::channel();
        conn.pending.lock().unwrap().insert(id.clone(), tx);
        conn.send(&Frame::Command {
            id: id.clone(),
            seq,
            action: action.into(),
            args,
        })
        .map_err(|_| CmdError::new("failed", "connection closed"))?;
        match tokio::time::timeout(COMMAND_TIMEOUT, rx).await {
            Ok(Ok((true, _))) => Ok(()),
            Ok(Ok((false, Some(err)))) => Err(CmdError {
                code: err.code,
                message: err.message.unwrap_or_default(),
            }),
            Ok(Ok((false, None))) => Err(CmdError::new("failed", "command failed")),
            Ok(Err(_)) | Err(_) => Err(CmdError::new("failed", "no ack received")),
        }
    }

    /// Push a metadata-only queue + position to the target; the target resolves
    /// media locally and acknowledges.
    pub async fn handoff(&self, device_id: &str, mut request: HandoffRequest) -> Result<(), CmdError> {
        if request.transfer_id.is_empty() {
            request.transfer_id = uuid::Uuid::new_v4().to_string();
        }
        let conn = self
            .connect(device_id)
            .await
            .map_err(|e| CmdError::new("unreachable", e.to_string()))?;
        if !conn.authed.load(Ordering::SeqCst) {
            return Err(CmdError::new("unauthorized", "device is not paired"));
        }
        let (tx, rx) = oneshot::channel();
        conn.pending
            .lock()
            .unwrap()
            .insert(format!("handoff:{}", request.transfer_id), tx);
        conn.send(&Frame::Handoff(Box::new(request)))
            .map_err(|_| CmdError::new("failed", "connection closed"))?;
        match tokio::time::timeout(HANDOFF_TIMEOUT, rx).await {
            Ok(Ok((true, _))) => Ok(()),
            Ok(Ok((false, Some(err)))) => Err(CmdError {
                code: err.code,
                message: err.message.unwrap_or_default(),
            }),
            _ => Err(CmdError::new("failed", "handoff timed out")),
        }
    }

    /// Remove a trusted device; notifies it over the wire when connected.
    pub async fn unpair(&self, device_id: &str) {
        let conn = self.inner.book.lock().unwrap().sessions.remove(device_id);
        if let Some(conn) = conn {
            let _ = conn.send(&Frame::Unpair);
        }
        let _ = self.inner.trust.lock().unwrap().remove(device_id);
        self.inner.book.lock().unwrap().wanted.remove(device_id);
        self.inner.refresh_devices();
    }

    /// Manual entry is limited to private/loopback/link-local IPv4 for v1.
    pub fn validate_manual_target(target: &str) -> Result<(), ConnError> {
        let (host, _port) = match target.split_once(':') {
            Some((h, p)) => (h, p.parse::<u16>().ok()),
            None => (target, Some(DEFAULT_PORT)),
        };
        let Ok(ip) = host.parse::<std::net::Ipv4Addr>() else {
            return Err(ConnError::Protocol(format!(
                "enter a private IPv4 address, not {target:?}"
            )));
        };
        if !(ip.is_private() || ip.is_loopback() || ip.is_link_local()) {
            return Err(ConnError::Protocol(
                "only LAN addresses can be added manually".into(),
            ));
        }
        Ok(())
    }

    async fn dial(&self, addr: &str, want_id: Option<&str>) -> Result<Arc<ClientHandle>, ConnError> {
        let addr = normalize_addr(addr);
        let tcp = TcpStream::connect(&addr).await?;
        let host = addr.split(':').next().unwrap_or("localhost");
        let server_name = rustls::pki_types::ServerName::try_from(host.to_string())
            .unwrap_or_else(|_| rustls::pki_types::ServerName::IpAddress(
                std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST).into(),
            ));
        let connector = TlsConnector::from(self.inner.tls.client.clone());
        let stream = connector.connect(server_name, tcp).await?;
        let peer_fp = stream
            .get_ref()
            .1
            .peer_certificates()
            .and_then(|c| c.first())
            .map(|c| cert_fingerprint(c.as_ref()))
            .ok_or(ConnError::Tls("peer sent no certificate".into()))?;
        let mut link = link_spawn(stream);
        let hello = Frame::Hello {
            protocol: 1,
            min_protocol: 1,
            device: self.local_device(),
        };
        link.send(hello).ok();
        // Read until the peer's hello and auth verdict arrive.
        let (peer_hello, authed) = handshake_wait(&mut link).await?;
        // authOk only means the peer trusts us; we must also have pinned its
        // certificate, or an impostor could accept our commands.
        let pinned = self
            .inner
            .trust
            .lock()
            .unwrap()
            .get(&peer_hello.id)
            .is_some_and(|t| t.fingerprint == hex::encode(peer_fp));
        if authed && !pinned {
            let name = &peer_hello.name;
            return Err(ConnError::Protocol(format!(
                "{name} trusts this device, but this device has no matching record of it. Remove this device on {name} and pair again."
            )));
        }
        let (ready_tx, _) = watch::channel(Some(authed));
        let (pair_tx, _) = tokio::sync::broadcast::channel::<Frame>(64);
        let (state_tx, _) = watch::channel(None);
        let handle = Arc::new(ClientHandle {
            tx: link.sender(),
            peer: peer_hello.clone(),
            peer_fp,
            authed: AtomicBool::new(authed),
            ready: ready_tx,
            seq: AtomicU64::new(0),
            pending: Mutex::new(HashMap::new()),
            pair_tx,
            pair_lock: tokio::sync::Mutex::new(()),
            last_rev: AtomicU64::new(0),
            state_tx: state_tx.clone(),
            dead: Notify::new(),
        });
        let key = want_id.map(str::to_string).unwrap_or_else(|| peer_hello.id.clone());
        {
            let mut book = self.inner.book.lock().unwrap();
            book.sessions.insert(peer_hello.id.clone(), handle.clone());
            book.sessions.insert(key.clone(), handle.clone());
            book.wanted.insert(peer_hello.id.clone());
            let entry = book.entries.entry(peer_hello.id.clone()).or_insert(DeviceEntry {
                id: None,
                name: peer_hello.name.clone(),
                platform: None,
                addr: Some(addr.clone()),
                last_resolve: None,
                conn: "disconnected",
                last_error: None,
            });
            entry.id = Some(peer_hello.id.clone());
            entry.name = peer_hello.name.clone();
            entry.platform = Some(peer_hello.platform.clone());
            entry.addr = Some(addr.clone());
            entry.conn = if authed { "paired" } else { "connected" };
        }
        self.inner.refresh_devices();
        let inner = self.inner.clone();
        let h2 = handle.clone();
        tokio::spawn(async move {
            client_dispatch(inner, h2, link.rx).await;
        });
        if authed {
            let _ = handle.send(&Frame::Subscribe);
        }
        Ok(handle)
    }

    fn push_prompts(&self) {
        let list: Vec<PairingPrompt> = self
            .inner
            .prompts
            .lock()
            .unwrap()
            .values()
            .map(|p| p.view.clone())
            .collect();
        let _ = self.inner.prompts_tx.send_replace(list);
    }
}

impl ClientHandle {
    fn send(&self, frame: &Frame) -> Result<(), ()> {
        self.tx.try_send(frame.clone()).map_err(|_| ())
    }
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn parse_hex32(s: &str) -> Option<[u8; 32]> {
    let bytes = hex::decode(s).ok()?;
    bytes.try_into().ok()
}

fn normalize_addr(addr: &str) -> String {
    if addr.contains(':') {
        addr.to_string()
    } else {
        format!("{addr}:{DEFAULT_PORT}")
    }
}

fn resolve_target(_inner: &Inner, target: &str) -> (Option<String>, Option<String>) {
    // A target that looks like host[:port] is dialed directly; otherwise it is a device id.
    if target.contains(':') || target.contains('.') || target == "localhost" {
        (None, Some(normalize_addr(target)))
    } else {
        (Some(target.to_string()), None)
    }
}

/// Consumes frames during connect: returns the peer hello and auth verdict.
async fn handshake_wait(link: &mut Link) -> Result<(DeviceInfo, bool), ConnError> {
    let deadline = Instant::now() + Duration::from_secs(15);
    let mut hello: Option<DeviceInfo> = None;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(ConnError::Protocol("handshake timed out".into()));
        }
        let next = match tokio::time::timeout(left, link.rx.recv()).await {
            Ok(v) => v,
            Err(_) => return Err(ConnError::Protocol("handshake timed out".into())),
        };
        match next {
            Some(Inbound::Frame(Frame::Hello { device, protocol, min_protocol })) => {
                if protocol < 1 || min_protocol > 1 {
                    return Err(ConnError::Protocol("incompatible_version".into()));
                }
                hello = Some(device);
            }
            Some(Inbound::Frame(Frame::AuthOk)) => {
                return hello
                    .map(|h| (h, true))
                    .ok_or(ConnError::Protocol("auth before hello".into()))
            }
            Some(Inbound::Frame(Frame::AuthRequired)) => {
                return hello
                    .map(|h| (h, false))
                    .ok_or(ConnError::Protocol("auth before hello".into()))
            }
            Some(Inbound::Frame(Frame::Error { code, .. })) => {
                return Err(ConnError::Protocol(code));
            }
            Some(Inbound::Closed) | None => {
                return Err(ConnError::Protocol("connection closed".into()))
            }
            Some(Inbound::Frame(_)) => continue,
        }
    }
}

/// The dispatch loop of an outbound session.
async fn client_dispatch(inner: Arc<Inner>, conn: Arc<ClientHandle>, mut rx: mpsc::UnboundedReceiver<Inbound>) {
    while inner.alive.load(Ordering::SeqCst) {
        let Some(inbound) = (tokio::select! {
            i = rx.recv() => i,
            _ = inner.shutdown.notified() => break,
        }) else { break };
        match inbound {
            Inbound::Closed => break,
            Inbound::Frame(frame) => match frame {
                Frame::Ack { id, ok, error } => {
                    if let Some(tx) = conn.pending.lock().unwrap().remove(&id) {
                        let _ = tx.send((ok, error));
                    }
                }
                Frame::HandoffResult { transfer_id, ok, error } => {
                    if let Some(tx) = conn.pending.lock().unwrap().remove(&format!("handoff:{transfer_id}")) {
                        let _ = tx.send((ok, error));
                    }
                }
                Frame::State(state) => {
                    let last = conn.last_rev.swap(state.rev, Ordering::SeqCst);
                    if state.rev > last {
                        let _ = conn.state_tx.send_replace(Some(*state));
                    }
                }
                Frame::AuthOk => {
                    conn.authed.store(true, Ordering::SeqCst);
                    let _ = conn.ready.send_replace(Some(true));
                    let _ = conn.send(&Frame::Subscribe);
                    inner.set_conn_state(&conn.peer.id, "paired");
                }
                Frame::AuthRequired => {
                    conn.authed.store(false, Ordering::SeqCst);
                    let _ = conn.ready.send_replace(Some(false));
                }
                Frame::PairNonce { .. } | Frame::PairResult { .. } | Frame::PairConfirm { .. } => {
                    tracing::debug!("dispatch pair frame {:?}", frame);
                    let _ = conn.pair_tx.send(frame);
                }
                Frame::Unpair => {
                    let _ = inner.trust.lock().unwrap().remove(&conn.peer.id);
                    inner.refresh_devices();
                    break;
                }
                Frame::Error { code, message } => {
                    tracing::debug!("peer error {code} {:?}", message);
                }
                _ => {}
            },
        }
    }
    // Connection ended: fail waiters, mark offline.
    let mut pending = conn.pending.lock().unwrap();
    for (_, tx) in pending.drain() {
        let _ = tx.send((false, Some(FrameError { code: "closed".into(), message: None })));
    }
    inner.book.lock().unwrap().sessions.retain(|_, h| !Arc::ptr_eq(h, &conn));
    inner.set_conn_state(&conn.peer.id, "disconnected");
    inner.refresh_devices();
    conn.dead.notify_waiters();
}

impl Inner {
    fn set_conn_state(&self, device_id: &str, conn: &'static str) {
        if let Some(e) = self.book.lock().unwrap().entries.get_mut(device_id) {
            e.conn = conn;
        }
        self.refresh_devices();
    }

    fn mark_error(&self, device_id: &str, error: String) {
        {
            let mut book = self.book.lock().unwrap();
            if let Some(e) = book.entries.get_mut(device_id) {
                e.last_error = Some(error);
                e.conn = "disconnected";
            }
        }
        self.refresh_devices();
    }

    fn refresh_devices(&self) {
        let trusted = self.trust.lock().unwrap().all();
        let trusted_ids: HashSet<String> = trusted.iter().map(|d| d.device_id.clone()).collect();
        let mut book = self.book.lock().unwrap();
        let mut list: Vec<DeviceView> = Vec::new();
        let now = Instant::now();
        let live: HashSet<String> = book.sessions.keys().cloned().collect();
        for (key, e) in book.entries.iter_mut() {
            let connected = e
                .id
                .as_ref()
                .filter(|id| live.contains(*id))
                .map(|_| e.conn)
                .unwrap_or(e.conn);
            let online = connected != "disconnected"
                || e.last_resolve.map(|t| now.duration_since(t) < ONLINE_TTL).unwrap_or(false);
            list.push(DeviceView {
                id: e.id.clone().or_else(|| {
                    if key.starts_with("addr:") { None } else { Some(key.clone()) }
                }),
                name: e.name.clone(),
                platform: e.platform.clone(),
                addr: e.addr.clone(),
                online,
                trusted: e.id.as_ref().map(|id| trusted_ids.contains(id)).unwrap_or(false),
                connection: connected.to_string(),
                last_error: e.last_error.clone(),
            });
        }
        drop(book);
        let _ = self.devices_tx.send_replace(list);
    }

    fn note_pair_failure(&self) {
        let mut f = self.pairing_failures.lock().unwrap();
        f.push_back(Instant::now());
        while f.len() > MAX_PAIR_FAILURES {
            f.pop_front();
        }
    }

    fn pairing_rate_limited(&self) -> bool {
        let f = self.pairing_failures.lock().unwrap();
        let now = Instant::now();
        if f.len() < MAX_PAIR_FAILURES {
            return false;
        }
        f.iter().all(|t| now.duration_since(*t) < PAIR_FAILURE_WINDOW)
            && f.back().map(|t| now.duration_since(*t) < PAIR_COOLDOWN).unwrap_or(false)
    }
}

/// Periodic snapshot publisher: assigns queue_rev and notifies subscribers.
async fn publisher(inner: Arc<Inner>) {
    let mut changed = inner.adapter.changed();
    let mut last: Option<PlayerSnapshot> = None;
    let mut queue_rev: u64 = 0;
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        tokio::select! {
            _ = tick.tick() => {}
            r = changed.changed() => { if r.is_err() { return; } }
            _ = inner.shutdown.notified() => return,
        }
        let snap = inner.adapter.snapshot().await;
        let meaningful = match &last {
            None => true,
            Some(prev) => {
                prev.playback != snap.playback
                    || prev.track != snap.track
                    || prev.volume != snap.volume
                    || prev.shuffle != snap.shuffle
                    || prev.repeat != snap.repeat
                    || prev.queue_index != snap.queue_index
                    || prev.queue != snap.queue
                    || prev.error != snap.error
                    || prev.duration_ms != snap.duration_ms
            }
        };
        if snap.queue != last.as_ref().map(|p| p.queue.clone()).unwrap_or_default() {
            queue_rev += 1;
        }
        let moved = last
            .as_ref()
            .map(|p| p.position_ms != snap.position_ms)
            .unwrap_or(false);
        if meaningful || moved {
            let _ = inner.published.send_replace(Published { snap: snap.clone(), queue_rev });
        }
        last = Some(snap);
    }
}

async fn accept_loop(inner: Arc<Inner>, listener: TcpListener) {
    loop {
        let accepted = tokio::select! {
            r = listener.accept() => r,
            _ = inner.shutdown.notified() => break,
        };
        let Ok((stream, addr)) = accepted else {
            continue;
        };
        // Admission control: at most 2 pending (unauthenticated) sessions per
        // source IP and 4 in total.
        {
            let mut pending = inner.pending_auth.lock().unwrap();
            let total = pending.1;
            let count = pending.0.entry(addr.ip()).or_insert(0);
            if *count >= 2 || total >= 4 {
                continue;
            }
            *count += 1;
            pending.1 += 1;
        }
        let inner = inner.clone();
        let slot = PendingSlot {
            inner: inner.clone(),
            ip: addr.ip(),
            released: AtomicBool::new(false),
        };
        tokio::spawn(async move {
            let acceptor = TlsAcceptor::from(inner.tls.server.clone());
            let Ok(tls) = acceptor.accept(stream).await else {
                slot.release();
                return;
            };
            let peer_fp = tls
                .get_ref()
                .1
                .peer_certificates()
                .and_then(|c| c.first())
                .map(|c| cert_fingerprint(c.as_ref()));
            let Some(peer_fp) = peer_fp else {
                slot.release();
                return;
            };
            let mut link = link_spawn(tls);
            let hello = Frame::Hello {
                protocol: 1,
                min_protocol: 1,
                device: DeviceInfo {
                    id: inner.identity.device_id.clone(),
                    name: inner.identity.name.clone(),
                    platform: inner.platform.clone(),
                    app: "Kasane".into(),
                    app_version: inner.app_version.clone(),
                },
            };
            let _ = link.send(hello);
            // Wait for the peer's hello only; the auth verdict is ours to send.
            let peer = match wait_hello(&mut link).await {
                Ok(d) => d,
                Err(_) => {
                    slot.release();
                    return;
                }
            };
            server_session(inner, link, peer, peer_fp, slot).await;
        });
    }
}

/// A slot in the unauthenticated-connection budget; released on auth or close.
struct PendingSlot {
    inner: Arc<Inner>,
    ip: std::net::IpAddr,
    released: AtomicBool,
}

impl PendingSlot {
    fn release(&self) {
        if self.released.swap(true, Ordering::SeqCst) {
            return;
        }
        let mut pending = self.inner.pending_auth.lock().unwrap();
        let zero = match pending.0.get_mut(&self.ip) {
            Some(c) => {
                *c = c.saturating_sub(1);
                *c == 0
            }
            None => false,
        };
        if zero {
            pending.0.remove(&self.ip);
        }
        pending.1 = pending.1.saturating_sub(1);
    }
}

/// Read until the peer's `hello` arrives (other frames are deferred: the
/// session loop replays nothing, but nothing legitimate arrives before hello).
async fn wait_hello(link: &mut Link) -> Result<DeviceInfo, ()> {
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(());
        }
        match tokio::time::timeout(left, link.rx.recv()).await {
            Ok(Some(Inbound::Frame(Frame::Hello { device, protocol, min_protocol }))) => {
                if protocol < 1 || min_protocol > 1 {
                    let _ = link.send(Frame::error("incompatible_version"));
                    return Err(());
                }
                return Ok(device);
            }
            Ok(Some(Inbound::Frame(Frame::Error { .. }))) | Ok(Some(Inbound::Closed)) | Ok(None) => {
                return Err(())
            }
            Ok(Some(Inbound::Frame(_))) => continue,
            Err(_) => return Err(()),
        }
    }
}

struct ServerSession {
    authed: bool,
    subscribed: bool,
    last_seq: u64,
    next_rev: u64,
    last_sent_qrev: u64,
    last_pos_ms: u64,
    last_sent_at: Instant,
    pairing: Option<Pairing>,
}

/// The player side of a connection: auth gate, pairing, commands, state pushes.
async fn server_session(
    inner: Arc<Inner>,
    mut link: Link,
    peer: DeviceInfo,
    peer_fp: [u8; 32],
    slot: PendingSlot,
) {
    let fp_hex = hex::encode(peer_fp);
    let trusted = {
        let trust = inner.trust.lock().unwrap();
        match trust.get(&peer.id) {
            Some(record) if record.fingerprint == fp_hex => true,
            Some(_) => {
                tracing::warn!(
                    "trusted device {} presented a different certificate fingerprint",
                    peer.id
                );
                false
            }
            None => false,
        }
    };
    let mut s = ServerSession {
        authed: trusted,
        subscribed: false,
        last_seq: 0,
        next_rev: 0,
        last_sent_qrev: 0,
        last_pos_ms: 0,
        last_sent_at: Instant::now() - Duration::from_secs(10),
        pairing: None,
    };
    let _ = link.send(if trusted { Frame::AuthOk } else { Frame::AuthRequired });
    if trusted {
        slot.release();
    }
    let mut published = inner.published.subscribe();
    // The local user's decision for an incoming pairing arrives here.
    let (decision_tx, mut decision_rx) = mpsc::unbounded_channel::<bool>();
    let mut decision_fwd = false;
    let mut last_activity = Instant::now();
    loop {
        // Pairing deadline handling.
        let deadline = s
            .pairing
            .as_ref()
            .map(|p| p.deadline)
            .unwrap_or_else(|| Instant::now() + Duration::from_secs(86400));
        let deadline_fut = tokio::time::sleep_until(tokio::time::Instant::from_std(deadline));
        tokio::pin!(deadline_fut);
        // Unauthenticated sessions that stall for >20 s are closed so they can
        // never starve paired devices.
        let idle = last_activity + Duration::from_secs(20);
        let idle_fut = tokio::time::sleep_until(tokio::time::Instant::from_std(idle));
        tokio::pin!(idle_fut);
        tokio::select! {
            biased;
            decision = decision_rx.recv(), if decision_fwd => {
                decision_fwd = false;
                let accepted = decision.unwrap_or(false);
                if let Some(p) = s.pairing.as_mut() {
                    p.local = Some(accepted);
                    let _ = link.send(Frame::PairConfirm { accepted });
                    if finish_pairing(&inner, &mut s, &link, &peer).await == Flow::Close { break; }
                    if s.authed { slot.release(); }
                }
            }
            inbound = link.rx.recv() => {
                let Some(inbound) = inbound else { break };
                last_activity = Instant::now();
                match inbound {
                    Inbound::Closed => break,
                    Inbound::Frame(frame) => {
                        if handle_frame(&inner, &mut s, &link, &peer, &fp_hex, frame, &decision_tx, &mut decision_fwd).await == Flow::Close {
                            break;
                        }
                        if s.authed { slot.release(); }
                    }
                }
            }
            // A pairing has its own 120 s deadline while the users compare codes.
            _ = &mut idle_fut, if !s.authed && s.pairing.is_none() => {
                break;
            }
            changed = published.changed() => {
                if changed.is_err() { break; }
                if s.subscribed {
                    maybe_push_state(&inner, &mut s, &link, &published, false);
                }
            }
            _ = deadline_fut, if s.pairing.is_some() => {
                if let Some(p) = s.pairing.take() {
                    let _ = link.send(Frame::PairResult { accepted: false, reason: Some("timeout".into()) });
                    drop(p);
                    inner.note_pair_failure();
                }
            }
            _ = inner.shutdown.notified() => break,
        }
        if !inner.alive.load(Ordering::SeqCst) {
            break;
        }
    }
    // If a pairing was in-flight its prompt must not linger.
    if let Some(p) = s.pairing.take() {
        let mut prompts = inner.prompts.lock().unwrap();
        prompts.remove(&p.prompt_id);
        drop(prompts);
        push_prompts(&inner);
    }
    slot.release();
}

#[derive(PartialEq)]
enum Flow {
    Close,
    Open,
}

fn push_prompts(inner: &Inner) {
    let list: Vec<PairingPrompt> = inner
        .prompts
        .lock()
        .unwrap()
        .values()
        .map(|p| p.view.clone())
        .collect();
    let _ = inner.prompts_tx.send_replace(list);
}

fn push_state(inner: &Inner, s: &mut ServerSession, link: &Link, published: &watch::Receiver<Published>, force_queue: bool) {
    let p = published.borrow();
    s.next_rev += 1;
    let include_queue = force_queue || p.queue_rev != s.last_sent_qrev;
    if include_queue {
        s.last_sent_qrev = p.queue_rev;
    }
    let snap = &p.snap;
    let frame = StateFrame {
        rev: s.next_rev,
        queue_rev: p.queue_rev,
        playback: snap.playback.clone(),
        track: snap.track.clone(),
        position_ms: snap.position_ms,
        duration_ms: snap.duration_ms,
        volume: snap.volume,
        shuffle: snap.shuffle,
        repeat: snap.repeat.clone(),
        queue_index: snap.queue_index,
        queue: if include_queue { Some(snap.queue.clone()) } else { None },
        error: snap.error.clone(),
        capabilities: inner.adapter.capabilities(),
    };
    s.last_pos_ms = snap.position_ms;
    s.last_sent_at = Instant::now();
    let _ = link.send(Frame::State(Box::new(frame)));
}

fn maybe_push_state(inner: &Inner, s: &mut ServerSession, link: &Link, published: &watch::Receiver<Published>, force: bool) {
    let p = published.borrow();
    let position_only = !force
        && p.queue_rev == s.last_sent_qrev
        && p.snap.position_ms != s.last_pos_ms;
    if position_only {
        // Position-only updates: at most one per second while playing.
        if p.snap.playback == "playing" && s.last_sent_at.elapsed() >= Duration::from_secs(1) {
            drop(p);
            push_state(inner, s, link, published, false);
        }
        return;
    }
    drop(p);
    push_state(inner, s, link, published, force);
}

/// Decide whether a pairing can be finalized and send pairResult/authOk.
async fn finish_pairing(inner: &Inner, s: &mut ServerSession, link: &Link, peer: &DeviceInfo) -> Flow {
    let Some(p) = s.pairing.as_ref() else { return Flow::Open };
    if p.local.is_none() || p.peer.is_none() {
        return Flow::Open;
    }
    let accepted = p.local == Some(true) && p.peer == Some(true);
    let prompt_id = p.prompt_id.clone();
    let reason = if accepted { None } else { Some("rejected".to_string()) };
    let _ = link.send(Frame::PairResult { accepted, reason });
    if accepted {
        let device = TrustedDevice {
            device_id: p.peer_id.clone(),
            name: p.peer_name.clone(),
            platform: peer.platform.clone(),
            fingerprint: p.peer_fp.clone(),
            paired_at: now_secs(),
            endpoints: Vec::new(),
        };
        let _ = inner.trust.lock().unwrap().put(device);
        s.authed = true;
        let _ = link.send(Frame::AuthOk);
        inner.refresh_devices();
    } else {
        inner.note_pair_failure();
    }
    s.pairing = None;
    inner.prompts.lock().unwrap().remove(&prompt_id);
    push_prompts(inner);
    Flow::Open
}

#[allow(clippy::too_many_arguments)]
async fn handle_frame(
    inner: &Arc<Inner>,
    s: &mut ServerSession,
    link: &Link,
    peer: &DeviceInfo,
    fp_hex: &str,
    frame: Frame,
    decision_tx: &mpsc::UnboundedSender<bool>,
    decision_fwd: &mut bool,
) -> Flow {
    // Frames allowed before auth: hello, pair*, ping/pong (handled in link).
    if !s.authed {
        match &frame {
            Frame::Hello { .. } => return Flow::Open,
            Frame::PairRequest { .. } | Frame::PairReveal { .. } | Frame::PairConfirm { .. } => {}
            _ => {
                let _ = link.send(Frame::error("unauthorized"));
                return Flow::Open;
            }
        }
    }
    match frame {
        Frame::PairRequest { commit } => {
            if inner.pairing_rate_limited() {
                let _ = link.send(Frame::PairResult {
                    accepted: false,
                    reason: Some("rate_limited".into()),
                });
                return Flow::Open;
            }
            let Ok(slot) = inner.pair_slot.clone().try_lock_owned() else {
                let _ = link.send(Frame::PairResult {
                    accepted: false,
                    reason: Some("busy".into()),
                });
                return Flow::Open;
            };
            if s.pairing.is_some() {
                drop(slot);
                let _ = link.send(Frame::PairResult {
                    accepted: false,
                    reason: Some("busy".into()),
                });
                return Flow::Open;
            }
            let ns: [u8; 32] = rand::random();
            tracing::debug!("pairing with {} started", peer.id);
            s.pairing = Some(Pairing {
                ns,
                commit,
                prompt_id: uuid::Uuid::new_v4().to_string(),
                _slot: slot,
                local: None,
                peer: None,
                deadline: Instant::now() + PAIRING_TIMEOUT,
                peer_id: peer.id.clone(),
                peer_name: peer.name.clone(),
                peer_fp: fp_hex.to_string(),
            });
            let _ = link.send(Frame::PairNonce { ns: hex::encode(ns) });
        }
        Frame::PairReveal { nc } => {
            let Some(p) = s.pairing.as_mut() else { return Flow::Open };
            let Some(nc_bytes) = parse_hex32(&nc) else {
                let _ = link.send(Frame::PairResult {
                    accepted: false,
                    reason: Some("commit_mismatch".into()),
                });
                s.pairing = None;
                inner.note_pair_failure();
                return Flow::Open;
            };
            if hex::encode(sas::commit(&nc_bytes)) != p.commit {
                let _ = link.send(Frame::PairResult {
                    accepted: false,
                    reason: Some("commit_mismatch".into()),
                });
                s.pairing = None;
                inner.note_pair_failure();
                return Flow::Open;
            }
            // Compute the SAS over server fp (us) then client fp (peer).
            let code = sas::code(
                &inner.identity.fingerprint(),
                &parse_hex32(fp_hex).unwrap_or([0; 32]),
                &p.ns,
                &nc_bytes,
            );
            let prompt_id = p.prompt_id.clone();
            inner.prompts.lock().unwrap().insert(
                prompt_id,
                PromptHandle {
                    view: PairingPrompt {
                        prompt_id: s.pairing.as_ref().unwrap().prompt_id.clone(),
                        incoming: true,
                        peer_name: peer.name.clone(),
                        code,
                        device_id: Some(peer.id.clone()),
                    },
                    reply: PromptReply::Incoming(decision_tx.clone()),
                },
            );
            push_prompts(inner);
            *decision_fwd = true;
        }
        Frame::PairConfirm { accepted } => {
            if let Some(p) = s.pairing.as_mut() {
                p.peer = Some(accepted);
                // A peer rejection ends the pairing now; our prompt is withdrawn.
                if !accepted && p.local.is_none() {
                    p.local = Some(false);
                }
                return finish_pairing(inner, s, link, peer).await;
            }
        }
        Frame::Command { id, seq, action, args } => {
            handle_command(inner, s, link, peer, id, seq, action, args).await;
        }
        Frame::Subscribe => {
            s.subscribed = true;
            let published = inner.published.subscribe();
            push_state(inner, s, link, &published, true);
        }
        Frame::Sync => {
            let published = inner.published.subscribe();
            push_state(inner, s, link, &published, true);
        }
        Frame::Handoff(request) => {
            let result = inner.adapter.handoff(&request).await;
            let _ = link.send(match result {
                Ok(()) => Frame::HandoffResult {
                    transfer_id: request.transfer_id,
                    ok: true,
                    error: None,
                },
                Err(e) => Frame::HandoffResult {
                    transfer_id: request.transfer_id,
                    ok: false,
                    error: Some(FrameError {
                        code: e.code,
                        message: Some(e.message),
                    }),
                },
            });
        }
        Frame::Unpair => {
            let _ = inner.trust.lock().unwrap().remove(&peer.id);
            inner.refresh_devices();
            return Flow::Close;
        }
        Frame::Hello { .. } | Frame::AuthOk | Frame::AuthRequired | Frame::Ping | Frame::Pong => {}
        Frame::Ack { .. } | Frame::PairNonce { .. } | Frame::PairResult { .. }
        | Frame::State(_) | Frame::HandoffResult { .. } | Frame::Error { .. } => {}
    }
    Flow::Open
}

#[allow(clippy::too_many_arguments)]
async fn handle_command(
    inner: &Arc<Inner>,
    s: &mut ServerSession,
    link: &Link,
    peer: &DeviceInfo,
    id: String,
    seq: u64,
    action: String,
    args: Map<String, Value>,
) {
    // Duplicate-id cache survives reconnects; check it before sequencing.
    {
        let dedup = inner.dedup.lock().unwrap();
        if let Some(cache) = dedup.get(&peer.id) {
            if let Some((_, ok, err)) = cache.iter().find(|(cid, _, _)| *cid == id) {
                let _ = link.send(Frame::Ack {
                    id,
                    ok: *ok,
                    error: err.clone(),
                });
                return;
            }
        }
    }
    let (ok, error) = if seq <= s.last_seq {
        (
            false,
            Some(FrameError {
                code: "replayed".into(),
                message: None,
            }),
        )
    } else {
        s.last_seq = seq;
        let stale = matches!(action.as_str(), "playQueueIndex" | "removeQueueIndex" | "moveQueue")
            && args
                .get("queueRev")
                .and_then(Value::as_u64)
                .map(|q| q != inner.published.borrow().queue_rev)
                .unwrap_or(true);
        if stale {
            (
                false,
                Some(FrameError {
                    code: "stale_queue".into(),
                    message: None,
                }),
            )
        } else {
            match inner.adapter.execute(&action, &args).await {
                Ok(()) => (true, None),
                Err(e) => (
                    false,
                    Some(FrameError {
                        code: e.code,
                        message: Some(e.message),
                    }),
                ),
            }
        }
    };
    {
        let mut dedup = inner.dedup.lock().unwrap();
        let cache = dedup.entry(peer.id.clone()).or_default();
        cache.push_back((id.clone(), ok, error.clone()));
        while cache.len() > DEDUP_CAP {
            cache.pop_front();
        }
    }
    let _ = link.send(Frame::Ack { id, ok, error });
}

/// mdns-sd events arrive over a std channel; bridging must not park a worker.
fn mdns_events(inner: Arc<Inner>, rx: std::sync::mpsc::Receiver<DiscoveryEvent>) {
    while let Ok(event) = rx.recv() {
        match event {
            DiscoveryEvent::Resolved(Discovered { id, name, platform, addr, .. }) => {
                let key = id.clone().unwrap_or_else(|| format!("addr:{addr}"));
                let immediate;
                {
                    let mut book = inner.book.lock().unwrap();
                    let entry = book.entries.entry(key.clone()).or_insert(DeviceEntry {
                        id: None,
                        name: name.clone(),
                        platform: None,
                        addr: None,
                        last_resolve: None,
                        conn: "disconnected",
                        last_error: None,
                    });
                    if let Some(id) = &id {
                        entry.id = Some(id.clone());
                    }
                    entry.name = name.trim_end_matches('.').to_string();
                    entry.platform = platform.or_else(|| entry.platform.clone());
                    entry.addr = Some(addr.clone());
                    entry.last_resolve = Some(Instant::now());
                    immediate = book.wanted.contains(&key) && !book.sessions.contains_key(&key);
                }
                inner.refresh_devices();
                if immediate {
                    let _ = inner.mdns_trigger.send(key);
                }
            }
            DiscoveryEvent::Removed(_fullname) => {}
        }
    }
}

/// Keeps wanted outbound connections alive with 1,2,4..30 s backoff and
/// reconnects immediately on a fresh mDNS resolve.
async fn maintain(inner: Arc<Inner>, mut mdns_rx: mpsc::UnboundedReceiver<String>) {
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    let mut attempts: HashMap<String, u32> = HashMap::new();
    loop {
        tokio::select! {
            _ = tick.tick() => {}
            _ = mdns_rx.recv() => {
                // Fresh resolve resets the backoff for wanted devices.
                attempts.clear();
            }
            _ = inner.shutdown.notified() => return,
        }
        let wanted: Vec<(String, String)> = {
            let book = inner.book.lock().unwrap();
            book.wanted
                .iter()
                .filter(|id| !book.sessions.contains_key(*id))
                .filter_map(|id| {
                    book.entries
                        .get(id)
                        .and_then(|e| e.addr.clone())
                        .map(|a| (id.clone(), a))
                })
                .collect()
        };
        for (id, addr) in wanted {
            let tries = attempts.entry(id.clone()).or_insert(0);
            let delay = Duration::from_secs(1 << (*tries).min(5));
            *tries += 1;
            let inner2 = inner.clone();
            let addr2 = addr.clone();
            let id2 = id.clone();
            inner.set_conn_state(&id, "connecting");
            let svc = ConnectService { inner: inner2.clone() };
            tokio::spawn(async move {
                tokio::time::sleep(delay).await;
                if let Err(e) = svc.dial(&addr2, Some(&id2)).await {
                    inner2.mark_error(&id2, e.to_string());
                }
            });
        }
    }
}

