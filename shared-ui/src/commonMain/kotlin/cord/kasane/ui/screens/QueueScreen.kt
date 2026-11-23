package cord.kasane.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import cord.kasane.ui.components.MenuEntry
import cord.kasane.ui.components.TrackListItem
import cord.kasane.ui.theme.labelMediumMuted
import cord.kasane.ui.theme.bodyMediumMuted
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What the queue screen renders; both platforms project their player into this. */
data class QueueUiState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    /** Queue indices in the order they will actually play after the current item (follows shuffle). */
    val upcomingOrder: List<Int> = emptyList(),
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val shuffle: Boolean = false,
)

/**
 * The queue as the player really plays it: the current track, then "Up next" in true play order, then what
 * already played. Reordering is only offered when shuffle is off (with shuffle the order is not the list order);
 * removing and the Move up / Move down menu entries work in every mode, so no action needs a drag gesture.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueContent(
    state: QueueUiState,
    onPlayIndex: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(state.queue.size, state.currentIndex, state.upcomingOrder) {
        QueuePolicy.sections(state.queue.size, state.currentIndex, state.upcomingOrder)
    }
    val reorderable = !state.shuffle
    var upNext by remember(sections.upNext, state.queue.map { it.id }) { mutableStateOf(sections.upNext) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var showPlayed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val headerCount = 3
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - headerCount; val t = to.index - headerCount
        if (f in upNext.indices && t in upNext.indices) upNext = upNext.toMutableList().apply { add(t, removeAt(f)) }
    }
    val haptics = LocalHapticFeedback.current
    val idsUnique = remember(state.queue) { state.queue.map { it.id }.toSet().size == state.queue.size }
    fun key(prefix: String, queueIndex: Int) = if (idsUnique) prefix + state.queue[queueIndex].id else "$prefix#$queueIndex"
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(if (state.shuffle) "Shuffle on" else "${state.queue.size} tracks", style = MaterialTheme.typography.labelMediumMuted)
        }
        if (state.queue.isEmpty()) {
            Text("Nothing is queued. Play a song and Kasane builds a radio from it.", style = MaterialTheme.typography.bodyMediumMuted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "h-now") { SectionLabel("Now playing") }
            item(key = "current") {
                val current = state.queue.getOrNull(sections.currentIndex)
                if (current != null) TrackListItem(current, onClick = { onPlayIndex(sections.currentIndex) }, isPlaying = state.isPlaying, isLoading = state.isLoading,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)))
            }
            item(key = "h-up") { SectionLabel(if (upNext.isEmpty()) "Up next · nothing yet" else "Up next") }
            itemsIndexed(upNext, key = { _, queueIndex -> key("q:", queueIndex) }) { position, queueIndex ->
                val track = state.queue[queueIndex]
                ReorderableItem(reorderState, key = key("q:", queueIndex)) { dragging ->
                    val menu = buildList {
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
                        TrackListItem(track, onClick = { onPlayIndex(queueIndex) }, menu = menu,
                            modifier = Modifier.background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface),
                            trailing = if (reorderable) ({
                                Icon(Icons.Default.DragHandle, "Reorder ${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp).padding(12.dp).draggableHandle(
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
                    TrackListItem(state.queue[queueIndex], onClick = { onPlayIndex(queueIndex) },
                        menu = listOf(MenuEntry("Remove from queue") { onRemove(queueIndex) }))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
}
