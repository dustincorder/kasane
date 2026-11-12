package cord.kasane.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
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
import cord.kasane.music.ui.components.MiniPlayer
import cord.kasane.music.ui.components.NowPlayingSheet
import cord.kasane.music.ui.components.TrackRow
import cord.kasane.music.ui.theme.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun KasaneApp(app: KasaneApplication) {
    val account by app.accountRepository.state.collectAsState()
    val session = app.accountRepository.session().takeIf { account is AccountState.SignedIn }
    val cloud = remember(session) { session?.let { captured -> cord.kasane.music.provider.CloudLikes(
        read = { id -> app.ytProvider.catalog.likeState(captured, id) },
        write = { id, liked -> app.ytProvider.catalog.setLikedFor(captured, id, liked) },
        writeDislike = { id, disliked -> app.ytProvider.catalog.setDislikedFor(captured, id, disliked) }) } }
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
    val context = LocalContext.current
    LaunchedEffect(cloud) { cloud?.errors?.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    CompositionLocalProvider(cord.kasane.music.ui.components.LocalCloudLikes provides cloud,
        cord.kasane.music.ui.components.LocalTrackActions provides cord.kasane.music.ui.components.TrackActions(
            app.playbackManager::playTrack, app.playbackManager::playNextInQueue, app.playbackManager::addToQueue, app.playbackManager::startRadio)) { KasaneContent(app) }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun KasaneContent(app: KasaneApplication) {
    MaterialTheme(
        colorScheme = KasaneDarkColorScheme,
        typography = KasaneTypography
    ) {
        val playbackState by app.playbackManager.uiState.collectAsState()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val exportLocalData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val favoritesSnapshot = app.database.trackDao().getFavoritesForExport()
                        val historySnapshot = app.database.trackDao().getHistoryForExport()
                        val json = LocalDataExporter.encode(favoritesSnapshot, historySnapshot,
                            preferences = mapOf("autoplay" to context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE).getBoolean("autoplay", true).toString()))
                        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(json) }
                            ?: error("Could not open the selected file.")
                    }
                }
                val message = if (result.isSuccess) "Kasane local data exported." else "Could not export local data."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
        val nowPlayingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var isNowPlayingOpen by remember { mutableStateOf(false) }

        val screenState = rememberSaveableStateHolder()
        var libraryHistory by rememberSaveable { mutableStateOf(false) }
        var libraryOnline by rememberSaveable { mutableStateOf(false) }
        var libraryFilter by rememberSaveable { mutableStateOf("All") }
        var detailStack by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
        var homeShelves by remember { mutableStateOf<List<CatalogShelf>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }
        val favorites by app.database.trackDao().getFavorites().collectAsState(initial = emptyList())
        val history by app.database.trackDao().getRecentHistory().collectAsState(initial = emptyList())
        val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

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
        var activeHomeScope by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(homeRevision, accountState, catalogScope) {
            if (activeHomeScope != catalogScope) {
                activeHomeScope = catalogScope
                homeShelves = emptyList()
                homeRecommendations = emptyList()
            }
            val saved = app.catalogCache.read(catalogScope, cord.kasane.music.data.CachedCatalogRepository.HOME_KEY)
            if (saved != null) {
                homeShelves = saved.page.shelves
                homeRecommendations = saved.page.tracks
            }
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
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                homeError = "Recommendations unavailable. Check your connection and try again."
            } finally { isHomeLoading = false; isHomeRefreshing = false; handledHomeRevision = homeRevision }
        }
        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Gray950,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ProfileScreen(accountState, startWebSessionLogin,
                    { scope.launch { app.accountRepository.signOut(); clearYtMusicWebSession() } },
                    { scope.launch { app.accountRepository.restore() } }, {
                        app.accountRepository.cancelSignIn()
                        showLoginDialog = false
                        clearYtMusicWebSession()
                    }, onExportLocalData = { exportLocalData.launch("kasane-local-data.json") })
            }
        }
        if (showLoginDialog) {
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
        PredictiveBackHandler(enabled = isNowPlayingOpen || detailStack.isNotEmpty() || selectedTab != 0) { progress ->
            try {
                progress.collect { event -> predictiveBackProgress = event.progress }
                predictiveBackProgress = 0f
            val navigation = navigationAfterBack(selectedTab, isNowPlayingOpen, detailStack.size)
            when {
                isNowPlayingOpen -> {
                    scope.launch { nowPlayingSheetState.hide() }.invokeOnCompletion {
                        isNowPlayingOpen = navigation.nowPlayingOpen
                    }
                }
                detailStack.isNotEmpty() -> { detailStack = detailStack.take(navigation.detailDepth) }
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
            entry.track()?.let { app.playbackManager.playTrack(it) }
                ?: run { isNowPlayingOpen = false; detailStack = detailStack + entry }
        }
        CompositionLocalProvider(cord.kasane.music.ui.components.LocalTrackNavigation provides
            cord.kasane.music.ui.components.TrackNavigation(app.ytProvider.catalog.trackLinks, openCatalog)) {
        // Fullscreen Now Playing Sheet
        if (isNowPlayingOpen && playbackState.currentTrack != null) {
            val currentTrack = playbackState.currentTrack!!
            NowPlayingSheet(
                uiState = playbackState,
                onTogglePlay = { app.playbackManager.togglePlayPause() },
                onNext = { app.playbackManager.playNext() },
                onPrev = { app.playbackManager.playPrevious() },
                onSeek = { app.playbackManager.seekTo(it) },
                onDismiss = { isNowPlayingOpen = false },
                isFavorite = favoriteIds.contains(currentTrack.id),
                onToggleFavorite = {
                    scope.launch {
                        app.database.trackDao().insertTrack(currentTrack)
                        if (favoriteIds.contains(currentTrack.id)) {
                            app.database.trackDao().removeFavorite(currentTrack.id)
                        } else {
                            app.database.trackDao().addFavorite(FavoriteEntity(currentTrack.id))
                        }
                    }
                },
                onQueueTrack = { app.playbackManager.playTrack(it) },
                onShuffle = { app.playbackManager.toggleShuffle() },
                onRepeat = { app.playbackManager.cycleRepeat() },
                sheetState = nowPlayingSheetState
            )
        }

        Scaffold(
            modifier = Modifier.graphicsLayer {
                translationX = size.width * predictiveBackProgress * 0.08f
            },
            containerColor = Gray950,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                if (playbackState.errorMessage != null) {
                    Snackbar(
                        containerColor = Gray850,
                        contentColor = White,
                        action = {
                            Row {
                                TextButton(onClick = { app.playbackManager.retryPlayback() }) {
                                    Text("Retry", color = AccentRose)
                                }
                                TextButton(onClick = { app.playbackManager.clearError() }) {
                                    Text("Dismiss", color = Gray400)
                                }
                            }
                        },
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(playbackState.errorMessage!!)
                    }
                }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gray900)
                ) {
                    MiniPlayer(
                        uiState = playbackState,
                        onTogglePlay = { app.playbackManager.togglePlayPause() },
                        onNext = { app.playbackManager.playNext() },
                        onPrev = { app.playbackManager.playPrevious() },
                        onOpenNowPlaying = { isNowPlayingOpen = true }
                    )

                    HorizontalDivider(
                        color = Gray800,
                        thickness = 0.5.dp
                    )

                    // Streamlined Bottom Navigation (compact 64dp, no pill clutter, proper insets)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Gray900)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val navItems = listOf(
                            Triple(0, "Home", Icons.Default.Home),
                            Triple(1, "Search", Icons.Default.Search),
                            Triple(2, "Library", Icons.Default.LibraryMusic)
                        )

                        for ((tabIndex, label, icon) in navItems) {
                            val isSelected = selectedTab == tabIndex
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable {
                                        if (tabIndex != 1) focusManager.clearFocus()
                                        val navigation = navigationAfterTabSelection(tabIndex)
                                        selectedTab = navigation.selectedTab
                                        detailStack = emptyList()
                                        isNowPlayingOpen = navigation.nowPlayingOpen
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSelected) AccentRose else Gray500,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    style = KasaneTypography.labelMedium.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) AccentRose else Gray500
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Gray950)
            ) {
                if (detailStack.isNotEmpty()) {
                    val detail = detailStack.last()
                    screenState.SaveableStateProvider("detail:${detail.kind}:${detail.id}") {
                        CatalogDetailScreen(detail, app.ytProvider.catalog,
                            { detailStack = detailStack.dropLast(1) }, openCatalog,
                            { tracks, shuffle -> app.playbackManager.playTracks(tracks, shuffle) },
                            { app.playbackManager.addToQueue(it) }, app.cachedCatalog, catalogScope)
                    }
                } else screenState.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        currentTrack = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        isLoading = playbackState.isLoading,
                        recentHistory = history,
                        favorites = favorites,
                        favoriteIds = favoriteIds,
                        recommendations = homeRecommendations,
                        isHomeLoading = isHomeLoading,
                        onOpenSearch = { selectedTab = 1 },
                        onOpenSettings = { showSettings = true },
                        catalogShelves = homeShelves,
                        isRefreshing = isHomeRefreshing,
                        onRefresh = { homeRevision++ },
                        onCatalogOpen = openCatalog,
                        homeError = homeError,
                        onRetryHome = { homeRevision++ },
                        onTrackClick = { app.playbackManager.playTrack(it) },
                        onAddToQueue = { app.playbackManager.addToQueue(it) },
                        onToggleFavorite = { track ->
                            scope.launch {
                                app.database.trackDao().insertTrack(track)
                                if (favoriteIds.contains(track.id)) {
                                    app.database.trackDao().removeFavorite(track.id)
                                } else {
                                    app.database.trackDao().addFavorite(FavoriteEntity(track.id))
                                }
                            }
                        }
                    )
                    1 -> CatalogSearchScreen(
                        app.ytProvider.catalog, openCatalog, playbackState.currentTrack?.id, favoriteIds,
                        { track -> scope.launch {
                            app.database.trackDao().insertTrack(track)
                            if (track.id in favoriteIds) app.database.trackDao().removeFavorite(track.id)
                            else app.database.trackDao().addFavorite(FavoriteEntity(track.id))
                        } }, { app.playbackManager.addToQueue(it) }, { app.playbackManager.playNextInQueue(it) },
                        cachedCatalog = app.cachedCatalog, cacheScope = catalogScope)
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
                        favoritesCount = favorites.size,
                        historyCount = history.size,
                        onCatalogOpen = { entry -> entry.track()?.let { app.playbackManager.playTrack(it) }
                            ?: run { detailStack = detailStack + entry } },
                        onAddToQueue = { app.playbackManager.addToQueue(it) },
                        favoritesContent = { FavoritesScreen(
                            favorites = favorites,
                            currentTrackId = playbackState.currentTrack?.id,
                            isPlaying = playbackState.isPlaying,
                            isLoading = playbackState.isLoading,
                            onTrackClick = { app.playbackManager.playTrack(it) },
                            onAddToQueue = { app.playbackManager.addToQueue(it) },
                            onRemoveFavorite = { track -> scope.launch { app.database.trackDao().removeFavorite(track.id) } }
                        ) },
                        historyContent = { HistoryScreen(
                            history = history,
                            currentTrackId = playbackState.currentTrack?.id,
                            isPlaying = playbackState.isPlaying,
                            isLoading = playbackState.isLoading,
                            favoriteIds = favoriteIds,
                            onHistoryItemClick = { item -> app.playbackManager.playTrack(Track(
                                id = item.trackId, title = item.title, artist = item.artist, album = item.album,
                                thumbnailUri = item.thumbnailUri)) },
                            onAddToQueue = { item -> app.playbackManager.addToQueue(Track(
                                id = item.trackId, title = item.title, artist = item.artist, album = item.album,
                                thumbnailUri = item.thumbnailUri)) },
                            onToggleFavorite = { item -> scope.launch {
                                val track = Track(item.trackId, item.title, item.artist, item.album, thumbnailUri = item.thumbnailUri)
                                app.database.trackDao().insertTrack(track)
                                if (track.id in favoriteIds) app.database.trackDao().removeFavorite(track.id)
                                else app.database.trackDao().addFavorite(FavoriteEntity(track.id))
                            } }
                        ) }
                    )
                }
                }
            }
        }
    }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    currentTrack: Track?,
    isPlaying: Boolean,
    isLoading: Boolean,
    recentHistory: List<HistoryEntity>,
    favorites: List<Track>,
    favoriteIds: Set<String>,
    recommendations: List<Track>,
    isHomeLoading: Boolean,
    onOpenSearch: () -> Unit,
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onOpenSettings: () -> Unit = {},
    homeError: String? = null,
    onRetryHome: () -> Unit = {},
    catalogShelves: List<CatalogShelf> = emptyList(),
    onCatalogOpen: (CatalogItem) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    val listenAgain = remember(recentHistory) { HomeFeedPolicy.latestHistory(recentHistory) }
    val uniqueRecommendations = remember(recommendations) { HomeFeedPolicy.uniqueTracks(recommendations) }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(cord.kasane.music.R.mipmap.ic_launcher), null, modifier = Modifier.size(32.dp))
                Text("Kasane", color = White, style = KasaneTypography.titleLarge, modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, "Search", tint = Gray200) }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Person, "Profile", tint = AccentRose) }
            }
        }
        if (catalogShelves.isNotEmpty()) {
            catalogShelves.forEach { shelf -> item { CatalogShelfRow(shelf, onCatalogOpen) } }
            if (isHomeLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = AccentRose) }
            if (homeError != null) item {
                Text(homeError, color = Gray400, modifier = Modifier.padding(20.dp))
                TextButton(onClick = onRetryHome) { Text("Retry") }
            }
        } else {
        item { SectionTitle("Fast picks", "For the moment") }
        item {
            when {
                isHomeLoading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp), color = AccentRose)
                homeError != null -> Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(homeError, color = Gray400)
                    TextButton(onClick = onRetryHome) { Text("Try again") }
                }
                uniqueRecommendations.isEmpty() -> Text("No recommendations yet. Find something you love in Search.", color = Gray400, modifier = Modifier.padding(20.dp))
                else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uniqueRecommendations, key = { it.id }) { track ->
                        cord.kasane.music.ui.components.TrackCard(track, 156.dp, onClick = { onTrackClick(track) },
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)),
                            onLocalFavorite = { onToggleFavorite(track) }, localFavorite = track.id in favoriteIds)
                    }
                }
            }
        }
        }
        if (listenAgain.isNotEmpty()) {
            item { SectionTitle("Listen Again", "Recently played") }
            items(listenAgain.take(15), key = { it.trackId }) { item ->
                val track = Track(item.trackId, item.title, item.artist, item.album, thumbnailUri = item.thumbnailUri)
                TrackRow(track = track, isPlaying = currentTrack?.id == track.id && isPlaying,
                    isLoading = currentTrack?.id == track.id && isLoading,
                    onClick = { onTrackClick(track) }, onAddToQueue = { onAddToQueue(track) },
                    onToggleFavorite = { onToggleFavorite(track) }, isFavorite = track.id in favoriteIds,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Gray900))
            }
        }
    }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, style = KasaneTypography.titleLarge, modifier = Modifier.weight(1f))
        Text(subtitle, style = KasaneTypography.labelMedium, color = Gray400)
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
) {
    Column(Modifier.fillMaxSize()) {
        Text("Your library", color = White, style = KasaneTypography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Playlists", "Songs", "Albums", "Artists")) { item ->
                cord.kasane.music.ui.components.KasaneChoiceChip(item, filter == item, { onFilter(item) })
            }
        }
        when (filter) {
            "Songs" -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showingHistory) "Recently played" else "Favorites", color = White, style = KasaneTypography.titleMedium, modifier = Modifier.weight(1f))
                    Text("On this device", color = Gray400, style = KasaneTypography.labelMedium)
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
                    LibraryCollectionRow("Favorites", "$favoritesCount songs · On this device", Icons.Default.Favorite) {
                        onFilter("Songs"); onSelectHistory(false)
                    }
                    LibraryCollectionRow("History", "$historyCount played · On this device", Icons.Default.History) {
                        onFilter("Songs"); onSelectHistory(true)
                    }
                    Text("YouTube Music", color = Gray400, style = KasaneTypography.labelMedium,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AccentRose, modifier = Modifier.size(34.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = White, style = KasaneTypography.bodyLarge)
            Text(subtitle, color = Gray400, style = KasaneTypography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Gray400)
    }
}

@Preview(name = "HomeScreen State Preview", showBackground = true, backgroundColor = 0xFF0C0D10, widthDp = 390, heightDp = 844)
@Composable
fun HomeScreenPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        HomeScreen(
            currentTrack = Track(
                id = "prev-current",
                title = "Beat It",
                artist = "Michael Jackson",
                album = "Thriller",
                durationSeconds = 258.0
            ),
            isPlaying = true,
            isLoading = false,
            recentHistory = listOf(
                HistoryEntity(
                    trackId = "prev-h1",
                    title = "Midnight City Lights",
                    artist = "Aether",
                    album = "Neon Drift"
                ),
                HistoryEntity(
                    trackId = "prev-h2",
                    title = "Subway Melancholy",
                    artist = "Dustin Corder",
                    album = "Echoes of Silence"
                )
            ),
            favorites = listOf(
                Track(
                    id = "prev-f1",
                    title = "Synthetic Dawn",
                    artist = "Kuro",
                    album = "Genesis"
                )
            ),
            favoriteIds = setOf("prev-f1"),
            recommendations = listOf(
                Track(
                    id = "prev-r1",
                    title = "Choosin' Texas",
                    artist = "Ella Langley",
                    album = "Hungover"
                ),
                Track(
                    id = "prev-r2",
                    title = "a thousand years",
                    artist = "Christina Perri",
                    album = "Lovestrong"
                )
            ),
            isHomeLoading = false,
            onOpenSearch = {},
            onTrackClick = {},
            onAddToQueue = {},
            onToggleFavorite = {}
        )
    }
}

@Composable
fun FavoritesScreen(
    favorites: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onTrackClick: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onRemoveFavorite: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Compact Header (52dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Favorites",
                style = KasaneTypography.titleMedium.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = White
            )
            Text(
                text = "${favorites.size} tracks",
                style = KasaneTypography.labelMedium,
                color = Gray400
            )
        }

        HorizontalDivider(
            color = Gray800,
            thickness = 0.5.dp
        )

        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No favorite tracks yet",
                    style = KasaneTypography.bodyMedium,
                    color = Gray400
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
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
                        useCloudFavorite = false
                    )
                    HorizontalDivider(
                        color = Gray800,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    history: List<HistoryEntity>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    favoriteIds: Set<String>,
    onHistoryItemClick: (HistoryEntity) -> Unit,
    onAddToQueue: (HistoryEntity) -> Unit,
    onToggleFavorite: (HistoryEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Compact Header (52dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "History",
                style = KasaneTypography.titleMedium.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = White
            )
            Text(
                text = "${history.size} played",
                style = KasaneTypography.labelMedium,
                color = Gray400
            )
        }

        HorizontalDivider(
            color = Gray800,
            thickness = 0.5.dp
        )

        if (history.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No listening history yet",
                    style = KasaneTypography.bodyMedium,
                    color = Gray400
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(history, key = { it.id }) { item ->
                    val track = Track(
                        id = item.trackId,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        thumbnailUri = item.thumbnailUri
                    )
                    val isCurrent = currentTrackId == track.id
                    TrackRow(
                        track = track,
                        isPlaying = isCurrent && isPlaying,
                        isLoading = isCurrent && isLoading,
                        onClick = { onHistoryItemClick(item) },
                        onAddToQueue = { onAddToQueue(item) },
                        onToggleFavorite = { onToggleFavorite(item) },
                        isFavorite = favoriteIds.contains(track.id)
                    )
                    HorizontalDivider(
                        color = Gray800,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SearchScreenPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        CatalogSearchScreen(cord.kasane.music.provider.OnlineCatalog(okhttp3.OkHttpClient()), {}, null, emptySet(), {}, {})
    }
}
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun LibraryScreenPreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
            val provider = remember { object : cord.kasane.music.provider.AccountLibraryProvider {
                override suspend fun collection(collection: cord.kasane.music.provider.AccountCollection, continuation: String?) = cord.kasane.music.model.CatalogPage("", "", null, emptyList())
                override suspend fun playlist(playlistId: String, continuation: String?) = cord.kasane.music.model.CatalogPage("", "", null, emptyList())
                override suspend fun setLiked(videoId: String, liked: Boolean) = Unit
            } }
            LibraryScreen(accountState = AccountState.Guest, accountProvider = provider, filter = "All", onFilter = {},
                showingHistory = false, onSelectHistory = {}, onSignIn = {}, onExpired = {}, onCatalogOpen = {}, onAddToQueue = {},
                favoritesContent = { FavoritesScreen(emptyList(), null, false, false, {}, {}, {}) }, historyContent = {})
        }
    }
}

@Preview(name = "Library · YouTube Music", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun LibraryOnlinePreview() {
    MaterialTheme(colorScheme = KasaneDarkColorScheme, typography = KasaneTypography) {
        Surface(color = Gray950) {
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
