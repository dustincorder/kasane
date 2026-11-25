package cord.kasane.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cord.kasane.music.KasaneApplication
import cord.kasane.music.model.Track
import cord.kasane.music.offline.*
import cord.kasane.music.ui.components.TrackArtwork
import cord.kasane.music.ui.theme.KasaneTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(app: KasaneApplication, onBack: () -> Unit, onPlay: (tracks: List<Track>, index: Int) -> Unit) {
    val records by app.offline.pinned.collectAsState(initial = emptyList())
    val progress by app.offline.progress.collectAsState()
    val usage by app.offline.usage.collectAsState()
    val offlineOnly by app.settings.offlineOnly.collectAsState()
    val tracks by produceState<Map<String, Track>>(emptyMap(), records.map { it.videoId }) {
        value = records.mapNotNull { r -> app.database.trackDao().getTrackById(r.videoId)?.let { r.videoId to it } }.toMap()
    }
    LaunchedEffect(records.size) { app.offline.refreshUsage() }
    DownloadsContent(records, tracks, progress, usage, offlineOnly, onBack, onPlay,
        onCancelAll = app.offline::cancelAll, onRetry = app.offline::retry, onRemove = app.offline::remove)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsContent(
    records: List<AudioFileRecord>,
    tracks: Map<String, Track>,
    progress: Map<String, DownloadProgress>,
    usage: StorageUsage,
    offlineOnly: Boolean,
    onBack: () -> Unit,
    onPlay: (tracks: List<Track>, index: Int) -> Unit,
    onCancelAll: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val active = records.count { it.audioState == AudioState.QUEUED || it.audioState == AudioState.DOWNLOADING }
    // Only complete saves are playable; the queue keeps this screen's order.
    val savedTracks = records.filter { it.audioState == AudioState.SAVED }.mapNotNull { tracks[it.videoId] }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Saved music") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (active > 0) TextButton(onClick = onCancelAll) { Text("Cancel all") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Text(
            "${usage.savedCount} saved · ${OfflinePolicy.formatBytes(usage.savedBytes)} on this device" +
                if (offlineOnly) " · Offline Only is on" else "",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (records.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
                Text("Open a song's menu and choose Save offline, or save a playlist. Saved music plays without a connection and is never removed automatically.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(records, key = { it.videoId }) { record ->
                DownloadRow(record, tracks[record.videoId], progress[record.videoId],
                    onPlay = {
                        val index = savedTracks.indexOfFirst { it.id == record.videoId }
                        if (index >= 0) onPlay(savedTracks, index)
                    }, onRetry, onRemove)
            }
        }
    }
}

@Composable
fun DownloadRow(
    record: AudioFileRecord,
    track: Track?,
    progress: DownloadProgress?,
    onPlay: () -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val state = record.audioState
    Row(
        Modifier.fillMaxWidth().then(if (state == AudioState.SAVED && track != null) Modifier.clickable { onPlay() } else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(track?.thumbnailUri, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track?.title ?: record.videoId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (state) {
                AudioState.SAVED -> Text("Saved · ${OfflinePolicy.formatBytes(record.contentLength)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                AudioState.DOWNLOADING -> {
                    val fraction = progress?.fraction ?: 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text("Downloading · ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                AudioState.QUEUED -> Text(record.failure ?: "Waiting in queue", style = MaterialTheme.typography.labelMedium)
                AudioState.FAILED -> Text(record.failure ?: "Download failed", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
                AudioState.CACHED -> Unit
            }
        }
        when (state) {
            AudioState.FAILED -> {
                IconButton(onClick = { onRetry(record.videoId) }) { Icon(Icons.Default.Refresh, "Retry download") }
                IconButton(onClick = { onRemove(record.videoId) }) { Icon(Icons.Default.Delete, "Remove") }
            }
            AudioState.QUEUED, AudioState.DOWNLOADING -> IconButton(onClick = { onRemove(record.videoId) }) { Icon(Icons.Default.Close, "Cancel download") }
            else -> IconButton(onClick = { onRemove(record.videoId) }) { Icon(Icons.Default.Delete, "Remove from offline") }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
fun DownloadsPreview() {
    val now = 0L
    fun rec(id: String, state: AudioState, failure: String? = null) =
        AudioFileRecord(id, "ytm:$id:140", 140, "audio/mp4", 4_200_000, state.key, true, 0, failure, state == AudioState.SAVED, now, now)
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            DownloadsContent(
                listOf(rec("aaaaaaaaaaa", AudioState.SAVED), rec("bbbbbbbbbbb", AudioState.DOWNLOADING),
                    rec("ccccccccccc", AudioState.QUEUED, "Waiting for an unmetered network."),
                    rec("ddddddddddd", AudioState.FAILED, "This track is delivered as a streaming-only format and cannot be saved offline.")),
                mapOf("aaaaaaaaaaa" to Track("aaaaaaaaaaa", "Midnight City Lights", "Aether"), "bbbbbbbbbbb" to Track("bbbbbbbbbbb", "Subway Melancholy", "Dustin Corder"),
                    "ccccccccccc" to Track("ccccccccccc", "Synthetic Dawn", "Kuro"), "ddddddddddd" to Track("ddddddddddd", "Locked Format", "Unknown")),
                mapOf("bbbbbbbbbbb" to DownloadProgress(1_800_000, 4_200_000)), StorageUsage(4_200_000, 90_000_000, 1L shl 30, 1),
                false, {}, { _, _ -> }, {}, {}, {})
        }
    }
}
