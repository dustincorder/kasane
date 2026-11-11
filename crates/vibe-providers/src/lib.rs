pub mod mock;
pub mod ytmusic;

pub use mock::MockProvider;
pub use ytmusic::YouTubeMusicProvider;

#[cfg(test)]
mod tests {
    use super::*;
    use vibe_core::traits::MusicProvider;

    #[tokio::test]
    async fn test_mock_provider_search() {
        let provider = MockProvider::new();
        let results = provider.search("synthwave", 5).await.unwrap();
        assert!(!results.is_empty());
        assert_eq!(results[0].title, "Midnight Drive");
    }

    #[tokio::test]
    async fn test_mock_provider_resolve() {
        let provider = MockProvider::new();
        let track = provider.get_track_details("mock-1").await.unwrap();
        let source = provider.resolve_stream(&track).await.unwrap();
        assert!(source.url.contains("mock-1"));
    }
}
