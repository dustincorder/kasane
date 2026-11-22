package cord.kasane.music.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Per-track offline availability shown beside the artist line. */
enum class OfflineBadge(val label: String) {
    SAVED("Saved offline"), DOWNLOADING("Downloading"), FAILED("Download failed"), CACHED("Cached from playback")
}

val LocalOfflineBadges = staticCompositionLocalOf<Map<String, OfflineBadge>> { emptyMap() }

@Composable
fun OfflineBadgeIcon(badge: OfflineBadge) {
    val colors = MaterialTheme.colorScheme
    when (badge) {
        OfflineBadge.SAVED -> Icon(Icons.Default.DownloadDone, badge.label, Modifier.size(14.dp), tint = colors.primary)
        OfflineBadge.DOWNLOADING -> Icon(Icons.Default.Downloading, badge.label, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        OfflineBadge.FAILED -> Icon(Icons.Default.ErrorOutline, badge.label, Modifier.size(14.dp), tint = colors.error)
        OfflineBadge.CACHED -> Icon(Icons.Default.CloudOff, badge.label, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
    }
}
