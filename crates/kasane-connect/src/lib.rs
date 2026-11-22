pub mod adapter;
pub mod discovery;
pub mod identity;
pub mod link;
pub mod fake;
pub mod protocol;
pub mod raw;
pub mod sas;
pub mod service;
pub mod tls;
pub mod trust;

pub use adapter::{CmdError, PlayerAdapter, PlayerSnapshot};
pub use protocol::{
    AlbumMeta, ArtistMeta, DeviceInfo, Frame, HandoffRequest, StateFrame, TrackMeta,
};
pub use service::{ClientHandle, ConnError, ConnectService, DeviceView, PairingPrompt};
