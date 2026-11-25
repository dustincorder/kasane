package cord.kasane.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
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
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.desktop.playback.RepeatMode
import cord.kasane.music.model.Track
import cord.kasane.music.playback.QueuePolicy
import cord.kasane.music.provider.CloudLikeState
import cord.kasane.ui.components.KasaneSlider
import cord.kasane.ui.components.MenuEntry
import kotlinx.coroutines.launch

/** Everything the player surfaces need; routes to local playback or, later, a Connect target. */
interface PlayerControls {
    fun toggle(); fun next(); fun previous(); fun seek(sec: Double); fun setVolume(v: Float)
    fun toggleShuffle(); fun cycleRepeat(); fun playIndex(i: Int); fun remove(i: Int); fun move(from: Int, to: Int)
}

@Composable
private fun SeekBar(
    state: PlayerState,
    controls: PlayerControls,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationSec.coerceAtLeast(0.0)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            formatTime(dragging?.toDouble() ?: state.positionSec),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
        KasaneSlider(
            value = (dragging ?: state.positionSec.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(0.01f)),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { controls.seek(it.toDouble()) }; dragging = null },
            valueRange = 0f..duration.toFloat().coerceAtLeast(0.01f),
            enabled = duration > 0,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatTime(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
private fun TransportButtons(
    state: PlayerState,
    controls: PlayerControls,
    playSize: Dp = 40.dp,
    compact: Boolean = false,
    large: Boolean = false,
) {
    val sideButtonSize = if (large) 44.dp else 34.dp
    val skipButtonSize = if (large) 48.dp else 36.dp
    val sideIconSize = if (large) 22.dp else 18.dp
    val skipIconSize = if (large) 28.dp else 22.dp
    val playIconSize = if (large) 30.dp else 22.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 6.dp),
    ) {
        if (!compact) {
            IconButton(onClick = controls::toggleShuffle, modifier = Modifier.size(sideButtonSize)) {
                Icon(
                    Icons.Filled.Shuffle,
                    if (state.shuffle) "Shuffle on" else "Shuffle off",
                    modifier = Modifier.size(sideIconSize),
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = controls::previous,
            enabled = state.current != null,
            modifier = Modifier.size(skipButtonSize),
        ) {
            Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(skipIconSize))
        }
        FilledIconButton(
            onClick = controls::toggle,
            enabled = state.current != null,
            modifier = Modifier.size(playSize),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    Modifier.size(playSize * 0.48f),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(playIconSize),
                )
            }
        }
        IconButton(
            onClick = controls::next,
            enabled = state.canNext,
            modifier = Modifier.size(skipButtonSize),
        ) {
            Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(skipIconSize))
        }
        if (!compact) {
            IconButton(onClick = controls::cycleRepeat, modifier = Modifier.size(sideButtonSize)) {
                Icon(
                    if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat ${state.repeat.name.lowercase()}",
                    modifier = Modifier.size(sideIconSize),
                    tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Bottom bar: track on the left, transport in the middle, queue/device/volume on the right. */
@Composable
fun PlayerBar(
    state: PlayerState,
    controls: PlayerControls,
    actions: Actions,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    onExpand: () -> Unit,
    deviceButton: @Composable () -> Unit,
    deviceLabel: String?,
    lyricsOpen: Boolean = false,
    onToggleLyrics: () -> Unit = {},
) {
    var preMuteVolume by remember { mutableFloatStateOf(0.8f) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 960.dp
                Row(
                    Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val track = state.current
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (track != null) {
                            Row(
                                Modifier
                                    .weight(1f, fill = false)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClick = onExpand)
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(track.thumbnailUri, 48.dp, MaterialTheme.shapes.small)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.widthIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            LikeButton(track, actions)
                        } else {
                            Text("Nothing playing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Column(
                        Modifier.weight(if (compact) 2.2f else 1.4f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TransportButtons(state, controls, playSize = 38.dp, compact = compact)
                        SeekBar(
                            state = state,
                            controls = controls,
                            compact = true,
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                        )
                    }

                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (deviceLabel != null && !compact) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                Text(
                                    deviceLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 140.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (lyricsOpen) {
                            FilledTonalIconButton(onClick = onToggleLyrics, enabled = track != null, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Lyrics, "Close lyrics", modifier = Modifier.size(20.dp))
                            }
                        } else {
                            IconButton(onClick = onToggleLyrics, enabled = track != null, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Outlined.Lyrics,
                                    "Show lyrics",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (queueOpen) {
                            FilledTonalIconButton(onClick = onToggleQueue, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Close queue", modifier = Modifier.size(20.dp))
                            }
                        } else {
                            IconButton(onClick = onToggleQueue, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    "Open queue",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        deviceButton()
                        if (!compact) {
                            var volumeDrag by remember { mutableStateOf<Float?>(null) }
                            val currentVol = volumeDrag ?: state.volume
                            IconButton(
                                onClick = {
                                    if (currentVol > 0.01f) {
                                        preMuteVolume = currentVol
                                        controls.setVolume(0f)
                                    } else {
                                        controls.setVolume(preMuteVolume.coerceAtLeast(0.2f))
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    when {
                                        currentVol <= 0.01f -> Icons.AutoMirrored.Outlined.VolumeOff
                                        currentVol < 0.5f -> Icons.AutoMirrored.Outlined.VolumeDown
                                        else -> Icons.AutoMirrored.Outlined.VolumeUp
                                    },
                                    if (currentVol <= 0.01f) "Unmute" else "Mute",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            KasaneSlider(
                                value = currentVol,
                                compact = true,
                                onValueChange = { volumeDrag = it; controls.setVolume(it) },
                                onValueChangeFinished = { volumeDrag = null },
                                modifier = Modifier.width(96.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(
                            onClick = onExpand,
                            enabled = track != null,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Outlined.OpenInFull,
                                "Expand Now Playing",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.error != null && state.current != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Can't play this song.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { controls.playIndex(state.index) }) { Text("Retry") }
                }
            }
        }
    }
}

/** The YouTube Music heart: only for signed-in YouTube tracks; hidden for guests and local files. */
@Composable
private fun LikeButton(track: Track, actions: Actions) {
    if (!actions.likesEnabled || track.isLocal || track.sourceProvider != "youtube") return
    val state = actions.likeState(track.id)
    val liked = state == CloudLikeState.LIKED
    IconButton(
        onClick = { actions.toggleLike(track) },
        enabled = !actions.isLikePending(track.id) && state != CloudLikeState.LOADING,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            if (liked) "Remove YouTube Music like" else "Like on YouTube Music",
            modifier = Modifier.size(20.dp),
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The queue in real play order — Now playing, Up next, Played — via the shared [QueuePolicy].
 * Rows carry both queue operations and the full track menu; reordering is offered as menu moves.
 */
@Composable
fun QueueList(
    state: PlayerState,
    controls: PlayerControls,
    actions: Actions,
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable () -> Unit)? = null,
) {
    val sections = remember(state.queue.size, state.index) {
        QueuePolicy.sections(
            state.queue.size,
            state.index,
            if (state.index >= 0) (state.index + 1 until state.queue.size).toList() else emptyList(),
        )
    }
    var showPlayed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = if (headerTrailing != null) 8.dp else 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                if (state.shuffle) "Shuffle on" else "${state.queue.size} tracks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = if (headerTrailing != null) 4.dp else 0.dp),
            )
            headerTrailing?.invoke()
        }
        if (state.queue.isEmpty()) {
            Text(
                "Nothing is queued. Play a song and Kasane builds a radio from it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "h-now") {
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item(key = "current") {
                val current = state.queue.getOrNull(sections.currentIndex)
                if (current != null) {
                    Box(Modifier.padding(horizontal = 6.dp)) {
                        TrackRow(current, actions, current = true, onPlay = {})
                    }
                }
            }
            item(key = "h-up") {
                Text(
                    if (sections.upNext.isEmpty()) "Up next · nothing yet" else "Up next",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            itemsIndexed(sections.upNext, key = { pos, qi -> "q:${state.queue[qi].id}#$pos" }) { position, queueIndex ->
                val track = state.queue[queueIndex]
                val queueOps = buildList {
                    if (!state.shuffle) {
                        if (position > 0) add(MenuEntry("Move up", icon = Icons.Outlined.ArrowUpward, group = 0) { controls.move(queueIndex, queueIndex - 1) })
                        if (position < sections.upNext.lastIndex) add(MenuEntry("Move down", icon = Icons.Outlined.ArrowDownward, group = 0) { controls.move(queueIndex, queueIndex + 1) })
                    }
                    add(MenuEntry("Remove from queue", icon = Icons.Outlined.RemoveCircleOutline, group = 0) { controls.remove(queueIndex) })
                }
                Box(Modifier.padding(horizontal = 6.dp)) {
                    TrackRow(track, actions, menuExtras = queueOps, onPlay = { controls.playIndex(queueIndex) })
                }
            }
            if (sections.played.isNotEmpty()) {
                item(key = "h-played") {
                    Row(
                        Modifier.fillMaxWidth().clickable { showPlayed = !showPlayed }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Played · ${sections.played.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Icon(
                            if (showPlayed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            if (showPlayed) "Hide played" else "Show played",
                        )
                    }
                }
                if (showPlayed) itemsIndexed(sections.played, key = { pos, qi -> "p:${state.queue[qi].id}#$pos" }) { _, queueIndex ->
                    Box(Modifier.padding(horizontal = 6.dp)) {
                        TrackRow(
                            state.queue[queueIndex],
                            actions,
                            menuExtras = listOf(MenuEntry("Remove from queue", icon = Icons.Outlined.RemoveCircleOutline, group = 0) { controls.remove(queueIndex) }),
                            onPlay = { controls.playIndex(queueIndex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueuePanel(state: PlayerState, controls: PlayerControls, actions: Actions, onClose: () -> Unit) {
    Row(Modifier.fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(368.dp).fillMaxHeight()) {
            QueueList(
                state = state,
                controls = controls,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
                headerTrailing = {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "Close queue", modifier = Modifier.size(20.dp))
                    }
                },
            )
        }
    }
}

/** Full-window player: large artwork with metadata and controls aligned to artwork width, queue beside it when wide. */
@Composable
fun NowPlaying(
    state: PlayerState,
    controls: PlayerControls,
    actions: Actions,
    onClose: () -> Unit,
    deviceButton: @Composable () -> Unit,
    onShowLyrics: () -> Unit = {},
) {
    val track = state.current
    LaunchedEffect(track?.id) {
        if (track != null) actions.loadLinks(track)
    }
    // Pull the player down to close it — the same gesture the Android bottom-sheet player uses.
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    val dismissThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { translationY = dismissOffset.value }
            .draggable(
                rememberDraggableState { delta ->
                    scope.launch { dismissOffset.snapTo((dismissOffset.value + delta).coerceAtLeast(0f)) }
                },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dismissOffset.value > dismissThreshold || velocity > 1400f) onClose()
                    else scope.launch { dismissOffset.animateTo(0f, spring()) }
                },
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
            val wide = maxWidth > 980.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close player") }
                        Spacer(Modifier.width(4.dp))
                        Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onShowLyrics, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Lyrics,
                                "Show lyrics",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        deviceButton()
                    }
                    if (track == null) return@Column
                    val art = minOf(
                        this@BoxWithConstraints.maxHeight - 252.dp,
                        if (wide) this@BoxWithConstraints.maxWidth * 0.36f else this@BoxWithConstraints.maxWidth - 64.dp,
                    ).coerceIn(220.dp, 400.dp)
                    val contentWidth = art.coerceAtLeast(320.dp)

                    Spacer(Modifier.weight(1f))
                    Artwork(track.thumbnailUri, contentWidth, MaterialTheme.shapes.large)
                    Spacer(Modifier.height(24.dp))

                    Column(Modifier.width(contentWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val links = actions.linksFor(track.id)
                        TrackMenu(track, actions) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val artistLink = links?.artist
                                        val albumLink = links?.album
                                        val albumName = track.album?.takeIf(String::isNotBlank) ?: albumLink?.title
                                        Text(
                                            track.artist,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (artistLink != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .then(
                                                    if (artistLink != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { onClose(); actions.open(artistLink) }
                                                    else Modifier
                                                ),
                                        )
                                        if (!albumName.isNullOrBlank()) {
                                            Text(" · ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                albumName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (albumLink != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .weight(1f, fill = false)
                                                    .then(
                                                        if (albumLink != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable { onClose(); actions.open(albumLink) }
                                                        else Modifier
                                                    ),
                                            )
                                        }
                                    }
                                }
                                LikeButton(track, actions)
                                var menuOpen by remember { mutableStateOf(false) }
                                LaunchedEffect(menuOpen) { if (menuOpen) actions.loadLinks(track) }
                                Box {
                                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(
                                            Icons.Outlined.MoreVert,
                                            "More options for ${track.title}",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TrackDropdownMenu(
                                        entries = trackMenuItems(track, actions, includePlay = false),
                                        expanded = menuOpen,
                                        onDismiss = { menuOpen = false },
                                    )
                                }
                            }
                        }

                        state.error?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Can't play this song.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { controls.playIndex(state.index) }) { Text("Retry") }
                            }
                        }

                        SeekBar(state = state, controls = controls, compact = false, modifier = Modifier.fillMaxWidth())

                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TransportButtons(state, controls, playSize = 60.dp, large = true)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
                if (wide) {
                    Spacer(Modifier.width(24.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.width(400.dp).fillMaxHeight(),
                    ) {
                        QueueList(state, controls, actions, Modifier.fillMaxSize().padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
