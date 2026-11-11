package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import cord.kasane.music.ui.theme.*

@Composable
fun TrackArtwork(uri: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Gray850), contentAlignment = Alignment.Center) {
        if (uri.isNullOrBlank()) {
            ArtworkUnavailable()
        } else {
            // Coil's application-wide ImageLoader shares memory/disk caches across all screens.
            // Keep loading distinct from a missing image; never leave an always-visible note behind it.
            SubcomposeAsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize()) },
                error = { ArtworkUnavailable() }
            )
        }
    }
}

@Composable
private fun ArtworkUnavailable() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = AccentRose)
    }
}
