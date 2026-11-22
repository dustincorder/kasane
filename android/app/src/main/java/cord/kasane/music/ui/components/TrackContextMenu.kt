package cord.kasane.music.ui.components

import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
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
class MenuEntry(val label: String, val enabled: Boolean = true, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val group: Int = GROUP_LIBRARY, val action: () -> Unit) {
    companion object { const val GROUP_PLAYBACK = 0; const val GROUP_LIBRARY = 1; const val GROUP_GO = 2 }
}

/** The id of the track that is playing now; its menu omits "Play", which would do nothing. */
val LocalCurrentTrackId = staticCompositionLocalOf<String?> { null }

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
    val currentId = LocalCurrentTrackId.current
    val pb = MenuEntry.GROUP_PLAYBACK; val lib = MenuEntry.GROUP_LIBRARY; val go = MenuEntry.GROUP_GO
    return buildList {
        if (track.id != currentId) add(MenuEntry("Play", icon = Icons.Default.PlayArrow, group = pb, action = onPlay))
        (onNext ?: actions?.let { { it.next(track) } })?.let { add(MenuEntry("Play next", icon = Icons.Default.SkipNext, group = pb, action = it)) }
        (onQueue ?: actions?.let { { it.queue(track) } })?.let { add(MenuEntry("Add to queue", icon = Icons.AutoMirrored.Filled.PlaylistAdd, group = pb, action = it)) }
        if (actions != null && youtube) add(MenuEntry("Start radio", icon = Icons.Default.Radio, group = pb) { actions.radio(track) })
        addAll(extra)
        ratings.like?.let { add(MenuEntry(it, enabled = track.id !in pending && states[track.id] != CloudLikeState.LOADING,
            icon = if (states[track.id] == CloudLikeState.LIKED) Icons.Default.Favorite else Icons.Default.FavoriteBorder, group = lib) { cloud?.toggle(track.id) }) }
        ratings.dislike?.let { add(MenuEntry(it, enabled = track.id !in pending, icon = Icons.Default.ThumbDown, group = lib) { cloud?.toggleDislike(track.id) }) }
        if (youtube && playlists != null) add(MenuEntry("Add to playlist", icon = Icons.AutoMirrored.Filled.PlaylistAdd, group = lib) { playlists.open(track) })
        if (offlineActions != null && cord.kasane.music.offline.OfflinePolicy.canRequestSave(track)) add(when (offlineBadge) {
            OfflineBadge.SAVED -> MenuEntry("Remove from offline", icon = Icons.Default.DownloadDone, group = lib) { offlineActions.remove(track.id) }
            OfflineBadge.DOWNLOADING -> MenuEntry("Cancel download", icon = Icons.Default.Close, group = lib) { offlineActions.remove(track.id) }
            OfflineBadge.FAILED -> MenuEntry("Retry saving offline", icon = Icons.Default.Refresh, group = lib) { offlineActions.retry(track.id) }
            else -> MenuEntry("Save offline", icon = Icons.Default.Download, group = lib) { offlineActions.save(listOf(track)) }
        })
        if (onLocalFavorite != null) add(MenuEntry(if (localFavorite) "Remove local favorite" else "Save to local favorites",
            icon = if (localFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, group = lib, action = onLocalFavorite))
        links?.album?.let { album -> add(MenuEntry("Go to album", icon = Icons.Default.Album, group = go) { navigation?.open?.invoke(album) }) }
        links?.artist?.let { artist -> add(MenuEntry("Go to artist", icon = Icons.Default.Person, group = go) { navigation?.open?.invoke(artist) }) }
        if (youtube) add(MenuEntry("Share", icon = Icons.Default.Share, group = go) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${track.id}")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share song"))
        })
    }
}

/**
 * Track actions as a modal bottom sheet: header with the track, then grouped, labelled actions with icons.
 * It scrolls when there are more actions than the screen holds. Used by rows, grid items and the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenu(
    track: Track, expanded: Boolean, onDismiss: () -> Unit, onPlay: () -> Unit,
    onNext: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    onLocalFavorite: (() -> Unit)? = null, localFavorite: Boolean = false,
    extra: List<MenuEntry> = emptyList(),
) {
    val entries = rememberTrackMenuEntries(track, onPlay, onNext, onQueue, onLocalFavorite, localFavorite, extra, refreshRating = expanded)
    if (!expanded) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        TrackActions(track, entries, onDismiss)
    }
}

@Composable
fun TrackActions(track: Track, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(track.thumbnailUri, Modifier.size(52.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodyMediumMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        entries.groupBy { it.group }.toSortedMap().values.forEach { group ->
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            group.forEach { entry ->
                ListItem(
                    headlineContent = { Text(entry.label, style = MaterialTheme.typography.bodyLarge) },
                    leadingContent = entry.icon?.let { icon -> { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        headlineColor = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                    modifier = Modifier.heightIn(min = 52.dp).clickable(enabled = entry.enabled) { onDismiss(); entry.action() })
            }
        }
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 412, heightDp = 700)
@Composable
fun TrackActionsPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            val t = Track("wzpAehzNCU8", "A long track title that must wrap safely inside the sheet header", "Preview artist")
            TrackActions(t, listOf(
                MenuEntry("Play next", icon = Icons.Default.SkipNext, group = MenuEntry.GROUP_PLAYBACK) {},
                MenuEntry("Add to queue", icon = Icons.AutoMirrored.Filled.PlaylistAdd, group = MenuEntry.GROUP_PLAYBACK) {},
                MenuEntry("Start radio", icon = Icons.Default.Radio, group = MenuEntry.GROUP_PLAYBACK) {},
                MenuEntry("Like on YouTube Music", icon = Icons.Default.FavoriteBorder) {},
                MenuEntry("Add to playlist", icon = Icons.AutoMirrored.Filled.PlaylistAdd) {},
                MenuEntry("Save offline", icon = Icons.Default.Download) {},
                MenuEntry("Go to album", icon = Icons.Default.Album, group = MenuEntry.GROUP_GO) {},
                MenuEntry("Go to artist", icon = Icons.Default.Person, group = MenuEntry.GROUP_GO) {},
                MenuEntry("Share", icon = Icons.Default.Share, group = MenuEntry.GROUP_GO) {}), {})
        }
    }
}
