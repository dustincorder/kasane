package cord.kasane.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
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
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogKind
import cord.kasane.music.model.Track
import cord.kasane.music.connect.toMeta
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

enum class Tab(val label: String) { HOME("Home"), SEARCH("Search"), LIBRARY("Library"), SETTINGS("Settings") }

/** Window-level navigation, so keyboard shortcuts work regardless of focus. */
class Nav {
    var tab by mutableStateOf(Tab.HOME)
    var stack by mutableStateOf(listOf<CatalogItem>())
    var queueOpen by mutableStateOf(false)
    var nowPlaying by mutableStateOf(false)
    /** A text field has focus: plain keys belong to it. */
    var typing by mutableStateOf(false)
    val searchFocus = FocusRequester()
    /** The routed controls of the window (this computer or the current Connect device). */
    var controls: PlayerControls? = null

    fun back(): Boolean = when {
        nowPlaying -> { nowPlaying = false; true }
        stack.isNotEmpty() -> { stack = stack.dropLast(1); true }
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
                e.key == Key.Spacebar && !nav.typing -> { nav.controls?.toggle(); true }
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
    val favorites by app.library.favorites.collectAsState()
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

    val actions = remember(favorites, remoteName) {
        object : Actions {
            override fun play(track: Track) = routed({ app.player.playTrack(track) }, "playTrack", trackArgs(track))
            override fun playNext(track: Track) = routed({ app.player.playNext(track) }, "playNext", trackArgs(track))
            override fun addToQueue(track: Track) = routed({ app.player.addToQueue(track) }, "addToQueue", trackArgs(track))
            override fun startRadio(track: Track) = routed({ app.player.startRadio(track) }, "playTrack", trackArgs(track))
            override fun toggleFavorite(track: Track) = app.library.toggleFavorite(track)
            override fun isFavorite(id: String) = favorites.any { it.id == id }
            override fun open(item: CatalogItem) { nav.stack = nav.stack + item }
            override fun playList(tracks: List<Track>, index: Int, shuffle: Boolean) {
                val id = target
                if (remote == null || remoteName == null || id == null) return app.player.playCollection(tracks, index, shuffle)
                val start = if (shuffle && tracks.isNotEmpty()) tracks.indices.random() else index
                app.scope.launch { runCatching { remote.manager.handoff(id, cord.kasane.music.connect.Frame.Handoff("",
                    tracks.map { it.toMeta() }, start.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), 0, true, shuffle, "off")) } }
            }
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
    val openSettings = { nav.nowPlaying = false; nav.stack = emptyList(); nav.tab = Tab.SETTINGS }
    androidx.compose.runtime.SideEffect { nav.controls = controls }
    val openMore: (String) -> Unit = { id -> nav.stack = nav.stack + CatalogItem(id, CatalogKind.PLAYLIST.takeIf { id.startsWith("VL") } ?: CatalogKind.ALBUM, "") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Tab.entries.forEach { t ->
                            val selected = nav.tab == t && nav.stack.isEmpty()
                            NavigationRailItem(selected = selected, onClick = { nav.tab = t; nav.stack = emptyList() }, label = { Text(t.label) }, icon = {
                                Icon(when (t) {
                                    Tab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
                                    Tab.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
                                    Tab.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
                                    Tab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
                                }, null)
                            })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        val top = nav.stack.lastOrNull()
                        if (top != null) {
                            Column {
                                IconButton(onClick = { nav.back() }, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                                saveable.SaveableStateProvider("browse:${nav.stack.size}:${top.id}") { BrowseScreen(app, top, actions, openMore) }
                            }
                        } else saveable.SaveableStateProvider(nav.tab.name) {
                            when (nav.tab) {
                                Tab.HOME -> HomeScreen(app, actions, openMore)
                                Tab.SEARCH -> SearchScreen(app, actions, nav.searchFocus) { nav.typing = it }
                                Tab.LIBRARY -> LibraryScreen(app, actions)
                                Tab.SETTINGS -> SettingsScreen(app) { ConnectSettings(app) }
                            }
                        }
                    }
                    if (nav.queueOpen) QueuePanel(state, controls) { nav.queueOpen = false }
                }
                PlayerBar(state, controls, actions, nav.queueOpen, onToggleQueue = { nav.queueOpen = !nav.queueOpen },
                    onExpand = { if (state.current != null) nav.nowPlaying = true }, deviceButton = { DeviceButton(app, openSettings) }, deviceLabel = remoteName?.let { "Playing on $it" })
            }
            if (nav.nowPlaying && state.current != null) NowPlaying(state, controls, actions, onClose = { nav.nowPlaying = false }, deviceButton = { DeviceButton(app, openSettings) })
            PairingPrompts(app)
        }
    }
}
