use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

/// Long-lived self-signed ECDSA P-256 identity: PKCS8 key (0600), cert DER and
/// the advertised device record, all under `<app_data_dir>/connect/`.
pub struct Identity {
    pub key_der: Vec<u8>,
    pub cert_der: Vec<u8>,
    pub device_id: String,
    pub name: String,
}

#[derive(Serialize, Deserialize)]
struct DeviceFile {
    id: String,
    name: String,
}

impl Identity {
    pub fn fingerprint(&self) -> [u8; 32] {
        Sha256::digest(&self.cert_der).into()
    }

    pub fn load_or_create(dir: &Path, device_name: &str) -> std::io::Result<Identity> {
        std::fs::create_dir_all(dir)?;
        let key_path = dir.join("identity-key.p8");
        let cert_path = dir.join("identity-cert.der");
        let device_path = dir.join("device.json");

        if key_path.exists() && cert_path.exists() && device_path.exists() {
            let key_der = std::fs::read(&key_path)?;
            let cert_der = std::fs::read(&cert_path)?;
            let device: DeviceFile =
                serde_json::from_slice(&std::fs::read(&device_path)?).map_err(invalid)?;
            return Ok(Identity {
                key_der,
                cert_der,
                device_id: device.id,
                name: device.name,
            });
        }

        let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).map_err(invalid)?;
        let params = CertificateParams::new(Vec::new()).map_err(invalid)?;
        let cert = params.self_signed(&key_pair).map_err(invalid)?;

        let key_der = key_pair.serialize_der();
        let cert_der = cert.der().to_vec();
        let device = DeviceFile {
            id: uuid::Uuid::new_v4().to_string(),
            name: device_name.into(),
        };
        write_atomic(&key_path, &key_der, true)?;
        write_atomic(&cert_path, &cert_der, false)?;
        write_atomic(
            &device_path,
            serde_json::to_string_pretty(&device)?.as_bytes(),
            false,
        )?;
        Ok(Identity {
            key_der,
            cert_der,
            device_id: device.id,
            name: device.name,
        })
    }
}

fn invalid<E: std::fmt::Display>(e: E) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string())
}

pub fn write_atomic(path: &Path, bytes: &[u8], secret: bool) -> std::io::Result<()> {
    let tmp = PathBuf::from(format!("{}.tmp", path.display()));
    std::fs::write(&tmp, bytes)?;
    #[cfg(unix)]
    if secret {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
    }
    std::fs::rename(&tmp, path)
}
