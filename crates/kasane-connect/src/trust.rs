use crate::identity::write_atomic;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrustedDevice {
    pub device_id: String,
    pub name: String,
    pub platform: String,
    /// Lowercase hex SHA-256 of the peer's leaf certificate DER.
    pub fingerprint: String,
    pub paired_at: u64,
    /// Manual `host:port` endpoints learned outside mDNS.
    #[serde(default)]
    pub endpoints: Vec<String>,
}

/// `trusted.json`, rewritten atomically on every mutation.
pub struct TrustStore {
    path: PathBuf,
    devices: HashMap<String, TrustedDevice>,
}

impl TrustStore {
    pub fn load(dir: &Path) -> std::io::Result<TrustStore> {
        let path = dir.join("trusted.json");
        let devices = match std::fs::read(&path) {
            Ok(bytes) => serde_json::from_slice::<Vec<TrustedDevice>>(&bytes)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?
                .into_iter()
                .map(|d| (d.device_id.clone(), d))
                .collect(),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => HashMap::new(),
            Err(e) => return Err(e),
        };
        Ok(TrustStore { path, devices })
    }

    pub fn get(&self, device_id: &str) -> Option<&TrustedDevice> {
        self.devices.get(device_id)
    }

    pub fn all(&self) -> Vec<TrustedDevice> {
        self.devices.values().cloned().collect()
    }

    pub fn put(&mut self, device: TrustedDevice) -> std::io::Result<()> {
        self.devices.insert(device.device_id.clone(), device);
        self.save()
    }

    pub fn remove(&mut self, device_id: &str) -> std::io::Result<Option<TrustedDevice>> {
        let removed = self.devices.remove(device_id);
        self.save()?;
        Ok(removed)
    }

    pub fn add_endpoint(&mut self, device_id: &str, endpoint: String) -> std::io::Result<()> {
        if let Some(d) = self.devices.get_mut(device_id) {
            if !d.endpoints.contains(&endpoint) {
                d.endpoints.push(endpoint);
            }
            self.save()?;
        }
        Ok(())
    }

    fn save(&self) -> std::io::Result<()> {
        let mut list: Vec<&TrustedDevice> = self.devices.values().collect();
        list.sort_by(|a, b| a.device_id.cmp(&b.device_id));
        let json = serde_json::to_string_pretty(&list)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;
        write_atomic(&self.path, json.as_bytes(), false)
    }
}
