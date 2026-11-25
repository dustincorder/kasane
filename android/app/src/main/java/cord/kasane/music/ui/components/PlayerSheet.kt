package cord.kasane.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import cord.kasane.music.model.Track
import cord.kasane.music.playback.PlaybackUiState
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Expansion of the player: 0 = mini-player docked above the navigation bar, 1 = full screen.
 * It is one continuous value driven by dragging, tapping or Back, so the transition is always interruptible
 * and the cover is a single element that grows from the mini-player thumbnail to the large artwork.
 */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    /** Bounded: a spring or a fling can never push the sheet outside 0..1 (overshoot produced negative padding). */
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }
    val isExpanded: Boolean get() = progress.targetValue >= 0.5f
    val isVisibleExpanded: Boolean get() = progress.value > 0.02f
    private val spec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun expand() { scope.launch { progress.animateTo(1f, spec) } }
    fun collapse() { scope.launch { progress.animateTo(0f, spec) } }
    internal suspend fun dragTo(value: Float) { progress.snapTo(value.coerceIn(0f, 1f)) }
    internal suspend fun settle(velocityFraction: Float) {
        val target = when { velocityFraction > 1.2f -> 1f; velocityFraction < -1.2f -> 0f; progress.value >= 0.5f -> 1f; else -> 0f }
        progress.animateTo(target, spec, initialVelocity = velocityFraction)
    }
}

/**
 * All sizes of the player at a given expansion. Every value is clamped, so no progress value (including spring
 * overshoot or a hostile window size such as split screen) can produce a negative padding or size. Compose throws
 * on negative padding, which crashed the whole process when the sheet settled at full screen.
 */
data class PlayerGeometry(
    val progress: Float, val sheetHeight: Dp, val sidePadding: Dp, val bottomPadding: Dp, val corner: Dp,
    val cover: Dp, val coverSize: Dp, val coverX: Dp, val coverY: Dp, val coverTop: Dp,
    /** Spare height in the expanded layout is spread over the gaps instead of left as one hole. */
    val gapTop: Dp, val gap: Dp,
) {
    companion object {
        val MiniHeight = 64.dp
        val TopBar = 56.dp
        private val MiniCover = 44.dp

        fun of(rawProgress: Float, width: Dp, height: Dp, statusTop: Dp, navBottom: Dp, dockedBottom: Dp): PlayerGeometry {
            val p = if (rawProgress.isNaN()) 0f else rawProgress.coerceIn(0f, 1f)
            val w = width.coerceAtLeast(0.dp); val h = height.coerceAtLeast(MiniHeight)
            // Fixed chrome below the cover: title 64 + seek 56 + controls 72 + queue 40 + padding 12 + gaps.
            val available = h - statusTop.coerceAtLeast(0.dp) - TopBar - navBottom.coerceAtLeast(0.dp)
            val cover = minOf(w - 48.dp, available - 310.dp - 8.dp, 480.dp).coerceAtLeast(150.dp)
            val extra = (available - 310.dp - 8.dp - cover).coerceAtLeast(0.dp)
            val gapTop = 8.dp + (extra * 0.3f).coerceAtMost(56.dp)
            val gap = 12.dp + (extra * 0.7f / 4f).coerceAtMost(36.dp)
            val coverTop = statusTop.coerceAtLeast(0.dp) + TopBar + gapTop
            fun mix(a: Dp, b: Dp) = lerp(a.value, b.value, p).dp.coerceAtLeast(0.dp)
            return PlayerGeometry(
                progress = p,
                sheetHeight = mix(MiniHeight, h),
                sidePadding = mix(8.dp, 0.dp),
                bottomPadding = mix(dockedBottom.coerceAtLeast(0.dp) + 6.dp, 0.dp),
                corner = mix(16.dp, 0.dp),
                cover = cover,
                coverSize = mix(MiniCover, cover),
                coverX = lerp(10f, ((w - cover) / 2).value, p).dp,
                coverY = lerp(10f, coverTop.value, p).dp,
                coverTop = coverTop, gapTop = gapTop, gap = gap,
            )
        }
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember { PlayerSheetState(scope) }
}

class PlayerActions(
    val togglePlay: () -> Unit, val next: () -> Unit, val previous: () -> Unit, val seekTo: (Long) -> Unit,
    val shuffle: () -> Unit, val repeat: () -> Unit, val retry: () -> Unit, val dismissError: () -> Unit,
    val toggleFavorite: () -> Unit, val openQueue: () -> Unit,
    val openLyrics: (() -> Unit)? = null,
)

/**
 * Swipe-to-skip: a track changes only when the gesture is confirmed — dragged past the distance
 * threshold, or flung fast enough in the direction already travelled. Anything else springs the
 * content back, so pulling back or a short accidental drag never switches. Evaluated once at
 * release, so a single gesture can switch at most one track.
 */
/**
 * -1 = next (left), +1 = previous (right), 0 = cancelled. Distance past the threshold always
 * confirms; a fast fling confirms only when it agrees with the direction already dragged, so
 * pulling back and releasing never switches tracks.
 */
internal fun swipeSkipDirection(offsetPx: Float, velocityPxPerSec: Float, minDistancePx: Float, flingPxPerSec: Float): Int = when {
    offsetPx <= -minDistancePx -> -1
    offsetPx >= minDistancePx -> 1
    abs(offsetPx) >= minDistancePx * 0.35f && abs(velocityPxPerSec) >= flingPxPerSec &&
        (offsetPx < 0f) == (velocityPxPerSec < 0f) -> if (offsetPx < 0f) -1 else 1
    else -> 0
}

@Composable
private fun Modifier.swipeToSkip(
    enabled: Boolean, canNext: Boolean, canPrev: Boolean,
    offsetX: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, minDistancePx: Float, flingPxPerSec: Float,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback, scope: CoroutineScope,
    onNext: () -> Unit, onPrevious: () -> Unit,
): Modifier {
    val drag = rememberDraggableState { delta ->
        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-minDistancePx * 2f, minDistancePx * 2f)) }
    }
    return draggable(drag, Orientation.Horizontal, enabled = enabled, onDragStopped = { velocity ->
        when (swipeSkipDirection(offsetX.value, velocity, minDistancePx, flingPxPerSec)) {
            -1 -> if (canNext) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() }
            1 -> if (canPrev) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() }
        }
        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
    })
}

/**
 * Mini-player and full-screen Now Playing as one surface. [dockedBottom] is the height of whatever sits below the
 * collapsed player (the navigation bar), which slides away as the sheet opens.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PlayerSheet(
    state: PlaybackUiState,
    sheet: PlayerSheetState,
    actions: PlayerActions,
    isFavorite: Boolean,
    dockedBottom: Dp,
    remoteName: String? = null,
    onOpenDevices: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val track = state.currentTrack ?: return
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val g = PlayerGeometry.of(sheet.progress.value, maxWidth, maxHeight, statusTop, navBottom, dockedBottom)
        val p = g.progress
        val miniHeight = PlayerGeometry.MiniHeight
        val height = maxHeight
        val cover = g.cover
        val travel = with(density) { (height - miniHeight - dockedBottom).toPx() }.coerceAtLeast(1f)
        val scope = rememberCoroutineScope()
        val drag = rememberDraggableState { delta -> scope.launch { sheet.dragTo(sheet.progress.value - delta / travel) } }

        Surface(
            color = lerpColor(colors.surfaceContainerHigh, colors.surface, p),
            shape = RoundedCornerShape(g.corner),
            tonalElevation = 0.dp, shadowElevation = lerp(3f, 0f, p).dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = g.sidePadding).padding(bottom = g.bottomPadding)
                .fillMaxWidth().height(g.sheetHeight)
                .draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> scope.launch { sheet.settle(-velocity / travel) } })
        ) {
            Box(Modifier.fillMaxSize()) {
                if (p < 0.6f) MiniContent(state, track, actions, remoteName, Modifier.graphicsLayer { alpha = (1f - p * 2.2f).coerceIn(0f, 1f) },
                    onOpen = sheet::expand, coverStart = 0.dp, onOpenDevices = onOpenDevices)
                if (p > 0.02f) FullContent(state, track, actions, isFavorite, remoteName, onOpenDevices, onCollapse = sheet::collapse,
                    topInset = statusTop, bottomInset = navBottom, coverSize = cover, gapTop = g.gapTop, gap = g.gap,
                    modifier = Modifier.graphicsLayer { alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) })

                // One artwork element for both states: it grows and travels, it is never swapped.
                val coverSwipe = remember(track.id) { Animatable(0f) }
                val coverPx = with(density) { cover.roundToPx() }
                CoverArtwork(track, state.queueIndex, g.coverSize, state.isPlaying || state.isLoading, p,
                    Modifier.offset(g.coverX, g.coverY).graphicsLayer { translationX = coverSwipe.value }
                        .swipeToSkip(p > 0.5f, state.canSkipNext, state.canSkipPrevious, coverSwipe,
                            minDistancePx = coverPx * 0.3f, flingPxPerSec = with(density) { 400.dp.toPx() },
                            haptics = LocalHapticFeedback.current, scope = scope,
                            onNext = actions.next, onPrevious = actions.previous),
                    pixels = coverPx)
            }
        }
    }
}

@Composable
private fun CoverArtwork(track: Track, queueIndex: Int, size: Dp, playing: Boolean, progress: Float, modifier: Modifier, pixels: Int) {
    // Changing track slides the cover in the direction of travel; the scale only breathes when the sheet is open.
    val breathe by androidx.compose.animation.core.animateFloatAsState(if (playing || progress < 0.5f) 1f else 0.94f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "cover")
    AnimatedContent(
        targetState = track.id to queueIndex, modifier = modifier.size(size).graphicsLayer { scaleX = breathe; scaleY = breathe },
        transitionSpec = {
            val forward = targetState.second >= initialState.second
            (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) -it / 3 else it / 3 } + fadeOut())
        }, label = "coverSwap"
    ) { _ ->
        TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize(), shape = RoundedCornerShape(lerp(8f, 16f, progress).dp), minPixels = pixels)
    }
}

@Composable
private fun MiniContent(state: PlaybackUiState, track: Track, actions: PlayerActions, remoteName: String?, modifier: Modifier, onOpen: () -> Unit, coverStart: Dp,
    onOpenDevices: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val swipeX = remember(track.id) { Animatable(0f) }
    var rowWidth by remember(track.id) { mutableFloatStateOf(1f) }
    Column(modifier.fillMaxSize().graphicsLayer { translationX = swipeX.value }) {
        Row(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open player", onClick = onOpen)
                .onSizeChanged { rowWidth = it.width.toFloat().coerceAtLeast(1f) }
                .swipeToSkip(enabled = true, canNext = state.canSkipNext, canPrev = state.canSkipPrevious,
                    offsetX = swipeX,
                    minDistancePx = (rowWidth * 0.3f).coerceAtLeast(with(density) { 88.dp.toPx() }),
                    flingPxPerSec = with(density) { 400.dp.toPx() },
                    haptics = haptics, scope = scope,
                    onNext = actions.next, onPrevious = actions.previous)
                .padding(start = 10.dp + 44.dp + 12.dp + coverStart, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.errorMessage ?: remoteName?.let { "$it · ${track.artist}" } ?: track.artist,
                    style = MaterialTheme.typography.bodySmallMuted.let {
                        if (state.errorMessage != null) it.copy(color = MaterialTheme.colorScheme.error)
                        else if (remoteName != null) it.copy(color = MaterialTheme.colorScheme.primary) else it },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Playing on another device: its icon sits here and opens Connect, like Spotify.
            if (remoteName != null && onOpenDevices != null) {
                IconButton(onClick = onOpenDevices) {
                    Icon(Icons.Outlined.Devices, "Playing on $remoteName", tint = MaterialTheme.colorScheme.primary)
                }
            }
            FilledIconButton(onClick = actions.togglePlay, modifier = Modifier.size(40.dp)) {
                if (state.isLoading) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(22.dp))
            }
            IconButton(onClick = actions.next, enabled = state.canSkipNext) { Icon(Icons.Default.SkipNext, "Next track", Modifier.size(24.dp)) }
        }
        val progress = if (state.durationMs > 0) (state.currentPositionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FullContent(
    state: PlaybackUiState, track: Track, actions: PlayerActions, isFavorite: Boolean,
    remoteName: String?, onOpenDevices: (() -> Unit)?, onCollapse: () -> Unit,
    topInset: Dp, bottomInset: Dp, coverSize: Dp, gapTop: Dp, gap: Dp, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val navigation = LocalTrackNavigation.current
    val allLinks by key(navigation) { (navigation?.links ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState() }
    val links = allLinks[track.id]
    Column(modifier.fillMaxSize().padding(bottom = bottomInset + 12.dp)) {
        // Title is centred on the screen, independent of how wide the action group is.
        Box(Modifier.fillMaxWidth().padding(top = topInset).height(56.dp).padding(horizontal = 4.dp)) {
            IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.Default.KeyboardArrowDown, "Minimize player") }
            Text("Now playing", Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            Row(Modifier.align(Alignment.CenterEnd)) {
                actions.openLyrics?.let { open ->
                    IconButton(onClick = open) { Icon(Icons.Outlined.Lyrics, "Lyrics") }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    TrackContextMenu(track, menuOpen, { menuOpen = false }, onPlay = { if (!state.isPlaying) actions.togglePlay() },
                        onLocalFavorite = actions.toggleFavorite, localFavorite = isFavorite)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // The cover itself is drawn by PlayerSheet so it can travel from the mini-player; reserve its space here.
        Spacer(Modifier.height(gapTop + coverSize + gap).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(track.id, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "titles") { _ ->
                Column {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Real metadata only: the track's own names, completed by the linked names YouTube supplied. Nothing generic.
                    val artistLink = links?.artist
                    val artistName = track.artist.takeIf { it.isNotBlank() } ?: artistLink?.title
                    val albumLink = links?.album
                    val albumName = track.album?.takeIf { it.isNotBlank() } ?: albumLink?.title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (artistName != null) Text(artistName, style = MaterialTheme.typography.bodyLarge.copy(color = if (artistLink != null) colors.primary else colors.onSurfaceVariant),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(
                                if (artistLink != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClickLabel = "Open artist $artistName") { navigation?.open?.invoke(artistLink) } else Modifier))
                        if (albumName != null) {
                            if (artistName != null) Text(" · ", style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurfaceVariant))
                            Text(albumName, style = MaterialTheme.typography.bodyLarge.copy(color = if (albumLink != null) colors.primary else colors.onSurfaceVariant),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).then(
                                    if (albumLink != null) Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClickLabel = "Open album $albumName") { navigation?.open?.invoke(albumLink) } else Modifier))
                        }
                    }
                }
            }
            TrackLikeButton(track, isFavorite, actions.toggleFavorite, refreshOnEnter = true)
        }
        if (state.errorMessage != null) Surface(color = colors.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(state.errorMessage, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
                Row { TextButton(onClick = actions.retry) { Text("Retry") }; TextButton(onClick = actions.dismissError) { Text("Dismiss") } }
            }
        } else state.noticeMessage?.let {
            Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer, modifier = Modifier.padding(12.dp))
            }
        }
        Spacer(Modifier.height(gap))
        val duration = state.durationMs
        val position = state.currentPositionMs.coerceIn(0, duration.coerceAtLeast(1))
        var scrub by remember(track.id) { mutableStateOf<Float?>(null) }
        cord.kasane.ui.components.KasaneSlider(
            value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
            enabled = duration > 0,
            onValueChange = { scrub = it },
            onValueChangeFinished = { scrub?.let { actions.seekTo((it * duration).toLong()) }; scrub = null },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek" },
        )
        val shown = scrub?.let { (it * duration).toLong() } ?: position
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shown / 1000.0), style = MaterialTheme.typography.labelMediumMuted)
            Text(if (state.isLoading && duration == 0L) "Buffering…" else formatDuration(duration / 1000.0), style = MaterialTheme.typography.labelMediumMuted)
        }
        Spacer(Modifier.height(gap / 2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.shuffle, modifier = Modifier.semantics { stateDescription = if (state.shuffleEnabled) "On" else "Off" }) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffleEnabled) colors.primary else colors.onSurfaceVariant)
            }
            IconButton(onClick = actions.previous, enabled = state.canSkipPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(34.dp)) }
            val corner by animateDpAsState(if (state.isPlaying) 36.dp else 24.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "playShape")
            FilledIconButton(onClick = actions.togglePlay, modifier = Modifier.size(72.dp), shape = RoundedCornerShape(corner)) {
                if (state.isLoading) CircularProgressIndicator(color = colors.onPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(36.dp))
            }
            IconButton(onClick = actions.next, enabled = state.canSkipNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next track", Modifier.size(34.dp)) }
            IconButton(onClick = actions.repeat, modifier = Modifier.semantics { stateDescription = when (state.repeatMode) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Off" } }) {
                Icon(if (state.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat",
                    tint = if (state.repeatMode != 0) colors.primary else colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        // Queue access sits at the bottom and spans the width, so either thumb reaches it without stretching to the top.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Which device plays, bottom-left like Spotify; a tap opens Connect.
            if (onOpenDevices != null) {
                TextButton(onClick = onOpenDevices, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Devices, null, Modifier.size(18.dp),
                        tint = if (remoteName != null) colors.primary else colors.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(remoteName ?: "This phone", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (remoteName != null) colors.primary else colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalButton(onClick = actions.openQueue, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.queue.size > 1) "Up next · ${state.queue.size}" else "Up next", maxLines = 1)
            }
        }
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Composable
fun PlayerSheetCollapsedPreview() {
    KasaneTheme(dynamicColor = false) {
        val sheet = rememberPlayerSheetState()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PlayerSheet(previewState(), sheet, previewActions(), false, dockedBottom = 80.dp)
        }
    }
}

@Preview(name = "360", showBackground = true, widthDp = 360, heightDp = 780)
@Preview(name = "412", showBackground = true, widthDp = 412, heightDp = 892)
@Preview(name = "700", showBackground = true, widthDp = 700, heightDp = 900)
@Composable
fun PlayerSheetExpandedPreview() {
    KasaneTheme(dynamicColor = false) {
        val sheet = rememberPlayerSheetState()
        LaunchedEffect(Unit) { sheet.progress.snapTo(1f) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { PlayerSheet(previewState(), sheet, previewActions(), true, dockedBottom = 80.dp) }
    }
}

private fun previewState() = PlaybackUiState(currentTrack = Track("preview", "Midnight City Lights with a very long title", "Aether"), durationMs = 240000,
    currentPositionMs = 65000, isPlaying = true, canSkipNext = true, canSkipPrevious = true, queue = List(8) { Track("q$it", "T$it", "A") }, queueIndex = 0)
private fun previewActions() = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
