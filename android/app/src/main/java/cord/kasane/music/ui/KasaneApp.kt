package cord.kasane.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cord.kasane.music.KasaneApplication
import cord.kasane.music.auth.AccountSession
import cord.kasane.music.auth.AccountChannel
import cord.kasane.music.auth.AccountState
import cord.kasane.music.auth.CapturedWebSession
import cord.kasane.music.data.LocalDataExporter
import cord.kasane.music.model.FavoriteEntity
import cord.kasane.music.model.HistoryEntity
import cord.kasane.music.model.Track
import cord.kasane.music.model.CatalogItem
import cord.kasane.music.model.CatalogShelf
import cord.kasane.music.provider.accountHistory
import cord.kasane.music.provider.lyrics
import cord.kasane.music.ui.components.*
import cord.kasane.music.ui.components.TrackRow
import cord.kasane.music.ui.theme.*
import cord.kasane.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/** The track-menu gate must match `remoteActive`: a target only routes remotely while a
 *  state frame for it is actually shown — a stale/adopted target alone must not eat taps. */
private fun remoteShows(remote: cord.kasane.music.connect.RemoteController): Boolean =
    remote.target.value?.let { remote.displayFrames.value[it] != null } == true

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun KasaneApp(app: KasaneApplication) {
    val account by app.accountRepository.state.collectAsState()
    val session = app.accountRepository.session().takeIf { account is AccountState.SignedIn }
    val cloud = remember(session) { session?.let { captured ->
        val scopeKey = cord.kasane.music.data.CatalogCache.accountScope(captured)
        suspend fun invalidateLikes() {
            try { app.cachedCatalog.playlistChanged(scopeKey, "LM") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Remote success remains success if local metadata refresh fails. */ }
        }
        cord.kasane.music.provider.CloudLikes(
            read = { id -> app.ytProvider.catalog.likeState(captured, id) },
            write = { id, liked -> app.ytProvider.catalog.setLikedFor(captured, id, liked); invalidateLikes() },
            writeDislike = { id, disliked -> app.ytProvider.catalog.setDislikedFor(captured, id, disliked); invalidateLikes() })
    } }
    val playlists = remember(session) { session?.let { captured ->
        cord.kasane.music.provider.PlaylistPicker(captured, app.ytProvider.catalog,
            onAdded = { id -> app.cachedCatalog.playlistChanged(cord.kasane.music.data.CatalogCache.accountScope(captured), id) })
    } }
    DisposableEffect(playlists) { onDispose { playlists?.close() } }
    LaunchedEffect(cloud, session) {
        if (cloud != null && session != null) {
            val scopeKey = cord.kasane.music.data.CatalogCache.accountScope(session, (account as? AccountState.SignedIn)?.identity)
            try {
                app.cachedCatalog.cached(scopeKey, "playlist:VLLM")?.page?.tracks?.let { tracks -> cloud.seedLiked(tracks.map { it.id }) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { /* Optional hints must not prevent fresh per-track status loading. */ }
        }
    }
    DisposableEffect(cloud) { onDispose { cloud?.close() } }
    val cloudVisible = cloud?.takeIf { !app.settings.offlineOnly.value }
    CompositionLocalProvider(cord.kasane.music.ui.components.LocalCloudLikes provides cloudVisible,
        cord.kasane.music.ui.components.LocalPlaylistPicker provides playlists,
        cord.kasane.music.ui.components.LocalTrackActions provides cord.kasane.music.ui.components.TrackActions(
            play = { t -> app.remote.value?.takeIf { remoteShows(it) && !t.isLocal }?.playTrack(t) ?: app.playbackManager.playTrack(t) },
            next = { t -> app.remote.value?.takeIf { remoteShows(it) && !t.isLocal }?.playNext(t) ?: app.playbackManager.playNextInQueue(t) },
            queue = { t -> app.remote.value?.takeIf { remoteShows(it) && !t.isLocal }?.addToQueue(t) ?: app.playbackManager.addToQueue(t) },
            radio = { t -> app.remote.value?.takeIf { remoteShows(it) && !t.isLocal }?.playTrack(t) ?: app.playbackManager.startRadio(t) })) { KasaneContent(app) }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun KasaneContent(app: KasaneApplication) {
    val themeMode by app.settings.themeMode.collectAsState()
    val accentSource by app.settings.accentSource.collectAsState()
    val accentArgb by app.settings.accentColor.collectAsState()
    KasaneTheme(themeMode, accentSource, androidx.compose.ui.graphics.Color(accentArgb)) {
        val playbackState by app.playbackManager.uiState.collectAsState()
        val remote by app.remote.collectAsState()
        val remoteTarget by remote?.target?.collectAsState() ?: remember { mutableStateOf(null as String?) }
        val remoteStates by remote?.displayFrames?.collectAsState()
            ?: remember { mutableStateOf(emptyMap<String, cord.kasane.music.connect.Frame.State>()) }
        val remoteDevices by remote?.manager?.devices?.collectAsState()
            ?: remember { mutableStateOf(emptyList<cord.kasane.music.connect.ConnectDeviceView>()) }
        val remotePrompts by remote?.manager?.prompts?.collectAsState()
            ?: remember { mutableStateOf(emptyList<cord.kasane.music.connect.ConnectPairingPrompt>()) }
        val remoteFrame = remoteTarget?.let(remoteStates::get)
        val remoteActive = remote != null && remoteFrame != null
        val remoteName = remoteTarget?.let { id -> remoteDevices.firstOrNull { it.id == id }?.name }
        val displayedState = if (remoteActive) remote!!.project(remoteFrame!!) else playbackState
        var showDevices by remember { mutableStateOf(false) }

        fun routePlayTrack(t: Track) {
            if (remoteActive && !t.isLocal) remote!!.playTrack(t) else app.playbackManager.playTrack(t)
        }
        fun routeAddToQueue(t: Track) {
            if (remoteActive && !t.isLocal) remote!!.addToQueue(t) else app.playbackManager.addToQueue(t)
        }
        fun routePlayList(tracks: List<Track>, shuffle: Boolean) {
            val target = remoteTarget
            if (remoteActive && target != null) remote!!.playTracksRemotely(target, tracks, shuffle)
            else app.playbackManager.playTracks(tracks, shuffle)
        }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        // Transient app notices ride the themed snackbar slot — never a system Toast.
        var appNotice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(appNotice) { if (appNotice != null) { kotlinx.coroutines.delay(4_000); appNotice = null } }
        val exportLocalData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val favoritesSnapshot = app.database.trackDao().getFavoritesForExport()
                        val historySnapshot = app.database.trackDao().getHistoryForExport()
                        val json = LocalDataExporter.encode(favoritesSnapshot, historySnapshot,
                            preferences = mapOf("autoplay" to app.settings.autoplayFlow.value.toString()))
                        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(json) }
                            ?: error("Could not open the selected file.")
                    }
                }
                val message = if (result.isSuccess) "Kasane local data exported." else "Could not export local data."
                appNotice = message
            }
        }
        val focusManager = LocalFocusManager.current
        var showLoginDialog by remember { mutableStateOf(false) }
        var loginChannels by remember { mutableStateOf<List<AccountChannel>?>(null) }
        var capturedLogin by remember { mutableStateOf<CapturedWebSession?>(null) }
        var loginError by remember { mutableStateOf<String?>(null) }
        var checkingLoginChannels by remember { mutableStateOf(false) }
        val startWebSessionLogin: () -> Unit = {
            loginChannels = null
            capturedLogin = null
            loginError = null
            app.accountRepository.beginSignIn()
            showLoginDialog = true
        }

        var selectedTab by rememberSaveable { mutableStateOf(0) } // Home / Search / Library
        val playerSheet = rememberPlayerSheetState()
        val isNowPlayingOpen = playerSheet.isExpanded
        var showQueue by remember { mutableStateOf(false) }
        var navBarHeightPx by remember { mutableIntStateOf(0) }

        val screenState = rememberSaveableStateHolder()
        var libraryHistory by rememberSaveable { mutableStateOf(false) }
        var libraryOnline by rememberSaveable { mutableStateOf(false) }
        var libraryFilter by rememberSaveable { mutableStateOf("All") }
        // Each section keeps its own detail stack so switching tabs never loses a place.
        var detailStacks by remember { mutableStateOf(List(3) { emptyList<CatalogItem>() }) }
        val detailStack = detailStacks[selectedTab]
        val setDetailStack: (List<CatalogItem>) -> Unit = { stack ->
            detailStacks = detailStacks.mapIndexed { i, s -> if (i == selectedTab) stack else s }
        }
        var homeShelves by remember { mutableStateOf<List<CatalogShelf>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }
        var showProfile by remember { mutableStateOf(false) }
        var showDownloads by remember { mutableStateOf(false) }
        var showConnectSettings by remember { mutableStateOf(false) }
        var pendingSave by remember { mutableStateOf<List<Track>?>(null) }
        val offlineOnly by app.settings.offlineOnly.collectAsState()
        val recentSearches by app.searchHistory.recent.collectAsState()
        val offlineRecords by app.offline.all.collectAsState(initial = emptyList())
        val savedCount = remember(offlineRecords) { offlineRecords.count { it.pinned && it.audioState == cord.kasane.music.offline.AudioState.SAVED } }
        val offlineBadges = remember(offlineRecords) {
            offlineRecords.mapNotNull { record ->
                val badge = when (record.audioState) {
                    cord.kasane.music.offline.AudioState.SAVED -> cord.kasane.music.ui.components.OfflineBadge.SAVED
                    cord.kasane.music.offline.AudioState.QUEUED, cord.kasane.music.offline.AudioState.DOWNLOADING -> cord.kasane.music.ui.components.OfflineBadge.DOWNLOADING
                    cord.kasane.music.offline.AudioState.FAILED -> if (record.pinned) cord.kasane.music.ui.components.OfflineBadge.FAILED else null
                    cord.kasane.music.offline.AudioState.CACHED -> null
                }
                badge?.let { record.videoId to it }
            }.toMap()
        }
        val runSave: (List<Track>) -> Unit = { tracks ->
            scope.launch {
                val summary = app.offline.save(tracks)
                val message = when {
                    summary.queued > 0 -> "Saving ${summary.queued} song${if (summary.queued == 1) "" else "s"} offline" +
                        (summary.blockedBy?.let { " · $it" } ?: "")
                    summary.alreadySaved > 0 -> "Already saved offline."
                    else -> "These tracks cannot be saved offline."
                }
                appNotice = message
            }
        }
        val offlineActions = remember(app) { cord.kasane.music.ui.components.OfflineActions(
            save = { tracks -> if (app.settings.offlineNoticeAccepted.value) runSave(tracks) else pendingSave = tracks },
            remove = app.offline::remove, retry = app.offline::retry) }
        pendingSave?.let { tracks ->
            AlertDialog(
                onDismissRequest = { pendingSave = null },
                title = { Text("Save offline") },
                text = { Text("Saved audio stays inside Kasane. It is not exported or shared. Whether a track can be saved depends on YouTube: some tracks stream in formats that cannot be stored, and saved copies may stop working if YouTube or its terms change. Only save music you are allowed to keep offline; Kasane does not grant any download rights.") },
                confirmButton = { TextButton(onClick = { app.settings.acceptOfflineNotice(); pendingSave = null; runSave(tracks) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { pendingSave = null }) { Text("Cancel") } })
        }
        // Likes are YTM-synced ratings only — there is no local favorites system.
        val cloud = cord.kasane.music.ui.components.LocalCloudLikes.current
        LaunchedEffect(cloud) { cloud?.errors?.collect { appNotice = it } }
        val likeStates by (cloud?.states ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, cord.kasane.music.provider.CloudLikeState>())).collectAsState()
        val likedIds = remember(likeStates) { likeStates.filterValues { it == cord.kasane.music.provider.CloudLikeState.LIKED }.keys }
        var likedTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        val allHistory by app.database.trackDao().getRecentHistory(300).collectAsState(initial = emptyList())
        // Guest and account histories never mix: the visible list matches the current session state.
        val historyOwner = if (app.accountRepository.session() != null) "account" else "guest"
        val history = remember(allHistory, historyOwner) { allHistory.filter { it.owner == historyOwner } }
        val monthAgo = remember { System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000 }
        val topPlayed by remember { app.database.trackDao().topPlayed(monthAgo) }.collectAsState(initial = emptyList())
        val recentlySaved by remember { app.database.trackDao().recentlySaved() }.collectAsState(initial = emptyList())
        // Home feed recommendations from YouTube Music
        var homeRecommendations by remember { mutableStateOf<List<Track>>(emptyList()) }
        var isHomeLoading by remember { mutableStateOf(false) }
        var isHomeRefreshing by remember { mutableStateOf(false) }

        var homeError by remember { mutableStateOf<String?>(null) }
        var homeRevision by remember { mutableIntStateOf(0) }
        var handledHomeRevision by remember { mutableIntStateOf(0) }
        val accountState by app.accountRepository.state.collectAsState()
        val catalogScope = remember(accountState, app.accountRepository.session()) {
            cord.kasane.music.data.CatalogCache.accountScope(
                app.accountRepository.session(), (accountState as? AccountState.SignedIn)?.identity)
        }
        LaunchedEffect(accountState) {
            if (accountState is AccountState.SignedIn) libraryOnline = true
        }
        var remoteHistory by remember { mutableStateOf<cord.kasane.music.model.CatalogPage?>(null) }
        LaunchedEffect(libraryHistory, accountState, catalogScope) {
            val session = app.accountRepository.session()
            if (!libraryHistory || accountState !is AccountState.SignedIn || session == null) {
                remoteHistory = null; return@LaunchedEffect
            }
            remoteHistory = try {
                app.cachedCatalog.refresh(catalogScope, "browse:FEmusic_history") {
                    app.ytProvider.catalog.accountHistory(session)
                }
            } catch (_: Exception) {
                app.cachedCatalog.cached(catalogScope, "browse:FEmusic_history")?.page
            }
        }
        // Liked songs (VLLM) — cached first, fresh after; the list mirrors the cloud ratings.
        LaunchedEffect(accountState, catalogScope, offlineOnly) {
            if (accountState !is AccountState.SignedIn || offlineOnly) { likedTracks = emptyList(); return@LaunchedEffect }
            likedTracks = app.cachedCatalog.cached(catalogScope, "playlist:VLLM")?.page?.tracks.orEmpty()
            try {
                val all = mutableListOf<Track>()
                val seenIds = HashSet<String>()
                val seenTokens = HashSet<String>()
                var continuation: String? = null
                var pages = 0
                do {
                    val page = app.accountLibraryProvider.playlist("VLLM", continuation)
                    page.tracks.forEach { t -> if (seenIds.add(t.id)) all += t }
                    // YouTube continuations can cycle on the last page — a repeated token is the end.
                    continuation = page.continuation?.takeIf { seenTokens.add(it) }
                } while (continuation != null && ++pages < 100)
                likedTracks = all
                cloud?.seedLiked(all.map { it.id })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* cached copy stays */ }
        }
        val likedVisible = remember(likedTracks, likeStates) { likedTracks.filter {
            likeStates[it.id] != cord.kasane.music.provider.CloudLikeState.NEUTRAL &&
            likeStates[it.id] != cord.kasane.music.provider.CloudLikeState.DISLIKED } }
        var activeHomeScope by remember { mutableStateOf<String?>(null) }
        var homeChips by remember { mutableStateOf<List<cord.kasane.music.model.CatalogChip>>(emptyList()) }
        var selectedChip by remember { mutableStateOf<String?>(null) }
        var chipShelves by remember { mutableStateOf<List<CatalogShelf>>(emptyList()) }
        var chipLoading by remember { mutableStateOf(false) }
        var chipError by remember { mutableStateOf<String?>(null) }
        // Artist and album links for Now Playing: fetched once per song when playback did not already bring them.
        val currentTrackId = playbackState.currentTrack?.takeIf { !it.isLocal }?.id
        val knownLinks by app.ytProvider.catalog.trackLinks.collectAsState()
        LaunchedEffect(currentTrackId, offlineOnly) {
            val id = currentTrackId ?: return@LaunchedEffect
            if (offlineOnly || knownLinks.containsKey(id) || !id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@LaunchedEffect
            try { app.ytProvider.catalog.loadTrackLinks(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val homeSections = remember(homeShelves, chipShelves, selectedChip, topPlayed, history, recentlySaved, likedVisible, offlineOnly) {
            if (selectedChip != null) HomeComposer.build(chipShelves, emptyList(), emptyList(), emptyList(), false, local = false)
            else HomeComposer.build(homeShelves, topPlayed, history, recentlySaved, offlineOnly, likedVisible)
        }
        // A mood chip is a real YouTube Music request: the feed for that mood replaces Home until it is cleared.
        LaunchedEffect(selectedChip, accountState, offlineOnly) {
            val params = selectedChip
            chipShelves = emptyList(); chipError = null
            if (params == null || offlineOnly) { chipLoading = false; return@LaunchedEffect }
            chipLoading = true
            try { chipShelves = app.ytProvider.catalog.homeFiltered(app.accountRepository.session(), params).shelves }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { chipError = "Couldn't load this mood. Check your connection and try again." }
            finally { chipLoading = false }
        }
        LaunchedEffect(homeRevision, accountState, catalogScope, offlineOnly) {
            if (activeHomeScope != catalogScope) {
                activeHomeScope = catalogScope
                homeShelves = emptyList()
                homeRecommendations = emptyList()
            }
            val saved = app.catalogCache.read(catalogScope, cord.kasane.music.data.CachedCatalogRepository.HOME_KEY)
            if (saved != null) {
                homeShelves = saved.page.shelves
                homeRecommendations = saved.page.tracks
                if (saved.page.chips.isNotEmpty()) homeChips = saved.page.chips
            }
            // Offline Only: the saved feed and local sections are all Home shows; never touch the network.
            if (offlineOnly) { isHomeLoading = false; isHomeRefreshing = false; homeError = null; handledHomeRevision = homeRevision; return@LaunchedEffect }
            val isStale = saved == null || System.currentTimeMillis() - saved.updatedAt >= cord.kasane.music.data.CachedCatalogRepository.HOME_TTL_MS
            val shouldRefresh = homeRevision != handledHomeRevision || isStale
            if (!shouldRefresh) { isHomeLoading = false; homeError = null; return@LaunchedEffect }
            isHomeLoading = saved == null
            isHomeRefreshing = saved != null
            homeError = null
            try {
                val homePage = app.cachedCatalog.home(
                    catalogScope,
                    app.accountRepository.session(),
                    force = homeRevision != handledHomeRevision,
                ) ?: return@LaunchedEffect
                homeShelves = homePage.shelves
                homeRecommendations = homePage.tracks
                if (homePage.chips.isNotEmpty()) homeChips = homePage.chips
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                homeError = "Recommendations unavailable. Check your connection and try again."
            } finally { isHomeLoading = false; isHomeRefreshing = false; handledHomeRevision = homeRevision }
        }
        if (showLoginDialog) {
            LaunchedEffect(Unit) { app.accountRepository.signInWindowOpen() }
            YtMusicLoginDialog(
                channels = loginChannels,
                isCheckingChannels = checkingLoginChannels,
                errorMessage = loginError,
                onCapture = { captured ->
                    capturedLogin = captured
                    loginChannels = null
                    loginError = null
                    checkingLoginChannels = true
                    scope.launch {
                        try {
                            val provisional = AccountSession(captured.cookie, captured.authUser,
                                captured.pageId, captured.dataSyncId?.substringBefore("||"),
                                captured.visitorData, captured.dataSyncId)
                            val channels = app.ytProvider.catalog.accountChannels(provisional)
                            if (channels.isEmpty()) loginError = "YouTube Music did not return a channel list. No account session was saved."
                            else loginChannels = channels
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { loginError = e.message ?: "Could not read YouTube channels. Check the connection and retry." }
                        finally { checkingLoginChannels = false }
                    }
                },
                onConfirmChannel = { channel ->
                    val captured = capturedLogin ?: return@YtMusicLoginDialog
                    val session = AccountSession(captured.cookie, captured.authUser,
                        channel.pageId ?: captured.pageId,
                        channel.dataSyncId?.substringBefore("||") ?: captured.dataSyncId?.substringBefore("||"),
                        captured.visitorData, channel.dataSyncId ?: captured.dataSyncId)
                    checkingLoginChannels = true
                    loginError = null
                    scope.launch {
                        app.accountRepository.accept(session)
                        val verified = app.accountRepository.state.value is AccountState.SignedIn
                        if (verified) {
                            app.settings.setSessionMode(cord.kasane.music.auth.SessionMode.ACCOUNT)
                            showLoginDialog = false
                            loginChannels = null
                            capturedLogin = null
                            clearYtMusicWebSession()
                        } else {
                            loginError = (app.accountRepository.state.value as? AccountState.Unavailable)?.message
                                ?: "The selected YouTube channel could not be verified. Try another channel."
                        }
                        checkingLoginChannels = false
                    }
                },
                onRetryChannels = {
                    val captured = capturedLogin
                    if (captured != null) {
                        checkingLoginChannels = true; loginError = null
                        scope.launch {
                            try {
                                val provisional = AccountSession(captured.cookie, captured.authUser, captured.pageId,
                                    captured.dataSyncId?.substringBefore("||"), captured.visitorData, captured.dataSyncId)
                                val channels = app.ytProvider.catalog.accountChannels(provisional)
                                if (channels.isEmpty()) loginError = "YouTube Music did not return a channel list."
                                else loginChannels = channels
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { loginError = e.message ?: "Could not read YouTube channels." }
                            finally { checkingLoginChannels = false }
                        }
                    }
                },
                onDismiss = {
                    showLoginDialog = false
                    loginChannels = null
                    capturedLogin = null
                    app.accountRepository.cancelSignIn()
                    clearYtMusicWebSession()
                },
            )
        }

        var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
        // AndroidX supplies predictive progress and dispatches the same path for completed legacy Back.
        PredictiveBackHandler(enabled = isNowPlayingOpen || showSettings || showProfile || showDownloads || showConnectSettings ||
            detailStack.isNotEmpty() || (selectedTab == 2 && (libraryHistory || libraryFilter != "All")) || selectedTab != 0) { progress ->
            try {
                progress.collect { event -> predictiveBackProgress = event.progress }
                predictiveBackProgress = 0f
            val navigation = navigationAfterBack(selectedTab, isNowPlayingOpen, detailStack.size)
            when {
                showDownloads && !isNowPlayingOpen -> showDownloads = false
                showSettings && !isNowPlayingOpen -> showSettings = false
                showConnectSettings && !isNowPlayingOpen -> showConnectSettings = false
                showProfile && !isNowPlayingOpen -> showProfile = false
                isNowPlayingOpen -> playerSheet.collapse()
                detailStack.isNotEmpty() -> setDetailStack(detailStack.take(navigation.detailDepth))
                selectedTab == 2 && libraryHistory -> libraryHistory = false
                selectedTab == 2 && libraryFilter != "All" -> { libraryFilter = "All"; libraryOnline = false }
                selectedTab != 0 -> {
                    focusManager.clearFocus()
                    selectedTab = navigation.selectedTab
                }
            }
            } catch (cancelled: CancellationException) {
                predictiveBackProgress = 0f
                throw cancelled
            }
        }

        val openCatalog: (CatalogItem) -> Unit = { entry ->
            entry.track()?.let { routePlayTrack(it) }
                ?: run { playerSheet.collapse(); setDetailStack(detailStack + entry) }
        }
        var showLyrics by remember { mutableStateOf(false) }
        var lyricsTrack by remember { mutableStateOf<Track?>(null) }
        var lyricsState by remember { mutableStateOf<cord.kasane.music.ui.components.LyricsState>(cord.kasane.music.ui.components.LyricsState.Loading) }
        var lyricsCandidates by remember { mutableStateOf<List<Pair<cord.kasane.music.lyrics.LyricsCandidate, cord.kasane.music.lyrics.LyricsDocument?>>?>(null) }
        val openLyrics: (Track) -> Unit = { t ->
            lyricsTrack = t
            lyricsState = cord.kasane.music.ui.components.LyricsState.Loading
            showLyrics = true
            scope.launch {
                lyricsState = try {
                    when (val r = app.lyrics.lyricsResolutionFor(t)) {
                        is cord.kasane.music.lyrics.LyricsResolution.Found -> cord.kasane.music.ui.components.LyricsState.Ready(r.document)
                        cord.kasane.music.lyrics.LyricsResolution.Absent -> cord.kasane.music.ui.components.LyricsState.Absent
                        is cord.kasane.music.lyrics.LyricsResolution.Unavailable -> cord.kasane.music.ui.components.LyricsState.Unavailable
                    }
                } catch (_: Exception) { cord.kasane.music.ui.components.LyricsState.Unavailable }
            }
        }
        CompositionLocalProvider(cord.kasane.music.ui.components.LocalTrackNavigation provides
            cord.kasane.music.ui.components.TrackNavigation(app.ytProvider.catalog.trackLinks, openCatalog),
            cord.kasane.music.ui.components.LocalOfflineBadges provides offlineBadges,
            cord.kasane.music.ui.components.LocalCurrentTrackId provides playbackState.currentTrack?.id,
            cord.kasane.music.ui.components.LocalTrackLyrics provides openLyrics,
            cord.kasane.music.ui.components.LocalAppNotice provides { message: String -> appNotice = message },
            cord.kasane.music.ui.components.LocalOfflineActions provides offlineActions) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.graphicsLayer {
                translationX = size.width * predictiveBackProgress * 0.08f
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                if (playbackState.errorMessage != null) {
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        action = {
                            Row {
                                TextButton(onClick = { app.playbackManager.retryPlayback() }) {
                                    Text("Retry", color = MaterialTheme.colorScheme.inversePrimary)
                                }
                                TextButton(onClick = { app.playbackManager.clearError() }) {
                                    Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                                }
                            }
                        },
                        modifier = Modifier.padding(16.dp).padding(bottom = if (displayedState.currentTrack != null) 72.dp else 0.dp)
                    ) {
                        Text(playbackState.errorMessage!!)
                    }
                } else if (playbackState.noticeMessage != null) {
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        action = {
                            TextButton(onClick = { app.playbackManager.clearNotice() }) {
                                Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                            }
                        },
                        modifier = Modifier.padding(16.dp).padding(bottom = if (displayedState.currentTrack != null) 72.dp else 0.dp)
                    ) {
                        Text(playbackState.noticeMessage!!)
                    }
                } else appNotice?.let { message ->
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        action = {
                            TextButton(onClick = { appNotice = null }) {
                                Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                            }
                        },
                        modifier = Modifier.padding(16.dp).padding(bottom = if (displayedState.currentTrack != null) 72.dp else 0.dp)
                    ) {
                        Text(message)
                    }
                }
            },
            bottomBar = {
                Box(Modifier.onSizeChanged { navBarHeightPx = it.height }.graphicsLayer { translationY = playerSheet.progress.value * size.height }) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                        val navItems = listOf(
                            Triple(0, "Home", Icons.Default.Home to Icons.Outlined.Home),
                            Triple(1, "Search", Icons.Default.Search to Icons.Outlined.Search),
                            Triple(2, "Library", Icons.Default.LibraryMusic to Icons.Outlined.LibraryMusic)
                        )
                        for ((tabIndex, label, icons) in navItems) {
                            val isSelected = selectedTab == tabIndex && !showSettings && !showProfile && !showDownloads
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    if (tabIndex != 1) focusManager.clearFocus()
                                    val navigation = navigationAfterTabSelection(tabIndex)
                                    if (selectedTab == tabIndex) {
                                        // Re-tap on the active section returns to its root; other sections keep their stacks.
                                        setDetailStack(emptyList())
                                        if (tabIndex == 2) { libraryHistory = false; libraryOnline = false; libraryFilter = "All" }
                                    } else {
                                        selectedTab = navigation.selectedTab
                                    }
                                    showSettings = false
                                    showProfile = false
                                    showDownloads = false
                                    showConnectSettings = false
                                    playerSheet.collapse()
                                },
                                icon = { Icon(if (isSelected) icons.first else icons.second, contentDescription = null) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
          // consumeWindowInsets: the Scaffold already padded the status bar, so nested TopAppBars must not add it again.
          Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).background(MaterialTheme.colorScheme.background)) {
            if (offlineOnly) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Offline Only · playing saved and cached music", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { app.settings.setOfflineOnly(false) }) { Text("Turn off") }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = if (displayedState.currentTrack != null) 76.dp else 0.dp)) {
                if (showConnectSettings) {
                    val connectOn by app.settings.connectEnabled.collectAsState()
                    cord.kasane.music.ui.components.ConnectDevicesScreen(remote, connectOn, app.settings::setConnectEnabled,
                        localName = remote?.manager?.localDeviceName ?: cord.kasane.music.connect.AndroidConnect.deviceName(app),
                        onBack = { showConnectSettings = false })
                } else if (showDownloads) {
                    // Saved bytes only exist on this device, so saved music always plays locally
                    // (a remote Connect target could not read this file anyway).
                    DownloadsScreen(app, onBack = { showDownloads = false },
                        onPlay = { tracks, index -> app.playbackManager.playTracksAt(tracks, index) })
                } else if (showSettings) {
                    SettingsScreen(
                        app = app, state = accountState,
                        onBack = { showSettings = false },
                        onSignIn = startWebSessionLogin,
                        onSignOut = { scope.launch {
                            app.accountRepository.signOut()
                            app.settings.setSessionMode(cord.kasane.music.auth.SessionMode.UNDECIDED)
                            clearYtMusicWebSession()
                        } },
                        onRetry = { scope.launch { app.accountRepository.restore() } },
                        onCancel = {
                            app.accountRepository.cancelSignIn()
                            showLoginDialog = false
                            clearYtMusicWebSession()
                        },
                        onExportLocalData = { exportLocalData.launch("kasane-local-data.json") },
                        onManageSaved = { showDownloads = true },
                        onOpenConnect = { showConnectSettings = true })
                } else if (showProfile) {
                    val connectOn by app.settings.connectEnabled.collectAsState()
                    ProfileScreen(accountState, connectOn,
                        onBack = { showProfile = false },
                        onSignIn = startWebSessionLogin,
                        onSignOut = { scope.launch {
                            app.accountRepository.signOut()
                            app.settings.setSessionMode(cord.kasane.music.auth.SessionMode.UNDECIDED)
                            clearYtMusicWebSession()
                        } },
                        onRetry = { scope.launch { app.accountRepository.restore() } },
                        onCancel = {
                            app.accountRepository.cancelSignIn()
                            showLoginDialog = false
                            clearYtMusicWebSession()
                        },
                        onOpenDownloads = { showDownloads = true },
                        onOpenHistory = {
                            showProfile = false
                            selectedTab = 2
                            libraryFilter = "All"; libraryOnline = false
                            libraryHistory = true
                        },
                        onOpenConnect = { showConnectSettings = true },
                        onOpenSettings = { showSettings = true })
                } else if (detailStack.isNotEmpty()) {
                    val detail = detailStack.last()
                    screenState.SaveableStateProvider("detail:${detail.kind}:${detail.id}") {
                        CatalogDetailScreen(detail, app.ytProvider.catalog,
                            { setDetailStack(detailStack.dropLast(1)) }, openCatalog,
                            { tracks, shuffle -> routePlayList(tracks, shuffle) },
                            { routeAddToQueue(it) }, app.cachedCatalog, catalogScope,
                            app.accountRepository.session().takeIf { accountState is AccountState.SignedIn },
                            (accountState as? AccountState.SignedIn)?.identity,
                            onPlayNext = if (remoteActive) null else ({ tracks -> app.playbackManager.playAllNext(tracks) }),
                            onAddAllToQueue = if (remoteActive) null else ({ tracks -> app.playbackManager.addAllToQueue(tracks) }),
                            onSaveAll = { tracks -> if (app.settings.offlineNoticeAccepted.value) runSave(tracks) else pendingSave = tracks },
                            bulkAddToPlaylist = { tracks, playlistId ->
                                val session = app.accountRepository.session()
                                if (session != null) tracks.forEach { app.ytProvider.catalog.addVideoToPlaylist(session, playlistId, it.id) }
                            })
                    }
                } else Crossfade(selectedTab, animationSpec = tween(200), label = "tabSwitch") { tab ->
                screenState.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> HomeScreen(
                        sections = homeSections, loading = if (selectedChip != null) chipLoading else isHomeLoading,
                        refreshing = isHomeRefreshing, error = if (selectedChip != null) chipError else homeError,
                        chips = if (offlineOnly) emptyList() else homeChips, selectedChip = selectedChip,
                        onChip = { selectedChip = it?.params },
                        offlineOnly = offlineOnly, currentTrackId = playbackState.currentTrack?.id,
                        actions = HomeActions(
                            playTrack = { routePlayTrack(it) },
                            playList = { tracks, index -> routePlayList(tracks.drop(index.coerceAtLeast(0)), false) },
                            startRadio = { t -> if (remoteActive && !t.isLocal) remote!!.playTrack(t) else app.playbackManager.startRadio(t) },
                            open = openCatalog, openSearch = { selectedTab = 1 }, openProfile = { showProfile = true },
                            openSaved = { showDownloads = true }, refresh = { homeRevision++ },
                            openShelf = { title, id -> openCatalog(CatalogItem(id, cord.kasane.music.model.CatalogKind.PLAYLIST, title)) }),
                        accountAvatar = (accountState as? AccountState.SignedIn)?.identity?.avatar)
                    1 -> if (offlineOnly) OfflineSearchScreen(app.database.trackDao(), playbackState.currentTrack?.id) { routePlayTrack(it) }
                    else CatalogSearchScreen(
                        app.ytProvider.catalog, openCatalog, playbackState.currentTrack?.id, likedIds,
                        { track -> cloud?.toggle(track.id) }, { routeAddToQueue(it) }, { t -> if (remoteActive && !t.isLocal) remote!!.playNext(t) else app.playbackManager.playNextInQueue(t) },
                        cachedCatalog = app.cachedCatalog, cacheScope = catalogScope,
                        recentSearches = recentSearches, onCommitSearch = app.searchHistory::add,
                        onRemoveRecent = app.searchHistory::remove, onClearRecent = app.searchHistory::clear)
                    2 -> LibraryScreen(
                        accountState = accountState,
                        accountProvider = app.accountLibraryProvider,
                        filter = libraryFilter,
                        onFilter = { libraryFilter = it; libraryOnline = it in setOf("Playlists", "Albums", "Artists"); if (it != "Songs") libraryHistory = false },
                        showingHistory = libraryHistory,
                        onSelectHistory = { libraryHistory = it },
                        onSignIn = startWebSessionLogin,
                        onExpired = { scope.launch { app.accountRepository.expire() } },
                        cachedCatalog = app.cachedCatalog,
                        session = app.accountRepository.session(),
                        favoritesCount = likedVisible.size,
                        historyCount = history.size,
                        savedCount = savedCount,
                        onOpenDownloads = { showDownloads = true },
                        onOpenSettings = { showSettings = true },
                        onCatalogOpen = { entry -> entry.track()?.let { routePlayTrack(it) }
                            ?: run { setDetailStack(detailStack + entry) } },
                        onAddToQueue = { routeAddToQueue(it) },
                        favoritesContent = { FavoritesScreen(
                            favorites = likedVisible,
                            signedIn = accountState is AccountState.SignedIn,
                            currentTrackId = playbackState.currentTrack?.id,
                            isPlaying = playbackState.isPlaying,
                            isLoading = playbackState.isLoading,
                            onTrackClick = { routePlayTrack(it) },
                            onAddToQueue = { routeAddToQueue(it) },
                            onRemoveFavorite = { track -> cloud?.toggle(track.id) }
                        ) },
                        historyContent = { HistoryScreen(
                            local = history,
                            remote = remoteHistory,
                            currentTrackId = playbackState.currentTrack?.id,
                            isPlaying = playbackState.isPlaying,
                            isLoading = playbackState.isLoading,
                            favoriteIds = likedIds,
                            onPlay = { routePlayTrack(it) },
                            onAddToQueue = { routeAddToQueue(it) },
                            onToggleFavorite = { track -> cloud?.toggle(track.id) },
                            onOpenCatalog = openCatalog,
                            onRelaunchMix = { item -> app.playbackManager.startRadio(Track(item.trackId,
                                item.title.removePrefix("Mix — "), item.artist, thumbnailUri = item.thumbnailUri)) }
                        ) }
                    )
                }
                }
                }
            }
          }
        }
        val current = displayedState.currentTrack
        if (current != null) {
            PlayerSheet(
                state = displayedState, sheet = playerSheet, isFavorite = likedIds.contains(current.id),
                dockedBottom = with(androidx.compose.ui.platform.LocalDensity.current) { navBarHeightPx.toDp() },
                remoteName = remoteName.takeIf { remoteActive },
                onOpenDevices = remote?.let { { showDevices = true } },
                actions = if (remoteActive) PlayerActions(
                    togglePlay = { remote!!.toggle() }, next = { remote!!.next() },
                    previous = { remote!!.previous() }, seekTo = { remote!!.seekTo(it) },
                    shuffle = { remote!!.toggleShuffle(displayedState.shuffleEnabled) },
                    repeat = { remote!!.cycleRepeat(displayedState.repeatMode) },
                    retry = { }, dismissError = { },
                    toggleFavorite = { cloud?.toggle(current.id) },
                    openQueue = { showQueue = true }, openLyrics = { openLyrics(current) })
                else PlayerActions(
                    togglePlay = { app.playbackManager.togglePlayPause() }, next = { app.playbackManager.playNext() },
                    previous = { app.playbackManager.playPrevious() }, seekTo = { app.playbackManager.seekTo(it) },
                    shuffle = { app.playbackManager.toggleShuffle() }, repeat = { app.playbackManager.cycleRepeat() },
                    retry = { app.playbackManager.retryPlayback() }, dismissError = { app.playbackManager.clearError() },
                    toggleFavorite = { cloud?.toggle(current.id) },
                    openQueue = { showQueue = true }, openLyrics = { openLyrics(current) }))
        }
        lyricsTrack?.takeIf { showLyrics }?.let { t ->
            cord.kasane.music.ui.components.LyricsSheet(t.title, lyricsState,
                positionMs = displayedState.currentPositionMs,
                onDismiss = { showLyrics = false },
                onSeek = { ms -> if (remoteActive) remote?.seekTo(ms) else app.playbackManager.seekTo(ms) },
                onAdjustOffset = { delta ->
                    scope.launch {
                        app.lyrics.adjustOffset(t.id, delta)
                        (lyricsState as? cord.kasane.music.ui.components.LyricsState.Ready)?.let {
                            lyricsState = it.copy(document = it.document.copy(offsetMs = it.document.offsetMs + delta))
                        }
                    }
                },
                onPickSource = {
                    scope.launch {
                        lyricsCandidates = try { app.lyrics.candidatesFor(t) }
                            catch (_: Exception) { emptyList() }
                    }
                })
        }
        if (lyricsCandidates != null) {
            val track = lyricsTrack
            AlertDialog(onDismissRequest = { lyricsCandidates = null },
                title = { Text("Lyrics sources") },
                text = {
                    Column {
                        if (lyricsCandidates!!.isEmpty()) Text("No sources found for this song.")
                        lyricsCandidates!!.forEach { (candidate, doc) ->
                            TextButton(onClick = {
                                lyricsCandidates = null
                                if (track != null) scope.launch {
                                    app.lyrics.choose(track.id, candidate)
                                    lyricsState = doc?.let { cord.kasane.music.ui.components.LyricsState.Ready(it) }
                                        ?: cord.kasane.music.ui.components.LyricsState.Unavailable
                                }
                            }) {
                                Text(listOfNotNull(candidate.providerLabel,
                                    candidate.title?.let { "“$it”" },
                                    doc?.syncKind?.name?.lowercase()).joinToString(" · "))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { lyricsCandidates = null }) { Text("Close") } })
        }
        if (showQueue) QueueSheet(displayedState,
            onPlayIndex = { if (remoteActive) remote!!.playQueueIndex(it) else app.playbackManager.playQueueIndex(it) },
            onMove = { from, to -> if (remoteActive) remote!!.moveQueueItem(from, to) else app.playbackManager.moveQueueItem(from, to) },
            onRemove = { if (remoteActive) remote!!.removeQueueItem(it) else app.playbackManager.removeQueueItem(it) },
            onDismiss = { showQueue = false })
        }
        val devicesRemote = remote
        if (showDevices && devicesRemote != null) {
            cord.kasane.music.ui.components.ConnectSheet(devicesRemote,
                localName = devicesRemote.manager.localDeviceName,
                localTrack = playbackState.currentTrack?.let { t -> listOf(t.title, t.artist).filter(String::isNotBlank).joinToString(" — ") },
                onAddDevice = { showSettings = true; showConnectSettings = true },
                onDismiss = { showDevices = false })
        }
        remotePrompts.firstOrNull()?.let { prompt ->
            cord.kasane.music.ui.components.PairingDialog(prompt) { ok ->
                devicesRemote?.confirm(prompt.promptId, ok)
            }
        }
        cord.kasane.music.ui.components.PlaylistPickerHost()
        val onboardingDone by app.settings.onboardingDone.collectAsState()
        var welcomeVisible by remember { mutableStateOf(!app.settings.onboardingDone.value) }
        LaunchedEffect(accountState) {
            if (welcomeVisible && accountState is AccountState.SignedIn) {
                app.settings.completeOnboarding(); welcomeVisible = false
            }
        }
        if (welcomeVisible && accountState is AccountState.Checking) {
            // A stored session is being validated — hold a neutral screen so onboarding never flashes for it.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { OnboardingSplash() }
        } else if (welcomeVisible && cord.kasane.music.auth.SessionPolicy.showsOnboarding(onboardingDone, accountState)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val signInPhase by app.accountRepository.signInPhase.collectAsState()
                OnboardingScreen(accountState, app.settings, signInPhase,
                    onSignIn = startWebSessionLogin,
                    onDone = { app.settings.completeOnboarding(); welcomeVisible = false })
            }
        }
    }
    }
}

@Composable
fun LibraryScreen(
    accountState: AccountState,
    accountProvider: cord.kasane.music.provider.AccountLibraryProvider,
    filter: String,
    onFilter: (String) -> Unit,
    showingHistory: Boolean,
    onSelectHistory: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onExpired: () -> Unit,
    onCatalogOpen: (CatalogItem) -> Unit,
    onAddToQueue: (Track) -> Unit,
    favoritesContent: @Composable () -> Unit,
    historyContent: @Composable () -> Unit,
    cachedCatalog: cord.kasane.music.data.CachedCatalogRepository? = null,
    session: AccountSession? = null,
    favoritesCount: Int = 0,
    historyCount: Int = 0,
    onOpenSettings: () -> Unit = {},
    savedCount: Int = 0,
    onOpenDownloads: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Playlists", "Songs", "Albums", "Artists")) { item ->
                cord.kasane.music.ui.components.KasaneChoiceChip(item, filter == item, { onFilter(item) })
            }
        }
        when (filter) {
            "Songs" -> {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cord.kasane.music.ui.components.KasaneChoiceChip("Liked songs · $favoritesCount", !showingHistory, { onSelectHistory(false) })
                    cord.kasane.music.ui.components.KasaneChoiceChip("History", showingHistory, { onSelectHistory(true) })
                }
                Box(Modifier.weight(1f)) { if (showingHistory) historyContent() else favoritesContent() }
            }
            "Playlists", "Albums", "Artists" -> AccountLibraryScreen(
                accountState, accountProvider, onSignIn, onCatalogOpen, onAddToQueue, onExpired,
                modifier = Modifier.weight(1f).fillMaxWidth(), cachedCatalog = cachedCatalog, session = session,
                selected = when (filter) {
                    "Albums" -> cord.kasane.music.provider.AccountCollection.ALBUMS
                    "Artists" -> cord.kasane.music.provider.AccountCollection.ARTISTS
                    else -> cord.kasane.music.provider.AccountCollection.PLAYLISTS
                }
            )
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryCollectionRow("Liked songs", plural(favoritesCount, "song"), Icons.Default.Favorite) {
                        onFilter("Songs"); onSelectHistory(false)
                    }
                    LibraryCollectionRow("Saved music", plural(savedCount, "song") + " · offline", Icons.Default.DownloadDone, onOpenDownloads)
                    LibraryCollectionRow("History", if (historyCount == 0) "Nothing played yet" else "$historyCount recently played", Icons.Default.History) {
                        onFilter("Songs"); onSelectHistory(true)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Your YouTube Music playlists", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                    AccountLibraryScreen(accountState, accountProvider, onSignIn, onCatalogOpen, onAddToQueue, onExpired,
                        modifier = Modifier.weight(1f).fillMaxWidth(), cachedCatalog = cachedCatalog, session = session,
                        selected = cord.kasane.music.provider.AccountCollection.PLAYLISTS)
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FavoritesScreen(
    favorites: List<Track>,
    signedIn: Boolean = true,
    currentTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onRemoveFavorite: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (signedIn) "No liked songs yet" else "Sign in to see your liked songs",
                    style = MaterialTheme.typography.bodyMediumMuted,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(favorites, key = { it.id }) { track ->
                    val isCurrent = currentTrackId == track.id
                    TrackRow(
                        track = track,
                        isPlaying = isCurrent && isPlaying,
                        isLoading = isCurrent && isLoading,
                        onClick = { onTrackClick(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onToggleFavorite = { onRemoveFavorite(track) },
                        isFavorite = true,
                        useCloudFavorite = true,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SearchScreenPreview() {
    KasaneTheme(dynamicColor = false) {
        CatalogSearchScreen(cord.kasane.music.provider.OnlineCatalog(okhttp3.OkHttpClient()), {}, null, emptySet(), {}, {})
    }
}
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun LibraryScreenPreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val provider = remember { object : cord.kasane.music.provider.AccountLibraryProvider {
                override suspend fun collection(collection: cord.kasane.music.provider.AccountCollection, continuation: String?) = cord.kasane.music.model.CatalogPage("", "", null, emptyList())
                override suspend fun playlist(playlistId: String, continuation: String?) = cord.kasane.music.model.CatalogPage("", "", null, emptyList())
                override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
            } }
            LibraryScreen(accountState = AccountState.Guest, accountProvider = provider, filter = "All", onFilter = {},
                showingHistory = false, onSelectHistory = {}, onSignIn = {}, onExpired = {}, onCatalogOpen = {}, onAddToQueue = {},
                favoritesContent = { FavoritesScreen(emptyList(), signedIn = false, null, false, false, {}, {}, {}) }, historyContent = {})
        }
    }
}

@Preview(name = "Library · YouTube Music", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun LibraryOnlinePreview() {
    KasaneTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val provider = remember { object : cord.kasane.music.provider.AccountLibraryProvider {
                override suspend fun collection(collection: cord.kasane.music.provider.AccountCollection, continuation: String?) =
                    when (collection) {
                        cord.kasane.music.provider.AccountCollection.PLAYLISTS -> cord.kasane.music.model.CatalogPage(
                            "Playlists", "", null, listOf(cord.kasane.music.model.CatalogShelf("Playlists", listOf(
                                CatalogItem("PLpreview", cord.kasane.music.model.CatalogKind.PLAYLIST, "Late night drive", "Kasane Preview")
                            )))
                        )
                        cord.kasane.music.provider.AccountCollection.ALBUMS -> cord.kasane.music.model.CatalogPage("Albums", "", null, emptyList())
                        cord.kasane.music.provider.AccountCollection.ARTISTS -> cord.kasane.music.model.CatalogPage("Artists", "", null, emptyList())
                    }
                override suspend fun playlist(playlistId: String, continuation: String?) = cord.kasane.music.model.CatalogPage("", "", null, emptyList())
                override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
            } }
            LibraryScreen(
                accountState = AccountState.SignedIn(cord.kasane.music.auth.AccountIdentity("Preview Listener", null, "UCpreview")),
                accountProvider = provider,
                filter = "Playlists",
                onFilter = {},
                showingHistory = false,
                onSelectHistory = {},
                onSignIn = {},
                onExpired = {},
                onCatalogOpen = {},
                onAddToQueue = {},
                favoritesContent = {},
                historyContent = {},
            )
        }
    }
}
