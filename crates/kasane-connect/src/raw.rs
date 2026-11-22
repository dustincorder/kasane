use crate::identity::Identity;
use crate::link::{spawn as link_spawn, Inbound, Link};
use crate::protocol::{DeviceInfo, Frame};
use crate::service::{ConnError, DEFAULT_PORT};
use crate::tls::{cert_fingerprint, TlsConfigs};
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;

/// A low-level protocol client used by loopback tests: exposes frame-level
/// send/recv so tests can drive sequences the facade never produces (replayed
/// seq numbers, forged commits, …).
pub struct RawClient {
    link: Link,
    pub peer: DeviceInfo,
    pub peer_fp: [u8; 32],
    pub authed: bool,
}

impl RawClient {
    pub async fn connect(
        addr: &str,
        identity: &Identity,
        tls: &TlsConfigs,
    ) -> Result<RawClient, ConnError> {
        let addr = if addr.contains(':') {
            addr.to_string()
        } else {
            format!("{addr}:{DEFAULT_PORT}")
        };
        let tcp = TcpStream::connect(&addr).await?;
        let host = addr.split(':').next().unwrap_or("localhost");
        let server_name = rustls::pki_types::ServerName::try_from(host.to_string())
            .unwrap_or_else(|_| {
                rustls::pki_types::ServerName::IpAddress(
                    std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST).into(),
                )
            });
        let connector = TlsConnector::from(tls.client.clone());
        let stream = connector.connect(server_name, tcp).await?;
        let peer_fp = stream
            .get_ref()
            .1
            .peer_certificates()
            .and_then(|c| c.first())
            .map(|c| cert_fingerprint(c.as_ref()))
            .ok_or(ConnError::Tls("peer sent no certificate".into()))?;
        let mut client = RawClient {
            link: link_spawn(stream),
            peer: DeviceInfo {
                id: String::new(),
                name: String::new(),
                platform: String::new(),
                app: String::new(),
                app_version: String::new(),
            },
            peer_fp,
            authed: false,
        };
        client.send(Frame::Hello {
            protocol: 1,
            min_protocol: 1,
            device: DeviceInfo {
                id: identity.device_id.clone(),
                name: identity.name.clone(),
                platform: "test".into(),
                app: "Kasane".into(),
                app_version: "test".into(),
            },
        });
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            if left.is_zero() {
                return Err(ConnError::Protocol("handshake timed out".into()));
            }
            let inbound = match tokio::time::timeout(left, client.recv()).await {
                Ok(Some(f)) => f,
                _ => return Err(ConnError::Protocol("handshake timed out".into())),
            };
            match inbound {
                Frame::Hello { device, .. } => client.peer = device,
                Frame::AuthOk => {
                    client.authed = true;
                    return Ok(client);
                }
                Frame::AuthRequired => return Ok(client),
                Frame::Error { code, .. } => return Err(ConnError::Protocol(code)),
                _ => {}
            }
        }
    }

    pub fn send(&self, frame: Frame) {
        let _ = self.link.send(frame);
    }

    /// Next frame within `timeout`, ignoring ping/pong (handled by the link).
    pub async fn recv(&mut self) -> Option<Frame> {
        while let Some(inbound) = self.link.rx.recv().await {
            if let Inbound::Frame(f) = inbound {
                return Some(f);
            }
            if matches!(inbound, Inbound::Closed) {
                return None;
            }
        }
        None
    }
}
