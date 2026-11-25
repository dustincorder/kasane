package cord.kasane.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.offline.DesktopOffline
import cord.kasane.desktop.offline.OfflineRecord

/**
 * The desktop counterpart of Android's Downloads screen: saved music and the stream cache,
 * the running download queue, and storage management. Saved rows play back without a network.
 */
@Composable
fun DownloadsScreen(app: DesktopApp, actions: Actions) {
    val records by app.offline.records.collectAsState()
    val progress by app.offline.progress.collectAsState()
    val usage by app.offline.usage.collectAsState()
    val saved = records.filter { it.pinned && it.state == "saved" }
    val busy = records.filter { it.state == "downloading" || it.state == "queued" }
    val failed = records.filter { it.state == "failed" }
    val cached = records.filter { !it.pinned && it.state == "cached" }

    LazyColumn(
        Modifier.widthIn(max = 920.dp).fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${usage.savedCount} saved · ${DesktopOffline.formatBytes(usage.savedBytes)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Temporary cache ${DesktopOffline.formatBytes(usage.tempBytes)} of ${DesktopOffline.formatBytes(usage.tempLimitBytes)}" +
                                if (usage.replayableCount > 0) " · ${usage.replayableCount} songs replay offline" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        if (saved.isEmpty() && records.isNotEmpty()) {
                            Text(
                                "Nothing saved yet — choose \"Save offline\" in any song's menu to listen without a connection.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (saved.isNotEmpty()) {
                            Button(onClick = { actions.playList(saved.mapNotNull { it.track }) }, shape = MaterialTheme.shapes.small) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Play saved")
                            }
                        }
                        if (busy.isNotEmpty()) {
                            OutlinedButton(onClick = { app.offline.cancelAll() }, shape = MaterialTheme.shapes.small) { Text("Cancel all") }
                        }
                        if (usage.tempBytes > 0) {
                            OutlinedButton(onClick = { app.offline.clearTemporaryCache(); app.offline.refreshUsage() }, shape = MaterialTheme.shapes.small) {
                                Text("Clear cache")
                            }
                        }
                    }
                }
            }
        }
        if (busy.isNotEmpty()) {
            item { SectionLabel("Downloading") }
            itemsIndexed(busy, key = { _, r -> "d:" + r.videoId }) { _, row -> DownloadRow(app, row, progress[row.videoId]) }
        }
        if (failed.isNotEmpty()) {
            item { SectionLabel("Needs attention") }
            itemsIndexed(failed, key = { _, r -> "f:" + r.videoId }) { _, row -> DownloadRow(app, row, progress[row.videoId]) }
        }
        if (saved.isNotEmpty()) {
            item { SectionLabel("Saved on this computer") }
            itemsIndexed(saved, key = { i, r -> "s:$i:" + r.videoId }) { i, row ->
                DownloadRow(
                    app = app,
                    row = row,
                    progress = progress[row.videoId],
                    onPlay = row.track?.let { { actions.playList(saved.mapNotNull { it.track }, i) } },
                )
            }
        }
        if (cached.isNotEmpty()) {
            item { SectionLabel("Cached · automatic") }
            itemsIndexed(cached, key = { _, r -> "c:" + r.videoId }) { _, row -> DownloadRow(app, row, progress[row.videoId]) }
        }
        if (records.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.DownloadDone, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Choose \"Save offline\" in any song's menu to listen without a connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun DownloadRow(
    app: DesktopApp,
    row: OfflineRecord,
    progress: cord.kasane.desktop.offline.DownloadProgress?,
    onPlay: (() -> Unit)? = null,
) {
    val track = row.track
    val artwork = app.offline.savedArtwork(row.videoId) ?: track?.thumbnailUri
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artwork, 44.dp, MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track?.title ?: row.videoId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (row.state) {
                    "downloading" -> "Downloading · ${((progress?.fraction ?: 0f) * 100).toInt()}%"
                    "queued" -> row.failure ?: "Queued"
                    "failed" -> row.failure ?: "Download failed"
                    else -> track?.artist.orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (row.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.state == "downloading") {
                LinearProgressIndicator(
                    progress = { progress?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        when (row.state) {
            "failed" -> {
                IconButton(onClick = { app.offline.retry(row.videoId) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Refresh, "Retry download", Modifier.size(18.dp))
                }
                IconButton(onClick = { app.offline.remove(row.videoId) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Delete, "Remove", Modifier.size(18.dp))
                }
            }
            else -> IconButton(onClick = { app.offline.remove(row.videoId) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Delete, if (row.pinned) "Remove from offline" else "Remove cached copy", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
