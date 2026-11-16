package com.tvtune.music.history

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TvHistoryEntry(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String? = null,
    val albumName: String? = null,
    val albumId: String? = null,
    val thumbnail: String? = null,
    val duration: Long? = null,
    val playedAt: Long = System.currentTimeMillis()
) {
    fun toSongItem(): SongItem = SongItem(
        id = id,
        title = title,
        thumbnail = thumbnail,
        artists = listOf(ArtistTiny(id = artistId, name = artistName)),
        album = albumName?.let { AlbumTiny(id = albumId ?: "", name = it) },
        duration = duration
    )

    companion object {
        fun fromSongItem(song: SongItem): TvHistoryEntry = TvHistoryEntry(
            id = song.id,
            title = song.title,
            artistName = song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
            artistId = song.artists.firstOrNull()?.id,
            albumName = song.album?.name,
            albumId = song.album?.id,
            thumbnail = song.thumbnail,
            duration = song.duration
        )
    }
}

class TvHistoryManager(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TvHistoryManager"
        private const val PREFS_NAME = "tv_tune_history"
        private const val KEY_HISTORY = "listening_history"
        private const val MAX_HISTORY_ITEMS = 50
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _history = MutableStateFlow<List<SongItem>>(emptyList())
    val history: StateFlow<List<SongItem>> = _history.asStateFlow()

    private val _lastPlayedSong = MutableStateFlow<SongItem?>(null)
    val lastPlayedSong: StateFlow<SongItem?> = _lastPlayedSong.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val raw = prefs.getString(KEY_HISTORY, null)
        if (!raw.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<TvHistoryEntry>>(raw)
                val songs = list.map { it.toSongItem() }
                _history.value = songs
                _lastPlayedSong.value = songs.firstOrNull()
                Log.i(TAG, "Loaded ${songs.size} items from listening history")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode history: ${e.message}")
            }
        }
    }

    fun recordPlay(song: SongItem) {
        val current = _history.value.toMutableList()
        current.removeAll { it.id == song.id }
        current.add(0, song)
        val trimmed = current.take(MAX_HISTORY_ITEMS)
        _history.value = trimmed
        _lastPlayedSong.value = song
        saveHistory(trimmed)
    }

    fun removeHistoryItem(songId: String) {
        val current = _history.value.filter { it.id != songId }
        _history.value = current
        _lastPlayedSong.value = current.firstOrNull()
        saveHistory(current)
    }

    fun clearAllHistory() {
        _history.value = emptyList()
        _lastPlayedSong.value = null
        scope.launch(Dispatchers.IO) {
            prefs.edit().remove(KEY_HISTORY).apply()
        }
    }

    private fun saveHistory(songs: List<SongItem>) {
        scope.launch(Dispatchers.IO) {
            try {
                val entries = songs.map { TvHistoryEntry.fromSongItem(it) }
                val raw = json.encodeToString(entries)
                prefs.edit().putString(KEY_HISTORY, raw).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save history: ${e.message}")
            }
        }
    }
}
