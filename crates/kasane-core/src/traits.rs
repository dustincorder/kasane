use async_trait::async_trait;
use crate::error::Result;
use crate::model::{AudioFeatures, PlaybackProgress, PlaybackState, StreamSource, Track};

#[async_trait]
pub trait MusicProvider: Send + Sync {
    fn provider_name(&self) -> &'static str;
    async fn search(&self, query: &str, limit: usize) -> Result<Vec<Track>>;
    async fn get_track_details(&self, track_id: &str) -> Result<Track>;
    async fn resolve_stream(&self, track: &Track) -> Result<StreamSource>;
}

#[async_trait]
pub trait PlaybackBackend: Send + Sync {
    async fn play_stream(&mut self, source: &StreamSource) -> Result<()>;
    async fn pause(&mut self) -> Result<()>;
    async fn resume(&mut self) -> Result<()>;
    async fn stop(&mut self) -> Result<()>;
    async fn seek(&mut self, position_secs: f64) -> Result<()>;
    async fn set_volume(&mut self, volume: f32) -> Result<()>;
    async fn get_progress(&self) -> Result<PlaybackProgress>;
    async fn get_state(&self) -> Result<PlaybackState>;
}

#[async_trait]
pub trait AudioAnalyzer: Send + Sync {
    async fn analyze_track(&self, track: &Track, source: &StreamSource) -> Result<AudioFeatures>;
}

#[async_trait]
pub trait AIProvider: Send + Sync {
    fn provider_name(&self) -> &'static str;
    async fn recommend_next(
        &self,
        current_track: &Track,
        history: &[Track],
        context: &str,
    ) -> Result<Vec<Track>>;
    async fn analyze_vibe(&self, tracks: &[Track]) -> Result<String>;
}
