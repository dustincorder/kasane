package cord.kasane.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalDensity
import cord.kasane.desktop.SystemScale
import cord.kasane.ui.settings.AccentSource
import cord.kasane.ui.theme.FallbackSeed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cord.kasane.desktop.DesktopApp
import cord.kasane.desktop.playback.PlayerState
import cord.kasane.music.auth.AccountState
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.Track
import cord.kasane.music.provider.CloudLikeState
import cord.kasane.music.connect.toMeta
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library"), PROFILE("Account"), SETTINGS("Settings") }

/** Window-level navigation, so keyboard shortcuts work regardless of focus. */
class Nav {
    var tab by mutableStateOf(Tab.HOME)
    var returnTab by mutableStateOf(Tab.HOME)
    var stack by mutableStateOf(listOf<CatalogItem>())
    var connectOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)
    /** Track the lyrics panel was opened for from a track menu; null = the playing track. */
    var lyricsTrack by mutableStateOf<Track?>(null)
    var nowPlaying by mutableStateOf(false)
    var libraryTab by androidx.compose.runtime.mutableIntStateOf(0)
    var searchQuery by mutableStateOf("")
    /** A text field has focus: plain keys belong to it. */
    var typing by mutableStateOf(false)
    val searchFocus = FocusRequester()
    /** The routed controls of the window (this computer or the current Connect device). */
    var controls: PlayerControls? = null

    fun openProfile() {
        if (tab != Tab.PROFILE) returnTab = tab
        nowPlaying = false
        connectOpen = false
        stack = emptyList()
        tab = Tab.PROFILE
    }

    fun back(): Boolean = when {
        nowPlaying -> { nowPlaying = false; true }
        connectOpen -> { connectOpen = false; true }
        stack.isNotEmpty() -> { stack = stack.dropLast(1); true }
        tab == Tab.PROFILE -> { tab = returnTab; true }
        tab == Tab.SETTINGS -> { tab = Tab.HOME; true }
        else -> false
    }
}

fun runUi() = application {
    val app = remember { DesktopApp() }
    val nav = remember { Nav() }
    val systemScale = remember { SystemScale.detect() }
    val initial = remember { initialWindowSize(systemScale * app.settings.prefs.value.uiScale) }
    val window = rememberWindowState(size = initial)
    Window(onCloseRequest = { app.close(); exitApplication() }, state = window, title = "Kasane",
        icon = painterResource("kasane.png"), onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when {
                e.key == Key.Spacebar && !nav.typing && !e.isCtrlPressed && !e.isAltPressed -> { nav.controls?.toggle(); true }
                e.isCtrlPressed && e.key == Key.DirectionRight -> { nav.controls?.next(); true }
                e.isCtrlPressed && e.key == Key.DirectionLeft -> { nav.controls?.previous(); true }
                e.isCtrlPressed && e.key == Key.F -> {
                    nav.nowPlaying = false; nav.stack = emptyList(); nav.tab = Tab.SEARCH
                    runCatching { nav.searchFocus.requestFocus() }; true
                }
                e.isCtrlPressed && (e.key == Key.Equals || e.key == Key.Plus) -> { app.settings.update { it.copy(uiScale = stepScale(it.uiScale, +1)) }; true }
                e.isCtrlPressed && e.key == Key.Minus -> { app.settings.update { it.copy(uiScale = stepScale(it.uiScale, -1)) }; true }
                e.isCtrlPressed && e.key == Key.Zero -> { app.settings.update { it.copy(uiScale = 1f) }; true }
                e.key == Key.Escape || (e.isAltPressed && e.key == Key.DirectionLeft) -> nav.back()
                else -> false
            }
        }) {
        val prefs by app.settings.prefs.collectAsState()
        val artwork = remember { DesktopArtworkLoader(app.http) }
        val seed = if (prefs.accentSource == AccentSource.MANUAL) Color(prefs.accent) else FallbackSeed
        KasaneTheme(prefs.theme, seed, artwork) {
            ScaledDensity(systemScale, prefs.uiScale) {
                val density = LocalDensity.current
                androidx.compose.runtime.LaunchedEffect(density.density) {
                    this@Window.window.minimumSize = java.awt.Dimension((MIN_WIDTH_DP * density.density).toInt(), (MIN_HEIGHT_DP * density.density).toInt())
                }
                KasaneWindow(app, nav)
            }
        }
    }
}

const val MIN_WIDTH_DP = 720
const val MIN_HEIGHT_DP = 520

/** 10 % steps between 75 % and 250 %, for Ctrl +/-. */
fun stepScale(current: Float, direction: Int): Float = ((current * 100).toInt() / 5 * 5 + direction * 10).coerceIn(75, 250) / 100f

/** About 1320x860 dp, never more than 90 % of the screen, so a scaled UI still opens fully on screen. */
private fun initialWindowSize(effective: Double): DpSize {
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    val awt = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX }.getOrDefault(1.0)
    // Window sizes are in AWT units; the UI draws at awt * effective-if-not-already-applied.
    val density = if (awt > 1.01) awt else effective
    val w = minOf(1320.0 * density, screen.width * 0.9) / awt
    val h = minOf(860.0 * density, screen.height * 0.9) / awt
    return DpSize(w.dp, h.dp)
}

@Composable
internal fun KasaneWindow(app: DesktopApp, nav: Nav) {
    val likeStates by app.likes.states.collectAsState()
    val likePending by app.likes.pending.collectAsState()
    val trackLinks by app.catalog.trackLinks.collectAsState()
    val offlineRecords by app.offline.records.collectAsState()
    val playlistPicker = remember { cord.kasane.desktop.library.DesktopPlaylistPicker(app.scope, { app.account.session() }, app.catalog) }
    val localState: PlayerState by app.player.state.collectAsState()
    val remote = app.connect?.remote
    val target by (remote?.target ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val remoteStates by (app.connect?.manager?.remoteStates ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap()) }).collectAsState()
    val devices by (app.connect?.manager?.devices ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    // Re-project the remote position every half second while it plays.
    var tick by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(target) { while (target != null) { kotlinx.coroutines.delay(500); tick++ } }
    val remoteFrame = target?.let(remoteStates::get)
    val state: PlayerState = if (remote != null && target != null && remoteFrame != null) { tick; remote.project(target!!, remoteFrame) } else localState
    val remoteName = if (remoteFrame != null) target?.let { id -> devices.firstOrNull { it.id == id }?.name } else null
    val saveable = rememberSaveableStateHolder()
    fun routed(local: () -> Unit, action: String, args: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) =
        if (remote != null && remoteName != null) remote.send(action, args) else local()
    fun trackArgs(t: Track) = kotlinx.serialization.json.buildJsonObject {
        put("track", cord.kasane.music.connect.ConnectJson.json.encodeToJsonElement(cord.kasane.music.connect.TrackMeta.serializer(), t.toMeta()))
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        launch { playlistPicker.messages.collect { app.player.notify(it) } }
        launch { app.likes.errors.collect { app.player.notify(it) } }
    }
    val actions = remember(likeStates, likePending, trackLinks, offlineRecords, remoteName) {
        object : Actions {
            override fun play(track: Track) = routed({ app.player.playTrack(track) }, "playTrack", trackArgs(track))
            override fun playNext(track: Track) = routed({ app.player.playNext(track) }, "playNext", trackArgs(track))
            override fun addToQueue(track: Track) = routed({ app.player.addToQueue(track) }, "addToQueue", trackArgs(track))
            override fun startRadio(track: Track) = routed({ app.player.startRadio(track) }, "playTrack", trackArgs(track))
            override fun open(item: CatalogItem) { nav.stack = nav.stack + item }
            override fun playList(tracks: List<Track>, index: Int, shuffle: Boolean) {
                val id = target
                if (remote == null || remoteName == null || id == null) return app.player.playCollection(tracks, index, shuffle)
                val start = if (shuffle && tracks.isNotEmpty()) tracks.indices.random() else index
                app.scope.launch { runCatching { remote.manager.handoff(id, cord.kasane.music.connect.Frame.Handoff("",
                    tracks.map { it.toMeta() }, start.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0, true, shuffle, "off")) } }
            }
            // YouTube Music ratings exist only while a session does; the UI hides the actions otherwise.
            override val likesEnabled: Boolean get() = app.account.session() != null
            override fun likeState(id: String): CloudLikeState? {
                app.likes.refresh(id)
                return likeStates[id]
            }
            override fun isLikePending(id: String) = id in likePending
            override fun toggleLike(track: Track) = app.likes.toggle(track)
            override fun toggleDislike(track: Track) = app.likes.toggleDislike(track)
            override fun addToPlaylist(track: Track) = playlistPicker.open(track)
            override fun lyrics(track: Track) { nav.lyricsTrack = track; nav.queueOpen = false; nav.lyricsOpen = true }
            override fun share(track: Track) {
                val link = "https://music.youtube.com/watch?v=${track.id}"
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(link), null)
                    app.player.notify("Link copied to clipboard")
                }.onFailure { app.player.notify("Could not copy the link.") }
            }
            override fun linksFor(id: String) = trackLinks[id]
            override fun loadLinks(track: Track) {
                if (track.isLocal || track.sourceProvider != "youtube" || !track.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return
                if (trackLinks.containsKey(track.id)) return
                app.scope.launch { runCatching { app.catalog.loadTrackLinks(track.id) } }
            }
            // Offline badge states mirror Android's OfflineBadge→menu mapping.
            override fun offlineBadge(track: Track): cord.kasane.desktop.offline.OfflineBadge? =
                if (cord.kasane.desktop.offline.DesktopOffline.canSave(track)) app.offline.badge(track) else null
            override fun saveOffline(track: Track) {
                app.scope.launch {
                    val summary = app.offline.save(listOf(track))
                    app.player.notify(if (summary.queued > 0) "Saving for offline playback"
                        else if (summary.alreadySaved > 0) "Already saved on this computer" else "This song cannot be saved offline")
                }
            }
            override fun removeOffline(track: Track) = app.offline.remove(track.id)
            override fun retryOffline(track: Track) = app.offline.retry(track.id)
        }
    }
    val controls = remember(remoteName, state.shuffle, state.repeat) {
        object : PlayerControls {
            override fun toggle() = routed({ app.player.toggle() }, "toggle")
            override fun next() = routed({ app.player.next() }, "next")
            override fun previous() = routed({ app.player.previous() }, "previous")
            override fun seek(sec: Double) = routed({ app.player.seek(sec) }, "seek", kotlinx.serialization.json.buildJsonObject { put("positionMs", (sec * 1000).toLong()) })
            override fun setVolume(v: Float) = if (remote != null && remoteName != null) remote.setVolume(v) else app.player.setVolume(v)
            override fun toggleShuffle() = routed({ app.player.toggleShuffle() }, "setShuffle", kotlinx.serialization.json.buildJsonObject { put("enabled", !state.shuffle) })
            override fun cycleRepeat() = routed({ app.player.cycleRepeat() }, "setRepeat", kotlinx.serialization.json.buildJsonObject {
                put("mode", cord.kasane.desktop.playback.RepeatMode.entries[(state.repeat.ordinal + 1) % 3].name.lowercase()) })
            override fun playIndex(i: Int) = routed({ app.player.playIndex(i) }, "playQueueIndex", kotlinx.serialization.json.buildJsonObject { put("index", i); put("queueRev", remote?.queueRev() ?: 0) })
            override fun remove(i: Int) = routed({ app.player.removeAt(i) }, "removeQueueIndex", kotlinx.serialization.json.buildJsonObject { put("index", i); put("queueRev", remote?.queueRev() ?: 0) })
            override fun move(from: Int, to: Int) = routed({ app.player.move(from, to) }, "moveQueue", kotlinx.serialization.json.buildJsonObject { put("from", from); put("to", to); put("queueRev", remote?.queueRev() ?: 0) })
        }
    }
    val openSettings = { nav.nowPlaying = false; nav.stack = emptyList(); nav.tab = Tab.SETTINGS; nav.connectOpen = true }
    androidx.compose.runtime.SideEffect { nav.controls = controls }
    val openMore: (String) -> Unit = { id -> nav.stack = nav.stack + CatalogItem(id, CatalogKind.PLAYLIST.takeIf { id.startsWith("VL") } ?: CatalogKind.ALBUM, "") }

    val prefs by app.settings.prefs.collectAsState()
    val accountState by app.account.state.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // A stored session being validated must not flash the first-run flow — hold a splash.
        if (!prefs.onboardingDone && accountState == AccountState.Checking) {
            OnboardingSplash()
        // A live session skips the first-run flow even if `onboardingDone` was lost or never set.
        } else if (cord.kasane.music.auth.SessionPolicy.showsOnboarding(prefs.onboardingDone, accountState)) {
            OnboardingScreen(app, onDone = {})
        } else Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    val railItemColors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        header = {
                            ProfileMenu(
                                app = app,
                                selected = nav.tab == Tab.PROFILE && nav.stack.isEmpty(),
                                onAccount = { nav.openProfile() },
                                onHistory = { nav.nowPlaying = false; nav.stack = emptyList(); nav.connectOpen = false; nav.libraryTab = 0; nav.tab = Tab.LIBRARY },
                                onDownloads = { nav.nowPlaying = false; nav.stack = emptyList(); nav.connectOpen = false; nav.libraryTab = 1; nav.tab = Tab.LIBRARY },
                                onConnect = { nav.nowPlaying = false; nav.stack = emptyList(); nav.tab = Tab.SETTINGS; nav.connectOpen = true },
                                onSettings = { nav.nowPlaying = false; nav.stack = emptyList(); nav.tab = Tab.SETTINGS; nav.connectOpen = false },
                            )
                            HorizontalDivider(
                                modifier = Modifier.width(32.dp).padding(bottom = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        },
                        modifier = Modifier.width(80.dp).fillMaxHeight(),
                    ) {
                        listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY).forEach { t ->
                            val selected = nav.tab == t
                            NavigationRailItem(
                                selected = selected,
                                onClick = { nav.tab = t; nav.stack = emptyList(); nav.connectOpen = false },
                                label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                                colors = railItemColors,
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
                                            Tab.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
                                            Tab.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
                                            else -> Icons.Outlined.Home
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val settingsSelected = nav.tab == Tab.SETTINGS
                        NavigationRailItem(
                            selected = settingsSelected,
                            onClick = { nav.tab = Tab.SETTINGS; nav.stack = emptyList(); nav.connectOpen = false },
                            label = { Text(Tab.SETTINGS.label, style = MaterialTheme.typography.labelMedium) },
                            colors = railItemColors,
                            icon = {
                                Icon(
                                    if (settingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        val top = nav.stack.lastOrNull()
                        if (top != null) {
                            Column(Modifier.fillMaxSize()) {
                                SubScreenHeader(
                                    title = top.kind.label.removeSuffix("s"),
                                    onBack = { nav.back() },
                                )
                                saveable.SaveableStateProvider("browse:${nav.stack.size}:${top.id}") {
                                    BrowseScreen(app, top, actions, openMore)
                                }
                            }
                        } else Crossfade(nav.tab, animationSpec = tween(200), label = "tabSwitch") { tab ->
                            saveable.SaveableStateProvider(tab.name) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(app, actions, openMore)
                                Tab.SEARCH -> SearchScreen(
                                    app = app,
                                    actions = actions,
                                    focus = nav.searchFocus,
                                    query = nav.searchQuery,
                                    onQueryChange = { nav.searchQuery = it },
                                    onTyping = { nav.typing = it },
                                )
                                Tab.LIBRARY -> LibraryScreen(app, actions, nav.libraryTab) { nav.libraryTab = it }
                                Tab.PROFILE -> ProfileScreen(
                                    app = app,
                                    onBack = { nav.back() },
                                    onOpenLikes = {
                                        nav.tab = Tab.LIBRARY; nav.libraryTab = 2
                                        if (accountState is AccountState.SignedIn) nav.stack = listOf(YtmLikedSongs)
                                    },
                                    onOpenHistory = { nav.libraryTab = 0; nav.tab = Tab.LIBRARY },
                                    onOpenDownloads = { nav.libraryTab = 1; nav.tab = Tab.LIBRARY },
                                    onOpenConnect = { nav.tab = Tab.SETTINGS; nav.connectOpen = true },
                                    onOpenSettings = { nav.tab = Tab.SETTINGS; nav.connectOpen = false },
                                )
                                Tab.SETTINGS -> if (nav.connectOpen) {
                                    Column(Modifier.fillMaxSize()) {
                                        SubScreenHeader(
                                            title = "Kasane Connect",
                                            onBack = { nav.back() },
                                        )
                                        ConnectSettings(app)
                                    }
                                } else SettingsScreen(
                                    app = app,
                                    onConnect = { nav.connectOpen = true },
                                    onAccount = { nav.openProfile() },
                                    onManageSaved = { nav.libraryTab = 1; nav.tab = Tab.LIBRARY },
                                )
                            }
                        }
                    }
                    }
                    // Only one side panel at a time — two open panels would squeeze the content below usable width.
                    AnimatedVisibility(
                        nav.queueOpen && !nav.lyricsOpen,
                        enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
                        exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180)),
                    ) {
                        QueuePanel(state, controls, actions) { nav.queueOpen = false }
                    }
                    AnimatedVisibility(
                        nav.lyricsOpen && (state.current != null || nav.lyricsTrack != null),
                        enter = slideInHorizontally(tween(220)) { it } + fadeIn(tween(220)),
                        exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180)),
                    ) {
                        LyricsPanel(app, state, controls, track = nav.lyricsTrack) { nav.lyricsOpen = false; nav.lyricsTrack = null }
                    }
                }
                if (state.queue.isNotEmpty()) PlayerBar(state, controls, actions, nav.queueOpen,
                    onToggleQueue = { nav.queueOpen = !nav.queueOpen; if (nav.queueOpen) nav.lyricsOpen = false },
                    onExpand = { if (state.current != null) nav.nowPlaying = true },
                    deviceButton = { DeviceButton(app, openSettings) }, deviceLabel = remoteName?.let { "Playing on $it" },
                    lyricsOpen = nav.lyricsOpen, onToggleLyrics = { nav.lyricsOpen = !nav.lyricsOpen; nav.lyricsTrack = null; if (nav.lyricsOpen) nav.queueOpen = false })
            }
            AnimatedVisibility(
                nav.nowPlaying && state.current != null,
                enter = slideInVertically(tween(220)) { it / 6 } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it / 6 } + fadeOut(tween(180)),
            ) {
                NowPlaying(state, controls, actions, onClose = { nav.nowPlaying = false },
                    deviceButton = { DeviceButton(app, openSettings) },
                    onShowLyrics = { nav.nowPlaying = false; nav.queueOpen = false; nav.lyricsTrack = null; nav.lyricsOpen = true })
            }
            PlaylistPickerDialog(playlistPicker)
            PairingPrompts(app)
            localState.notice?.let { notice ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 96.dp)) {
                    Text(notice, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
internal fun SubScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
