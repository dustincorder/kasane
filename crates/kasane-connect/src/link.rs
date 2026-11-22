use crate::protocol::{decode, encode, Decoded, Frame};
use std::io;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;

pub const MAX_FRAME: usize = 1024 * 1024;
const PING_INTERVAL: Duration = Duration::from_secs(10);
const DEAD_AFTER: Duration = Duration::from_secs(30);

/// What a session loop receives from the wire side.
pub enum Inbound {
    Frame(Frame),
    /// The peer closed the connection or it timed out / broke.
    Closed,
}

/// One live connection: `send` pushes frames out, `rx` yields peer frames.
/// Ping/pong, the 1 MiB frame cap and the 30 s dead peer rule live here so
/// sessions only ever see protocol frames.
pub struct Link {
    tx: mpsc::Sender<Frame>,
    pub rx: mpsc::UnboundedReceiver<Inbound>,
}

const OUTBOUND_QUEUE: usize = 128;

/// The outbound queue is full or closed; the session treats it as a dead peer.
#[derive(Debug)]
pub struct SendFail;

impl Link {
    /// Bounded outbound queue: a stalled peer is disconnected rather than
    /// allowed to grow memory without limit.
    pub fn send(&self, frame: Frame) -> Result<(), SendFail> {
        self.tx.try_send(frame).map_err(|_| SendFail)
    }

    pub(crate) fn sender(&self) -> mpsc::Sender<Frame> {
        self.tx.clone()
    }
}

pub fn spawn<S>(stream: S) -> Link
where
    S: AsyncRead + AsyncWrite + Send + 'static,
    S: Unpin,
{
    let (frame_tx, frame_rx) = mpsc::channel::<Frame>(OUTBOUND_QUEUE);
    let (in_tx, in_rx) = mpsc::unbounded_channel::<Inbound>();
    let (rd, mut wr) = tokio::io::split(stream);

    // Outbound side: queued frames plus the keepalive ticker.
    let out_frames = frame_tx.clone();
    tokio::spawn(async move {
        let mut rx = frame_rx;
        let mut ticker = tokio::time::interval(PING_INTERVAL);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                frame = rx.recv() => {
                    let Some(frame) = frame else { break };
                    let mut line = encode(&frame);
                    line.push('\n');
                    if wr.write_all(line.as_bytes()).await.is_err() {
                        break;
                    }
                }
                _ = ticker.tick() => {
                    if wr.write_all(b"{\"type\":\"ping\"}\n").await.is_err() {
                        break;
                    }
                }
            }
        }
        let _ = wr.shutdown().await;
    });

    // Inbound side: bounded lines, decode, answer pings, drop oversized peers.
    tokio::spawn(async move {
        let mut reader = BufReader::new(rd);
        let mut buf: Vec<u8> = Vec::with_capacity(8 * 1024);
        loop {
            buf.clear();
            let read = match tokio::time::timeout(DEAD_AFTER, read_line(&mut reader, &mut buf)).await {
                Ok(r) => r,
                Err(_) => {
                    let _ = in_tx.send(Inbound::Closed);
                    break;
                }
            };
            match read {
                Ok(0) => {
                    let _ = in_tx.send(Inbound::Closed);
                    break;
                }
                Ok(_) => {}
                Err(_) => {
                    let _ = in_tx.send(Inbound::Closed);
                    break;
                }
            }
            let line = match std::str::from_utf8(&buf) {
                Ok(l) => l.trim_end_matches(['\n', '\r']),
                Err(_) => {
                    let _ = in_tx.send(Inbound::Closed);
                    break;
                }
            };
            match decode(line) {
                Decoded::Frame(Frame::Ping) => {
                    let _ = out_frames.try_send(Frame::Pong);
                }
                Decoded::Frame(Frame::Pong) => {}
                Decoded::Frame(frame) => {
                    if in_tx.send(Inbound::Frame(frame)).is_err() {
                        break;
                    }
                }
                Decoded::UnknownType(_) => {
                    let _ = out_frames.try_send(Frame::error("unknown_type"));
                }
                Decoded::Malformed(_) => {
                    let _ = out_frames.try_send(Frame::error("invalid_frame"));
                }
            }
        }
        // Reading task owns the link lifetime: when it ends, the connection is dead.
        let _ = in_tx.send(Inbound::Closed);
    });

    Link {
        tx: frame_tx,
        rx: in_rx,
    }
}

/// Reads until `\n`; fails the connection once a single frame exceeds 1 MiB.
async fn read_line<R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
    buf: &mut Vec<u8>,
) -> io::Result<usize> {
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            return Ok(0);
        }
        match available.iter().position(|b| *b == b'\n') {
            Some(pos) => {
                buf.extend_from_slice(&available[..=pos]);
                reader.consume(pos + 1);
                return Ok(buf.len());
            }
            None => {
                buf.extend_from_slice(available);
                let n = available.len();
                reader.consume(n);
                if buf.len() > MAX_FRAME {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large"));
                }
            }
        }
    }
}
