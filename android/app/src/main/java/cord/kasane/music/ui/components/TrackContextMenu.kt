package cord.kasane.music.ui.components

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cord.kasane.music.ui.theme.*
import androidx.compose.runtime.*
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState

data class RatingMenuLabels(val like: String?, val dislike: String?)
object TrackMenuPolicy {
    fun ratings(signedIn: Boolean, local: Boolean, state: CloudLikeState?, dislikeSupported: Boolean): RatingMenuLabels =
        if (!signedIn || local) RatingMenuLabels(null, null) else RatingMenuLabels(
            when (state) {
                CloudLikeState.LIKED -> "Remove YouTube Music like"
                CloudLikeState.LOADING -> "Loading YouTube Music rating…"
                CloudLikeState.UNKNOWN, null -> "Like on YouTube Music (status unknown)"
                else -> "Like on YouTube Music"
            },
            if (!dislikeSupported) null else if (state == CloudLikeState.DISLIKED) "Remove YouTube Music dislike" else "Dislike on YouTube Music")
}
class TrackActions(val play: (Track) -> Unit, val next: (Track) -> Unit, val queue: (Track) -> Unit, val radio: (Track) -> Unit)
class TrackNavigation(val links: kotlinx.coroutines.flow.StateFlow<Map<String, cord.kasane.music.provider.TrackLinks>>, val open: (cord.kasane.music.model.CatalogItem) -> Unit)
private val EmptyTrackLinks: kotlinx.coroutines.flow.StateFlow<Map<String, cord.kasane.music.provider.TrackLinks>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
val LocalTrackNavigation = staticCompositionLocalOf<TrackNavigation?> { null }
val LocalTrackActions = staticCompositionLocalOf<TrackActions?> { null }

/** Shared menu for track rows, long press, and Now Playing; only wired actions are shown. */
@Composable
fun TrackContextMenu(
    track: Track, expanded: Boolean, onDismiss: () -> Unit, onPlay: () -> Unit,
    onNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false,
) {
    val navigation = LocalTrackNavigation.current
    val allLinks by key(navigation) { (navigation?.links ?: EmptyTrackLinks).collectAsState() }
    val links = allLinks[track.id]
    val actions = LocalTrackActions.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val youtube = !track.isLocal && track.sourceProvider == "youtube" && track.id.matches(Regex("[A-Za-z0-9_-]{11}"))
    val cloud = LocalCloudLikes.current?.takeIf { !track.isLocal && track.sourceProvider == "youtube" }
    val states by key(cloud) { (cloud?.states ?: EmptyCloudLikes).collectAsState() }
    val pending by key(cloud) { (cloud?.pending ?: EmptyPendingLikes).collectAsState() }
    LaunchedEffect(expanded, cloud, track.id) { if (expanded) cloud?.refresh(track.id) }
    val ratings = TrackMenuPolicy.ratings(cloud != null, track.isLocal, states[track.id], cloud?.supportsDislike == true)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Gray900) {
        Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(track.title, style = KasaneTypography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Gray400, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Gray800)
        DropdownMenuItem(text = { Text("Play") }, onClick = { onDismiss(); onPlay() })
        val next = onNext ?: actions?.let { { it.next(track) } }
        val queue = onQueue ?: actions?.let { { it.queue(track) } }
        if (next != null) DropdownMenuItem(text = { Text("Play next") }, onClick = { onDismiss(); next() })
        if (queue != null) DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onDismiss(); queue() })
        if (actions != null && youtube) DropdownMenuItem(text = { Text("Start radio") }, onClick = { onDismiss(); actions.radio(track) })
        val playlists = LocalPlaylistPicker.current
        if (youtube && playlists != null) DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { onDismiss(); playlists.open(track) })
        if (ratings.like != null) DropdownMenuItem(
            text = { Text(ratings.like) },
            enabled = track.id !in pending && states[track.id] != CloudLikeState.LOADING, onClick = { onDismiss(); cloud?.toggle(track.id) })
        if (ratings.dislike != null) DropdownMenuItem(
            text = { Text(ratings.dislike) },
            enabled = track.id !in pending, onClick = { onDismiss(); cloud?.toggleDislike(track.id) })
        links?.album?.let { album -> DropdownMenuItem(text = { Text("Go to album") }, onClick = { onDismiss(); navigation?.open?.invoke(album) }) }
        links?.artist?.let { artist -> DropdownMenuItem(text = { Text("Go to artist") }, onClick = { onDismiss(); navigation?.open?.invoke(artist) }) }
        if (youtube) DropdownMenuItem(text = { Text("Share") }, onClick = {
            onDismiss()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${track.id}")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share song"))
        })
        if (onLocalFavorite != null) DropdownMenuItem(
            text = { Text(if (localFavorite) "Remove local favorite" else "Save to local favorites") },
            onClick = { onDismiss(); onLocalFavorite() })
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun TrackContextMenuPreview() {
    val track = Track("wzpAehzNCU8", "A long track title that must wrap safely inside the menu", "Preview artist")
    val cloud = remember {
        cord.kasane.music.provider.CloudLikes({ CloudLikeState.LIKED }, { _, _ -> },
            writeDislike = { _, _ -> }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined).apply { seedLiked(listOf(track.id)) }
    }
    DisposableEffect(cloud) { onDispose { cloud.close() } }
    val playlists = remember {
        cord.kasane.music.provider.PlaylistPicker(cord.kasane.music.auth.AccountSession("SAPISID=preview-only"),
            object : cord.kasane.music.provider.AccountPlaylistProvider {
                override suspend fun playlistDestinations(account: cord.kasane.music.auth.AccountSession, videoId: String) = emptyList<cord.kasane.music.provider.PlaylistDestination>()
                override suspend fun addVideoToPlaylist(account: cord.kasane.music.auth.AccountSession, playlistId: String, videoId: String) {}
            }, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
    }
    DisposableEffect(playlists) { onDispose { playlists.close() } }
    val links = remember { kotlinx.coroutines.flow.MutableStateFlow(mapOf(track.id to cord.kasane.music.provider.TrackLinks(
        cord.kasane.music.model.CatalogItem("UCpreview", cord.kasane.music.model.CatalogKind.ARTIST, "Preview artist"),
        cord.kasane.music.model.CatalogItem("MPREpreview", cord.kasane.music.model.CatalogKind.ALBUM, "Preview album")))) }
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        CompositionLocalProvider(LocalCloudLikes provides cloud, LocalTrackActions provides TrackActions({}, {}, {}, {}),
            LocalPlaylistPicker provides playlists,
            LocalTrackNavigation provides TrackNavigation(links, {})) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(top = 24.dp, start = 16.dp)) {
                    TrackContextMenu(track, true, {}, {}, onLocalFavorite = {}, localFavorite = false)
                }
            }
        }
    }
}
