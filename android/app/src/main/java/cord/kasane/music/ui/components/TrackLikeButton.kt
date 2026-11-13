package cord.kasane.music.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikes
import cord.kasane.music.provider.CloudLikeState
import cord.kasane.music.ui.theme.*

internal val EmptyCloudLikes: kotlinx.coroutines.flow.StateFlow<Map<String, CloudLikeState>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
internal val EmptyPendingLikes: kotlinx.coroutines.flow.StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet())
val LocalCloudLikes = staticCompositionLocalOf<CloudLikes?> { null }

@Composable
fun TrackLikeButton(track: Track, localFavorite: Boolean, onLocalToggle: () -> Unit, useCloud: Boolean = true, refreshOnEnter: Boolean = false) {
    val cloud = LocalCloudLikes.current?.takeIf { useCloud && !track.isLocal && track.sourceProvider == "youtube" }
    val states by key(cloud) { (cloud?.states ?: EmptyCloudLikes).collectAsState() }
    val pending by key(cloud) { (cloud?.pending ?: EmptyPendingLikes).collectAsState() }
    val state = states[track.id] ?: CloudLikeState.UNKNOWN
    LaunchedEffect(cloud, track.id) { cloud?.refresh(track.id, force = refreshOnEnter) }
    val liked = if (cloud == null) localFavorite else state == CloudLikeState.LIKED
    val unknown = cloud != null && state in setOf(CloudLikeState.UNKNOWN, CloudLikeState.LOADING)
    val label = if (cloud == null) "Local favorite" else if (unknown) "YouTube Music like status unknown; like this track"
        else if (liked) "Remove YouTube Music like" else "Like on YouTube Music"
    IconButton(modifier = Modifier.semantics { contentDescription = label; stateDescription = if (cloud == null) { if (liked) "Saved" else "Not saved" } else state.name },
        enabled = track.id !in pending, onClick = { if (cloud != null) cloud.toggle(track.id) else onLocalToggle() }) {
        if (cloud != null && state == CloudLikeState.LOADING) CircularProgressIndicator(Modifier.size(20.dp), color = AccentRose, strokeWidth = 2.dp)
        else Icon(
            if (unknown) Icons.Default.QuestionMark else if (cloud != null && state == CloudLikeState.DISLIKED) Icons.Default.ThumbDown
            else if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = if (liked) AccentRose else Gray400,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 88)
@Composable
fun UnknownCloudLikePreview() {
    val cloud = remember { CloudLikes({ CloudLikeState.UNKNOWN }, { _, _ -> }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined) }
    DisposableEffect(cloud) { onDispose { cloud.close() } }
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface {
            CompositionLocalProvider(LocalCloudLikes provides cloud) {
                TrackRow(Track("preview", "Unknown cloud like", "YouTube Music"), onClick = {}, onToggleFavorite = {})
            }
        }
    }
}
