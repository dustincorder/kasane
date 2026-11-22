use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashMap;
use std::sync::mpsc as std_mpsc;
use std::time::{Duration, Instant};

pub const SERVICE_TYPE: &str = "_kasane._tcp.local.";
pub const PROTO_VERSION: u32 = 1;

#[derive(Debug, Clone)]
pub struct Discovered {
    /// TXT `id=` hint; identity is always proven by the TLS fingerprint.
    pub id: Option<String>,
    pub name: String,
    pub platform: Option<String>,
    pub addr: String,
    pub fp_hint: Option<String>,
    pub seen: Instant,
}

pub enum DiscoveryEvent {
    Resolved(Discovered),
    /// Full name of a service that went away.
    Removed(String),
}

/// Advertise this device and browse for peers. TXT records are hints only.
pub struct Discovery {
    daemon: ServiceDaemon,
    _service: ServiceInfo,
}

impl Discovery {
    pub fn start(
        own_id: &str,
        name: &str,
        platform: &str,
        port: u16,
        fp_hex: &str,
        events: std_mpsc::Sender<DiscoveryEvent>,
    ) -> Result<Discovery, mdns_sd::Error> {
        let daemon = ServiceDaemon::new()?;
        // Virtual and tunnel adapters (docker0, tailscale, Zerotier, …) only
        // produce noise and runaway re-resolution; Kasane Connect is LAN-only.
        if let Ok(interfaces) = if_addrs::get_if_addrs() {
            for iface in interfaces {
                let name = iface.name.to_lowercase();
                let virtual_adapter = ["docker", "veth", "br-", "zt", "tun", "tailscale", "ts-", "wg", "ppp", "virbr", "vmnet", "lo"]
                    .iter()
                    .any(|p| name.starts_with(p) || name == *p);
                if virtual_adapter {
                    let _ = daemon.disable_interface(&iface.name);
                }
            }
        }
        let mut props = HashMap::new();
        props.insert("id".to_string(), own_id.to_string());
        props.insert("platform".to_string(), platform.to_string());
        props.insert("proto".to_string(), PROTO_VERSION.to_string());
        props.insert("fp".to_string(), fp_hex.chars().take(16).collect::<String>());
        let info = ServiceInfo::new(SERVICE_TYPE, name, &host_label(name), "", port, props)?
            .enable_addr_auto();
        daemon.register(info.clone())?;
        let own_name = info.get_fullname().to_string();

        let receiver = daemon.browse(SERVICE_TYPE)?;
        let own_id = own_id.to_string();
        std::thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                match event {
                    ServiceEvent::ServiceResolved(resolved) => {
                        if resolved.get_fullname() == own_name {
                            continue;
                        }
                        let props = resolved.get_properties();
                        let id = props.get("id").map(|v| v.val_str().to_string());
                        if id.as_deref() == Some(own_id.as_str()) {
                            continue;
                        }
                        let addr = resolved
                            .get_addresses()
                            .iter()
                            .map(|a| a.to_ip_addr().to_string())
                            .find(|a| !a.contains(':'))
                            .or_else(|| {
                                resolved
                                    .get_addresses()
                                    .iter()
                                    .map(|a| a.to_ip_addr().to_string())
                                    .next()
                            });
                        let Some(addr) = addr else { continue };
                        let _ = events.send(DiscoveryEvent::Resolved(Discovered {
                            id,
                            name: resolved.get_fullname().to_string(),
                            platform: props.get("platform").map(|v| v.val_str().to_string()),
                            addr: format!("{}:{}", addr, resolved.get_port()),
                            fp_hint: props.get("fp").map(|v| v.val_str().to_string()),
                            seen: Instant::now(),
                        }));
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        let _ = events.send(DiscoveryEvent::Removed(fullname));
                    }
                    _ => {}
                }
            }
        });
        Ok(Discovery {
            daemon,
            _service: info,
        })
    }

    pub fn stop(self) {
        let _ = self.daemon.shutdown();
    }
}

fn host_label(name: &str) -> String {
    let label: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '-' })
        .collect();
    format!("{label}.local.")
}

/// A resolved record stays "online" for this long without a fresh resolve.
pub const ONLINE_TTL: Duration = Duration::from_secs(120);
