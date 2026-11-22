use sha2::{Digest, Sha256};

/// `commit = SHA256("kasane-commit-v1" || nc)`.
pub fn commit(nc: &[u8; 32]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(b"kasane-commit-v1");
    h.update(nc);
    h.finalize().into()
}

/// `SAS = u32_be(SHA256("kasane-pair-v1" || fpS || fpC || ns || nc)[0..4]) % 1_000_000`,
/// formatted zero-padded to six digits.
pub fn code(fp_server: &[u8; 32], fp_client: &[u8; 32], ns: &[u8; 32], nc: &[u8; 32]) -> String {
    let mut h = Sha256::new();
    h.update(b"kasane-pair-v1");
    h.update(fp_server);
    h.update(fp_client);
    h.update(ns);
    h.update(nc);
    let digest: [u8; 32] = h.finalize().into();
    let value = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{value:06}")
}

pub fn fingerprint_hex(fp: &[u8; 32]) -> String {
    hex::encode(fp)
}
