package cord.kasane.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import cord.kasane.music.lyrics.LyricsDocument
import cord.kasane.music.lyrics.LyricLine
import cord.kasane.music.lyrics.LyricsSync
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*

sealed interface LyricsState {
    data object Loading : LyricsState
    /** Every provider answered empty — the track genuinely has no lyrics. */
    data object Absent : LyricsState
    /** Providers could not be reached — an error, not "no lyrics". */
    data object Unavailable : LyricsState
    data class Ready(val document: LyricsDocument) : LyricsState
}

/**
 * Synced-lyrics sheet: scrolls to the active line, highlights the active word inline, dims the rest,
 * tap a line to seek, ±250ms offset correction for shifted sources.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    title: String,
    state: LyricsState,
    positionMs: Long,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onAdjustOffset: (Long) -> Unit = {},
    onPickSource: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state is LyricsState.Ready && state.document.hasTiming) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onAdjustOffset(-250) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp),
                            ) { Text("−0.25s", style = MaterialTheme.typography.labelMedium) }
                            TextButton(
                                onClick = { onAdjustOffset(250) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp),
                            ) { Text("+0.25s", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
                FilledTonalButton(
                    onClick = onPickSource,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(34.dp),
                ) { Text("Sources", style = MaterialTheme.typography.labelMedium) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (state) {
                LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
                LyricsState.Absent -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.MusicNote, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No lyrics available", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("Try switching sources above if another provider has this song.", style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(top = 4.dp))
                }
                LyricsState.Unavailable -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.MusicNote, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Lyrics could not be loaded", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text("Check the connection and reopen this panel to retry.", style = MaterialTheme.typography.bodySmallMuted, modifier = Modifier.padding(top = 4.dp))
                }
                is LyricsState.Ready -> SyncedLyricsList(state.document, positionMs, onSeek)
            }
        }
    }
}

@Composable
private fun SyncedLyricsList(document: LyricsDocument, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val active = if (document.hasTiming) LyricsSync.activeLine(document, positionMs) else -1
    val inBreak = document.hasTiming && LyricsSync.inInstrumentalBreak(document, positionMs)

    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -220)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 200.dp),
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
                InstrumentalBreakRow(alpha)
            } else {
                LyricLineRow(document, line, isActive, alpha, positionMs, onSeek)
            }
            if (line.isInstrumentalBreak) InstrumentalBreakRow(alpha)
        }
    }
}

@Composable
private fun InstrumentalBreakRow(alpha: Float) {
    Text(
        "• • •",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

@Composable
private fun LyricLineRow(
    document: LyricsDocument,
    line: LyricLine,
    isActive: Boolean,
    alpha: Float,
    positionMs: Long,
    onSeek: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (isActive && document.hasTiming) colors.secondaryContainer.copy(alpha = 0.28f) else Color.Transparent)
            .alpha(alpha)
            .clickable(enabled = document.hasTiming, onClickLabel = "Seek to line") {
                onSeek(LyricsSync.seekPosition(line, document.offsetMs))
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
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
                    withStyle(SpanStyle(color = wordColor)) {
                        append(word.text)
                    }
                }
            }
            Text(
                text = styled,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
        } else {
            Text(
                text = line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isActive || !document.hasTiming) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (isActive || !document.hasTiming) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        line.romanizedText?.let { Text(it, style = MaterialTheme.typography.bodySmallMuted) }
        line.translatedText?.let { Text(it, style = MaterialTheme.typography.bodyMediumMuted) }
    }
}
