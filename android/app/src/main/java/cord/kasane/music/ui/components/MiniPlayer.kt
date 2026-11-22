package cord.kasane.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.theme.*

@Composable
fun MiniPlayer(
    uiState: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack
    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (track == null) return@AnimatedVisibility
        val shape = MaterialTheme.shapes.large
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClickLabel = "Open player", onClick = onOpenNowPlaying)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackArtwork(track.thumbnailUri, Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp)) {
                        if (uiState.isLoading) CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(
                            if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (uiState.isPlaying) "Pause" else "Play", Modifier.size(22.dp))
                    }
                    IconButton(onClick = onNext, enabled = uiState.canSkipNext) {
                        Icon(Icons.Default.SkipNext, "Next track", Modifier.size(24.dp))
                    }
                }
                val progress = if (uiState.durationMs > 0) (uiState.currentPositionMs.toFloat() / uiState.durationMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Preview(name = "MiniPlayer Playing", showBackground = true, backgroundColor = 0xFF0F1012)
@Composable
fun MiniPlayerPreview() {
    KasaneTheme(dynamicColor = false) {
        MiniPlayer(
            uiState = PlaybackUiState(
                currentTrack = Track(id = "preview-1", title = "Nice Boys", artist = "TEMPOREX", album = "Care", durationSeconds = 181.0),
                isPlaying = true, currentPositionMs = 64000, durationMs = 181000, canSkipNext = true),
            onTogglePlay = {}, onNext = {}, onPrev = {}, onOpenNowPlaying = {})
    }
}
