package cord.kasane.music.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import cord.kasane.music.KasaneApplication
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

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun KasaneApp(app: KasaneApplication) {
    MaterialTheme(
        colorScheme = KasaneDarkColorScheme,
        typography = KasaneTypography
    ) {
        val playbackState by app.playbackManager.uiState.collectAsState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        var selectedTab by rememberSaveable { mutableStateOf(0) } // Home / Search / Library
        val nowPlayingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var isNowPlayingOpen by remember { mutableStateOf(false) }

        val screenState = rememberSaveableStateHolder()
        var libraryHistory by rememberSaveable { mutableStateOf(false) }
        var detailStack by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
        var homeShelves by remember { mutableStateOf<List<CatalogShelf>>(emptyList()) }
        var showAccountLibrary by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        val favorites by app.database.trackDao().getFavorites().collectAsState(initial = emptyList())
        val history by app.database.trackDao().getRecentHistory().collectAsState(initial = emptyList())
        val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

        // Home feed recommendations from YouTube Music
        var homeRecommendations by remember { mutableStateOf<List<Track>>(emptyList()) }
        var isHomeLoading by remember { mutableStateOf(false) }

        var homeError by remember { mutableStateOf<String?>(null) }
        var homeRevision by remember { mutableIntStateOf(0) }
        val accountState by app.accountRepository.state.collectAsState()
        LaunchedEffect(accountState) { homeShelves = emptyList(); homeRecommendations = emptyList() }
        LaunchedEffect(homeRevision, accountState) {
            isHomeLoading = true
            homeError = null
            try {
                val homePage = app.ytProvider.catalog.home()
                homeShelves = homePage.shelves
                homeRecommendations = homePage.tracks
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                homeError = "Recommendations unavailable. Check your connection and try again."
            } finally { isHomeLoading = false }
        }
        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Gray950,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ProfileScreen(accountState, { scope.launch { app.accountRepository.signOut() } },
                    { scope.launch { app.accountRepository.restore() } }, { app.accountRepository.cancelSignIn() })
            }
        }

        // Android Predictive / System Back navigation handler
        if (showAccountLibrary) {
            ModalBottomSheet(onDismissRequest = { showAccountLibrary = false }, containerColor = Gray950,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                AccountLibraryScreen(accountState, app.ytProvider.catalog,
                    { showAccountLibrary = false; showSettings = true },
                    { entry -> showAccountLibrary = false; entry.track()?.let { app.playbackManager.playTrack(it) }
                        ?: run { detailStack = detailStack + entry } },
                    { app.playbackManager.addToQueue(it) },
                    { scope.launch { app.accountRepository.expire() } })
            }
        }
        BackHandler(enabled = isNowPlayingOpen || detailStack.isNotEmpty() || selectedTab != 0) {
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
        }

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
                val openCatalog: (CatalogItem) -> Unit = { entry ->
                    entry.track()?.let { app.playbackManager.playTrack(it) }
                        ?: run { detailStack = detailStack + entry }
                }
                if (detailStack.isNotEmpty()) {
                    key(detailStack.last().id) {
                        CatalogDetailScreen(detailStack.last(), app.ytProvider.catalog,
                            { detailStack = detailStack.dropLast(1) }, openCatalog,
                            { tracks, shuffle -> app.playbackManager.playTracks(tracks, shuffle) },
                            { app.playbackManager.addToQueue(it) })
                    }
                } else screenState.SaveableStateProvider(selectedTab) {
                when (if (selectedTab == 2 && libraryHistory) 3 else selectedTab) {
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
                        } }, { app.playbackManager.addToQueue(it) }, { app.playbackManager.playNextInQueue(it) })
                    2 -> LibraryScreen(
                        onOpenAccount = { showAccountLibrary = true },
                        showingHistory = libraryHistory,
                        onSelectHistory = { libraryHistory = it },
                        favoritesContent = { FavoritesScreen(
                        favorites = favorites,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        isLoading = playbackState.isLoading,
                        onTrackClick = { app.playbackManager.playTrack(it) },
                        onAddToQueue = { app.playbackManager.addToQueue(it) },
                        onRemoveFavorite = { track ->
                            scope.launch { app.database.trackDao().removeFavorite(track.id) }
                        }
                    ) },
                        historyContent = {}
                    )
                    3 -> LibraryScreen(
                        onOpenAccount = { showAccountLibrary = true },
                        showingHistory = libraryHistory,
                        onSelectHistory = { libraryHistory = it },
                        favoritesContent = {},
                        historyContent = { HistoryScreen(
                        history = history,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        isLoading = playbackState.isLoading,
                        favoriteIds = favoriteIds,
                        onHistoryItemClick = { item ->
                            val track = Track(
                                id = item.trackId,
                                title = item.title,
                                artist = item.artist,
                                album = item.album,
                                thumbnailUri = item.thumbnailUri
                            )
                            app.playbackManager.playTrack(track)
                        },
                        onAddToQueue = { item ->
                            val track = Track(
                                id = item.trackId,
                                title = item.title,
                                artist = item.artist,
                                album = item.album,
                                thumbnailUri = item.thumbnailUri
                            )
                            app.playbackManager.addToQueue(track)
                        },
                        onToggleFavorite = { item ->
                            scope.launch {
                                val track = Track(
                                    id = item.trackId,
                                    title = item.title,
                                    artist = item.artist,
                                    album = item.album,
                                    thumbnailUri = item.thumbnailUri
                                )
                                app.database.trackDao().insertTrack(track)
                                if (favoriteIds.contains(track.id)) {
                                    app.database.trackDao().removeFavorite(track.id)
                                } else {
                                    app.database.trackDao().addFavorite(FavoriteEntity(track.id))
                                }
                            }
                        }
                    ) }
                    )
                }
                }
            }
        }
    }
}

@Composable
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
    onCatalogOpen: (CatalogItem) -> Unit = {}
) {
    val listenAgain = remember(recentHistory) { HomeFeedPolicy.latestHistory(recentHistory) }
    val uniqueRecommendations = remember(recommendations) { HomeFeedPolicy.uniqueTracks(recommendations) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
                        Column(Modifier.width(156.dp).clip(RoundedCornerShape(14.dp)).clickable { onTrackClick(track) }) {
                            cord.kasane.music.ui.components.TrackArtwork(track.thumbnailUri, Modifier.size(156.dp))
                            Text(track.title, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = KasaneTypography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Gray400, style = KasaneTypography.bodyMedium)
                        }
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

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, style = KasaneTypography.titleLarge, modifier = Modifier.weight(1f))
        Text(subtitle, style = KasaneTypography.labelMedium, color = Gray400)
    }
}

@Composable
fun LibraryScreen(showingHistory: Boolean, onSelectHistory: (Boolean) -> Unit,
    favoritesContent: @Composable () -> Unit, historyContent: @Composable () -> Unit, onOpenAccount: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Text("Your library", style = KasaneTypography.titleLarge, modifier = Modifier.padding(20.dp))
        if (onOpenAccount != null) TextButton(onClick = onOpenAccount, modifier = Modifier.padding(horizontal = 12.dp)) { Text("YouTube Music account library") }
        Text("On this device · favorites and listening history", color = Gray400, modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cord.kasane.music.ui.components.KasaneChoiceChip("Favorites", !showingHistory, { onSelectHistory(false) })
            cord.kasane.music.ui.components.KasaneChoiceChip("History", showingHistory, { onSelectHistory(true) })
        }
        Box(Modifier.weight(1f)) { if (showingHistory) historyContent() else favoritesContent() }
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
                        isFavorite = true
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
            LibraryScreen(false, {}, { FavoritesScreen(emptyList(), null, false, false, {}, {}, {}) }, {}, onOpenAccount = {})
        }
    }
}
