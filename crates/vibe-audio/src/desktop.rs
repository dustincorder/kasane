use crate::hls::{HlsSource, StreamError};
use async_trait::async_trait;
use rodio::{Decoder, OutputStream, OutputStreamBuilder, Sink, Source};
use std::io::Cursor;
use std::{fs::File, thread, time::Duration};
use tokio::sync::{mpsc, oneshot};
use vibe_core::error::{Result, VibeError};
use vibe_core::model::{PlaybackProgress, PlaybackState, StreamSource};
use vibe_core::traits::PlaybackBackend;

const MAX_DOWNLOAD_BYTES: usize = 64 * 1024 * 1024;
const DOWNLOAD_CHUNK_BYTES: usize = 1024 * 1024;

enum Media {
    File(String),
    Hls(String),
    Download(Vec<u8>),
}

enum Command {
    Play(Media),
    Pause,
    Resume,
    Stop,
    Seek(Duration),
    Volume(f32),
    Progress,
}

struct AudioWorker {
    sink: Option<Sink>,
    output: Option<OutputStream>,
    duration: Duration,
    volume: f32,
    stream_error: Option<StreamError>,
}

impl AudioWorker {
    fn play(
        &mut self,
        source: std::result::Result<impl Source + Send + 'static, rodio::decoder::DecoderError>,
    ) -> Result<()> {
        let source = source.map_err(|error| {
            VibeError::Playback(format!("Unsupported or invalid audio file: {error}"))
        })?;
        let duration = source.total_duration().unwrap_or_default();
        if self.output.is_none() {
            let output = OutputStreamBuilder::open_default_stream()
                .map_err(|_| VibeError::Audio("No working audio output is available".into()))?;
            self.output = Some(output);
        }
        let output = self.output.as_ref().ok_or_else(worker_closed)?;
        let sink = Sink::connect_new(output.mixer());
        sink.set_volume(self.volume);
        sink.pause();
        sink.append(source);
        self.sink = Some(sink);
        self.duration = duration;
        if let Some(sink) = &self.sink {
            sink.play();
        }
        Ok(())
    }

    fn progress(&self) -> PlaybackProgress {
        let (state, position) = match &self.sink {
            None => (PlaybackState::Stopped, Duration::ZERO),
            Some(sink) if sink.empty() => (PlaybackState::Ended, sink.get_pos()),
            Some(sink) if sink.is_paused() => (PlaybackState::Paused, sink.get_pos()),
            Some(sink) => (PlaybackState::Playing, sink.get_pos()),
        };
        PlaybackProgress {
            position_secs: if state == PlaybackState::Ended && !self.duration.is_zero() {
                self.duration
            } else if self.duration.is_zero() {
                position
            } else {
                position.min(self.duration)
            }
            .as_secs_f64(),
            duration_secs: self.duration.as_secs_f64(),
            state,
            current_track: None,
            notice: None,
        }
    }

    fn run(&mut self, command: Command) -> Result<PlaybackProgress> {
        match command {
            Command::Play(Media::File(path)) => {
                if !std::fs::metadata(&path)?.is_file() {
                    return Err(VibeError::Playback("Choose a regular audio file".into()));
                }
                self.play(Decoder::try_from(File::open(path)?))?;
                self.stream_error = None;
            }
            Command::Play(Media::Hls(url)) => {
                let audio = HlsSource::open(&url)?;
                let error = audio.error.clone();
                self.play(Ok(audio))?;
                self.stream_error = Some(error);
            }
            Command::Play(Media::Download(bytes)) => {
                let len = bytes.len() as u64;
                let decoder = Decoder::builder()
                    .with_data(Cursor::new(bytes))
                    .with_byte_len(len)
                    .with_seekable(true)
                    .build();
                self.play(decoder)?;
                self.stream_error = None;
            }
            Command::Pause => {
                if let Some(sink) = &self.sink {
                    sink.pause();
                }
            }
            Command::Resume => {
                if let Some(sink) = &self.sink {
                    sink.play();
                }
            }
            Command::Stop => {
                self.sink = None;
                self.duration = Duration::ZERO;
                self.stream_error = None;
            }
            Command::Seek(position) => {
                if let Some(sink) = self.sink.as_ref().filter(|sink| !sink.empty()) {
                    let position = if self.duration.is_zero() {
                        position
                    } else {
                        position.min(self.duration.saturating_sub(Duration::from_millis(1)))
                    };
                    sink.try_seek(position).map_err(|_| {
                        VibeError::Playback("Could not seek in this audio file".into())
                    })?;
                }
            }
            Command::Volume(volume) => {
                self.volume = volume;
                if let Some(sink) = &self.sink {
                    sink.set_volume(volume);
                }
            }
            Command::Progress => {}
        }
        if let Some(error) = &self.stream_error {
            if let Some(message) = error.lock().map_err(|_| worker_closed())?.as_ref() {
                if let Some(sink) = &self.sink {
                    sink.pause();
                }
                return Err(VibeError::Network(message.clone()));
            }
        }
        Ok(self.progress())
    }
}

fn worker_closed() -> VibeError {
    VibeError::Audio("Audio worker stopped".into())
}

pub struct DesktopAudioBackend {
    commands: mpsc::Sender<(Command, oneshot::Sender<Result<PlaybackProgress>>)>,
    client: reqwest::Client,
}

impl DesktopAudioBackend {
    pub fn new() -> Result<Self> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(20))
            .build()
            .map_err(|_| VibeError::Audio("Could not initialize audio HTTP client".into()))?;
        let (commands, mut receiver) = mpsc::channel::<(Command, oneshot::Sender<_>)>(8);
        // Keep rodio's non-Send output stream on its own thread.
        thread::Builder::new()
            .name("kasane-audio".into())
            .spawn(move || {
                let mut worker = AudioWorker {
                    sink: None,
                    output: None,
                    duration: Duration::ZERO,
                    volume: 1.0,
                    stream_error: None,
                };
                while let Some((command, reply)) = receiver.blocking_recv() {
                    let _ = reply.send(worker.run(command));
                }
            })?;
        Ok(Self { commands, client })
    }

    async fn send(&self, command: Command) -> Result<PlaybackProgress> {
        let (reply, response) = oneshot::channel();
        self.commands
            .send((command, reply))
            .await
            .map_err(|_| worker_closed())?;
        response.await.map_err(|_| worker_closed())?
    }

    /// Downloads in bounded byte ranges so throttled CDNs (YouTube serves a
    /// whole-file request at roughly playback speed) finish within the timeout.
    async fn download(&self, url: &str) -> Result<Vec<u8>> {
        let mut bytes = Vec::new();
        loop {
            let start = bytes.len();
            let mut response = self
                .client
                .get(url)
                .header(
                    reqwest::header::RANGE,
                    format!("bytes={start}-{}", start + DOWNLOAD_CHUNK_BYTES - 1),
                )
                .send()
                .await
                .map_err(|_| VibeError::Network("Could not reach the audio server".into()))?;
            let status = response.status();
            if !status.is_success() {
                return Err(VibeError::Playback(format!(
                    "Audio server returned HTTP {status}"
                )));
            }
            let partial = status == reqwest::StatusCode::PARTIAL_CONTENT;
            if !partial && start > 0 {
                return Err(VibeError::Playback(
                    "Audio server stopped honoring byte ranges".into(),
                ));
            }
            let total = if partial {
                response
                    .headers()
                    .get(reqwest::header::CONTENT_RANGE)
                    .and_then(|value| value.to_str().ok())
                    .and_then(|value| value.rsplit('/').next())
                    .and_then(|value| value.parse::<u64>().ok())
            } else {
                response.content_length()
            };
            if total.is_some_and(|size| size > MAX_DOWNLOAD_BYTES as u64) {
                return Err(VibeError::Playback("Audio stream is too large".into()));
            }
            while let Some(chunk) = response
                .chunk()
                .await
                .map_err(|_| VibeError::Network("Could not download audio".into()))?
            {
                if chunk.len() > MAX_DOWNLOAD_BYTES - bytes.len() {
                    return Err(VibeError::Playback("Audio stream is too large".into()));
                }
                bytes.extend_from_slice(&chunk);
            }
            let received = bytes.len() - start;
            let done = !partial
                || received == 0
                || total.map_or(received < DOWNLOAD_CHUNK_BYTES, |size| bytes.len() as u64 >= size);
            if done {
                return Ok(bytes);
            }
        }
    }
}

#[async_trait]
impl PlaybackBackend for DesktopAudioBackend {
    async fn play_stream(&mut self, source: &StreamSource) -> Result<()> {
        let media = if source.url.starts_with("https://") || source.url.starts_with("http://") {
            if matches!(
                source.mime_type.as_str(),
                "application/vnd.apple.mpegurl" | "application/x-mpegURL"
            ) {
                Media::Hls(source.url.clone())
            } else {
                Media::Download(self.download(&source.url).await?)
            }
        } else {
            Media::File(source.url.clone())
        };
        self.send(Command::Play(media)).await?;
        Ok(())
    }

    async fn pause(&mut self) -> Result<()> {
        self.send(Command::Pause).await?;
        Ok(())
    }

    async fn resume(&mut self) -> Result<()> {
        self.send(Command::Resume).await?;
        Ok(())
    }

    async fn stop(&mut self) -> Result<()> {
        self.send(Command::Stop).await?;
        Ok(())
    }

    async fn seek(&mut self, position_secs: f64) -> Result<()> {
        let position = Duration::try_from_secs_f64(position_secs)
            .map_err(|_| VibeError::Playback("Invalid seek position".into()))?;
        self.send(Command::Seek(position)).await?;
        Ok(())
    }

    async fn set_volume(&mut self, volume: f32) -> Result<()> {
        if !volume.is_finite() {
            return Err(VibeError::Playback("Invalid volume".into()));
        }
        self.send(Command::Volume(volume.clamp(0.0, 1.0))).await?;
        Ok(())
    }

    async fn get_progress(&self) -> Result<PlaybackProgress> {
        self.send(Command::Progress).await
    }

    async fn get_state(&self) -> Result<PlaybackState> {
        Ok(self.get_progress().await?.state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm_duration_and_near_end_seek_match_the_audio() {
        const RATE: u32 = 48_000;
        const SECONDS: u32 = 45;
        const DATA_BYTES: u32 = RATE * SECONDS * 2;
        let mut wav = b"RIFF".to_vec();
        wav.extend((36 + DATA_BYTES).to_le_bytes());
        wav.extend(b"WAVEfmt ");
        wav.extend(16u32.to_le_bytes());
        wav.extend(1u16.to_le_bytes());
        wav.extend(1u16.to_le_bytes());
        wav.extend(RATE.to_le_bytes());
        wav.extend((RATE * 2).to_le_bytes());
        wav.extend(2u16.to_le_bytes());
        wav.extend(16u16.to_le_bytes());
        wav.extend(b"data");
        wav.extend(DATA_BYTES.to_le_bytes());
        wav.resize(44 + DATA_BYTES as usize, 0);
        let mut decoder = Decoder::try_from(Cursor::new(wav)).unwrap();
        let duration = Duration::from_secs(SECONDS.into());
        assert_eq!(decoder.total_duration(), Some(duration));
        decoder
            .try_seek(duration - Duration::from_millis(1))
            .unwrap();
        assert!(decoder.next().is_some());
    }

    #[tokio::test]
    async fn download_reassembles_byte_ranges() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let body: Vec<u8> = (0..(DOWNLOAD_CHUNK_BYTES * 2 + 1234)).map(|i| (i % 251) as u8).collect();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/audio", listener.local_addr().unwrap());
        let served = body.clone();
        let server = tokio::spawn(async move {
            let mut ranges = Vec::new();
            for _ in 0..3 {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut head = Vec::new();
                while !head.ends_with(b"\r\n\r\n") {
                    let mut byte = [0; 1];
                    socket.read_exact(&mut byte).await.unwrap();
                    head.push(byte[0]);
                }
                let head = String::from_utf8(head).unwrap().to_lowercase();
                let range = head.lines().find_map(|l| l.strip_prefix("range: bytes=")).unwrap().to_owned();
                let (start, end) = range.split_once('-').unwrap();
                let start: usize = start.parse().unwrap();
                let end = end.parse::<usize>().unwrap().min(served.len() - 1);
                let header = format!(
                    "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {start}-{end}/{}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    served.len(),
                    end - start + 1
                );
                socket.write_all(header.as_bytes()).await.unwrap();
                socket.write_all(&served[start..=end]).await.unwrap();
                ranges.push(range);
            }
            ranges
        });
        let backend = DesktopAudioBackend::new().unwrap();
        assert_eq!(backend.download(&url).await.unwrap(), body);
        assert_eq!(server.await.unwrap()[2], format!("{}-{}", DOWNLOAD_CHUNK_BYTES * 2, DOWNLOAD_CHUNK_BYTES * 3 - 1));
    }

    #[tokio::test]
    async fn undecodable_media_does_not_start_playback() {
        let backend = DesktopAudioBackend::new().unwrap();
        let result = backend
            .send(Command::Play(Media::Download(b"not audio".to_vec())))
            .await;
        assert!(matches!(result, Err(VibeError::Playback(_))));
        assert_eq!(backend.get_state().await.unwrap(), PlaybackState::Stopped);
    }
}
