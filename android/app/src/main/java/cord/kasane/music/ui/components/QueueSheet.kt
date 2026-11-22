package cord.kasane.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.playback.QueuePolicy
import cord.kasane.music.ui.theme.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The queue as the player really plays it: the current track, then "Up next" in true play order, then what
 * already played. Reordering is only offered when shuffle is off (with shuffle the order is not the list order);
 * removing and the Move up / Move down menu entries work in every mode, so no action needs a drag gesture.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QueueSheet(
    state: PlaybackUiState,
    onPlayIndex: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        QueueContent(state, onPlayIndex, onMove, onRemove)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueContent(state: PlaybackUiState, onPlayIndex: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit) {
    val sections = remember(state.queue.size, state.queueIndex, state.upcomingOrder) {
        QueuePolicy.sections(state.queue.size, state.queueIndex, state.upcomingOrder)
    }
    val reorderable = !state.shuffleEnabled
    // Local order for the items being dragged; resynchronised whenever the real queue changes.
    var upNext by remember(sections.upNext, state.queue.map { it.id }) { mutableStateOf(sections.upNext) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var showPlayed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Header rows sit before the draggable rows: "Now playing" label + current + "Up next" label.
    val headerCount = 3
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - headerCount; val t = to.index - headerCount
        if (f in upNext.indices && t in upNext.indices) upNext = upNext.toMutableList().apply { add(t, removeAt(f)) }
    }
    val haptics = LocalHapticFeedback.current
    // Ids are stable keys for drag-and-drop; fall back to positions if a queue ever contains a duplicate.
    val idsUnique = remember(state.queue) { state.queue.map { it.id }.toSet().size == state.queue.size }
    fun key(prefix: String, queueIndex: Int) = if (idsUnique) prefix + state.queue[queueIndex].id else "$prefix#$queueIndex"
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(if (state.shuffleEnabled) "Shuffle on" else "${state.queue.size} tracks", style = MaterialTheme.typography.labelMediumMuted)
        }
        if (state.queue.isEmpty()) {
            Text("Nothing is queued. Play a song and Kasane builds a radio from it.", style = MaterialTheme.typography.bodyMediumMuted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "h-now") { SectionLabel("Now playing") }
            item(key = "current") {
                val current = state.queue.getOrNull(sections.currentIndex)
                if (current != null) TrackRow(current, isPlaying = state.isPlaying, isLoading = state.isLoading, onClick = { onPlayIndex(sections.currentIndex) },
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)))
            }
            item(key = "h-up") { SectionLabel(if (upNext.isEmpty()) "Up next · nothing yet" else "Up next") }
            itemsIndexed(upNext, key = { _, queueIndex -> key("q:", queueIndex) }) { position, queueIndex ->
                val track = state.queue[queueIndex]
                ReorderableItem(reorderState, key = key("q:", queueIndex)) { dragging ->
                    val moveEntries = buildList {
                        if (reorderable && position > 0) add(MenuEntry("Move up") { onMove(queueIndex, queueIndex - 1) })
                        if (reorderable && position < upNext.lastIndex) add(MenuEntry("Move down") { onMove(queueIndex, queueIndex + 1) })
                        add(MenuEntry("Remove from queue") { onRemove(queueIndex) })
                    }
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onRemove(queueIndex); true } else false
                    })
                    SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                            Text("Remove", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }) {
                        TrackRow(track, onClick = { onPlayIndex(queueIndex) }, extraMenu = moveEntries,
                            modifier = Modifier.background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                            trailing = if (reorderable) ({
                                Icon(Icons.Default.DragHandle, "Reorder ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp).padding(8.dp).draggableHandle(
                                        onDragStarted = { dragStartIndex = queueIndex; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragStopped = {
                                            val start = dragStartIndex; dragStartIndex = null
                                            val now = upNext.indexOf(queueIndex)
                                            // Queue indices after the current item are contiguous in play order when shuffle is off.
                                            if (start != null && now >= 0) onMove(start, sections.currentIndex + 1 + now)
                                        }))
                            }) else null)
                    }
                }
            }
            if (sections.played.isNotEmpty()) {
                item(key = "h-played") {
                    Row(Modifier.fillMaxWidth().clickable { showPlayed = !showPlayed }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Played · ${sections.played.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Icon(if (showPlayed) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (showPlayed) "Hide played" else "Show played")
                    }
                }
                if (showPlayed) itemsIndexed(sections.played, key = { _, i -> key("p:", i) }) { _, queueIndex ->
                    TrackRow(state.queue[queueIndex], onClick = { onPlayIndex(queueIndex) },
                        extraMenu = listOf(MenuEntry("Remove from queue") { onRemove(queueIndex) }))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
fun QueuePreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            val tracks = listOf("Intro", "Midnight City Lights", "Subway Melancholy with a very long title that has to truncate", "Synthetic Dawn", "Neon Drift", "Echoes").mapIndexed { i, t -> Track("track$i", t, "Artist $i", durationSeconds = 180.0 + i * 7) }
            QueueContent(PlaybackUiState(currentTrack = tracks[2], queue = tracks, queueIndex = 2, isPlaying = true, upcomingOrder = listOf(3, 4, 5)), {}, { _, _ -> }, {})
        }
    }
}
