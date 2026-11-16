package com.tvtune.music

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.metrolist.shared.model.LyricsEntry
import com.tvtune.music.history.TvHistoryManager
import coil3.compose.AsyncImage
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.viewmodels.SearchHistoryCodec
import com.tvtune.music.auth.TvPairingServer
import com.tvtune.music.auth.TvQrUtils
import com.tvtune.music.playback.TvPlaybackManager
import com.tvtune.music.playback.TvRepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class TvMainActivity : ComponentActivity() {

    private val playbackManager: TvPlaybackManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvTheme {
                TvMainContent(playbackManager)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                playbackManager.togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playbackManager.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playbackManager.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playbackManager.next()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playbackManager.previous()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                playbackManager.seekRelative(10000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                playbackManager.seekRelative(-10000L)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackManager.release()
    }
}

val TvGreen = Color(0xFF1DB954)
val TvDarkBg = Color(0xFF0F0F11)
val TvCardBg = Color(0xFF1C1C20)
val TvCardFocusBg = Color(0xFF282830)
val TvTextDim = Color(0xFF9E9EA8)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvGreen,
            background = TvDarkBg,
            surface = TvCardBg,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

sealed class TvScreen {
    object Home : TvScreen()
    object Search : TvScreen()
    object Library : TvScreen()
    object Account : TvScreen()
    object Player : TvScreen()
    object Lyrics : TvScreen()
    data class Artist(val channelId: String, val name: String, val thumbnail: String? = null) : TvScreen()
    data class Collection(
        val id: String,
        val isAlbum: Boolean,
        val title: String,
        val thumbnail: String? = null,
        val subtitle: String? = null
    ) : TvScreen()
}

enum class TvNavTab(val title: String, val icon: () -> ImageVector) {
    HOME("Home", { MetrolistIcons.Filled.Home }),
    SEARCH("Search", { MetrolistIcons.Filled.Search }),
    LIBRARY("Library", { MetrolistIcons.Filled.LibraryMusic }),
    SIGN_IN("Account", { MetrolistIcons.Filled.AccountCircle }),
    PLAYER("Player", { MetrolistIcons.Filled.PlayArrow })
}

@Composable
fun TvMainContent(playbackManager: TvPlaybackManager) {
    var screenStack by remember { mutableStateOf<List<TvScreen>>(listOf(TvScreen.Home)) }
    var isSidebarFocused by remember { mutableStateOf(false) }
    val currentSong by playbackManager.currentSong.collectAsState()
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val firstNavRequester = remember { FocusRequester() }

    val currentScreen = screenStack.lastOrNull() ?: TvScreen.Home

    // Fullscreen Immersive Jellyfin-style Lyrics View
    if (currentScreen is TvScreen.Lyrics) {
        TvLyricsScreen(
            playbackManager = playbackManager,
            onBack = {
                if (screenStack.size > 1) {
                    screenStack = screenStack.dropLast(1)
                } else {
                    screenStack = listOf(TvScreen.Player)
                }
            }
        )
        return
    }

    val currentTab = when (currentScreen) {
        is TvScreen.Home -> TvNavTab.HOME
        is TvScreen.Search, is TvScreen.Artist -> TvNavTab.SEARCH
        is TvScreen.Library -> TvNavTab.LIBRARY
        is TvScreen.Account -> TvNavTab.SIGN_IN
        is TvScreen.Player -> TvNavTab.PLAYER
        is TvScreen.Collection -> if (screenStack.any { it is TvScreen.Library }) TvNavTab.LIBRARY else TvNavTab.HOME
        else -> TvNavTab.HOME
    }

    // Remote Back button navigation stack handling
    BackHandler(enabled = screenStack.size > 1 || currentScreen != TvScreen.Home) {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        } else {
            screenStack = listOf(TvScreen.Home)
        }
    }

    val railWidth by animateDpAsState(
        targetValue = if (isSidebarFocused) 190.dp else 68.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "sidebarWidth"
    )

    LaunchedEffect(Unit) {
        firstNavRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvDarkBg)
    ) {
        // Collapsible Navigation Rail on the Left (TV remote friendly)
        Column(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .background(Color(0xFF0C0C10))
                .onFocusChanged { focusState ->
                    isSidebarFocused = focusState.hasFocus
                }
                .padding(vertical = 24.dp, horizontal = if (isSidebarFocused) 12.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header / App Branding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = if (isSidebarFocused) Alignment.CenterStart else Alignment.Center
            ) {
                if (isSidebarFocused) {
                    Text(
                        text = "Tv-Tune",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = TvGreen,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Icon(
                        imageVector = MetrolistIcons.Filled.MusicNote,
                        contentDescription = "Tv-Tune",
                        tint = TvGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            TvNavTab.values().forEachIndexed { index, tab ->
                val isSelected = currentTab == tab
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = {
                        screenStack = when (tab) {
                            TvNavTab.HOME -> listOf(TvScreen.Home)
                            TvNavTab.SEARCH -> listOf(TvScreen.Search)
                            TvNavTab.LIBRARY -> listOf(TvScreen.Library)
                            TvNavTab.SIGN_IN -> listOf(TvScreen.Account)
                            TvNavTab.PLAYER -> if (screenStack.lastOrNull() is TvScreen.Player) screenStack else screenStack + TvScreen.Player
                        }
                    },
                    color = when {
                        isFocused -> TvGreen
                        isSelected -> Color(0xFF222B24)
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = if (isSelected && !isFocused) BorderStroke(1.dp, TvGreen.copy(alpha = 0.5f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .onFocusChanged { focusState ->
                            isFocused = focusState.isFocused
                            if (focusState.isFocused) {
                                isSidebarFocused = true
                            }
                        }
                        .then(if (index == 0) Modifier.focusRequester(firstNavRequester) else Modifier)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isSidebarFocused) 12.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isSidebarFocused) Arrangement.Start else Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = tab.title,
                            tint = when {
                                isFocused -> Color.Black
                                isSelected -> TvGreen
                                else -> TvTextDim
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        if (isSidebarFocused) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = tab.title,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    isFocused -> Color.Black
                                    isSelected -> TvGreen
                                    else -> TvTextDim
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isSidebarFocused) {
                Text(
                    text = "D-Pad Nav",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
        }

        // Main Screen Content Area (collapses sidebar when focused)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 24.dp,
                    bottom = if (currentSong != null && currentScreen !is TvScreen.Player) 80.dp else 24.dp
                )
                .onFocusChanged { focusState ->
                    if (focusState.hasFocus) {
                        isSidebarFocused = false
                    }
                }
        ) {
            when (val screen = currentScreen) {
                is TvScreen.Home -> TvHomeScreen(
                    playbackManager = playbackManager,
                    onPlaySong = { song ->
                        playbackManager.playSong(song)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onOpenAlbum = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = true, title = title, thumbnail = thumb)
                    },
                    onOpenPlaylist = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = false, title = title, thumbnail = thumb)
                    },
                    onOpenArtist = { id, name, thumb ->
                        screenStack = screenStack + TvScreen.Artist(channelId = id, name = name, thumbnail = thumb)
                    }
                )
                is TvScreen.Search -> TvSearchScreen(
                    onPlaySong = { song ->
                        playbackManager.playSong(song)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onOpenAlbum = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = true, title = title, thumbnail = thumb)
                    },
                    onOpenPlaylist = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = false, title = title, thumbnail = thumb)
                    },
                    onOpenArtist = { id, name, thumb ->
                        screenStack = screenStack + TvScreen.Artist(channelId = id, name = name, thumbnail = thumb)
                    }
                )
                is TvScreen.Library -> TvLibraryScreen(
                    onPlaySong = { song ->
                        playbackManager.playSong(song)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onOpenPlaylist = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = false, title = title, thumbnail = thumb)
                    }
                )
                is TvScreen.Account -> TvAccountScreen()
                is TvScreen.Lyrics -> {}
                is TvScreen.Player -> TvPlayerScreen(
                    playbackManager = playbackManager,
                    onOpenLyrics = {
                        screenStack = screenStack + TvScreen.Lyrics
                    },
                    onBackToHome = { screenStack = listOf(TvScreen.Home) }
                )
                is TvScreen.Collection -> TvCollectionScreen(
                    id = screen.id,
                    isAlbum = screen.isAlbum,
                    title = screen.title,
                    thumbnail = screen.thumbnail,
                    subtitle = screen.subtitle,
                    onPlayTrack = { songs, index ->
                        playbackManager.playQueue(songs, index)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onPlayAll = { songs ->
                        playbackManager.playQueue(songs, 0)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onPlayShuffled = { songs ->
                        playbackManager.playQueueShuffled(songs)
                        screenStack = screenStack + TvScreen.Player
                    },
                    onBack = {
                        if (screenStack.size > 1) screenStack = screenStack.dropLast(1) else screenStack = listOf(TvScreen.Home)
                    }
                )
                is TvScreen.Artist -> TvArtistScreen(
                    channelId = screen.channelId,
                    artistName = screen.name,
                    thumbnail = screen.thumbnail,
                    playbackManager = playbackManager,
                    onOpenAlbum = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = true, title = title, thumbnail = thumb)
                    },
                    onOpenPlaylist = { id, title, thumb ->
                        screenStack = screenStack + TvScreen.Collection(id, isAlbum = false, title = title, thumbnail = thumb)
                    },
                    onOpenPlayer = {
                        screenStack = screenStack + TvScreen.Player
                    },
                    onBack = {
                        if (screenStack.size > 1) screenStack = screenStack.dropLast(1) else screenStack = listOf(TvScreen.Home)
                    }
                )
            }

            // Bottom Mini-Player Bar (shown when playing and not in full player screen)
            if (currentSong != null && currentScreen !is TvScreen.Player && currentScreen !is TvScreen.Lyrics) {
                TvMiniPlayer(
                    song = currentSong!!,
                    isPlaying = isPlaying,
                    onTogglePlay = { playbackManager.togglePlayPause() },
                    onExpandPlayer = { screenStack = screenStack + TvScreen.Player },
                    onOpenLyrics = { screenStack = screenStack + TvScreen.Lyrics },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
fun TvMiniPlayer(
    song: SongItem,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        color = if (isFocused) Color(0xFF282834) else Color(0xFF181820),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvGreen else Color(0xFF333340),
                shape = RoundedCornerShape(16.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onExpandPlayer() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Column {
                    Text(
                        text = song.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.artists.joinToString(", ") { it.name },
                        fontSize = 13.sp,
                        color = TvTextDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenLyrics,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3825))
                ) {
                    Text(
                        text = "📝 Lyrics",
                        color = TvGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Button(
                    onClick = onTogglePlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFocused) TvGreen else Color(0xFF30303E)
                    )
                ) {
                    Text(
                        text = if (isPlaying) "Pause" else "Play",
                        color = if (isFocused) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TvHomeScreen(
    playbackManager: TvPlaybackManager,
    onPlaySong: (SongItem) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenPlaylist: (String, String, String?) -> Unit,
    onOpenArtist: (String, String, String?) -> Unit
) {
    var homeData by remember { mutableStateOf<HomePageData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    val lastPlayedSong by playbackManager.historyManager.lastPlayedSong.collectAsState()
    var recommendedSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val data = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getHomePageData()
                }
                homeData = data
            } catch (e: Exception) {
                errorMessage = "Failed to load Home: ${e.localizedMessage ?: "Network error"}"
            } finally {
                isLoading = false
            }
        }
    }

    // Load smart recommendations based on the user's last listened song
    LaunchedEffect(lastPlayedSong?.id) {
        val song = lastPlayedSong
        if (song != null) {
            try {
                val radio = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.startRadioQueue(song.id)
                }
                recommendedSongs = radio.songs.filter { it.id != song.id }
            } catch (_: Exception) {
                recommendedSongs = emptyList()
            }
        } else {
            recommendedSongs = emptyList()
        }
    }

    if (showHistoryDialog) {
        TvListeningHistoryDialog(
            historyManager = playbackManager.historyManager,
            onPlaySong = { song ->
                showHistoryDialog = false
                onPlaySong(song)
            },
            onDismiss = { showHistoryDialog = false }
        )
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TvGreen)
        }
        return
    }

    if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = errorMessage ?: "", color = Color.Red, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        try {
                            homeData = withContext(Dispatchers.IO) {
                                GlobalYouTube.repository.getHomePageData()
                            }
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage
                        } finally {
                            isLoading = false
                        }
                    }
                }) {
                    Text("Retry")
                }
            }
        }
        return
    }

    val sections = homeData?.sections?.filter { it.items.isNotEmpty() } ?: emptyList()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // Top Header Row with Explore Title and Listening History Button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Explore & Discover",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                var isHistoryFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = { showHistoryDialog = true },
                    color = if (isHistoryFocused) TvGreen else Color(0xFF22222E),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isHistoryFocused) TvGreen else Color(0xFF3A3A4C)),
                    modifier = Modifier
                        .onFocusChanged { isHistoryFocused = it.isFocused }
                        .focusable()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.History,
                            contentDescription = "Listening History",
                            tint = if (isHistoryFocused) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Listening History",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isHistoryFocused) Color.Black else Color.White
                        )
                    }
                }
            }
        }

        // Recommendations based on last listened song
        if (recommendedSongs.isNotEmpty()) {
            item(key = "recommendations_last_played") {
                val headerTitle = if (lastPlayedSong != null) {
                    "Because you listened to ${lastPlayedSong!!.title}"
                } else {
                    "Recommended for You"
                }
                TvRecommendedSongsRow(
                    title = headerTitle,
                    songs = recommendedSongs,
                    onPlaySong = onPlaySong
                )
            }
        }

        items(sections, key = { it.title }) { section ->
            TvSectionRow(
                section = section,
                onPlaySong = onPlaySong,
                onOpenAlbum = onOpenAlbum,
                onOpenPlaylist = onOpenPlaylist,
                onOpenArtist = onOpenArtist
            )
        }
    }
}

@Composable
fun TvRecommendedSongsRow(
    title: String,
    songs: List<SongItem>,
    onPlaySong: (SongItem) -> Unit
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                TvCardItem(
                    item = song,
                    index = index,
                    listState = listState,
                    onClick = { onPlaySong(song) }
                )
            }
        }
    }
}

@Composable
fun TvSectionRow(
    section: HomeSection,
    onPlaySong: (SongItem) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenPlaylist: (String, String, String?) -> Unit,
    onOpenArtist: (String, String, String?) -> Unit
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = section.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(section.items) { index, item ->
                TvCardItem(
                    item = item,
                    index = index,
                    listState = listState,
                    onClick = {
                        when (item) {
                            is SongItem -> onPlaySong(item)
                            is AlbumItem -> onOpenAlbum(item.id, item.title, item.thumbnail)
                            is PlaylistItem -> onOpenPlaylist(item.id, item.title, item.thumbnail)
                            is ArtistItem -> onOpenArtist(item.channelId ?: item.id, item.title, item.thumbnail)
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TvCardItem(
    item: YTItem,
    index: Int,
    listState: LazyListState,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Smoothly scroll into view when remote cursor moves here
    LaunchedEffect(isFocused) {
        if (isFocused) {
            scope.launch {
                listState.animateScrollToItem(index.coerceAtLeast(0))
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        animationSpec = spring(),
        label = "cardScale"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) TvGreen else Color.Transparent,
        label = "cardBorder"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) TvCardFocusBg else TvCardBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier
            .width(160.dp)
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth()
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val subtitle = when (item) {
                is SongItem -> item.artists.joinToString(", ") { it.name }
                is AlbumItem -> item.artists.joinToString(", ") { it.name }
                is PlaylistItem -> item.author ?: "Playlist"
                is ArtistItem -> item.subscribers ?: "Artist"
                else -> ""
            }

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TvTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

enum class TvSearchCategory(val label: String, val filter: SearchFilter?) {
    ALL("All", null),
    SONGS("Songs", SearchFilter.SONG),
    VIDEO_SONGS("Video Songs", SearchFilter.VIDEO),
    ALBUMS("Albums", SearchFilter.ALBUM),
    COMMUNITY_PLAYLISTS("Community Playlists", SearchFilter.COMMUNITY_PLAYLIST)
}

@Composable
fun TvSearchScreen(
    onPlaySong: (SongItem) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenPlaylist: (String, String, String?) -> Unit,
    onOpenArtist: (String, String, String?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(TvSearchCategory.ALL) }
    var results by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var hasSubmittedSearch by remember { mutableStateOf(false) }
    var searchHistory by remember { mutableStateOf(SearchHistoryCodec.decode(Settings.shared.searchHistory)) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(query) {
        if (query.length >= 2 && !hasSubmittedSearch) {
            delay(250)
            try {
                val sug = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getSearchSuggestions(query)
                }
                suggestions = sug?.queries ?: emptyList()
            } catch (_: Exception) {
                suggestions = emptyList()
            }
        } else {
            suggestions = emptyList()
        }
    }

    fun performSearch(queryText: String, category: TvSearchCategory) {
        if (queryText.isBlank()) return
        query = queryText
        hasSubmittedSearch = true
        keyboardController?.hide()

        val updated = (listOf(queryText) + (searchHistory - queryText)).take(25)
        searchHistory = updated
        Settings.shared.searchHistory = SearchHistoryCodec.encode(updated)

        scope.launch {
            isSearching = true
            errorMsg = null
            try {
                val list = withContext(Dispatchers.IO) {
                    if (category.filter == null) {
                        GlobalYouTube.repository.search(queryText)
                    } else {
                        GlobalYouTube.repository.searchFiltered(queryText, category.filter).items
                    }
                }
                results = list
            } catch (e: Exception) {
                errorMsg = "Search error: ${e.localizedMessage}"
            } finally {
                isSearching = false
            }
        }
    }

    fun removeHistoryItem(item: String) {
        val updated = searchHistory - item
        searchHistory = updated
        Settings.shared.searchHistory = SearchHistoryCodec.encode(updated)
    }

    fun clearAllHistory() {
        searchHistory = emptyList()
        Settings.shared.searchHistory = ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (hasSubmittedSearch) hasSubmittedSearch = false
                },
                placeholder = { Text("Search songs, artists, albums, playlists...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvGreen,
                    unfocusedBorderColor = Color(0xFF333340),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(query, selectedCategory) }),
                modifier = Modifier.weight(1f)
            )

            var isSearchBtnFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { performSearch(query, selectedCategory) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSearchBtnFocused) TvGreen else Color(0xFF2A2A38)
                ),
                modifier = Modifier
                    .onFocusChanged { isSearchBtnFocused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    text = "Search",
                    color = if (isSearchBtnFocused) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TV Remote Filter Buttons Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TvSearchCategory.values().forEach { category ->
                val isSelected = selectedCategory == category
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = {
                        selectedCategory = category
                        if (query.isNotBlank()) {
                            performSearch(query, category)
                        }
                    },
                    color = when {
                        isFocused -> TvGreen
                        isSelected -> Color(0xFF2E3A32)
                        else -> Color(0xFF202028)
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) TvGreen else Color.Transparent
                    ),
                    modifier = Modifier
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Text(
                        text = category.label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isFocused -> Color.Black
                            isSelected -> TvGreen
                            else -> Color.White
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (isSearching) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvGreen)
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = errorMsg ?: "", color = Color.Red, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { performSearch(query, selectedCategory) }) {
                        Text("Retry")
                    }
                }
            }
        } else if (hasSubmittedSearch) {
            if (results.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No results found for \"$query\"", color = TvTextDim, fontSize = 16.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results) { item ->
                        var isFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                when (item) {
                                    is SongItem -> onPlaySong(item)
                                    is AlbumItem -> onOpenAlbum(item.id, item.title, item.thumbnail)
                                    is PlaylistItem -> onOpenPlaylist(item.id, item.title, item.thumbnail)
                                    is ArtistItem -> onOpenArtist(item.channelId ?: item.id, item.title, item.thumbnail)
                                    else -> {}
                                }
                            },
                            color = if (isFocused) TvCardFocusBg else TvCardBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isFocused) TvGreen else Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                AsyncImage(
                                    model = item.thumbnail,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(if (item is ArtistItem) CircleShape else RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val subtitle = when (item) {
                                        is SongItem -> item.artists.joinToString(", ") { it.name }
                                        is AlbumItem -> "Album • " + item.artists.joinToString(", ") { it.name }
                                        is PlaylistItem -> "Playlist • " + (item.author ?: "YouTube Music")
                                        is ArtistItem -> "Artist" + (if (!item.subscribers.isNullOrBlank()) " • ${item.subscribers}" else "")
                                        else -> ""
                                    }
                                    Text(
                                        text = subtitle,
                                        fontSize = 13.sp,
                                        color = TvTextDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (suggestions.isNotEmpty()) {
            // Live Search Recommendations / Suggestions
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text(
                        text = "Suggestions",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvGreen,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                items(suggestions) { suggestion ->
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { performSearch(suggestion, selectedCategory) },
                        color = if (isFocused) TvCardFocusBg else TvCardBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isFocused) TvGreen else Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(text = "🔍", fontSize = 16.sp)
                            Text(
                                text = suggestion,
                                fontSize = 15.sp,
                                color = if (isFocused) TvGreen else Color.White,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        } else {
            // Recent Searches History with Dismiss (X) button
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Searches",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (searchHistory.isNotEmpty()) {
                            var isClearFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { clearAllHistory() },
                                color = if (isClearFocused) Color(0xFF552222) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.onFocusChanged { isClearFocused = it.isFocused }
                            ) {
                                Text(
                                    text = "Clear All",
                                    fontSize = 12.sp,
                                    color = if (isClearFocused) Color.Red else TvTextDim,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (searchHistory.isEmpty()) {
                    item {
                        Text(
                            text = "No recent searches. Search songs, artists, or albums above.",
                            color = TvTextDim,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    items(searchHistory) { historyItem ->
                        var isItemFocused by remember { mutableStateOf(false) }
                        var isDismissFocused by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = { performSearch(historyItem, selectedCategory) },
                                color = if (isItemFocused) TvCardFocusBg else TvCardBg,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isItemFocused) TvGreen else Color.Transparent),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { isItemFocused = it.isFocused }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(text = "🕒", fontSize = 15.sp)
                                    Text(
                                        text = historyItem,
                                        fontSize = 15.sp,
                                        color = if (isItemFocused) TvGreen else Color.White,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Dedicated X button to dismiss specific recent search
                            Surface(
                                onClick = { removeHistoryItem(historyItem) },
                                color = if (isDismissFocused) Color(0xFF552222) else Color(0xFF22222E),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isDismissFocused) Color.Red else Color.Transparent),
                                modifier = Modifier
                                    .size(42.dp)
                                    .onFocusChanged { isDismissFocused = it.isFocused }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "✕",
                                        color = if (isDismissFocused) Color.Red else TvTextDim,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvLibraryScreen(
    onPlaySong: (SongItem) -> Unit,
    onOpenPlaylist: (String, String, String?) -> Unit
) {
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val list = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getUserPlaylists()
                }
                playlists = list
            } catch (e: Exception) {
                errorMsg = "Unable to fetch library playlists: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Your Library",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Account Playlists & Saved Collections",
            fontSize = 14.sp,
            color = TvTextDim,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvGreen)
            }
        } else if (errorMsg != null || playlists.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (playlists.isEmpty()) "No saved playlists found (Sign in to sync your YouTube Music playlists)" else (errorMsg ?: ""),
                        color = TvTextDim,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(playlists) { playlist ->
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            onOpenPlaylist(playlist.id, playlist.title, playlist.thumbnail)
                        },
                        color = if (isFocused) TvCardFocusBg else TvCardBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isFocused) TvGreen else Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AsyncImage(
                                model = playlist.thumbnail,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column {
                                Text(
                                    text = playlist.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${playlist.songCount ?: ""} • ${playlist.author ?: "You"}",
                                    fontSize = 13.sp,
                                    color = TvTextDim
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvCollectionScreen(
    id: String,
    isAlbum: Boolean,
    title: String,
    thumbnail: String?,
    subtitle: String?,
    onPlayTrack: (List<SongItem>, Int) -> Unit,
    onPlayAll: (List<SongItem>) -> Unit,
    onPlayShuffled: (List<SongItem>) -> Unit,
    onBack: () -> Unit
) {
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var resolvedTitle by remember { mutableStateOf(title) }
    var resolvedThumbnail by remember { mutableStateOf(thumbnail) }
    var resolvedSubtitle by remember { mutableStateOf(subtitle ?: "") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val playAllRequester = remember { FocusRequester() }

    LaunchedEffect(id) {
        isLoading = true
        errorMsg = null
        try {
            if (isAlbum) {
                val albumPage = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getAlbum(id)
                }
                if (albumPage != null) {
                    songs = albumPage.songs
                    resolvedTitle = albumPage.album.title.ifBlank { title }
                    resolvedThumbnail = albumPage.album.thumbnail ?: thumbnail
                    resolvedSubtitle = albumPage.album.artists.joinToString(", ") { it.name }.ifBlank {
                        albumPage.album.year ?: (if (songs.isNotEmpty()) "${songs.size} tracks" else "Album")
                    }
                } else {
                    errorMsg = "Album could not be loaded."
                }
            } else {
                val playlistData = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getPlaylist(id)
                }
                val extracted = playlistData.values.flatten().filterIsInstance<SongItem>()
                songs = extracted
                if (extracted.isEmpty()) {
                    errorMsg = "Playlist has no playable songs."
                }
            }
        } catch (e: Exception) {
            errorMsg = "Failed to load: ${e.localizedMessage ?: "Unknown error"}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && songs.isNotEmpty()) {
            try {
                playAllRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TvGreen)
        }
        return
    }

    if (errorMsg != null && songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = errorMsg ?: "", color = Color.Red, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                TvPlayerButton(label = "⬅ Go Back", onClick = onBack)
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Left Column: Album/Playlist Art & Info & Action Buttons
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Top
        ) {
            AsyncImage(
                model = resolvedThumbnail,
                contentDescription = null,
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF333342), RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = resolvedTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (resolvedSubtitle.isNotBlank()) {
                Text(
                    text = resolvedSubtitle,
                    fontSize = 13.sp,
                    color = TvTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Text(
                text = "${songs.size} tracks",
                fontSize = 13.sp,
                color = TvGreen,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvPlayerButton(
                    label = "▶ Play All",
                    onClick = { onPlayAll(songs) },
                    isPrimary = true,
                    modifier = Modifier.focusRequester(playAllRequester)
                )

                TvPlayerButton(
                    label = "🔀 Shuffle",
                    onClick = { onPlayShuffled(songs) }
                )

                TvPlayerButton(
                    label = "⬅ Back",
                    onClick = onBack
                )
            }
        }

        // Right Column: Tracklist
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(songs) { index, song ->
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onPlayTrack(songs, index) },
                    color = if (isFocused) TvCardFocusBg else TvCardBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isFocused) TvGreen else Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isFocused) TvGreen else TvTextDim,
                            modifier = Modifier.width(28.dp)
                        )

                        AsyncImage(
                            model = song.thumbnail ?: resolvedThumbnail ?: thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artists.joinToString(", ") { it.name },
                                fontSize = 12.sp,
                                color = TvTextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val dur = song.duration
                        if (dur != null && dur > 0) {
                            Text(
                                text = formatTime(dur * 1000L),
                                fontSize = 13.sp,
                                color = TvTextDim
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvArtistScreen(
    channelId: String,
    artistName: String,
    thumbnail: String?,
    playbackManager: TvPlaybackManager,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenPlaylist: (String, String, String?) -> Unit,
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit
) {
    var artistPage by remember { mutableStateOf<ArtistPageResult?>(null) }
    var resolvedName by remember { mutableStateOf(artistName) }
    var resolvedThumbnail by remember { mutableStateOf(thumbnail) }
    var subscribers by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isStartingRadio by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val radioRequester = remember { FocusRequester() }

    LaunchedEffect(channelId) {
        isLoading = true
        errorMsg = null
        try {
            val page = withContext(Dispatchers.IO) {
                GlobalYouTube.repository.getArtist(channelId)
            }
            artistPage = page
            val headerArtist = page.sections["header"]?.firstOrNull() as? ArtistItem
            if (headerArtist != null) {
                resolvedName = headerArtist.title.ifBlank { artistName }
                resolvedThumbnail = headerArtist.thumbnail ?: thumbnail
                subscribers = headerArtist.subscribers
            }
        } catch (e: Exception) {
            errorMsg = "Failed to load artist: ${e.localizedMessage ?: "Network error"}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            try {
                radioRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TvGreen)
        }
        return
    }

    if (errorMsg != null && artistPage == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = errorMsg ?: "", color = Color.Red, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                TvPlayerButton(label = "⬅ Go Back", onClick = onBack)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Artist Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            AsyncImage(
                model = resolvedThumbnail,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .border(2.dp, TvGreen, CircleShape),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = resolvedName,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (!subscribers.isNullOrBlank()) {
                    Text(
                        text = subscribers ?: "",
                        fontSize = 14.sp,
                        color = TvTextDim,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvPlayerButton(
                        label = if (isStartingRadio) "⏳ Starting Radio..." else "📻 Start Artist Radio",
                        onClick = {
                            if (!isStartingRadio) {
                                isStartingRadio = true
                                scope.launch {
                                    try {
                                        val radioSongs = withContext(Dispatchers.IO) {
                                            GlobalYouTube.repository.startArtistRadio(channelId)
                                        }
                                        if (radioSongs.isNotEmpty()) {
                                            playbackManager.playQueue(radioSongs, 0)
                                            onOpenPlayer()
                                        }
                                    } catch (_: Exception) {
                                    } finally {
                                        isStartingRadio = false
                                    }
                                }
                            }
                        },
                        isPrimary = true,
                        modifier = Modifier.focusRequester(radioRequester)
                    )

                    TvPlayerButton(
                        label = "⬅ Back",
                        onClick = onBack
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sections: Top Songs, Albums, Singles, etc.
        val displaySections = artistPage?.sections?.filterKeys {
            it != "header" && it != "description"
        } ?: emptyMap()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            displaySections.forEach { (sectionTitle, items) ->
                if (items.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = sectionTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )

                            val listState = rememberLazyListState()
                            LazyRow(
                                state = listState,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(items) { index, item ->
                                    TvCardItem(
                                        item = item,
                                        index = index,
                                        listState = listState,
                                        onClick = {
                                            when (item) {
                                                is SongItem -> {
                                                    playbackManager.playSong(item)
                                                    onOpenPlayer()
                                                }
                                                is AlbumItem -> {
                                                    onOpenAlbum(item.id, item.title, item.thumbnail)
                                                }
                                                is PlaylistItem -> {
                                                    onOpenPlaylist(item.id, item.title, item.thumbnail)
                                                }
                                                else -> {}
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvAccountScreen() {
    var cookieInput by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf<String?>(null) }
    var isSignedIn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    val firstActionRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun checkAccountStatus() {
        scope.launch {
            try {
                val acc = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getAccountInfo()
                }
                if (acc != null) {
                    accountName = acc.name ?: "User"
                    isSignedIn = true
                } else {
                    accountName = null
                    isSignedIn = false
                }
            } catch (_: Exception) {
                accountName = null
                isSignedIn = false
            }
        }
    }

    LaunchedEffect(Unit) {
        checkAccountStatus()
        try {
            firstActionRequester.requestFocus()
        } catch (_: Exception) {}
    }

    fun applyCookie(cookie: String) {
        if (cookie.isBlank()) return
        isSaving = true
        scope.launch {
            try {
                Settings.shared.innerTubeCookie = cookie
                GlobalYouTube.updateCookie(cookie)
                val info = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getAccountInfo()
                }
                if (info != null) {
                    accountName = info.name ?: "User"
                    isSignedIn = true
                    Toast.makeText(context, "Signed in as ${info.name}!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Cookie applied!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error verifying login: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    fun logOut() {
        scope.launch {
            Settings.shared.innerTubeCookie = ""
            GlobalYouTube.updateCookie(null)
            accountName = null
            isSignedIn = false
            cookieInput = ""
            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
        }
    }

    if (showLoginDialog) {
        TvLoginWebViewDialog(
            onDismiss = { showLoginDialog = false },
            onSuccess = { cookies ->
                showLoginDialog = false
                applyCookie(cookies)
            }
        )
    }

    if (showQrDialog) {
        TvQrPairingDialog(
            onDismiss = { showQrDialog = false },
            onSuccess = { cookies ->
                showQrDialog = false
                applyCookie(cookies)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "YouTube Music Account",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSignedIn) "Signed in as: ${accountName ?: "User"}" else "Guest Mode (Ad-free streaming enabled)",
            fontSize = 17.sp,
            color = if (isSignedIn) TvGreen else TvTextDim,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (isSignedIn) {
            Text(
                text = "Your account playlists, subscriptions, and library are synced.",
                color = TvTextDim,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            var isLogoutFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { logOut() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLogoutFocused) Color(0xFF882222) else Color(0xFF442222)
                ),
                modifier = Modifier
                    .focusRequester(firstActionRequester)
                    .onFocusChanged { isLogoutFocused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    text = "Sign Out",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        } else {
            // Action Buttons Row: QR Sign-In, In-App Google Sign-In, Paste from Clipboard
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var isQrFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = { showQrDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isQrFocused) TvGreen else Color(0xFF2A2A38)
                    ),
                    modifier = Modifier
                        .focusRequester(firstActionRequester)
                        .onFocusChanged { isQrFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        text = "📱 Scan QR to Sign In",
                        color = if (isQrFocused) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }

                var isGoogleFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = { showLoginDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGoogleFocused) TvGreen else Color(0xFF2A2A38)
                    ),
                    modifier = Modifier
                        .onFocusChanged { isGoogleFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        text = "🌐 Sign in with Google",
                        color = if (isGoogleFocused) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }

                var isPasteFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            val text = clipData?.getItemAt(0)?.text?.toString()
                            if (!text.isNullOrBlank()) {
                                cookieInput = text
                                applyCookie(text)
                            } else {
                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Clipboard error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPasteFocused) TvGreen else Color(0xFF2A2A38)
                    ),
                    modifier = Modifier
                        .onFocusChanged { isPasteFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        text = "📋 Paste from Clipboard",
                        color = if (isPasteFocused) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Or manual input field
            Text(
                text = "— OR PASTE COOKIE / TOKEN MANUALLY —",
                fontSize = 12.sp,
                color = TvTextDim,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = cookieInput,
                onValueChange = { cookieInput = it },
                label = { Text("YouTube Music Cookie / Token") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvGreen,
                    unfocusedBorderColor = Color(0xFF333340),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true,
                modifier = Modifier.width(520.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            var isSaveFocused by remember { mutableStateOf(false) }
            Button(
                onClick = { applyCookie(cookieInput) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSaveFocused) TvGreen else Color(0xFF1E2822)
                ),
                border = BorderStroke(1.dp, TvGreen),
                modifier = Modifier
                    .onFocusChanged { isSaveFocused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    text = if (isSaving) "Verifying..." else "Save Credentials",
                    color = if (isSaveFocused) Color.Black else TvGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TvLoginWebViewDialog(
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, TvGreen)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sign in to YouTube Music",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333342))
                    ) {
                        Text("Close", color = Color.White)
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null && (url.contains("music.youtube.com") || url.contains("youtube.com"))) {
                                        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                                            ?: CookieManager.getInstance().getCookie(url)
                                        if (!cookies.isNullOrBlank() && (cookies.contains("SAPISID") || cookies.contains("SID"))) {
                                            onSuccess(cookies)
                                        }
                                    }
                                }
                            }
                            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun TvQrPairingDialog(
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ipAddress by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        val ip = TvQrUtils.getDeviceIpAddress(context) ?: "192.168.0.x"
        ipAddress = ip
        val url = "http://$ip:8888"
        try {
            qrBitmap = TvQrUtils.generateQrBitmap(url, 400)
        } catch (_: Exception) {}

        val server = TvPairingServer(
            port = 8888,
            scope = scope,
            onCredentialsReceived = { credentials ->
                scope.launch(Dispatchers.Main) {
                    onSuccess(credentials)
                }
            }
        )
        server.start()

        onDispose {
            server.stop()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(460.dp)
                .wrapContentHeight()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, TvGreen)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📱 Scan QR to Sign In",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "Pairing QR Code",
                        modifier = Modifier
                            .size(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    CircularProgressIndicator(color = TvGreen)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Scan with phone camera or open:",
                    fontSize = 13.sp,
                    color = TvTextDim
                )
                Text(
                    text = "http://${ipAddress ?: "..."}:8888",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvGreen,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
                Text(
                    text = "Ensure devices are on the same Wi-Fi network.",
                    fontSize = 12.sp,
                    color = TvTextDim,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                var isCloseFocused by remember { mutableStateOf(false) }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCloseFocused) TvGreen else Color(0xFF282836)
                    ),
                    modifier = Modifier
                        .onFocusChanged { isCloseFocused = it.isFocused }
                        .focusable()
                ) {
                    Text("Close", color = if (isCloseFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TvPlayerScreen(
    playbackManager: TvPlaybackManager,
    onOpenLyrics: () -> Unit = {},
    onBackToHome: () -> Unit
) {
    val currentSong by playbackManager.currentSong.collectAsState()
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val isLoading by playbackManager.isLoading.collectAsState()
    val positionMs by playbackManager.positionMs.collectAsState()
    val durationMs by playbackManager.durationMs.collectAsState()
    val errorMessage by playbackManager.errorMessage.collectAsState()
    val playPauseRequester = remember { FocusRequester() }
    val isShuffle by playbackManager.isShuffle.collectAsState()
    val repeatMode by playbackManager.repeatMode.collectAsState()
    val autoQueueEnabled by playbackManager.autoQueueEnabled.collectAsState()
    val isVolumeNormalization by playbackManager.volumeNormalizationEnabled.collectAsState()
    val queue by playbackManager.queue.collectAsState()
    val currentIndex by playbackManager.currentIndex.collectAsState()

    LaunchedEffect(Unit) {
        try {
            playPauseRequester.requestFocus()
        } catch (_: Exception) {}
    }

    if (currentSong == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No track currently playing",
                    fontSize = 20.sp,
                    color = TvTextDim
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBackToHome) {
                    Text("Go to Home")
                }
            }
        }
        return
    }

    val song = currentSong!!

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Large Album Art + Lyrics Button on the left
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, Color(0xFF333342), RoundedCornerShape(20.dp))
                    .clickable { onOpenLyrics() }
            ) {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TvGreen)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            TvPlayerButton(
                label = "📝 Synchronized Lyrics",
                onClick = onOpenLyrics,
                isActive = true,
                modifier = Modifier.width(240.dp)
            )
        }

        // Track Details and Player Controls on the right
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = song.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = song.artists.joinToString(", ") { it.name },
                fontSize = 18.sp,
                color = TvGreen,
                fontWeight = FontWeight.Medium
            )

            if (song.album?.name != null) {
                Text(
                    text = song.album?.name ?: "",
                    fontSize = 14.sp,
                    color = TvTextDim,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Progress Slider Bar
            val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                color = TvGreen,
                trackColor = Color(0xFF282835),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Time format
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(positionMs), fontSize = 13.sp, color = TvTextDim)
                Text(text = formatTime(durationMs), fontSize = 13.sp, color = TvTextDim)
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    fontSize = 14.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Playback Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerButton(
                    label = "⏮ Prev",
                    onClick = { playbackManager.previous() }
                )

                TvPlayerButton(
                    label = "-10s",
                    onClick = { playbackManager.seekRelative(-10000L) }
                )

                TvPlayerButton(
                    label = if (isPlaying) "⏸ Pause" else "▶ Play",
                    onClick = { playbackManager.togglePlayPause() },
                    isPrimary = true,
                    modifier = Modifier.focusRequester(playPauseRequester)
                )

                TvPlayerButton(
                    label = "+10s",
                    onClick = { playbackManager.seekRelative(10000L) }
                )

                TvPlayerButton(
                    label = "⏭ Next",
                    onClick = { playbackManager.next() }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Secondary Controls: Shuffle, Repeat, Auto-Queue
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvPlayerButton(
                    label = if (isShuffle) "🔀 Shuffle: ON" else "🔀 Shuffle: OFF",
                    onClick = { playbackManager.toggleShuffle() },
                    isActive = isShuffle
                )

                TvPlayerButton(
                    label = when (repeatMode) {
                        TvRepeatMode.OFF -> "🔁 Repeat: OFF"
                        TvRepeatMode.ALL -> "🔁 Repeat: ALL"
                        TvRepeatMode.ONE -> "🔂 Repeat: ONE"
                    },
                    onClick = { playbackManager.toggleRepeat() },
                    isActive = repeatMode != TvRepeatMode.OFF
                )

                TvPlayerButton(
                    label = if (autoQueueEnabled) "📻 Auto-Queue: ON" else "📻 Auto-Queue: OFF",
                    onClick = { playbackManager.toggleAutoQueue() },
                    isActive = autoQueueEnabled
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Audio Normalization Control
            TvPlayerButton(
                label = if (isVolumeNormalization) "🔊 Normalizer: ON" else "🔊 Normalizer: OFF",
                onClick = { playbackManager.toggleVolumeNormalization() },
                isActive = isVolumeNormalization
            )

            if (queue.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                val queueText = "Track ${currentIndex + 1} of ${queue.size}" +
                    if (autoQueueEnabled) " • Infinite Auto-Queue Active" else ""
                Text(
                    text = queueText,
                    fontSize = 12.sp,
                    color = TvGreen
                )
            }
        }
    }
}

@Composable
fun TvPlayerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isActive: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        color = when {
            isFocused -> TvGreen
            isActive -> Color(0xFF1E3825)
            isPrimary -> Color(0xFF2A2A38)
            else -> Color(0xFF1E1E26)
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = when {
                isFocused -> TvGreen
                isActive -> TvGreen.copy(alpha = 0.6f)
                else -> Color(0xFF333342)
            }
        ),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isFocused -> Color.Black
                    isActive -> TvGreen
                    else -> Color.White
                }
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun TvListeningHistoryDialog(
    historyManager: TvHistoryManager,
    onPlaySong: (SongItem) -> Unit,
    onDismiss: () -> Unit
) {
    val history by historyManager.history.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(680.dp)
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, TvGreen)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listening History (${history.size})",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (history.isNotEmpty()) {
                            var isClearFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = { historyManager.clearAllHistory() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isClearFocused) Color(0xFF882222) else Color(0xFF382222)
                                ),
                                modifier = Modifier
                                    .onFocusChanged { isClearFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Text("Clear All", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        var isCloseFocused by remember { mutableStateOf(false) }
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCloseFocused) TvGreen else Color(0xFF282836)
                            ),
                            modifier = Modifier
                                .onFocusChanged { isCloseFocused = it.isFocused }
                                .focusable()
                        ) {
                            Text(
                                "Close",
                                color = if (isCloseFocused) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (history.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No listening history yet.\nSongs you play will appear here.",
                            color = TvTextDim,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history, key = { it.id }) { song ->
                            var isRowFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { onPlaySong(song) },
                                color = if (isRowFocused) TvCardFocusBg else TvCardBg,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (isRowFocused) TvGreen else Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isRowFocused = it.isFocused }
                                    .focusable()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    AsyncImage(
                                        model = song.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
                                            fontSize = 13.sp,
                                            color = TvTextDim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    var isDeleteFocused by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = { historyManager.removeHistoryItem(song.id) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .onFocusChanged { isDeleteFocused = it.isFocused }
                                            .focusable()
                                    ) {
                                        Icon(
                                            imageVector = MetrolistIcons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = if (isDeleteFocused) Color.Red else TvTextDim,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvLyricsScreen(
    playbackManager: TvPlaybackManager,
    onBack: () -> Unit
) {
    val currentSong by playbackManager.currentSong.collectAsState()
    val lyrics by playbackManager.currentLyrics.collectAsState()
    val isLyricsLoading by playbackManager.isLyricsLoading.collectAsState()
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val positionMs by playbackManager.positionMs.collectAsState()
    val durationMs by playbackManager.durationMs.collectAsState()

    BackHandler { onBack() }

    var currentTimeString by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (isActive) {
            currentTimeString = sdf.format(java.util.Date())
            delay(1000)
        }
    }

    val activeIndex = remember(positionMs, lyrics) {
        if (lyrics.isEmpty()) -1
        else {
            val idx = lyrics.indexOfLast { it.time <= positionMs }
            if (idx >= 0) idx else 0
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex in lyrics.indices) {
            val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
            val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            if (itemInfo != null && viewportHeight > 0) {
                val centerOffset = (viewportHeight - itemInfo.size) / 2
                val scrollDelta = itemInfo.offset - centerOffset
                listState.animateScrollBy(scrollDelta.toFloat())
            } else {
                val targetIndex = (activeIndex - 1).coerceAtLeast(0)
                listState.scrollToItem(targetIndex)
                delay(40)
                val newItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                val newViewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                if (newItem != null && newViewport > 0) {
                    val centerOffset = (newViewport - newItem.size) / 2
                    listState.animateScrollBy((newItem.offset - centerOffset).toFloat())
                }
            }
        }
    }

    val screenFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            screenFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF141824),
                        Color(0xFF0C0E14),
                        Color(0xFF08090C)
                    )
                )
            )
            .focusRequester(screenFocusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                            playbackManager.togglePlayPause()
                            true
                        }
                        Key.DirectionLeft -> {
                            playbackManager.seekRelative(-10000L)
                            true
                        }
                        Key.DirectionRight -> {
                            playbackManager.seekRelative(10000L)
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar: Tv-Tune Branding (Left) and Clock (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MetrolistIcons.Filled.MusicNote,
                        contentDescription = null,
                        tint = TvGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Tv-Tune",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = currentTimeString,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            // Center: Scrolling Synchronized Lyrics
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLyricsLoading -> {
                        CircularProgressIndicator(color = TvGreen)
                    }
                    lyrics.isEmpty() -> {
                        Text(
                            text = "No synchronized lyrics found for this track",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = TvTextDim,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(top = 160.dp, bottom = 200.dp)
                        ) {
                            itemsIndexed(lyrics) { idx, entry ->
                                val isActive = idx == activeIndex
                                val isUpcoming = idx in (activeIndex + 1)..(activeIndex + 2)
                                Text(
                                    text = entry.text,
                                    fontSize = if (isActive) 28.sp else 21.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isActive -> Color.White
                                        isUpcoming -> Color.White.copy(alpha = 0.55f)
                                        else -> Color.White.copy(alpha = 0.25f)
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 48.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Section: Song Meta on Left, Progress Bar at Bottom
            if (currentSong != null) {
                val song = currentSong!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AsyncImage(
                        model = song.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
                            fontSize = 15.sp,
                            color = TvTextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = if (isPlaying) "Playing" else "Paused",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isPlaying) TvGreen else TvTextDim
                    )
                }

                // Horizontal playback progress bar spanning the bottom
                val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    color = TvGreen,
                    trackColor = Color(0xFF2A2A38),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
