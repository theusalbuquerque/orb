package com.music.orb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.orb.auth.YtMusicLoginScreen
import com.music.orb.data.LocalMediaRepository
import com.music.orb.data.NerdStats
import com.music.orb.data.TrackLog
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.Song
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.scrobbling.LastFM
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.ThemeMode
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.TrackMatcher
import com.music.orb.ui.screens.AccountAndScrobblingScreen
import com.music.orb.ui.screens.SettingsScreen
import com.music.orb.playback.QueueBuilder
import com.music.orb.playback.QueueShuffle
import com.music.orb.playback.autoplaySectionStart
import com.music.orb.playback.dropAutoplayTracks
import com.music.orb.playback.playSongs
import com.music.orb.playback.toMediaItem
import com.music.orb.download.DownloadStore
import com.music.orb.download.Downloads
import com.music.orb.ui.components.PlaylistActionsSheet
import com.music.orb.ui.components.PlaylistPickerSheet
import com.music.orb.ui.components.SongActionsSheet
import com.music.orb.playback.rememberMediaController
import com.music.orb.playback.rememberPlayerState
import com.music.orb.ui.MainViewModel
import com.music.orb.ui.components.BottomFadeBlur
import com.music.orb.ui.components.BottomTab
import com.music.orb.ui.components.FloatingBottomBar
import com.music.orb.ui.components.FrostedTopBar
import com.music.orb.ui.components.LastfmLoginAlert
import com.music.orb.ui.components.ListenBrainzTokenAlert
import com.music.orb.ui.components.MiniPlayer
import com.music.orb.ui.components.TopBarAccountButton
import com.music.orb.ui.components.TopFadeBlur
import com.music.orb.ui.components.LyricsSourcesDialog
import com.music.orb.ui.components.UpdateAvailableDialog
import com.music.orb.ui.icons.BitChordIcons
import androidx.media3.common.Player
import com.music.orb.data.YtMusicRepository
import com.music.orb.ui.player.NowPlayingScreen
import com.music.orb.ui.screens.DetailScreen
import com.music.orb.ui.screens.LocalMusicScreen
import com.music.orb.ui.screens.HomeScreen
import com.music.orb.ui.screens.LibraryScreen
import com.music.orb.ui.screens.SearchScreen
import com.music.orb.ui.theme.BitChordTheme
import com.music.orb.ui.theme.rememberArtworkPalette
import com.music.orb.ui.theme.SystemBarIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            BitChordTheme(darkTheme = darkTheme) {
                BitChordApp(darkTheme = darkTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitChordApp(darkTheme: Boolean, viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAccountScrobbling by remember { mutableStateOf(false) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var linksLoading by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<UserPlaylist?>(null) }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    var searchFocusTrigger by remember { mutableIntStateOf(0) }

    SystemBarIcons(dark = !darkTheme && !showNowPlaying)

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()

    var updateDialogShown by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    val updateNotice = updateAvailable

    LaunchedEffect(updateNotice) {
        if (updateNotice != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog = true
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val exploreState by viewModel.explore.collectAsStateWithLifecycle()
    val libraryState by viewModel.library.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val detail = detailStack.lastOrNull()
    val isLocalDetail = detail?.browseId?.startsWith("local:") == true
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playlistsLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()

    LaunchedEffect(detail) { if (detail != null) showSettings = false }
    LaunchedEffect(showSettings) {
        if (!showSettings) {
            showAccountScrobbling = false
        }
    }

    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    LaunchedEffect(savedDownloads, detail?.browseId) {
        if (detail?.browseId == "local:downloads") {
            viewModel.reloadLocalDetail("local:downloads")
        }
    }

    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()

    var autoplaySeed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(autoplay, player.queueIndex, player.queue.size, player.song?.videoId, player.repeatMode) {
        val song = player.song
        val current = song?.videoId
        if (!autoplay || song == null || current == null) return@LaunchedEffect
        if (player.repeatMode == Player.REPEAT_MODE_ALL) return@LaunchedEffect
        if (player.queueIndex < player.queue.lastIndex) return@LaunchedEffect
        if (autoplaySeed == current) return@LaunchedEffect
        autoplaySeed = current
        val seed = youtubeSeedFor(song) ?: return@LaunchedEffect
        YtMusicRepository.radio(seed).onSuccess { related ->
            val extra = QueueBuilder.extend(player.queue, related, RADIO_BATCH)
            if (extra.isNotEmpty()) {
                val resolved = coroutineScope {
                    extra.map { async { YtMusicRepository.resolveAudio(it) } }.awaitAll()
                }
                controller?.addMediaItems(
                    resolved.map { it.copy(fromAutoplay = true).toMediaItem() },
                )
            }
        }
    }

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    LaunchedEffect(player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources) {
        player.song?.let {
            viewModel.loadLyrics(it.videoId, it.title, it.artist, player.durationMs, it.albumName)
        }
    }

    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val currentListState = when (selectedTab) {
        TAB_HOME -> homeListState
        TAB_EXPLORE -> exploreListState
        TAB_LIBRARY -> libraryListState
        else -> searchListState
    }

    val homePull = rememberPullToRefreshState()
    val explorePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val currentFeed = when {
        showSettings || showAccountScrobbling || detail != null -> null
        selectedTab == TAB_HOME -> MainViewModel.Feed.HOME
        selectedTab == TAB_EXPLORE -> MainViewModel.Feed.EXPLORE
        selectedTab == TAB_LIBRARY -> MainViewModel.Feed.LIBRARY
        else -> null
    }
    LaunchedEffect(currentFeed) {
        if (currentFeed == MainViewModel.Feed.HOME) viewModel.onHomeShown()
        if (currentFeed == MainViewModel.Feed.LIBRARY) viewModel.onLibraryShown()
    }

    val currentPull = when (currentFeed) {
        MainViewModel.Feed.HOME -> homePull
        MainViewModel.Feed.EXPLORE -> explorePull
        MainViewModel.Feed.LIBRARY -> libraryPull
        null -> null
    }
    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                    currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    val detailListState = remember(detail?.browseId) { LazyListState() }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                    detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    val tabPlay = stringResource(R.string.tab_play)
    val tabExplore = stringResource(R.string.tab_explore)
    val tabLibrary = stringResource(R.string.tab_library)
    val tabSearch = stringResource(R.string.tab_search)

    val tabs = remember(tabPlay, tabExplore, tabLibrary, tabSearch) {
        listOf(
            BottomTab(tabPlay, BitChordIcons.Play),
            BottomTab(tabExplore, BitChordIcons.Explore),
            BottomTab(tabLibrary, BitChordIcons.Library),
            BottomTab(tabSearch, BitChordIcons.Search),
        )
    }

    val scope = rememberCoroutineScope()

    suspend fun List<Song>.resolvedForQueue(): List<Song> = coroutineScope {
        map { async { YtMusicRepository.resolveAudio(it) } }.awaitAll()
    }

    val play: (List<Song>, Int) -> Unit = { songs, index ->
        scope.launch {
            val starting = YtMusicRepository.resolveAudio(songs[index])
            val queued = songs.toMutableList().also { it[index] = starting }
            controller?.playSongs(queued, index)
            showNowPlaying = true
            queued.forEachIndexed { i, song ->
                if (i == index || !song.isVideo) return@forEachIndexed
                launch {
                    val resolved = YtMusicRepository.resolveAudio(song)
                    if (resolved.videoId == song.videoId) return@launch
                    val c = controller ?: return@launch
                    val at = (0 until c.mediaItemCount)
                        .firstOrNull { c.getMediaItemAt(it).mediaId == song.videoId }
                        ?: return@launch
                    c.replaceMediaItem(at, resolved.toMediaItem())
                }
            }
        }
    }

    val playRadio: (Song) -> Unit = { song ->
        autoplaySeed = song.videoId
        scope.launch {
            val resolved = YtMusicRepository.resolveAudio(song)
            autoplaySeed = resolved.videoId
            controller?.playSongs(listOf(resolved), 0)
            showNowPlaying = true
            val seed = youtubeSeedFor(resolved) ?: return@launch
            YtMusicRepository.radio(seed).onSuccess { related ->
                if (controller?.currentMediaItem?.mediaId != resolved.videoId) return@onSuccess
                val extra = QueueBuilder.extend(listOf(resolved), related, RADIO_BATCH)
                if (extra.isNotEmpty()) {
                    controller?.addMediaItems(
                        extra.resolvedForQueue().map {
                            it.copy(fromAutoplay = true).toMediaItem()
                        },
                    )
                }
            }
        }
    }
    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            val resolved = YtMusicRepository.resolveAudio(song)
            controller?.let { it.addMediaItem(it.autoplaySectionStart(), resolved.toMediaItem()) }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            val resolved = YtMusicRepository.resolveAudio(song)
            controller?.let {
                it.addMediaItem(
                    (it.currentMediaItemIndex + 1).coerceAtMost(it.mediaItemCount),
                    resolved.toMediaItem(),
                )
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    var downloadPending by remember { mutableStateOf<List<Song>>(emptyList()) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songs = downloadPending
        downloadPending = emptyList()
        when {
            songs.isEmpty() -> Unit
            granted -> songs.forEach { Downloads.enqueue(context, it) }
            else -> Toast
                .makeText(context, "Storage access is needed to save songs", Toast.LENGTH_SHORT)
                .show()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
        } else {
            Toast.makeText(context, "Storage permission is required to read local audio files", Toast.LENGTH_SHORT).show()
        }
    }

    val startDownload: (List<Song>) -> Unit = { requested ->
        val saved = Downloads.saved.value
        val songs = requested.filter { it.videoId !in saved }
        if (songs.isNotEmpty()) {
            val needsStorage = DownloadStore.needsLegacyPermission() &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                downloadPending = songs
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                songs.forEach { Downloads.enqueue(context, it) }
            }
        }
        if (requested.size > 1) {
            val message = if (songs.isEmpty()) {
                "Already downloaded"
            } else {
                "Downloading ${songs.size} song" + if (songs.size == 1) "" else "s"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val listPadding = PaddingValues(
        top = 96.dp,
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    val detailPalette = rememberArtworkPalette(detail?.thumbnailUrl)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BackHandler(enabled = detail != null && !showSettings && !showAccountScrobbling) { viewModel.closeDetail() }
        BackHandler(enabled = showSettings && !showAccountScrobbling) {
            showSettings = false
            if (detail == null) selectedTab = TAB_HOME
        }
        BackHandler(enabled = detail == null && !showSettings && !showAccountScrobbling && selectedTab != TAB_HOME) {
            selectedTab = TAB_HOME
        }
        BackHandler(enabled = showUpdateDialog) { showUpdateDialog = false }
        BackHandler(enabled = showListenBrainzLogin) { showListenBrainzLogin = false }
        BackHandler(enabled = showLastfmLogin) { showLastfmLogin = false }

        AnimatedContent(
            targetState = when {
                showAccountScrobbling -> "account_scrobbling"
                showSettings -> "settings"
                detail != null -> detail.browseId
                else -> "tab:$selectedTab"
            },
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier.hazeSource(hazeState),
            label = "content",
        ) { key ->
            val page = detailStack.lastOrNull()?.takeIf {
                it.browseId == key && key != "settings" && key != "account_scrobbling"
            }
            if (key == "account_scrobbling") {
                AccountAndScrobblingScreen(
                    signedIn = signedIn,
                    account = account,
                    onSignIn = {
                        showAccountScrobbling = false
                        showSettings = false
                        showLogin = true
                    },
                    onSignOut = { viewModel.signOut() },
                    onOpenListenBrainzLogin = { showListenBrainzLogin = true },
                    onOpenLastfmLogin = { showLastfmLogin = true },
                    contentPadding = listPadding,
                )
            } else if (key == "settings") {
                SettingsScreen(
                    signedIn = signedIn,
                    account = account,
                    onSignIn = {
                        showSettings = false
                        showLogin = true
                    },
                    onSignOut = { viewModel.signOut() },
                    onAccountScrobbling = { showAccountScrobbling = true },
                    onLyricsSources = { showLyricsSources = true },
                    contentPadding = listPadding,
                )
            } else if (page != null && page.browseId.startsWith("local:")) {
                val localState = page.songs
                val localSongs = (localState as? com.music.orb.data.model.UiState.Success)
                    ?.data.orEmpty()
                LocalMusicScreen(
                    songs = localSongs,
                    onSongClick = play,
                    onSongLongPress = { songActions = it },
                    onSongSwipe = onSongSwipe,
                    onShuffle = { songs ->
                        QueueShuffle.enableForNextQueue()
                        play(songs, songs.indices.random())
                    },
                    emptyMessage = (localState as? com.music.orb.data.model.UiState.Error)
                        ?.message,
                    contentPadding = listPadding,
                )
            } else if (page != null) {
                val withAlbum: (Song) -> Song = { song ->
                    if (page.type == BrowseType.ALBUM) {
                        song.copy(albumName = song.albumName ?: page.title)
                    } else {
                        song
                    }
                }
                DetailScreen(
                    page = page,
                    listState = detailListState,
                    onSongClick = play,
                    onSongLongPress = { songActions = withAlbum(it) },
                    onSongSwipe = onSongSwipe,
                    onShuffle = { songs ->
                        QueueShuffle.enableForNextQueue()
                        play(songs, songs.indices.random())
                    },
                    onSectionItemClick = { item ->
                        item.browseId?.let { id ->
                            viewModel.openDetail(
                                browseId = id,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                                type = BrowseType.ALBUM,
                            )
                        }
                    },
                    onDownloadAll = { songs -> startDownload(songs.map(withAlbum)) },
                    onArtistClick = { id, name ->
                        viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
                    },
                    onAddSuggested = { song -> viewModel.addSuggestedSong(page.browseId, song) },
                    onToggleLibrary = if (signedIn) {
                        { viewModel.toggleLibrary(page.browseId) }
                    } else {
                        null
                    },
                    contentPadding = listPadding,
                )
            } else when (selectedTab) {
                TAB_HOME -> HomeScreen(
                    state = homeState,
                    listState = homeListState,
                    signedIn = signedIn,
                    onSignIn = { showLogin = true },
                    onItemClick = { item ->
                        when {
                            item.videoId != null -> playRadio(
                                Song(
                                    videoId = item.videoId,
                                    title = item.title,
                                    artist = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                ),
                            )
                            item.browseId != null -> viewModel.openDetail(
                                browseId = item.browseId,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                            )
                        }
                    },
                    onRetry = viewModel::loadHome,
                    refreshing = MainViewModel.Feed.HOME in refreshing,
                    onRefresh = { viewModel.refresh(MainViewModel.Feed.HOME) },
                    pullState = homePull,
                    contentPadding = listPadding,
                    onLoadMore = viewModel::loadMoreHome,
                    loadingMore = homeLoadingMore,
                )
                TAB_EXPLORE -> HomeScreen(
                    state = exploreState,
                    listState = exploreListState,
                    title = "Explore",
                    onItemClick = { item ->
                        when {
                            item.videoId != null -> playRadio(
                                Song(
                                    videoId = item.videoId,
                                    title = item.title,
                                    artist = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                ),
                            )
                            item.browseId != null -> viewModel.openDetail(
                                browseId = item.browseId,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                            )
                        }
                    },
                    onRetry = viewModel::loadExplore,
                    refreshing = MainViewModel.Feed.EXPLORE in refreshing,
                    onRefresh = { viewModel.refresh(MainViewModel.Feed.EXPLORE) },
                    pullState = explorePull,
                    contentPadding = listPadding,
                )
                TAB_SEARCH -> SearchScreen(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    filter = filter,
                    onFilterChange = viewModel::onFilterChange,
                    results = results,
                    listState = searchListState,
                    focusTrigger = searchFocusTrigger,
                    onSongClick = { songs, index ->
                        songs.getOrNull(index)?.let {
                            viewModel.recordSearch()
                            playRadio(it)
                        }
                    },
                    onSongLongPress = { songActions = it },
                    onSongSwipe = onSongSwipe,
                    onBrowseClick = { item ->
                        viewModel.recordSearch()
                        viewModel.openDetail(
                            browseId = item.browseId,
                            title = item.title,
                            subtitle = item.subtitle,
                            thumbnailUrl = item.thumbnailUrl,
                            type = item.type,
                        )
                    },
                    history = searchHistory,
                    suggestions = searchSuggestions,
                    onSubmit = viewModel::submitSearch,
                    onSuggestionClick = viewModel::searchFor,
                    onHistoryClick = viewModel::searchFor,
                    onHistoryRemove = viewModel::removeSearch,
                    onHistoryClear = viewModel::clearSearchHistory,
                    contentPadding = listPadding,
                )
                else -> LibraryScreen(
                    signedIn = signedIn,
                    state = libraryState,
                    listState = libraryListState,
                    onShelfItemClick = { item ->
                        item.browseId?.let { id ->
                            if (id == "local:all" && !LocalMediaRepository.hasStoragePermission(context)) {
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_AUDIO
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                mediaPermissionLauncher.launch(perm)
                            }
                            viewModel.openDetail(
                                browseId = id,
                                title = item.title,
                                subtitle = item.subtitle,
                                thumbnailUrl = item.thumbnailUrl,
                            )
                        }
                    },
                    onShelfItemLongPress = { item ->
                        playlistActions = viewModel.editablePlaylist(item.browseId)
                    },
                    onNewPlaylist = { creatingPlaylist = true },
                    onSignIn = { showLogin = true },
                    onRetry = viewModel::loadLibrary,
                    refreshing = MainViewModel.Feed.LIBRARY in refreshing,
                    onRefresh = { viewModel.refresh(MainViewModel.Feed.LIBRARY) },
                    pullState = libraryPull,
                    contentPadding = listPadding,
                )
            }
        }

        val isDetailVisible = detail != null && !isLocalDetail && !showSettings && !showAccountScrobbling
        if (isDetailVisible) {
            TopFadeBlur(
                hazeState = hazeState,
                pageColor = detailPalette.wash,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        val titleListenNow = stringResource(R.string.title_listen_now)
        val titleSettingsText = stringResource(R.string.title_settings)
        val titleAccountText = stringResource(R.string.title_account_scrobbling)

        FrostedTopBar(
            title = when {
                showAccountScrobbling -> titleAccountText
                showSettings -> titleSettingsText
                detail != null -> detail.title
                else -> tabs[selectedTab].let {
                    if (it.label == tabPlay) titleListenNow else it.label
                }
            },
            hazeState = hazeState,
            ownBackdrop = detail == null || isLocalDetail,
            scrolled = when {
                showSettings || showAccountScrobbling -> true
                detail != null -> detailScrolled
                else -> scrolled || selectedTab == TAB_SEARCH
            },
            refreshing = currentFeed != null && currentFeed in refreshing,
            pullFraction = { currentPull?.distanceFraction ?: 0f },
            onBack = when {
                showAccountScrobbling -> ({ showAccountScrobbling = false })
                showSettings -> ({ showSettings = false })
                detail != null -> ({ viewModel.closeDetail(); Unit })
                else -> null
            },
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                if (!showSettings && !showAccountScrobbling && detail == null && selectedTab == TAB_HOME) {
                    updateNotice?.let { update ->
                        IconButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        }) {
                            Icon(
                                Icons.Rounded.SystemUpdate,
                                contentDescription = "Update available: v${update.version}",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (!showSettings && !showAccountScrobbling) {
                    TopBarAccountButton(
                        account = account,
                        onClick = { showSettings = true },
                    )
                }
            },
        )

        BottomFadeBlur(
            hazeState = hazeState,
            withMiniPlayer = player.song != null,
            pageColor = if (isDetailVisible) detailPalette.background else MaterialTheme.colorScheme.background,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            player.song?.let { song ->
                MiniPlayer(
                    song = song,
                    isPlaying = player.isPlaying,
                    isLoading = player.isLoading,
                    hazeState = hazeState,
                    onPlayPause = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = { controller?.seekToNextMediaItem() },
                    onExpand = { showNowPlaying = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FloatingBottomBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                hazeState = hazeState,
                onTabSelected = { index ->
                    if (index == TAB_SEARCH && selectedTab == TAB_SEARCH) {
                        searchFocusTrigger++
                        return@FloatingBottomBar
                    }
                    if (index != TAB_SEARCH) {
                        searchFocusTrigger = 0
                    }
                    viewModel.clearDetail()
                    showSettings = false
                    showAccountScrobbling = false
                    selectedTab = index
                },
            )
        }

        if (showNowPlaying && player.song != null) {
            var links by remember { mutableStateOf<Song?>(null) }
            LaunchedEffect(player.song?.videoId) {
                links = null
                linksLoading = false
                val current = player.song ?: return@LaunchedEffect
                if (current.albumId != null && current.artistId != null) return@LaunchedEffect
                linksLoading = true
                links = YtMusicRepository.trackLinks(current.videoId).getOrNull()
                linksLoading = false
            }
            val song = player.song!!.let { current ->
                val extra = links?.takeIf { it.videoId == current.videoId }
                    ?: return@let current
                current.copy(
                    artistId = current.artistId ?: extra.artistId,
                    albumId = current.albumId ?: extra.albumId,
                    albumName = current.albumName ?: extra.albumName,
                )
            }
            LaunchedEffect(song) {
                if (songActions?.videoId == song.videoId) songActions = song
            }
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RectangleShape,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                NowPlayingScreen(
                    song = song,
                    isPlaying = player.isPlaying,
                    isLoading = player.isLoading,
                    positionMs = player.positionMs,
                    durationMs = player.durationMs,
                    onPlayPause = {
                        controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPrevious() },
                    onSeekFraction = { fraction ->
                        controller?.let { player ->
                            val duration = player.duration
                            if (duration > 0) {
                                player.seekTo(
                                    (fraction * duration).toLong()
                                        .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                                )
                            }
                        }
                    },
                    onSeek = { target ->
                        controller?.let { player ->
                            val duration = player.duration
                            player.seekTo(
                                if (duration > 0) {
                                    target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                                } else {
                                    target.coerceAtLeast(0L)
                                },
                            )
                        }
                    },
                    queue = player.queue,
                    queueIndex = player.queueIndex,
                    hasPrevious = player.hasPrevious,
                    hasNext = player.hasNext,
                    repeatMode = player.repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    autoplayEnabled = autoplay,
                    signedIn = signedIn,
                    likeStatus = likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT,
                    onToggleLike = { viewModel.toggleLike(song.videoId) },
                    onToggleShuffle = { controller?.let(QueueShuffle::toggle) },
                    onCycleRepeat = {
                        controller?.let {
                            val next = when (it.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            if (next == Player.REPEAT_MODE_ALL) it.dropAutoplayTracks()
                            it.repeatMode = next
                        }
                    },
                    onToggleAutoplay = {
                        val on = !autoplay
                        AppSettings.setAutoplay(on)
                        if (on) {
                            autoplaySeed = null
                        } else {
                            controller?.dropAutoplayTracks()
                        }
                    },
                    onJumpTo = { controller?.seekToDefaultPosition(it) },
                    onRemoveFromQueue = { controller?.removeMediaItem(it) },
                    onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
                    onOpenMenu = { songActions = song },
                    onOpenAlbum = { id ->
                        showNowPlaying = false
                        viewModel.openDetail(
                            id,
                            song.albumName ?: song.title,
                            song.artist,
                            song.thumbnailUrl,
                            BrowseType.ALBUM,
                        )
                    },
                    onOpenArtist = { id ->
                        showNowPlaying = false
                        viewModel.openDetail(id, song.artist, "Artist", null, BrowseType.ARTIST)
                    },
                    lyrics = lyrics,
                    lyricsSource = lyricsSource,
                    lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
                    onClearQueue = {
                        controller?.let { c ->
                            if (c.mediaItemCount > c.currentMediaItemIndex + 1) {
                                c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount)
                            }
                        }
                    },
                )
            }
        }

        songActions?.let { song ->
            val fromPlayer = showNowPlaying
            val share: () -> Unit = {
                Toast.makeText(context, "Sharing is coming soon", Toast.LENGTH_SHORT).show()
                songActions = null
            }
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                showNowPlaying = false
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(id, title, sub, art, type)
            }
            LaunchedEffect(song.videoId) { viewModel.loadSongMenu(song.videoId) }
            val editable = viewModel.editablePlaylist(detail?.browseId)
                ?.takeIf { !fromPlayer && song.setVideoId != null }
            ModalBottomSheet(
                onDismissRequest = { songActions = null },
                containerColor = Color.Transparent,
                dragHandle = null,
            ) {
                SongActionsSheet(
                    song = song,
                    signedIn = signedIn,
                    likeStatus = likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT,
                    onPlayNext = { playNext(song); songActions = null },
                    onAddToQueue = { addToQueue(song); songActions = null },
                    onDownload = { startDownload(listOf(song)) },
                    onToggleLike = { viewModel.toggleLike(song.videoId) },
                    onToggleDislike = { viewModel.toggleDislike(song.videoId) },
                    onAddToPlaylist = {
                        songActions = null
                        viewModel.loadPlaylists()
                        playlistTarget = song
                    },
                    onRemoveFromPlaylist = editable?.let {
                        {
                            songActions = null
                            viewModel.removeFromPlaylist(it.browseId, song)
                        }
                    },
                    onOpenAlbum = { id ->
                        openPage(
                            id,
                            song.albumName ?: song.title,
                            song.artist,
                            BrowseType.ALBUM,
                        )
                    },
                    onOpenArtist = { id ->
                        openPage(id, song.artist, "Artist", BrowseType.ARTIST)
                    },
                    resolvingLinks = fromPlayer && linksLoading,
                    showSleepTimer = fromPlayer,
                    onShare = share.takeIf { fromPlayer },
                    onCopyLog = if (fromPlayer) {
                        {
                            songActions = null
                            scope.launch {
                                val text = TrackLog.forTrack(song, NerdStats.current.value)
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(
                                    context,
                                    "Log copied · ${text.lineSequence().count()} lines",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (playlistTarget != null || creatingPlaylist) {
            val target = playlistTarget
            val dismiss = {
                playlistTarget = null
                creatingPlaylist = false
            }
            ModalBottomSheet(
                onDismissRequest = dismiss,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistPickerSheet(
                    playlists = playlists,
                    loading = playlistsLoading,
                    song = target,
                    startCreating = target == null,
                    onPick = { playlist ->
                        target?.let { viewModel.addToPlaylist(playlist, it) }
                        dismiss()
                    },
                    onCreate = { title, privacy ->
                        viewModel.createPlaylist(title, privacy, target)
                        dismiss()
                    },
                )
            }
        }

        playlistActions?.let { playlist ->
            ModalBottomSheet(
                onDismissRequest = { playlistActions = null },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                PlaylistActionsSheet(
                    playlist = playlist,
                    onOpen = {
                        playlistActions = null
                        viewModel.openDetail(
                            browseId = playlist.browseId,
                            title = playlist.title,
                            subtitle = playlist.subtitle,
                            thumbnailUrl = playlist.thumbnailUrl,
                            type = BrowseType.PLAYLIST,
                        )
                    },
                    onRename = { name ->
                        playlistActions = null
                        viewModel.renamePlaylist(playlist, name)
                    },
                    onDelete = {
                        playlistActions = null
                        viewModel.deletePlaylist(playlist)
                    },
                )
            }
        }

        if (showLogin) {
            BackHandler { showLogin = false }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showLogin = false }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            text = stringResource(R.string.sign_in_google),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    YtMusicLoginScreen(
                        onCookiesCaptured = { cookie ->
                            viewModel.onSignedIn(cookie)
                            showLogin = false
                            selectedTab = 2
                        },
                    )
                }
            }
        }

        if (showUpdateDialog) {
            updateNotice?.let { update ->
                UpdateAvailableDialog(
                    version = update.version,
                    hazeState = hazeState,
                    onDismiss = { showUpdateDialog = false },
                    onUpdate = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                        showUpdateDialog = false
                    },
                )
            }
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (showListenBrainzLogin) {
            var tokenInput by remember { mutableStateOf(listenBrainzToken) }
            ListenBrainzTokenAlert(
                hazeState = hazeState,
                tokenInput = tokenInput,
                onTokenInputChange = { tokenInput = it },
                onSave = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzLogin = false
                },
                onDismiss = { showListenBrainzLogin = false },
            )
        }

        if (showLastfmLogin) {
            var usernameInput by remember { mutableStateOf("") }
            var passwordInput by remember { mutableStateOf("") }
            var lastfmError by remember { mutableStateOf<String?>(null) }
            var lastfmLoading by remember { mutableStateOf(false) }
            LastfmLoginAlert(
                hazeState = hazeState,
                usernameInput = usernameInput,
                onUsernameInputChange = { usernameInput = it },
                passwordInput = passwordInput,
                onPasswordInputChange = { passwordInput = it },
                error = lastfmError,
                loading = lastfmLoading,
                onSignIn = {
                    lastfmLoading = true
                    lastfmError = null
                    scope.launch {
                        try {
                            LastFM.initialize(
                                apiKey = LastFM.FALLBACK_COMPAT_API_KEY,
                                secret = LastFM.FALLBACK_COMPAT_SECRET,
                            )
                            LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                .onSuccess { auth ->
                                    AppSettings.setLastfmSessionKey(auth.session.key)
                                    AppSettings.setLastfmUsername(auth.session.name)
                                    AppSettings.setLastfmEnabled(true)
                                    showLastfmLogin = false
                                }
                                .onFailure { e ->
                                    lastfmError = e.message ?: "Login failed"
                                }
                        } catch (e: Exception) {
                            lastfmError = e.message ?: "Login failed"
                        } finally {
                            lastfmLoading = false
                        }
                    }
                },
                onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
            )
        }
    }
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

private const val RADIO_BATCH = 20

private const val SEEK_END_GUARD_MS = 1_000L

private suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

private val DETAIL_TITLE_DROP = 320.dp

private const val TAB_HOME = 0
private const val TAB_EXPLORE = 1
private const val TAB_LIBRARY = 2
private const val TAB_SEARCH = 3