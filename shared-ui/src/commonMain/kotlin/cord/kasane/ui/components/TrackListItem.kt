package cord.kasane.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track

/** One menu action. [action] never dismisses anything; callers decide what happens around it. */
class MenuEntry(
    val label: String,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val group: Int = 0,
    val action: () -> Unit,
) {
    constructor(label: String, enabled: Boolean = true, action: () -> Unit) : this(label, enabled, null, 0, action)
    constructor(label: String, enabled: Boolean = true, icon: ImageVector?, action: () -> Unit) : this(label, enabled, icon, 0, action)
}

fun formatDuration(seconds: Double): String {
    val total = seconds.toInt()
    if (total < 0) return "--:--"
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * A track in any list. Tap plays, long-press (touch) or the overflow button opens [menu]; every entry is also a
 * screen-reader action. Rows are at least 56 dp tall with a 48 dp menu target, which suits touch and mouse alike.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isLoading: Boolean = false,
    menu: List<MenuEntry> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(
                indication = ripple(), interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClickLabel = "Play ${track.title}", onClick = onClick, onLongClickLabel = "Open menu",
                onLongClick = { if (menu.isNotEmpty()) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true } })
            .semantics { customActions = menu.filter { it.enabled }.map { e -> CustomAccessibilityAction(e.label) { e.action(); true } } }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            TrackArtwork(track.thumbnailUri, Modifier.fillMaxSize())
            if (isLoading || isPlaying) Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Default.GraphicEq, "Playing", tint = colors.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall,
                color = if (isPlaying || isLoading) colors.primary else colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.durationSeconds.takeIf { it > 0 }?.let(::formatDuration)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (menu.isNotEmpty()) Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, "More options for ${track.title}", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = MaterialTheme.shapes.small,
                containerColor = colors.surfaceContainerHigh,
            ) {
                menu.forEach { e ->
                    DropdownMenuItem(
                        text = { Text(e.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = e.icon?.let { icon ->
                            { Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant) }
                        },
                        enabled = e.enabled,
                        onClick = { menuOpen = false; e.action() },
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

