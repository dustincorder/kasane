package cord.kasane.music.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import cord.kasane.music.model.*
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.theme.*

/**
 * Debug/preview only. Original JPEGs fetched from the real URLs on 2026-11-11.
 * Coil 2 skips requests in inspection mode, so seed its memory cache with the
 * downloaded bytes and render through the same production TrackArtwork component.
 * This proves display/layout; the separate guest probe verifies the actual HTTP path.
 */
private val searchTrack = Track("artwork-preview-search", "Let Down", "Radiohead • OK Computer OKNOTOK 1997 2017 • 5:00", thumbnailUri = "https://yt3.googleusercontent.com/gdMTA2cTkNFr6COYNygeVpgIpxR2yNg5MjaX02OQqmsU6NlymcR2mqWR0UZe2HaBYVSTHU8lIQamafQC=w120-h120-l90-rj")
private val homeTrack = Track("artwork-preview-home", "Beat It", "Michael Jackson • 2.2B plays", thumbnailUri = "https://yt3.googleusercontent.com/URvHCfI2iyGAlAwqqBFeaFhU9DeKk_iuX40OIIIj8Zp0wIT3BVsJ2JRMwLLbUB9EZS7t7oDlMrI2S3OvGA=w120-h120-l90-rj")

@Composable
private fun WithRealArtwork(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val loaders = remember(context) {
        val previous = Coil.imageLoader(context)
        val cache = MemoryCache.Builder(context).maxSizeBytes(4 * 1024 * 1024).build()
        listOf(searchTrack.thumbnailUri!! to "search.jpg", homeTrack.thumbnailUri!! to "home.jpg").forEach { (url, asset) ->
            val bitmap = context.assets.open("artwork-previews/$asset").use { BitmapFactory.decodeStream(it) }
            checkNotNull(bitmap)
            cache[MemoryCache.Key(url)] = MemoryCache.Value(bitmap)
        }
        val preview = ImageLoader.Builder(context).memoryCache(cache).build()
        Coil.setImageLoader(preview)
        previous to preview
    }
    DisposableEffect(loaders) {
        onDispose { Coil.setImageLoader(loaders.first); loaders.second.shutdown() }
    }
    CompositionLocalProvider(LocalInspectionMode provides false) {
        MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
            Surface(color = Gray950, content = content)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 620)
@Composable
fun HomeRealArtworkPreview() = WithRealArtwork {
    HomeScreen(null, false, false, emptyList(), emptyList(), emptySet(), emptyList(), false,
        {}, {}, {}, {}, catalogShelves = listOf(CatalogShelf("Quick picks", listOf(
            CatalogItem(homeTrack.id, CatalogKind.SONG, homeTrack.title, homeTrack.artist, homeTrack.thumbnailUri),
            CatalogItem(searchTrack.id, CatalogKind.SONG, searchTrack.title, searchTrack.artist, searchTrack.thumbnailUri)))))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun SearchRealArtworkPreview() = WithRealArtwork {
    Column {
        Text("Search · Songs", style = KasaneTypography.titleLarge, modifier = Modifier.padding(16.dp))
        TrackRow(searchTrack, onClick = {})
        TrackRow(homeTrack, onClick = {})
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun MiniPlayerRealArtworkPreview() = WithRealArtwork {
    MiniPlayer(PlaybackUiState(currentTrack = searchTrack, isPlaying = true,
        currentPositionMs = 45000, durationMs = 180000), {}, {}, {}, {})
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun NowPlayingRealArtworkPreview() = WithRealArtwork {
    NowPlayingSheet(PlaybackUiState(currentTrack = homeTrack, currentPositionMs = 45000, durationMs = 180000),
        {}, {}, {}, {}, {}, false, {}, rememberModalBottomSheetState(skipPartiallyExpanded = true))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
fun MissingAndFailedArtworkPreview() = WithRealArtwork {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TrackArtwork(null, Modifier.size(96.dp))
        TrackArtwork("unsupported://unavailable", Modifier.size(96.dp))
    }
}
