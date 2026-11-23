package cord.kasane.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.desktop.playback.RepeatMode

/** Everything the player surfaces need; routes to local playback or, later, a Connect target. */
interface PlayerControls {
    fun toggle(); fun next(); fun previous(); fun seek(sec: Double); fun setVolume(v: Float)
    fun toggleShuffle(); fun cycleRepeat(); fun playIndex(i: Int); fun remove(i: Int); fun move(from: Int, to: Int)
}

@Composable
private fun SeekBar(state: PlayerState, controls: PlayerControls, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationSec.coerceAtLeast(0.0)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(dragging?.toDouble() ?: state.positionSec), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
        Slider(value = (dragging ?: state.positionSec.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(0.01f)),
            onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { controls.seek(it.toDouble()) }; dragging = null },
            valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f), enabled = duration > 0, modifier = Modifier.weight(1f))
        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp).padding(start = 8.dp))
    }
}

@Composable
private fun TransportButtons(state: PlayerState, controls: PlayerControls, playSize: Dp = 44.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = controls::toggleShuffle) {
            Icon(Icons.Filled.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = controls::previous, enabled = state.current != null) { Icon(Icons.Filled.SkipPrevious, "Previous") }
        FilledIconButton(onClick = controls::toggle, enabled = state.current != null, modifier = Modifier.size(playSize)) {
            if (state.isLoading) CircularProgressIndicator(Modifier.size(playSize / 2), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "Pause" else "Play")
        }
        IconButton(onClick = controls::next, enabled = state.canNext) { Icon(Icons.Filled.SkipNext, "Next") }
        IconButton(onClick = controls::cycleRepeat) {
            Icon(if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat ${state.repeat.name.lowercase()}",
                tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bottom bar: track on the left, transport in the middle, queue/device/volume on the right. */
@Composable
fun PlayerBar(
    state: PlayerState, controls: PlayerControls, actions: Actions, queueOpen: Boolean,
    onToggleQueue: () -> Unit, onExpand: () -> Unit, deviceButton: @Composable () -> Unit, deviceLabel: String?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column {
            if (deviceLabel != null) Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(deviceLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.CenterEnd))
            }
            Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val track = state.current
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (track != null) {
                        Row(Modifier.weight(1f, fill = false).clip(MaterialTheme.shapes.small).clickable(onClick = onExpand).padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Artwork(track.thumbnailUri, 56.dp, MaterialTheme.shapes.small)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.widthIn(max = 260.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state.error ?: track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val liked = actions.isFavorite(track.id)
                        IconButton(onClick = { actions.toggleFavorite(track) }) {
                            Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Unlike" else "Like",
                                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Text("Nothing playing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TransportButtons(state, controls)
                    SeekBar(state, controls, Modifier.widthIn(max = 640.dp).fillMaxWidth())
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = if (queueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    deviceButton()
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    var volume by remember { mutableStateOf<Float?>(null) }
                    Slider(value = volume ?: state.volume, onValueChange = { volume = it; controls.setVolume(it) },
                        onValueChangeFinished = { volume = null }, modifier = Modifier.width(120.dp))
                    IconButton(onClick = onExpand, enabled = track != null) { Icon(Icons.Outlined.OpenInFull, "Now playing") }
                }
            }
        }
    }
}

/** Up next and history; click plays, the arrows reorder, × removes. */
@Composable
fun QueueList(state: PlayerState, controls: PlayerControls, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) { if (state.index >= 0) listState.animateScrollToItem((state.index - 1).coerceAtLeast(0)) }
    if (state.queue.isEmpty()) { Message("The queue is empty.", modifier); return }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(8.dp)) {
        itemsIndexed(state.queue, key = { i, t -> "$i:${t.id}" }) { i, t ->
            val current = i == state.index
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable { controls.playIndex(i) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.thumbnailUri, 40.dp, MaterialTheme.shapes.extraSmall)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i < state.index) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!current) {
                    IconButton(onClick = { controls.move(i, i - 1) }, enabled = i > 0, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up", Modifier.size(18.dp)) }
                    IconButton(onClick = { controls.move(i, i + 1) }, enabled = i < state.queue.lastIndex, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down", Modifier.size(18.dp)) }
                    IconButton(onClick = { controls.remove(i) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
fun QueuePanel(state: PlayerState, controls: PlayerControls, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(360.dp).fillMaxHeight()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${state.queue.size} tracks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close queue") }
            }
            HorizontalDivider()
            QueueList(state, controls, Modifier.fillMaxSize())
        }
    }
}

/** Full-window player: large artwork with metadata and controls, queue beside it when there is room. */
@Composable
fun NowPlaying(state: PlayerState, controls: PlayerControls, actions: Actions, onClose: () -> Unit, deviceButton: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
            val wide = maxWidth > 980.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close player") }
                        Text("Now playing", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        deviceButton()
                    }
                    val track = state.current ?: return@Column
                    val art = minOf(this@BoxWithConstraints.maxHeight - 260.dp, if (wide) this@BoxWithConstraints.maxWidth * 0.42f else this@BoxWithConstraints.maxWidth - 48.dp).coerceIn(160.dp, 560.dp)
                    Spacer(Modifier.weight(1f))
                    Artwork(track.thumbnailUri, art, MaterialTheme.shapes.large)
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.width(art.coerceAtLeast(420.dp))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(track.artist, track.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val liked = actions.isFavorite(track.id)
                            IconButton(onClick = { actions.toggleFavorite(track) }) {
                                Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Unlike" else "Like",
                                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        SeekBar(state, controls, Modifier.fillMaxWidth().padding(top = 8.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TransportButtons(state, controls, playSize = 64.dp) }
                    }
                    Spacer(Modifier.weight(1f))
                }
                if (wide) {
                    Spacer(Modifier.width(24.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.width(400.dp).fillMaxHeight()) {
                        Column {
                            Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                            QueueList(state, controls, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

