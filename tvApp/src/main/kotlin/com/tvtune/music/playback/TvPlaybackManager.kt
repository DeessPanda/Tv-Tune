package com.tvtune.music.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.tvtune.music.history.TvHistoryManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class TvRepeatMode {
    OFF, ALL, ONE
}

class TvPlaybackManager(
    private val context: Context,
    private val streamResolver: StreamResolver,
    val historyManager: TvHistoryManager,
    private val lyricsRepository: LyricsRepository? = null,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TvPlaybackManager"
    }

    private val clientCandidates = listOf(
        "ANDROID_VR",
        "VISIONOS_0_1",
        "TVHTML5_SIMPLY",
        "TVHTML5_SAMSUNG",
        null
    )
    private var currentClientIndex = 0

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(TvDnsResolver)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val exoPlayer: ExoPlayer by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                        if (playing) {
                            startPositionUpdates()
                        } else {
                            stopPositionUpdates()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                _isLoading.value = true
                            }
                            Player.STATE_READY -> {
                                _isLoading.value = false
                                _durationMs.value = duration.coerceAtLeast(0L)
                            }
                            Player.STATE_ENDED -> {
                                _isLoading.value = false
                                next()
                            }
                            Player.STATE_IDLE -> {
                                _isLoading.value = false
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val cause = error.cause
                        Log.e(TAG, "ExoPlayer error: ${error.message}, cause: ${cause?.message} (${cause?.javaClass?.name})", error)
                        _isPlaying.value = false
                        val current = _currentSong.value
                        if (current != null && currentClientIndex + 1 < clientCandidates.size) {
                            Log.i(TAG, "Attempting recovery with next client tier (${clientCandidates[currentClientIndex + 1] ?: "AUTO"})...")
                            loadAndPlay(current, currentClientIndex + 1)
                        } else {
                            _isLoading.value = false
                            val causeMsg = cause?.message ?: cause?.javaClass?.simpleName ?: error.message
                            _errorMessage.value = "Playback error: $causeMsg"
                        }
                    }
                })
            }
    }

    private val _currentSong = MutableStateFlow<SongItem?>(null)
    val currentSong: StateFlow<SongItem?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<SongItem>>(emptyList())
    val queue: StateFlow<List<SongItem>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _autoQueueEnabled = MutableStateFlow(true)
    val autoQueueEnabled: StateFlow<Boolean> = _autoQueueEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(TvRepeatMode.OFF)
    val repeatMode: StateFlow<TvRepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val volumeNormalizer by lazy { TvVolumeNormalizer(exoPlayer) }
    private val _volumeNormalizationEnabled = MutableStateFlow(true)
    val volumeNormalizationEnabled: StateFlow<Boolean> = _volumeNormalizationEnabled.asStateFlow()

    private val _currentLyrics = MutableStateFlow<List<LyricsEntry>>(emptyList())
    val currentLyrics: StateFlow<List<LyricsEntry>> = _currentLyrics.asStateFlow()

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var positionJob: Job? = null
    private var playJob: Job? = null
    private var radioJob: Job? = null
    private var lyricsJob: Job? = null
    private var radioContinuation: String? = null

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    _positionMs.value = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration
                    if (dur > 0L) {
                        _durationMs.value = dur
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun playSong(song: SongItem) {
        _queue.value = listOf(song)
        _currentIndex.value = 0
        radioContinuation = null
        loadAndPlay(song)
        if (_autoQueueEnabled.value) {
            fetchRadioForSong(song.id)
        }
    }

    private fun fetchRadioForSong(videoId: String) {
        radioJob?.cancel()
        radioJob = scope.launch(Dispatchers.IO) {
            try {
                val radioPage = GlobalYouTube.repository.startRadioQueue(videoId)
                val newSongs = radioPage.songs.filter { it.id != videoId }
                if (newSongs.isNotEmpty()) {
                    radioContinuation = radioPage.continuation
                    _queue.value = _queue.value + newSongs
                    Log.i(TAG, "Autoqueue loaded ${newSongs.size} similar songs for $videoId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch autoqueue radio: ${e.message}")
            }
        }
    }

    fun playQueue(songs: List<SongItem>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val validIndex = startIndex.coerceIn(0, songs.size - 1)
        _queue.value = songs
        _currentIndex.value = validIndex
        radioContinuation = null
        loadAndPlay(songs[validIndex])
    }

    fun playQueueShuffled(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        playQueue(shuffled, 0)
    }

    fun playAlbum(albumId: String) {
        scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val album = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getAlbum(albumId)
                }
                val songs = album?.songs ?: emptyList()
                if (songs.isNotEmpty()) {
                    playQueue(songs, 0)
                } else {
                    _isLoading.value = false
                    _errorMessage.value = "No songs found in album"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load album $albumId", e)
                _isLoading.value = false
                _errorMessage.value = "Error loading album: ${e.localizedMessage}"
            }
        }
    }

    fun playPlaylist(playlistId: String) {
        scope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val playlistData = withContext(Dispatchers.IO) {
                    GlobalYouTube.repository.getPlaylist(playlistId)
                }
                val songs = playlistData.values.flatten().filterIsInstance<SongItem>()
                if (songs.isNotEmpty()) {
                    playQueue(songs, 0)
                } else {
                    _isLoading.value = false
                    _errorMessage.value = "No songs found in playlist"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load playlist $playlistId", e)
                _isLoading.value = false
                _errorMessage.value = "Error loading playlist: ${e.localizedMessage}"
            }
        }
    }

    private fun loadAndPlay(song: SongItem, startWithCandidateIndex: Int = 0) {
        playJob?.cancel()
        _currentSong.value = song
        _isLoading.value = true
        _errorMessage.value = null
        _positionMs.value = 0L
        _durationMs.value = (song.duration ?: 0L) * 1000L
        currentClientIndex = startWithCandidateIndex

        if (startWithCandidateIndex == 0) {
            historyManager.recordPlay(song)
            fetchLyrics(song)
        }

        playJob = scope.launch {
            try {
                Log.i(TAG, "Resolving stream for ${song.title} (${song.id}) starting at candidate index $startWithCandidateIndex...")

                var resolvedResource: PlaybackResource? = null
                var lastError: Exception? = null

                for (idx in startWithCandidateIndex until clientCandidates.size) {
                    val client = clientCandidates[idx]
                    try {
                        val hints = song.toContentHints(wantVideo = false)
                            .copy(playbackClientOverrideId = client, sabrFirst = false)
                            .withStreamCapabilities(
                                allowHls = true,
                                allowSabr = false,
                                allowBoundedRange = true
                            )
                        val res = withTimeout(15000L) {
                            withContext(Dispatchers.IO) {
                                streamResolver.resolve(
                                    videoId = song.id,
                                    hints = hints,
                                    sourceSong = song
                                )
                            }
                        }
                        if (res.url.isNotBlank() && !res.url.startsWith("sabr://")) {
                            resolvedResource = res
                            currentClientIndex = idx
                            Log.i(TAG, "Stream successfully resolved with candidate $idx (${client ?: "AUTO"}): ${res.url}")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Candidate $idx (${client ?: "AUTO"}) failed: ${e.message}")
                        lastError = e
                    }
                }

                val resource = resolvedResource ?: throw (lastError ?: Exception("No playable direct audio stream"))
                val streamUrl = resource.url
                Log.i(TAG, "Playing resolved stream: url=$streamUrl, mime=${resource.mimeType}, isSabr=${resource.isSabr}")

                withContext(Dispatchers.Main) {
                    val okHttpFactory = OkHttpDataSource.Factory(mediaOkHttpClient)
                    resource.headers.forEach { (k, v) ->
                        okHttpFactory.setDefaultRequestProperties(mapOf(k to v))
                    }

                    val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

                    val mediaMetadata = MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artists.joinToString(", ") { it.name })
                        .setAlbumTitle(song.album?.name)
                        .build()

                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(mediaMetadata)

                    if (!resource.mimeType.isNullOrBlank()) {
                        mediaItemBuilder.setMimeType(resource.mimeType)
                    }

                    val mediaItem = mediaItemBuilder.build()
                    val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                        .createMediaSource(mediaItem)

                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    volumeNormalizer.onTrackLoaded(resource.loudnessDb, resource.perceptualLoudnessDb)
                }

                // Buffering timeout watcher: if still buffering after 20s, retry next candidate or report error
                delay(20000L)
                if (_isLoading.value && !exoPlayer.isPlaying) {
                    if (currentClientIndex + 1 < clientCandidates.size) {
                        Log.w(TAG, "Buffering timeout reached for ${song.title}, cascading to next candidate...")
                        loadAndPlay(song, currentClientIndex + 1)
                    } else {
                        Log.w(TAG, "Buffering timeout reached for ${song.title}")
                        _isLoading.value = false
                        _errorMessage.value = "Buffering timed out. Try another song."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback resolve error", e)
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    _errorMessage.value = "Error playing song: ${e.localizedMessage ?: "Unknown"}"
                }
            }
        }
    }

    fun togglePlayPause() {
        scope.launch(Dispatchers.Main) {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                exoPlayer.play()
            }
        }
    }

    fun play() {
        scope.launch(Dispatchers.Main) {
            exoPlayer.play()
        }
    }

    fun pause() {
        scope.launch(Dispatchers.Main) {
            exoPlayer.pause()
        }
    }

    fun next() {
        val q = _queue.value
        if (_repeatMode.value == TvRepeatMode.ONE) {
            seekTo(0L)
            play()
            return
        }
        val nextIdx = _currentIndex.value + 1
        if (nextIdx in q.indices) {
            _currentIndex.value = nextIdx
            loadAndPlay(q[nextIdx])
            if (nextIdx >= q.size - 2 && _autoQueueEnabled.value && radioContinuation != null) {
                fetchMoreRadio()
            }
        } else if (_repeatMode.value == TvRepeatMode.ALL && q.isNotEmpty()) {
            _currentIndex.value = 0
            loadAndPlay(q[0])
        } else if (_autoQueueEnabled.value && q.isNotEmpty()) {
            fetchRadioAndPlayNext(q.last().id)
        }
    }

    private fun fetchMoreRadio() {
        val token = radioContinuation ?: return
        radioJob?.cancel()
        radioJob = scope.launch(Dispatchers.IO) {
            try {
                val page = GlobalYouTube.repository.continueRadioQueue(token)
                if (page.songs.isNotEmpty()) {
                    radioContinuation = page.continuation
                    _queue.value = _queue.value + page.songs
                    Log.i(TAG, "Autoqueue appended ${page.songs.size} more tracks")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to continue autoqueue: ${e.message}")
            }
        }
    }

    private fun fetchRadioAndPlayNext(videoId: String) {
        radioJob?.cancel()
        radioJob = scope.launch(Dispatchers.IO) {
            try {
                val radioPage = GlobalYouTube.repository.startRadioQueue(videoId)
                val newSongs = radioPage.songs.filter { it.id != videoId }
                if (newSongs.isNotEmpty()) {
                    radioContinuation = radioPage.continuation
                    val startIdx = _queue.value.size
                    _queue.value = _queue.value + newSongs
                    _currentIndex.value = startIdx
                    loadAndPlay(newSongs[0])
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extend autoqueue: ${e.message}")
            }
        }
    }

    fun toggleAutoQueue() {
        _autoQueueEnabled.value = !_autoQueueEnabled.value
        if (_autoQueueEnabled.value && _currentSong.value != null && _queue.value.size <= 1) {
            fetchRadioForSong(_currentSong.value!!.id)
        }
    }

    fun toggleRepeat() {
        val next = when (_repeatMode.value) {
            TvRepeatMode.OFF -> TvRepeatMode.ALL
            TvRepeatMode.ALL -> TvRepeatMode.ONE
            TvRepeatMode.ONE -> TvRepeatMode.OFF
        }
        _repeatMode.value = next
        scope.launch(Dispatchers.Main) {
            exoPlayer.repeatMode = when (next) {
                TvRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                TvRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                TvRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        val q = _queue.value
        val curIdx = _currentIndex.value
        if (newShuffle && q.size > curIdx + 1) {
            val played = q.take(curIdx + 1)
            val upcoming = q.drop(curIdx + 1).shuffled()
            _queue.value = played + upcoming
        }
    }

    fun previous() {
        val q = _queue.value
        // If > 3 seconds in, restart track
        if (_positionMs.value > 3000L) {
            seekTo(0L)
            return
        }
        val prevIdx = _currentIndex.value - 1
        if (prevIdx in q.indices) {
            _currentIndex.value = prevIdx
            loadAndPlay(q[prevIdx])
        } else {
            seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.Main) {
            exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
            _positionMs.value = positionMs
        }
    }

    fun seekRelative(offsetMs: Long) {
        scope.launch(Dispatchers.Main) {
            val newPos = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
            exoPlayer.seekTo(newPos)
            _positionMs.value = newPos
        }
    }

    fun toggleVolumeNormalization() {
        val next = !_volumeNormalizationEnabled.value
        _volumeNormalizationEnabled.value = next
        volumeNormalizer.isEnabled = next
        Log.i(TAG, "Audio volume normalization toggled: $next")
    }

    private fun fetchLyrics(song: SongItem) {
        lyricsJob?.cancel()
        _currentLyrics.value = emptyList()
        val repo = lyricsRepository ?: return
        lyricsJob = scope.launch(Dispatchers.IO) {
            _isLyricsLoading.value = true
            try {
                val artistName = song.artists.firstOrNull()?.name ?: ""
                val result = repo.getLyrics(
                    videoId = song.id,
                    title = song.title,
                    artist = artistName,
                    duration = song.duration,
                    album = song.album?.name
                )
                if (result != null && result.lyrics.isNotBlank()) {
                    val lines = LyricsUtils.parseLyrics(result.lyrics)
                    _currentLyrics.value = lines
                    Log.i(TAG, "Lyrics fetched for ${song.title}: ${lines.size} lines from ${result.provider}")
                } else {
                    Log.i(TAG, "No lyrics found for ${song.title}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch lyrics for ${song.title}: ${e.message}")
            } finally {
                _isLyricsLoading.value = false
            }
        }
    }

    fun release() {
        stopPositionUpdates()
        playJob?.cancel()
        radioJob?.cancel()
        lyricsJob?.cancel()
        volumeNormalizer.release()
        scope.launch(Dispatchers.Main) {
            exoPlayer.release()
        }
    }
}
