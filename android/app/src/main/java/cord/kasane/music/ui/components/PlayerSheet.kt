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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.ui.input.pointer.pointerInput
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
)

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
                if (p < 0.6f) MiniContent(state, track, actions, Modifier.graphicsLayer { alpha = (1f - p * 2.2f).coerceIn(0f, 1f) },
                    onOpen = sheet::expand, coverStart = 0.dp)
                if (p > 0.02f) FullContent(state, track, actions, isFavorite, onCollapse = sheet::collapse,
                    topInset = statusTop, bottomInset = navBottom, coverSize = cover, gapTop = g.gapTop, gap = g.gap,
                    modifier = Modifier.graphicsLayer { alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) })

                // One artwork element for both states: it grows and travels, it is never swapped.
                val swipe = Modifier.pointerInput(track.id, p > 0.5f) {
                    if (p < 0.5f) return@pointerInput
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onHorizontalDrag = { _, dx -> total += dx },
                        onDragEnd = { if (total < -90f) actions.next() else if (total > 90f) actions.previous() })
                }
                CoverArtwork(track, state.queueIndex, g.coverSize, state.isPlaying || state.isLoading, p,
                    Modifier.offset(g.coverX, g.coverY).then(swipe), pixels = with(density) { cover.roundToPx() })
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
private fun MiniContent(state: PlaybackUiState, track: Track, actions: PlayerActions, modifier: Modifier, onOpen: () -> Unit, coverStart: Dp) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open player", onClick = onOpen)
                .pointerInput(track.id) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onHorizontalDrag = { _, dx -> total += dx },
                        onDragEnd = {
                            if (total < -80f && state.canSkipNext) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.next() }
                            else if (total > 80f && state.canSkipPrevious) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.previous() }
                        })
                }
                .padding(start = 10.dp + 44.dp + 12.dp + coverStart, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.errorMessage ?: track.artist, style = MaterialTheme.typography.bodySmallMuted.let {
                    if (state.errorMessage != null) it.copy(color = MaterialTheme.colorScheme.error) else it }, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    state: PlaybackUiState, track: Track, actions: PlayerActions, isFavorite: Boolean, onCollapse: () -> Unit,
    topInset: Dp, bottomInset: Dp, coverSize: Dp, gapTop: Dp, gap: Dp, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = bottomInset + 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = topInset).height(56.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "Minimize player") }
            Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            IconButton(onClick = actions.openQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Open queue") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                TrackContextMenu(track, menuOpen, { menuOpen = false }, onPlay = { if (!state.isPlaying) actions.togglePlay() },
                    onLocalFavorite = actions.toggleFavorite, localFavorite = isFavorite)
            }
        }
        // The cover itself is drawn by PlayerSheet so it can travel from the mini-player; reserve its space here.
        Spacer(Modifier.height(gapTop + coverSize + gap).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(track.id, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "titles") { _ ->
                Column {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Slider(
            value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f, enabled = duration > 0,
            onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { actions.seekTo((it * duration).toLong()) }; scrub = null },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek" })
        val shown = scrub?.let { (it * duration).toLong() } ?: position
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
        Spacer(Modifier.height(gap / 2))
        TextButton(onClick = actions.openQueue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (state.queue.size > 1) "Up next · ${state.queue.size} tracks" else "Up next")
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

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
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
