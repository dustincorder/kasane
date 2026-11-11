package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.tooling.preview.Preview
import cord.kasane.music.model.Track
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    uiState: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    sheetState: SheetState,
    onQueueTrack: (Track) -> Unit = {},
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {}
) {
    val track = uiState.currentTrack ?: return

    var showQueue by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Gray900,
        contentColor = White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Gray700) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Close player") }
                Text("Now playing", modifier = Modifier.weight(1f), style = KasaneTypography.titleMedium)
                IconButton(onClick = { showQueue = !showQueue }) { Icon(Icons.Default.QueueMusic, "Show queue") }
            }
            TrackArtwork(track.thumbnailUri, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
            if (uiState.errorMessage != null) Text(uiState.errorMessage, color = AccentRose)
            if (showQueue) {
                Text("Queue", style = KasaneTypography.titleMedium)
                if (uiState.queue.isEmpty()) Text("The queue is empty", color = Gray400)
                uiState.queue.forEach { queued ->
                    TextButton(onClick = { onQueueTrack(queued) }) {
                        Text(queued.title + " · " + queued.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Metadata row with favorite button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = KasaneTypography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        style = KasaneTypography.titleMedium,
                        color = Gray400,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) AccentRose else Gray400,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scrubber
            val duration = uiState.durationMs
            val position = uiState.currentPositionMs.coerceIn(0, duration.coerceAtLeast(1))
            val progress = if (duration > 0) (position.toFloat() / duration.toFloat()) else 0f

            var scrub by remember(track.id) { mutableStateOf<Float?>(null) }
            Slider(
                value = scrub ?: progress,
                enabled = duration > 0,
                onValueChangeFinished = { scrub?.let { onSeek((it * duration).toLong()) }; scrub = null },
                onValueChange = { frac ->
                    scrub = frac
                },
                colors = SliderDefaults.colors(
                    thumbColor = AccentRose,
                    activeTrackColor = AccentRose,
                    inactiveTrackColor = Gray800
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration((position / 1000).toDouble()),
                    style = KasaneTypography.labelMedium
                )
                Text(
                    text = formatDuration((duration / 1000).toDouble()),
                    style = KasaneTypography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, if (uiState.shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                        tint = if (uiState.shuffleEnabled) AccentRose else Gray400)
                }
                IconButton(onClick = onRepeat) {
                    Icon(if (uiState.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        when (uiState.repeatMode) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat off" },
                        tint = if (uiState.repeatMode != 0) AccentRose else Gray400)
                }
            }
            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrev,
                    enabled = uiState.canSkipPrevious,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = if (uiState.canSkipPrevious) Gray200 else Gray700,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(KasaneCrimson),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(64.dp)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                color = White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onNext,
                    enabled = uiState.canSkipNext,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = if (uiState.canSkipNext) Gray200 else Gray700,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun NowPlayingPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        NowPlayingSheet(PlaybackUiState(currentTrack = Track("preview", "Midnight City Lights", "Aether"), durationMs = 240000, currentPositionMs = 65000),
            {}, {}, {}, {}, {}, false, {}, rememberModalBottomSheetState(skipPartiallyExpanded = true))
    }
}
