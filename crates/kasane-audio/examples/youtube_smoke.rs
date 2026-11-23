//! Run explicitly with `cargo run -p kasane-audio --example youtube_smoke`.
//! Uses anonymous live YouTube requests and the same backend as the desktop app.
use std::time::{Duration, Instant};
use kasane_audio::DesktopAudioBackend;
use kasane_core::model::PlaybackState;
use kasane_core::traits::{MusicProvider, PlaybackBackend};
use kasane_providers::YouTubeMusicProvider;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let provider = YouTubeMusicProvider::new();
    let mut tracks = provider
        .search("Rick Astley Never Gonna Give You Up", 3)
        .await?;
    if tracks.len() != 3 {
        return Err("Expected three real search results".into());
    }
    println!("SEARCH OK: {} real YouTube tracks", tracks.len());
    tracks.push(provider.get_track_details("dQw4w9WgXcQ").await?);
    tracks.push(provider.get_track_details("JALbemLw3G4").await?);
    let mut backend = DesktopAudioBackend::new()?;
    backend.set_volume(0.0).await?;
    let mut played = 0;
    let mut unavailable = 0;
    for track in tracks {
        let source = match provider.resolve_stream(&track).await {
            Ok(source) => source,
            Err(error) => {
                println!("RESOLVE UNAVAILABLE: {}: {}", track.id, error);
                unavailable += 1;
                continue;
            }
        };
        println!("RESOLVE OK: {} {}", track.id, source.mime_type);
        backend.play_stream(&source).await?;
        let deadline = Instant::now() + Duration::from_secs(20);
        let progress = loop {
            tokio::time::sleep(Duration::from_millis(100)).await;
            let progress = backend.get_progress().await?;
            if progress.state == PlaybackState::Playing && progress.position_secs >= 3.0 {
                break progress;
            }
            if progress.state == PlaybackState::Ended || Instant::now() >= deadline {
                return Err(format!("{}: did not decode three seconds", track.id).into());
            }
        };
        if progress.duration_secs <= 10.0 {
            return Err("Decoder did not report a valid duration".into());
        }
        println!(
            "DECODE OK: {} position={:.2}s duration={:.2}s",
            track.id, progress.position_secs, progress.duration_secs
        );
        backend.pause().await?;
        if backend.get_state().await? != PlaybackState::Paused {
            return Err("Pause failed".into());
        }
        backend.resume().await?;
        let target = progress.duration_secs - 10.0;
        backend.seek(target).await?;
        let after_seek = backend.get_progress().await?;
        if after_seek.position_secs < target - 1.0 {
            return Err("Near-end seek did not advance".into());
        }
        println!(
            "SEEK OK: {} position={:.2}s target={:.2}s",
            track.id, after_seek.position_secs, target
        );
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            let progress = backend.get_progress().await?;
            if progress.state == PlaybackState::Ended {
                println!(
                    "EOF OK: {} position={:.2}s",
                    track.id, progress.position_secs
                );
                break;
            }
            if Instant::now() >= deadline {
                return Err(format!("{}: EOF timed out", track.id).into());
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        backend.stop().await?;
        played += 1;
    }
    if played == 0 {
        return Err("No HLS tracks were decoded".into());
    }
    println!("LIVE SMOKE PASSED: {played} HLS tracks decoded/seeked/ended; {unavailable} explicit resolve errors");
    Ok(())
}
