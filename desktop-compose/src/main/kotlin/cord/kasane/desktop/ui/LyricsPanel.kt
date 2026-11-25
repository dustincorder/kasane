package cord.kasane.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.music.model.Track
import cord.kasane.music.lyrics.LyricLine
import cord.kasane.music.lyrics.LyricsCandidate
import cord.kasane.music.lyrics.LyricsDocument
import cord.kasane.music.lyrics.LyricsSync
import cord.kasane.music.lyrics.LyricsSyncKind
import cord.kasane.ui.theme.bodyMediumMuted
import cord.kasane.ui.theme.bodySmallMuted
import kotlinx.coroutines.launch

private sealed interface PanelState {
    data object Loading : PanelState
    /** Every reachable provider answered empty — the song genuinely has no lyrics. */
    data object Absent : PanelState
    /** At least one provider failed (network, rate limit) — a retry is worthwhile. */
    data class Unavailable(val detail: String?) : PanelState
    data class Ready(val document: LyricsDocument) : PanelState
}

/**
 * Synced-lyrics side panel — the desktop counterpart of Android's LyricsSheet: scrolls to the
 * active line, highlights the active word, tap a line to seek, ±250 ms offset correction and a
 * source picker over the shared resolver's candidates.
 */
@Composable
fun LyricsPanel(app: DesktopApp, state: PlayerState, controls: PlayerControls, track: Track? = null, onClose: () -> Unit) {
    // An explicit track (opened from a track menu) shows its lyrics; sync/seek stay bound to the playing one.
    val shown = track ?: state.current
    val synced = shown != null && shown.id == state.current?.id
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<Pair<LyricsCandidate, LyricsDocument?>>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(false) }

    val panel by produceState<PanelState>(PanelState.Loading, shown?.id, refreshTick) {
        value = PanelState.Loading
        value = if (shown == null) PanelState.Absent else when (val r = app.lyrics.lyricsStateFor(shown)) {
            is cord.kasane.music.lyrics.LyricsResolution.Found ->
                if (r.document.usable) PanelState.Ready(r.document) else PanelState.Absent
            cord.kasane.music.lyrics.LyricsResolution.Absent -> PanelState.Absent
            is cord.kasane.music.lyrics.LyricsResolution.Unavailable -> PanelState.Unavailable(r.detail)
        }
    }
    val positionMs = if (synced) (state.positionSec * 1000).toLong() else -1L

    Row(Modifier.fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(400.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lyrics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(shown?.let { "${it.title} — ${it.artist}" } ?: "", style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val ready = panel as? PanelState.Ready
                    if (ready != null && ready.document.hasTiming) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val t = shown ?: return@TextButton
                                    scope.launch { app.lyrics.adjustOffset(t.id, -250); refreshTick++ }
                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.size(width = 48.dp, height = 32.dp)) {
                                    Text("−0.25s", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = {
                                    val t = shown ?: return@TextButton
                                    scope.launch { app.lyrics.adjustOffset(t.id, 250); refreshTick++ }
                                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.size(width = 48.dp, height = 32.dp)) {
                                    Text("+0.25s", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    FilledTonalButton(onClick = {
                        val t = shown ?: return@FilledTonalButton
                        sourcesOpen = !sourcesOpen
                        if (sourcesOpen && !sourcesLoading) {
                            sourcesLoading = true
                            scope.launch {
                                sources = runCatching { app.lyrics.candidatesFor(t) }.getOrDefault(emptyList())
                                sourcesLoading = false
                            }
                        }
                    }, shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.size(width = 76.dp, height = 32.dp)) {
                        Text("Sources", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "Close lyrics", modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (sourcesOpen) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (sourcesLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Checking sources…", style = MaterialTheme.typography.bodySmallMuted)
                            }
                        } else {
                            sources.forEach { (candidate, doc) ->
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            val t = shown ?: return@clickable
                                            scope.launch { app.lyrics.choose(t.id, candidate); sourcesOpen = false; refreshTick++ }
                                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(candidate.providerLabel, style = MaterialTheme.typography.bodyMedium)
                                        Text(doc?.syncKind?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
                                            ?.let { "$it lyrics" } ?: "No usable lyrics",
                                            style = MaterialTheme.typography.bodySmallMuted)
                                    }
                                    val ready = panel as? PanelState.Ready
                                    if (ready != null && ready.document.providerId == candidate.providerId
                                        && ready.document.sourceRef == candidate.sourceRef) {
                                        Text("Current", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            if (sources.isEmpty() && !sourcesLoading) {
                                Text("No sources found for this track.", style = MaterialTheme.typography.bodySmallMuted)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                when (val s = panel) {
                    PanelState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                    PanelState.Absent -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.MusicNote, null, Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No lyrics available", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        Text("Try Sources if another provider has this song.",
                            style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(top = 4.dp))
                    }
                    is PanelState.Unavailable -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.MusicNote, null, Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Couldn't reach the lyrics providers", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        Text("Check your connection and try again.",
                            style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(top = 4.dp))
                    }
                    is PanelState.Ready -> SyncedList(s.document, positionMs,
                        onSeek = if (synced) ({ targetMs -> controls.seek(targetMs / 1000.0) }) else null)
                }
            }
        }
    }
}

@Composable
private fun SyncedList(document: LyricsDocument, positionMs: Long, onSeek: ((Long) -> Unit)?) {
    val listState = rememberLazyListState()
    val active = if (document.hasTiming) LyricsSync.activeLine(document, positionMs) else -1
    val inBreak = document.hasTiming && LyricsSync.inInstrumentalBreak(document, positionMs)

    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -160)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 120.dp),
    ) {
        itemsIndexed(document.lines, key = { i, line -> "$i|${line.startMs}" }) { i, line ->
            val isActive = i == active
            val alpha = when {
                !document.hasTiming -> 1f
                isActive -> 1f
                i < active -> 0.45f
                else -> 0.68f
            }
            if (inBreak && isActive) {
                BreakRow(alpha)
            } else {
                LineRow(document, line, isActive, alpha, positionMs, onSeek)
            }
            if (line.isInstrumentalBreak) BreakRow(alpha)
        }
    }
}

@Composable
private fun BreakRow(alpha: Float) {
    Text("• • •", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha).padding(horizontal = 8.dp, vertical = 12.dp))
}

@Composable
private fun LineRow(
    document: LyricsDocument,
    line: LyricLine,
    isActive: Boolean,
    alpha: Float,
    positionMs: Long,
    onSeek: ((Long) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(if (isActive && document.hasTiming) colors.secondaryContainer.copy(alpha = 0.28f) else Color.Transparent)
            .alpha(alpha)
            .clickable(enabled = onSeek != null && document.hasTiming, onClickLabel = "Seek to line") {
                onSeek?.invoke(LyricsSync.seekPosition(line, document.offsetMs))
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (line.words.isNotEmpty() && isActive) {
            val wordIndex = LyricsSync.activeWord(line, positionMs, document.offsetMs)
            val styled = buildAnnotatedString {
                line.words.forEachIndexed { idx, word ->
                    val wordColor = when {
                        idx < wordIndex -> colors.onSurface
                        idx == wordIndex -> colors.primary
                        else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                    withStyle(SpanStyle(color = wordColor)) { append(word.text) }
                }
            }
            Text(text = styled, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        } else {
            Text(
                text = line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = if (isActive || !document.hasTiming) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isActive || !document.hasTiming) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        line.romanizedText?.let { Text(it, style = MaterialTheme.typography.bodySmallMuted) }
        line.translatedText?.let { Text(it, style = MaterialTheme.typography.bodyMediumMuted) }
    }
}
