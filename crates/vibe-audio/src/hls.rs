use m3u8_rs::{AlternativeMediaType, Playlist};
use reqwest::{blocking::Client, Url};
use rodio::{source::SeekError, Source};
use std::{
    io::{self, Cursor, Read},
    sync::{mpsc, Arc, Mutex},
    thread,
    time::Duration,
};
use symphonia::core::{
    audio::SampleBuffer, codecs::DecoderOptions, errors::Error, formats::FormatOptions,
    io::MediaSourceStream, meta::MetadataOptions, probe::Hint,
};

const IOS_UA: &str = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_5 like Mac OS X;)";
const MAX_PLAYLIST: u64 = 2 * 1024 * 1024;
const MAX_SEGMENT: u64 = 8 * 1024 * 1024;
pub(crate) type StreamError = Arc<Mutex<Option<String>>>;

fn invalid(message: &str) -> io::Error {
    io::Error::other(message)
}

fn fetch(client: &Client, url: &Url, limit: u64) -> io::Result<Vec<u8>> {
    let mut last = invalid("HLS request failed");
    for attempt in 0..3 {
        let result = (|| {
            let response = client
                .get(url.clone())
                .send()
                .map_err(|_| invalid("Could not reach HLS server"))?;
            let status = response.status();
            if !status.is_success() {
                return Err(invalid(&format!("HLS server returned HTTP {status}")));
            }
            let mut bytes = Vec::new();
            response
                .take(limit + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| invalid("Could not read HLS response"))?;
            if bytes.len() as u64 > limit {
                return Err(invalid("HLS response is too large"));
            }
            Ok(bytes)
        })();
        match result {
            Ok(bytes) => return Ok(bytes),
            Err(error) => last = error,
        }
        if attempt < 2 {
            thread::sleep(Duration::from_millis(200 * (attempt + 1)));
        }
    }
    Err(last)
}

fn join(base: &Url, path: &str) -> io::Result<Url> {
    let url = base.join(path).map_err(|_| invalid("Invalid HLS URI"))?;
    if !matches!(url.scheme(), "http" | "https") || !url.username().is_empty() {
        return Err(invalid("Unsupported HLS URI"));
    }
    Ok(url)
}

fn parse(bytes: &[u8]) -> io::Result<Playlist> {
    if !bytes.starts_with(b"#EXTM3U") {
        return Err(invalid("Invalid HLS playlist"));
    }
    m3u8_rs::parse_playlist_res(bytes).map_err(|_| invalid("Invalid HLS playlist"))
}

#[derive(Clone)]
struct Segment {
    url: Url,
    start: Duration,
    duration: Duration,
}

fn media_segments(base: &Url, playlist: m3u8_rs::MediaPlaylist) -> io::Result<Vec<Segment>> {
    if !playlist.end_list {
        return Err(invalid("Unsupported live HLS playlist: ENDLIST required"));
    }
    if playlist.segments.is_empty() || playlist.segments.len() > 10_000 {
        return Err(invalid("Empty or oversized HLS media playlist"));
    }
    let mut start = Duration::ZERO;
    playlist
        .segments
        .into_iter()
        .map(|segment| {
            if segment.key.is_some() || segment.map.is_some() || segment.byte_range.is_some() {
                return Err(invalid(
                    "Unsupported encrypted, fragmented MP4 or byte-range HLS",
                ));
            }
            let seconds = segment.duration as f64;
            if !seconds.is_finite() || seconds <= 0.0 || seconds > 30.0 {
                return Err(invalid("Invalid HLS segment duration"));
            }
            let duration = Duration::from_secs_f64(seconds);
            let result = Segment {
                url: join(base, &segment.uri)?,
                start,
                duration,
            };
            start += duration;
            Ok(result)
        })
        .collect()
}

fn playlist(client: &Client, url: &Url) -> io::Result<Vec<Segment>> {
    let bytes = fetch(client, url, MAX_PLAYLIST)?;
    match parse(&bytes)? {
        Playlist::MediaPlaylist(media) => media_segments(url, media),
        Playlist::MasterPlaylist(master) => {
            let audio = master
                .alternatives
                .iter()
                .filter(|item| item.media_type == AlternativeMediaType::Audio && item.uri.is_some())
                .max_by_key(|item| {
                    let aac = master.variants.iter().any(|variant| {
                        variant.audio.as_deref() == Some(item.group_id.as_str())
                            && variant
                                .codecs
                                .as_deref()
                                .is_some_and(|codecs| codecs.contains("mp4a"))
                    });
                    (aac, item.default)
                })
                .ok_or_else(|| invalid("No compatible audio-only HLS rendition"))?;
            let media_url = join(
                url,
                audio
                    .uri
                    .as_deref()
                    .ok_or_else(|| invalid("Missing HLS audio URI"))?,
            )?;
            let media = fetch(client, &media_url, MAX_PLAYLIST)?;
            match parse(&media)? {
                Playlist::MediaPlaylist(media) => media_segments(&media_url, media),
                _ => Err(invalid("Expected HLS media playlist")),
            }
        }
    }
}

fn packed_aac(mut bytes: &[u8]) -> io::Result<&[u8]> {
    while bytes.starts_with(b"ID3") {
        if bytes.len() < 10 || bytes[6..10].iter().any(|b| b & 0x80 != 0) {
            return Err(invalid("Invalid packed AAC ID3 header"));
        }
        let size = bytes[6..10]
            .iter()
            .fold(0usize, |size, b| (size << 7) | *b as usize);
        let footer = if bytes[3] == 4 && bytes[5] & 0x10 != 0 {
            10
        } else {
            0
        };
        bytes = bytes
            .get(10 + size + footer..)
            .ok_or_else(|| invalid("Truncated packed AAC ID3 tag"))?;
    }
    if bytes.len() < 7 || bytes[0] != 0xff || bytes[1] & 0xf6 != 0xf0 {
        return Err(invalid(
            "Unsupported HLS segment format: expected packed AAC/ADTS",
        ));
    }
    let mut frames = bytes;
    while !frames.is_empty() {
        if frames.len() < 7 || frames[0] != 0xff || frames[1] & 0xf6 != 0xf0 {
            return Err(invalid("Invalid ADTS frame header"));
        }
        let length = ((frames[3] as usize & 3) << 11)
            | ((frames[4] as usize) << 3)
            | (frames[5] as usize >> 5);
        let header = if frames[1] & 1 != 0 { 7 } else { 9 };
        if length < header || length > frames.len() {
            return Err(invalid("Truncated ADTS frame"));
        }
        frames = &frames[length..];
    }
    Ok(bytes)
}

struct Audio {
    samples: Vec<f32>,
    channels: u16,
    rate: u32,
}

fn decode(bytes: Vec<u8>) -> io::Result<Audio> {
    let aac = packed_aac(&bytes)?.to_vec();
    let stream = MediaSourceStream::new(Box::new(Cursor::new(aac)), Default::default());
    let mut hint = Hint::new();
    hint.with_extension("aac");
    let mut format = symphonia::default::get_probe()
        .format(
            &hint,
            stream,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )
        .map_err(|_| invalid("Could not parse packed AAC segment"))?
        .format;
    let track = format
        .default_track()
        .ok_or_else(|| invalid("No AAC audio track"))?;
    let id = track.id;
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|_| invalid("Unsupported AAC codec"))?;
    let mut audio = Audio {
        samples: Vec::new(),
        channels: 0,
        rate: 0,
    };
    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(Error::IoError(error)) if error.kind() == io::ErrorKind::UnexpectedEof => break,
            Err(_) => return Err(invalid("Invalid AAC packet")),
        };
        if packet.track_id() != id {
            continue;
        }
        let decoded = decoder
            .decode(&packet)
            .map_err(|_| invalid("Could not decode AAC packet"))?;
        let channels = decoded.spec().channels.count() as u16;
        let rate = decoded.spec().rate;
        if audio.rate != 0 && (audio.channels != channels || audio.rate != rate) {
            return Err(invalid("AAC format changed inside a segment"));
        }
        audio.channels = channels;
        audio.rate = rate;
        let mut samples = SampleBuffer::<f32>::new(decoded.capacity() as u64, *decoded.spec());
        samples.copy_interleaved_ref(decoded);
        audio.samples.extend_from_slice(samples.samples());
        if audio.samples.len() > 30 * 192_000 * 8 {
            return Err(invalid("Decoded HLS segment is too large"));
        }
    }
    if audio.samples.is_empty() {
        return Err(invalid("Empty AAC segment"));
    }
    Ok(audio)
}

struct DecodedSegment {
    generation: u64,
    index: usize,
    audio: io::Result<Audio>,
}

pub(crate) struct HlsSource {
    segments: Vec<Segment>,
    duration: Duration,
    receiver: mpsc::Receiver<DecodedSegment>,
    commands: mpsc::Sender<(u64, usize)>,
    generation: u64,
    index: usize,
    samples: Vec<f32>,
    offset: usize,
    channels: u16,
    rate: u32,
    failed: bool,
    pub(crate) error: StreamError,
}

impl HlsSource {
    pub(crate) fn open(url: &str) -> io::Result<Self> {
        let client = Client::builder()
            .user_agent(IOS_UA)
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|_| invalid("Could not initialize HLS HTTP client"))?;
        let url = Url::parse(url).map_err(|_| invalid("Invalid HLS URL"))?;
        let segments = playlist(&client, &url)?;
        let last = segments
            .last()
            .ok_or_else(|| invalid("Empty HLS playlist"))?;
        let duration = last.start + last.duration;
        let (sender, receiver) = mpsc::sync_channel(2);
        let (commands, requests) = mpsc::channel();
        let worker_segments = segments.clone();
        thread::Builder::new()
            .name("kasane-hls".into())
            .spawn(move || {
                let mut generation = 0;
                let mut index = 0;
                loop {
                    if index >= worker_segments.len() {
                        match requests.recv() {
                            Ok((next_generation, next_index)) => {
                                generation = next_generation;
                                index = next_index;
                            }
                            Err(_) => break,
                        }
                    }
                    while let Ok((next_generation, next_index)) = requests.try_recv() {
                        generation = next_generation;
                        index = next_index;
                    }
                    let Some(segment) = worker_segments.get(index) else {
                        continue;
                    };
                    let audio = fetch(&client, &segment.url, MAX_SEGMENT).and_then(decode);
                    if sender
                        .send(DecodedSegment {
                            generation,
                            index,
                            audio,
                        })
                        .is_err()
                    {
                        break;
                    }
                    index += 1;
                }
            })?;
        let mut source = Self {
            segments,
            duration,
            receiver,
            commands,
            generation: 0,
            index: 0,
            samples: Vec::new(),
            offset: 0,
            channels: 0,
            rate: 0,
            failed: false,
            error: Arc::new(Mutex::new(None)),
        };
        source.load(0)?;
        Ok(source)
    }

    fn load(&mut self, index: usize) -> io::Result<()> {
        loop {
            let segment = self
                .receiver
                .recv()
                .map_err(|_| invalid("HLS worker stopped"))?;
            if segment.generation != self.generation || segment.index != index {
                continue;
            }
            let audio = segment.audio?;
            if self.rate != 0 && (self.rate != audio.rate || self.channels != audio.channels) {
                return Err(invalid("Unsupported HLS audio format change"));
            }
            self.rate = audio.rate;
            self.channels = audio.channels;
            self.samples = audio.samples;
            self.offset = 0;
            self.index = index;
            return Ok(());
        }
    }

    fn fail(&mut self, error: &io::Error) {
        self.failed = true;
        if let Ok(mut slot) = self.error.lock() {
            *slot = Some(error.to_string());
        }
    }

    fn seek(&mut self, position: Duration) -> io::Result<()> {
        let position = position.min(self.duration);
        if position == self.duration {
            self.index = self.segments.len();
            self.samples.clear();
            self.offset = 0;
            return Ok(());
        }
        let index = self
            .segments
            .partition_point(|s| s.start <= position)
            .saturating_sub(1);
        self.generation += 1;
        self.commands
            .send((self.generation, index))
            .map_err(|_| invalid("HLS worker stopped"))?;
        self.load(index)?;
        let skip = position
            .saturating_sub(self.segments[index].start)
            .as_secs_f64();
        self.offset =
            ((skip * self.rate as f64) as usize * self.channels as usize).min(self.samples.len());
        self.failed = false;
        Ok(())
    }
}

impl Iterator for HlsSource {
    type Item = f32;
    fn next(&mut self) -> Option<f32> {
        if self.failed {
            return None;
        }
        if self.offset == self.samples.len() {
            let next = self.index + 1;
            if next >= self.segments.len() {
                return None;
            }
            if let Err(error) = self.load(next) {
                self.fail(&error);
                return None;
            }
        }
        let sample = self.samples.get(self.offset).copied();
        self.offset += usize::from(sample.is_some());
        sample
    }
}

impl Source for HlsSource {
    fn current_span_len(&self) -> Option<usize> {
        Some(self.samples.len().saturating_sub(self.offset))
    }
    fn channels(&self) -> u16 {
        self.channels
    }
    fn sample_rate(&self) -> u32 {
        self.rate
    }
    fn total_duration(&self) -> Option<Duration> {
        Some(self.duration)
    }
    fn try_seek(&mut self, position: Duration) -> Result<(), SeekError> {
        self.seek(position).map_err(|error| {
            self.fail(&error);
            SeekError::Other(Box::new(error))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::Write, net::TcpListener};

    const AAC: &[u8] = include_bytes!("../tests/fixtures/tone.aac");
    const MEDIA: &str = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:0.277333,\na.aac\n#EXTINF:0.277333,\nb.aac\n#EXT-X-ENDLIST\n";

    fn server(responses: Vec<(u16, Vec<u8>)>) -> (String, thread::JoinHandle<Vec<String>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let task = thread::spawn(move || {
            let mut requests = Vec::new();
            for (status, body) in responses {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    assert_eq!(socket.read(&mut byte).unwrap(), 1);
                    request.push(byte[0]);
                }
                requests.push(String::from_utf8(request).unwrap());
                write!(
                    socket,
                    "HTTP/1.1 {status} Response\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                )
                .unwrap();
                socket.write_all(&body).unwrap();
            }
            requests
        });
        (format!("http://{address}/master.m3u8"), task)
    }

    #[test]
    fn packed_aac_decodes_and_id3_is_removed() {
        let decoded = decode(AAC.to_vec()).unwrap();
        assert_eq!(decoded.channels, 2);
        assert_eq!(decoded.rate, 48_000);
        assert!(decoded.samples.iter().any(|sample| sample.abs() > 0.001));
        let mut bytes = b"ID3\x03\x00\x00\x00\x00\x00\x03tag".to_vec();
        bytes.extend_from_slice(AAC);
        assert_eq!(decode(bytes).unwrap().samples.len(), decoded.samples.len());
        assert!(decode(Vec::new()).is_err());
        assert!(decode(b"ID3".to_vec()).is_err());
        assert!(decode(b"ID3\x03\x00\x00\x00\x00\x01\x00".to_vec()).is_err());
        assert!(decode(AAC[..AAC.len() - 1].to_vec()).is_err());
        assert!(decode(vec![0x47; 188]).is_err());
        assert!(decode(b"\x00\x00\x00\x18ftyp".to_vec()).is_err());
    }

    #[test]
    fn media_requires_finite_durations_endlist_and_supported_transport() {
        let base = Url::parse("https://example.com/audio/list.m3u8").unwrap();
        let Playlist::MediaPlaylist(media) = parse(MEDIA.as_bytes()).unwrap() else {
            panic!()
        };
        let segments = media_segments(&base, media).unwrap();
        assert_eq!(segments.len(), 2);
        assert_eq!(segments[0].url.as_str(), "https://example.com/audio/a.aac");
        assert_eq!(segments[1].start, segments[0].duration);
        for bad in [
            "#EXTM3U\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXTINF:1,\na.aac\n",
            "#EXTM3U\n#EXTINF:0,\na.aac\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXTINF:31,\na.aac\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:1,\na.aac\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST\n",
        ] {
            let result = parse(bad.as_bytes()).and_then(|p| match p {
                Playlist::MediaPlaylist(media) => media_segments(&base, media),
                _ => Err(invalid("not media")),
            });
            assert!(result.is_err(), "{bad}");
        }
        assert!(parse(b"not a playlist").is_err());
        assert!(join(&base, "file:///etc/passwd").is_err());
    }

    #[test]
    fn master_selects_aac_audio_and_streams_through_eof() {
        let master = b"#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"opus\",NAME=\"Opus\",DEFAULT=YES,URI=\"wrong.m3u8\"\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"AAC\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS=\"mp4a.40.2\",AUDIO=\"aac\"\nvideo.m3u8\n";
        let (url, task) = server(vec![
            (200, master.to_vec()),
            (200, MEDIA.as_bytes().to_vec()),
            (200, AAC.to_vec()),
            (200, AAC.to_vec()),
        ]);
        let mut source = HlsSource::open(&url).unwrap();
        assert!((source.total_duration().unwrap().as_secs_f64() - 0.554666).abs() < 0.00001);
        let count = source.by_ref().count();
        assert_eq!(count, decode(AAC.to_vec()).unwrap().samples.len() * 2);
        assert!(source.next().is_none());
        assert!(source.error.lock().unwrap().is_none());
        drop(source);
        let requests = task.join().unwrap();
        assert!(requests[1].starts_with("GET /audio.m3u8 "));
        assert!(requests[2].starts_with("GET /a.aac "));
        assert!(requests[3].starts_with("GET /b.aac "));
        assert!(requests
            .iter()
            .all(|r| r.to_lowercase().contains(&IOS_UA.to_lowercase())));
    }

    #[test]
    fn segment_failure_is_bounded_and_not_silent_eof() {
        let (url, task) = server(vec![
            (200, MEDIA.as_bytes().to_vec()),
            (200, AAC.to_vec()),
            (503, Vec::new()),
            (503, Vec::new()),
            (503, Vec::new()),
        ]);
        let mut source = HlsSource::open(&url).unwrap();
        let count = source.by_ref().count();
        assert_eq!(count, decode(AAC.to_vec()).unwrap().samples.len());
        assert!(source
            .error
            .lock()
            .unwrap()
            .as_ref()
            .unwrap()
            .contains("503"));
        assert!(source.next().is_none());
        drop(source);
        assert_eq!(task.join().unwrap().len(), 5);
    }

    #[test]
    fn transient_segment_failure_retries_then_decodes() {
        let (url, task) = server(vec![
            (200, MEDIA.as_bytes().to_vec()),
            (503, Vec::new()),
            (200, AAC.to_vec()),
            (200, AAC.to_vec()),
        ]);
        let mut source = HlsSource::open(&url).unwrap();
        assert_eq!(
            source.by_ref().count(),
            decode(AAC.to_vec()).unwrap().samples.len() * 2
        );
        assert!(source.error.lock().unwrap().is_none());
        drop(source);
        assert_eq!(task.join().unwrap().len(), 4);
    }

    #[test]
    fn seek_discards_prefetch_and_maps_time_to_segment() {
        // Two initial prefetched segments, followed by segment two again after seek.
        let (url, task) = server(vec![
            (200, MEDIA.as_bytes().to_vec()),
            (200, AAC.to_vec()),
            (200, AAC.to_vec()),
            (200, AAC.to_vec()),
        ]);
        let mut source = HlsSource::open(&url).unwrap();
        // Drain initial data so the worker is waiting for a new seek.
        let _ = source.by_ref().count();
        let target = Duration::from_secs_f64(0.377333);
        source.try_seek(target).unwrap();
        assert_eq!(source.index, 1);
        let expected = decode(AAC.to_vec()).unwrap().samples.len();
        assert!(source.offset.abs_diff(4_800 * 2) <= 2);
        assert!(source.by_ref().count().abs_diff(expected - 4_800 * 2) <= 2);
        source.try_seek(source.duration).unwrap();
        assert!(source.next().is_none());
        drop(source);
        assert!(task.join().unwrap()[3].starts_with("GET /b.aac "));
    }
}
