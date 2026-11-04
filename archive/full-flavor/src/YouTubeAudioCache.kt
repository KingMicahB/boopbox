package com.cardplayer.link

import android.content.Context
import com.cardplayer.data.Card
import com.cardplayer.data.SourceType
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Keeps the audio of YouTube / YouTube Music cards on the phone, so a tap plays inside BoopBox
 * (no YouTube app, no Premium needed for background play, works offline):
 *  - audio is fetched on the phone with yt-dlp (youtubedl-android), as the best m4a/webm audio stream,
 *    no re-encoding; one file per video, `files/youtube/<videoId>.<ext>`, shared by every card using it;
 *  - playlist / album cards keep their video list in `files/youtube/lists/<listId>.json`;
 *  - a card only plays from here once everything it needs is downloaded; until then it opens the app as before.
 */
class YouTubeAudioCache(context: Context) {
    @Serializable
    data class Entry(val id: String, val title: String)

    /** A downloaded track ready for our own player. */
    data class Track(val id: String, val title: String, val file: File)

    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(app.filesDir, "youtube").apply { mkdirs() }
    private val listsDir = File(root, "lists").apply { mkdirs() }
    private val tempRoot = File(root, ".tmp")
    private val prefs = app.getSharedPreferences("youtube_cache", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    /** Video ids being downloaded right now (pruning leaves them alone). */
    private val active: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    @Volatile private var initialized = false
    private var lastRefreshAll = 0L

    /** card uri -> progress 0..1 while its audio is being fetched. */
    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    /** Bumped whenever files are added or removed (UI recomputes sizes / "on this phone"). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun lockFor(key: String): Mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }

    /** Unpacks yt-dlp (slow the first time) and keeps it fresh: YouTube changes often, old yt-dlp stops working. */
    private suspend fun ensureInit() = initLock.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(app)
            tempRoot.deleteRecursively() // leftovers from a killed download
            initialized = true
            if (System.currentTimeMillis() - prefs.getLong("updatedAt", 0L) > UPDATE_EVERY_MS) updateYtDlp()
        }
    }

    private fun updateYtDlp(): Boolean = try {
        YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        prefs.edit().putLong("updatedAt", System.currentTimeMillis()).apply()
        true
    } catch (_: Exception) {
        false
    }

    /** The downloaded audio for one video, or null. */
    fun cachedFile(videoId: String): File? =
        root.listFiles { f -> f.isFile && f.nameWithoutExtension == videoId && f.length() > 0 }?.firstOrNull()

    private fun listFile(listId: String) = File(listsDir, "$listId.json")

    private fun savedList(listId: String): List<Entry>? = try {
        listFile(listId).takeIf { it.isFile }?.let { json.decodeFromString<List<Entry>>(it.readText()) }
    } catch (_: Exception) {
        null
    }

    /** True for a card whose audio this cache can keep. */
    fun handles(card: Card): Boolean =
        (card.sourceType == SourceType.YOUTUBE || card.sourceType == SourceType.YOUTUBE_MUSIC) &&
            (videoIdOf(card) != null || LinkResolver.youtubePlaylistId(card.uri) != null)

    /** A playlist card plays its whole list; a watch link that also carries `list=` counts as the list. */
    private fun videoIdOf(card: Card): String? =
        if (LinkResolver.isSingleTrack(card.sourceType, card.uri)) YouTubeChapters.videoId(card.uri)?.takeIf { VIDEO_ID.matches(it) } else null

    /** Everything [card] plays, downloaded; null while anything is still missing (the card then opens the app). */
    fun localTracks(card: Card): List<Track>? {
        if (!handles(card)) return null
        videoIdOf(card)?.let { id ->
            return cachedFile(id)?.let { listOf(Track(id, card.displayName(app), it)) }
        }
        val list = savedList(LinkResolver.youtubePlaylistId(card.uri) ?: return null)?.takeIf { it.isNotEmpty() } ?: return null
        return list.map { e -> Track(e.id, e.title, cachedFile(e.id) ?: return null) }
    }

    /** Background: download what [card] needs (no-op when it is all here or already downloading). */
    fun ensureAsync(card: Card) {
        if (!handles(card) || localTracks(card) != null) return
        scope.launch {
            try {
                ensure(card)
            } catch (_: Exception) {
            }
        }
    }

    /** Downloads everything [card] plays. Throws when YouTube can't be reached or yt-dlp fails. */
    suspend fun ensure(card: Card) = withContext(Dispatchers.IO) {
        if (!handles(card)) return@withContext
        lockFor(card.uri).withLock {
            if (localTracks(card) != null) return@withLock
            // Shown as 0% from the start: unpacking / updating yt-dlp and reading a playlist take a while.
            _downloading.update { it + (card.uri to 0f) }
            try {
                ensureInit()
                val ids = videoIdOf(card)?.let { listOf(it) } ?: fetchList(LinkResolver.youtubePlaylistId(card.uri)!!).map { it.id }
                val missing = ids.filter { cachedFile(it) == null }
                missing.forEachIndexed { i, id ->
                    download(id) { p -> _downloading.update { it + (card.uri to (i + p) / missing.size) } }
                    _version.update { it + 1 }
                }
            } finally {
                _downloading.update { it - card.uri }
            }
        }
    }

    /** Reads a playlist / album's videos (titles too) and remembers them for offline taps. */
    private fun fetchList(listId: String): List<Entry> {
        val request = YoutubeDLRequest("https://www.youtube.com/playlist?list=$listId").apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
        }
        val out = withYtDlpRetry { YoutubeDL.getInstance().execute(request).out }
        val entries = json.parseToJsonElement(out).jsonObject["entries"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
            Entry(id, o["title"]?.jsonPrimitive?.contentOrNull ?: id)
        }
        if (entries.isEmpty()) throw IllegalStateException("Empty playlist")
        listFile(listId).writeText(json.encodeToString(entries))
        return entries
    }

    private fun download(videoId: String, onProgress: (Float) -> Unit) {
        if (cachedFile(videoId) != null) return
        active += videoId
        val tempDir = File(tempRoot, videoId).apply { deleteRecursively(); mkdirs() }
        try {
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                // Audio only, as YouTube serves it (AAC m4a first, else Opus webm): no ffmpeg needed, ExoPlayer plays both.
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("--no-playlist")
                addOption("--fixup", "never")
                addOption("-o", "${tempDir.absolutePath}/%(id)s.%(ext)s")
            }
            withYtDlpRetry {
                YoutubeDL.getInstance().execute(request, "yt:$videoId") { progress, _, _ ->
                    if (progress >= 0f) onProgress(progress.coerceAtMost(100f) / 100f)
                }
            }
            val produced = tempDir.listFiles { f -> f.isFile && f.nameWithoutExtension == videoId && f.length() > 0 }?.firstOrNull()
                ?: throw IllegalStateException("No audio for $videoId")
            if (!produced.renameTo(File(root, produced.name))) throw IllegalStateException("Couldn't save audio for $videoId")
        } finally {
            tempDir.deleteRecursively()
            active -= videoId
        }
    }

    /** One retry after updating yt-dlp: a failure is most often YouTube having changed under an old yt-dlp. */
    private fun <T> withYtDlpRetry(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (!updateYtDlp()) throw e
        block()
    }

    /**
     * Background: download every YouTube card's audio (throttled) and delete audio no card uses any more.
     * [download] false only prunes.
     */
    fun refreshAllAsync(all: List<Card>, download: Boolean, minIntervalMs: Long = 30 * 60_000L) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAll < minIntervalMs) return
        lastRefreshAll = now
        scope.launch {
            prune(all)
            if (!download) return@launch
            all.filter { handles(it) }.forEach { card ->
                try {
                    ensure(card)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Deletes audio and lists no card points at. */
    private fun prune(all: List<Card>) {
        val cards = all.filter { handles(it) }
        val listIds = cards.mapNotNull { if (videoIdOf(it) == null) LinkResolver.youtubePlaylistId(it.uri) else null }.toSet()
        val keep = cards.mapNotNull { videoIdOf(it) }.toSet() + listIds.flatMap { savedList(it).orEmpty().map { e -> e.id } }
        var removed = false
        listsDir.listFiles()?.forEach { if (it.nameWithoutExtension !in listIds) removed = it.delete() || removed }
        root.listFiles { f -> f.isFile }?.forEach { if (it.nameWithoutExtension !in keep && !isDownloading(it.nameWithoutExtension)) removed = it.delete() || removed }
        if (removed) _version.update { it + 1 }
    }

    private fun isDownloading(videoId: String) = videoId in active

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        listsDir.mkdirs()
        _version.update { it + 1 }
    }

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private const val UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000
    }
}
