package cord.kasane.music.provider

import cord.kasane.music.model.Track

class MockMusicProvider : MusicProvider {
    private val mockTracks = listOf(
        Track(
            id = "mock-1",
            title = "Midnight City Lights",
            artist = "Aether",
            album = "Neon Drift",
            durationSeconds = 214.0,
            thumbnailUri = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=500&q=80",
            streamUri = "https://commondatastorage.googleapis.com/codeskulptor-demos/DDR_assets/Kangaroo_MusiQue_-_The_Neverwritten_Role_Playing_Game.mp3",
            sourceProvider = "mock"
        ),
        Track(
            id = "mock-2",
            title = "Subway Melancholy",
            artist = "Dustin Corder",
            album = "Echoes of Silence",
            durationSeconds = 188.0,
            thumbnailUri = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=500&q=80",
            streamUri = "https://commondatastorage.googleapis.com/codeskulptor-demos/DDR_assets/Sevish_-__nbsp_.mp3",
            sourceProvider = "mock"
        ),
        Track(
            id = "mock-3",
            title = "Synthetic Dawn",
            artist = "Kuro",
            album = "Genesis",
            durationSeconds = 245.0,
            thumbnailUri = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=500&q=80",
            streamUri = "https://commondatastorage.googleapis.com/codeskulptor-assets/Epoq-Lepidoptera.ogg",
            sourceProvider = "mock"
        )
    )

    override suspend fun search(query: String, limit: Int): List<Track> {
        return mockTracks.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }.ifEmpty { mockTracks }.take(limit)
    }

    override suspend fun resolveStreamUrl(trackId: String): String? {
        return mockTracks.find { it.id == trackId }?.streamUri
    }

    override suspend fun getHomeRecommendations(limit: Int): List<Track> {
        return mockTracks.take(limit)
    }
}
