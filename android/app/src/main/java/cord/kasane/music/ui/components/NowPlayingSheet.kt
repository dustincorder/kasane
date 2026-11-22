package cord.kasane.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline) }
    ) {
        val maxCover = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Close player") }
                Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Show queue") }
            }
            val coverScale by animateFloatAsState(if (uiState.isPlaying || uiState.isLoading) 1f else 0.92f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "cover")
            TrackArtwork(track.thumbnailUri,
                Modifier.widthIn(max = maxCover).fillMaxWidth().aspectRatio(1f).scale(coverScale),
                shape = MaterialTheme.shapes.large)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TrackLikeButton(track, isFavorite, onToggleFavorite, refreshOnEnter = true)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Track options") }
                    TrackContextMenu(track, menuOpen, { menuOpen = false },
                        onPlay = { if (!uiState.isPlaying) onTogglePlay() }, onLocalFavorite = onToggleFavorite, localFavorite = isFavorite)
                }
            }
            uiState.errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
            uiState.noticeMessage?.let {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            val duration = uiState.durationMs
            val position = uiState.currentPositionMs.coerceIn(0, duration.coerceAtLeast(1))
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            var scrub by remember(track.id) { mutableStateOf<Float?>(null) }
            Slider(
                value = scrub ?: progress,
                enabled = duration > 0,
                onValueChangeFinished = { scrub?.let { onSeek((it * duration).toLong()) }; scrub = null },
                onValueChange = { scrub = it },
                thumb = { SliderDefaults.Thumb(remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    thumbSize = DpSize(4.dp, 28.dp)) },
                modifier = Modifier.fillMaxWidth()
            )
            val shown = scrub?.let { (it * duration).toLong() } ?: position
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(shown / 1000.0), style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(duration / 1000.0), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, if (uiState.shuffleEnabled) "Disable shuffle" else "Enable shuffle",
                        tint = if (uiState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onPrev, enabled = uiState.canSkipPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(34.dp))
                }
                val corner by animateDpAsState(if (uiState.isPlaying) 36.dp else 24.dp,
                    spring(stiffness = Spring.StiffnessMediumLow), label = "playShape")
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp), shape = RoundedCornerShape(corner)) {
                    if (uiState.isLoading) CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    else Icon(if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (uiState.isPlaying) "Pause" else "Play", Modifier.size(36.dp))
                }
                IconButton(onClick = onNext, enabled = uiState.canSkipNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next track", Modifier.size(34.dp))
                }
                IconButton(onClick = onRepeat) {
                    Icon(if (uiState.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        when (uiState.repeatMode) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat off" },
                        tint = if (uiState.repeatMode != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showQueue) QueueSheet(uiState, track, onQueueTrack) { showQueue = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(uiState: PlaybackUiState, current: Track, onQueueTrack: (Track) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (uiState.queue.isEmpty()) Text("The queue is empty.", Modifier.padding(20.dp))
        val state = rememberLazyListState(maxOf(uiState.queueIndex - 1, 0))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = state) {
            items(uiState.queue, key = { it.id }) { queued ->
                TrackRow(queued, isPlaying = queued.id == current.id && uiState.isPlaying, onClick = { onQueueTrack(queued) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun NowPlayingPreview() {
    KasaneTheme(dynamicColor = false) {
        NowPlayingSheet(PlaybackUiState(currentTrack = Track("preview", "Midnight City Lights", "Aether"), durationMs = 240000,
            currentPositionMs = 65000, isPlaying = true, canSkipNext = true),
            {}, {}, {}, {}, {}, false, {}, rememberModalBottomSheetState(skipPartiallyExpanded = true))
    }
}
