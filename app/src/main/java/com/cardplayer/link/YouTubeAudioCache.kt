package com.cardplayer.link

import android.content.Context
import com.cardplayer.data.Card
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * YouTube audio is never downloaded (against Play policy), so YouTube / YouTube Music cards always open the app.
 * Same shape as the archived yt-dlp cache (archive/full-flavor/) so the rest of the code doesn't care.
 */
@Suppress("UNUSED_PARAMETER")
class YouTubeAudioCache(context: Context) {
    data class Track(val id: String, val title: String, val file: File)

    val downloading: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    val version: StateFlow<Int> = MutableStateFlow(0)

    fun cachedFile(videoId: String): File? = null

    fun handles(card: Card): Boolean = false

    fun localTracks(card: Card): List<Track>? = null

    fun ensureAsync(card: Card) = Unit

    suspend fun ensure(card: Card) = Unit

    fun refreshAllAsync(all: List<Card>, download: Boolean, minIntervalMs: Long = 0L) = Unit

    fun usedBytes(): Long = 0L

    fun clearAll() = Unit
}
