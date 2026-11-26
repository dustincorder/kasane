package cord.kasane.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState
import cord.kasane.music.provider.TrackLinks
import cord.kasane.ui.components.MenuEntry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** What a track or catalog entry can do; one implementation routes it to the player or navigation. */
interface Actions {
    fun play(track: Track)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun startRadio(track: Track)
    fun open(item: CatalogItem)
    /** A list in its own order (album, playlist, liked songs); shuffle picks the start. */
    fun playList(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false)
    /** YouTube Music ratings; false for guests, offline and local tracks — surfaces then hide the actions. */
    val likesEnabled: Boolean
    fun likeState(id: String): CloudLikeState?
    fun isLikePending(id: String): Boolean
    fun toggleLike(track: Track)
    fun toggleDislike(track: Track)
    fun addToPlaylist(track: Track)
    /** Open the synced-lyrics panel for this track. */
    fun lyrics(track: Track)
    fun share(track: Track)
    /** Album/artist destinations already resolved for this track, or null until [loadLinks] fills them in. */
    fun linksFor(id: String): TrackLinks?
    fun loadLinks(track: Track)
    /** Saved/downloading/failed state like Android's OfflineBadge; null when offline save does not apply. */
    fun offlineBadge(track: Track): cord.kasane.desktop.offline.OfflineBadge?
    fun saveOffline(track: Track)
    fun removeOffline(track: Track)
    fun retryOffline(track: Track)
}

/** Rating labels with Android's TrackMenuPolicy semantics: nothing for guests and local files. */
private fun ratingLabels(enabled: Boolean, track: Track, state: CloudLikeState?): Pair<String?, String?> =
    if (!enabled || track.isLocal || track.sourceProvider != "youtube") null to null else Pair(
        when (state) {
            CloudLikeState.LIKED -> "Remove YouTube Music like"
            CloudLikeState.LOADING -> "Loading YouTube Music rating…"
            CloudLikeState.UNKNOWN, null -> "Like on YouTube Music (status unknown)"
            else -> "Like on YouTube Music"
        },
        if (state == CloudLikeState.DISLIKED) "Remove YouTube Music dislike" else "Dislike on YouTube Music")

private const val MENU_GROUP_QUEUE = 0
private const val MENU_GROUP_PLAYBACK = 1
private const val MENU_GROUP_LIBRARY = 2
private const val MENU_GROUP_NAVIGATE = 3

/** The shared action list for a track: context menu and overflow button render these same entries. */
fun trackMenuItems(
    track: Track,
    actions: Actions,
    includePlay: Boolean = true,
    /** Entries prepended for a specific surface, e.g. queue operations. */
    extraTop: List<MenuEntry> = emptyList(),
): List<MenuEntry> {
    val state = actions.likeState(track.id)
    val pending = actions.isLikePending(track.id)
    val (likeLabel, dislikeLabel) = ratingLabels(actions.likesEnabled, track, state)
    val links = actions.linksFor(track.id)
    val youtube = !track.isLocal && track.sourceProvider == "youtube" && track.id.matches(Regex("[A-Za-z0-9_-]{11}"))
    return buildList {
        addAll(extraTop)
        if (includePlay) add(MenuEntry("Play", icon = Icons.Outlined.PlayArrow, group = MENU_GROUP_PLAYBACK) { actions.play(track) })
        add(MenuEntry("Play next", icon = Icons.Outlined.SkipNext, group = MENU_GROUP_PLAYBACK) { actions.playNext(track) })
        add(MenuEntry("Add to queue", icon = Icons.AutoMirrored.Outlined.QueueMusic, group = MENU_GROUP_PLAYBACK) { actions.addToQueue(track) })
        if (youtube) add(MenuEntry("Start radio", icon = Icons.Outlined.Radio, group = MENU_GROUP_PLAYBACK) { actions.startRadio(track) })
        likeLabel?.let { label ->
            add(MenuEntry(
                label = label,
                enabled = !pending && state != CloudLikeState.LOADING,
                icon = if (state == CloudLikeState.LIKED) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                group = MENU_GROUP_LIBRARY,
            ) { actions.toggleLike(track) })
        }
        dislikeLabel?.let { label ->
            add(MenuEntry(
                label = label,
                enabled = !pending,
                icon = if (state == CloudLikeState.DISLIKED) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                group = MENU_GROUP_LIBRARY,
            ) { actions.toggleDislike(track) })
        }
        if (youtube && actions.likesEnabled) {
            add(MenuEntry("Add to playlist", icon = Icons.AutoMirrored.Outlined.PlaylistAdd, group = MENU_GROUP_LIBRARY) { actions.addToPlaylist(track) })
        }
        when (actions.offlineBadge(track)) {
            cord.kasane.desktop.offline.OfflineBadge.SAVED ->
                add(MenuEntry("Remove from offline", icon = Icons.Outlined.DownloadDone, group = MENU_GROUP_LIBRARY) { actions.removeOffline(track) })
            cord.kasane.desktop.offline.OfflineBadge.DOWNLOADING, cord.kasane.desktop.offline.OfflineBadge.QUEUED ->
                add(MenuEntry("Cancel download", icon = Icons.Outlined.Close, group = MENU_GROUP_LIBRARY) { actions.removeOffline(track) })
            cord.kasane.desktop.offline.OfflineBadge.FAILED ->
                add(MenuEntry("Retry saving offline", icon = Icons.Outlined.Refresh, group = MENU_GROUP_LIBRARY) { actions.retryOffline(track) })
            else -> if (youtube) add(MenuEntry("Save offline", icon = Icons.Outlined.Download, group = MENU_GROUP_LIBRARY) { actions.saveOffline(track) })
        }
        links?.album?.let { album ->
            add(MenuEntry("Go to album", icon = Icons.Outlined.Album, group = MENU_GROUP_NAVIGATE) { actions.open(album) })
        }
        links?.artist?.let { artist ->
            add(MenuEntry("Go to artist", icon = Icons.Outlined.Person, group = MENU_GROUP_NAVIGATE) { actions.open(artist) })
        }
        add(MenuEntry("Lyrics", icon = Icons.Outlined.Lyrics, group = MENU_GROUP_NAVIGATE) { actions.lyrics(track) })
        if (youtube) {
            add(MenuEntry("Share link", icon = Icons.Outlined.Share, group = MENU_GROUP_NAVIGATE) { actions.share(track) })
        }
    }
}

/**
 * Coalesces rapid calls (slider drags) to at most one per [intervalMs]; the latest value
 * always lands. Used where each call crosses the wire or a heavy backend.
 */
@Composable
fun <T> rememberCoalesced(intervalMs: Long = 80L, block: (T) -> Unit): (T) -> Unit {
    val latestBlock = rememberUpdatedState(block)
    val pending = remember { kotlinx.coroutines.flow.MutableStateFlow<T?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val v = pending.filterNotNull().first()
            if (pending.compareAndSet(v, null)) latestBlock.value(v)
            kotlinx.coroutines.delay(intervalMs)
        }
    }
    return remember { { v: T -> pending.value = v } }
}

/** Unified Material 3 dropdown menu with icons and group dividers for both right-click and overflow triggers. */
@Composable
fun TrackDropdownMenu(
    entries: List<MenuEntry>,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.medium,
        containerColor = colors.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 288.dp),
    ) {
        val groups = entries.groupBy { it.group }.entries.sortedBy { it.key }.map { it.value }
        groups.forEachIndexed { groupIdx, group ->
            if (groupIdx > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = colors.outlineVariant,
                )
            }
            group.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = item.icon?.let { icon ->
                        {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    enabled = item.enabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = MenuDefaults.itemColors(
                        textColor = colors.onSurface,
                        leadingIconColor = colors.onSurfaceVariant,
                        disabledTextColor = colors.onSurface.copy(alpha = 0.38f),
                        disabledLeadingIconColor = colors.onSurfaceVariant.copy(alpha = 0.38f),
                    ),
                    onClick = {
                        onDismiss()
                        item.action()
                    },
                )
            }
        }
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackMenu(
    track: Track,
    actions: Actions,
    extraTop: List<MenuEntry> = emptyList(),
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    LaunchedEffect(open, track.id) {
        if (open) actions.loadLinks(track)
    }
    Box(
        Modifier.pointerInput(track.id) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val pos = event.changes.first().position
                        event.changes.forEach { it.consume() }
                        offset = DpOffset(pos.x.toDp(), pos.y.toDp())
                        open = true
                    }
                }
            }
        },
    ) {
        content()
        if (open) {
            Box(Modifier.offset(x = offset.x, y = offset.y).size(0.dp)) {
                TrackDropdownMenu(
                    entries = trackMenuItems(track, actions, includePlay = true, extraTop = extraTop),
                    expanded = open,
                    onDismiss = { open = false },
                )
            }
        }
    }
}

/** A song row: click plays, right-click or overflow button opens the unified Material 3 menu. */
@Composable
fun TrackRow(
    track: Track,
    actions: Actions,
    index: Int? = null,
    current: Boolean = false,
    menuExtras: List<MenuEntry> = emptyList(),
    onPlay: () -> Unit = { actions.play(track) },
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    TrackMenu(track, actions, menuExtras) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(
                    when {
                        current -> colors.secondaryContainer.copy(alpha = 0.65f)
                        hovered -> colors.surfaceContainerHigh
                        else -> Color.Transparent
                    }
                )
                .hoverable(hover)
                .clickable(onClick = onPlay)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    when {
                        current -> Icon(Icons.Filled.GraphicEq, "Playing", Modifier.size(18.dp), tint = colors.primary)
                        hovered -> Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(18.dp), tint = colors.onSurface)
                        else -> Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Artwork(track.thumbnailUri, 44.dp, MaterialTheme.shapes.small)
                if (current && index == null) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.GraphicEq, "Playing", tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (current) colors.primary else colors.onSurface,
                )
                Text(
                    listOfNotNull(track.artist.takeIf(String::isNotBlank), track.album?.takeIf(String::isNotBlank)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val state = actions.likeState(track.id)
            val liked = state == CloudLikeState.LIKED
            val likesOn = actions.likesEnabled && !track.isLocal && track.sourceProvider == "youtube"
            if (likesOn && (hovered || liked)) {
                IconButton(
                    onClick = { actions.toggleLike(track) },
                    enabled = !actions.isLikePending(track.id),
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (liked) "Unlike" else "Like",
                        tint = if (liked) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (track.durationSeconds > 0) {
                Text(
                    formatTime(track.durationSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 42.dp).padding(horizontal = 4.dp),
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            LaunchedEffect(menuOpen) { if (menuOpen) actions.loadLinks(track) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        "More options for ${track.title}",
                        Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
                TrackDropdownMenu(
                    entries = trackMenuItems(track, actions, includePlay = false, extraTop = menuExtras),
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

/** A catalog card for shelves: square for songs/albums/playlists, round for artists. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogCard(item: CatalogItem, actions: Actions, width: Dp = 156.dp) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val track = item.track()
    val isArtist = item.kind == CatalogKind.ARTIST
    val body: @Composable () -> Unit = {
        Column(
            Modifier
                .width(width)
                .clip(MaterialTheme.shapes.medium)
                .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                .hoverable(hover)
                .clickable { if (track != null) actions.play(track) else actions.open(item) }
                .padding(8.dp),
            horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Box {
                Artwork(
                    item.artwork,
                    width - 16.dp,
                    if (isArtist) CircleShape else MaterialTheme.shapes.medium,
                )
                if (hovered && !isArtist) {
                    FilledIconButton(
                        onClick = { if (track != null) actions.play(track) else actions.open(item) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    if (track != null) TrackMenu(track, actions, content = body) else body()
}

@Composable
fun ShelfRow(shelf: CatalogShelf, actions: Actions, onMore: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val more = shelf.moreBrowseId
            if (more != null && onMore != null) {
                TextButton(onClick = { onMore(more) }) { Text("See all") }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shelf.items, key = { it.kind.name + it.id }) { CatalogCard(it, actions) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp))

@Composable
fun Message(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(24.dp))
