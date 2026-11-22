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

/** One menu action. `action` never dismisses anything; callers decide what happens around it. */
class MenuEntry(val label: String, val enabled: Boolean = true, val action: () -> Unit)

/**
 * Single source of truth for a track's actions. The long-press menu, the trailing overflow button and the
 * TalkBack actions list all render these same entries, so they can never drift apart.
 */
@Composable
fun rememberTrackMenuEntries(
    track: Track, onPlay: () -> Unit,
    onNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false,
    extra: List<MenuEntry> = emptyList(),
    refreshRating: Boolean = false,
): List<MenuEntry> {
    val navigation = LocalTrackNavigation.current
    val allLinks by key(navigation) { (navigation?.links ?: EmptyTrackLinks).collectAsState() }
    val links = allLinks[track.id]
    val actions = LocalTrackActions.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val youtube = !track.isLocal && track.sourceProvider == "youtube" && track.id.matches(Regex("[A-Za-z0-9_-]{11}"))
    val cloud = LocalCloudLikes.current?.takeIf { !track.isLocal && track.sourceProvider == "youtube" }
    val states by key(cloud) { (cloud?.states ?: EmptyCloudLikes).collectAsState() }
    val pending by key(cloud) { (cloud?.pending ?: EmptyPendingLikes).collectAsState() }
    LaunchedEffect(refreshRating, cloud, track.id) { if (refreshRating) cloud?.refresh(track.id) }
    val ratings = TrackMenuPolicy.ratings(cloud != null, track.isLocal, states[track.id], cloud?.supportsDislike == true)
    val offlineActions = LocalOfflineActions.current
    val offlineBadge = LocalOfflineBadges.current[track.id]
    val playlists = LocalPlaylistPicker.current
    return buildList {
        add(MenuEntry("Play", action = onPlay))
        (onNext ?: actions?.let { { it.next(track) } })?.let { add(MenuEntry("Play next", action = it)) }
        (onQueue ?: actions?.let { { it.queue(track) } })?.let { add(MenuEntry("Add to queue", action = it)) }
        addAll(extra)
        if (actions != null && youtube) add(MenuEntry("Start radio") { actions.radio(track) })
        if (offlineActions != null && cord.kasane.music.offline.OfflinePolicy.canRequestSave(track)) add(when (offlineBadge) {
            OfflineBadge.SAVED -> MenuEntry("Remove from offline") { offlineActions.remove(track.id) }
            OfflineBadge.DOWNLOADING -> MenuEntry("Cancel download") { offlineActions.remove(track.id) }
            OfflineBadge.FAILED -> MenuEntry("Retry saving offline") { offlineActions.retry(track.id) }
            else -> MenuEntry("Save offline") { offlineActions.save(listOf(track)) }
        })
        if (youtube && playlists != null) add(MenuEntry("Add to playlist") { playlists.open(track) })
        ratings.like?.let { add(MenuEntry(it, enabled = track.id !in pending && states[track.id] != CloudLikeState.LOADING) { cloud?.toggle(track.id) }) }
        ratings.dislike?.let { add(MenuEntry(it, enabled = track.id !in pending) { cloud?.toggleDislike(track.id) }) }
        links?.album?.let { album -> add(MenuEntry("Go to album") { navigation?.open?.invoke(album) }) }
        links?.artist?.let { artist -> add(MenuEntry("Go to artist") { navigation?.open?.invoke(artist) }) }
        if (youtube) add(MenuEntry("Share") {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${track.id}")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share song"))
        })
        if (onLocalFavorite != null) add(MenuEntry(if (localFavorite) "Remove local favorite" else "Save to local favorites", action = onLocalFavorite))
    }
}

/** Shared menu for track rows, long press, and Now Playing; only wired actions are shown. */
@Composable
fun TrackContextMenu(
    track: Track, expanded: Boolean, onDismiss: () -> Unit, onPlay: () -> Unit,
    onNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false,
    extra: List<MenuEntry> = emptyList(),
) {
    val entries = rememberTrackMenuEntries(track, onPlay, onNext, onQueue, onLocalFavorite, localFavorite, extra, refreshRating = expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMediumMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        entries.forEach { entry -> DropdownMenuItem(text = { Text(entry.label) }, enabled = entry.enabled, onClick = { onDismiss(); entry.action() }) }
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
    KasaneTheme(dynamicColor = false) {
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
