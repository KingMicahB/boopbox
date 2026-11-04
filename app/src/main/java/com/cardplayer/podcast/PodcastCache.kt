package com.cardplayer.podcast

import android.content.Context
import com.cardplayer.data.Card
import com.cardplayer.data.PodcastEpisode
import com.cardplayer.data.PodcastFeed
import com.cardplayer.data.PodcastMode
import com.cardplayer.data.SourceType
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Keeps podcast cards playable and current:
 *  - the feed (episode list) is cached as JSON so the card works offline;
 *  - the episode the card points at (newest, or the chosen one) is downloaded to the phone,
 *    so a tap plays the latest episode instantly and without internet;
 *  - older downloads for that feed are pruned.
 */
class PodcastCache(context: Context) {
    @Serializable
    data class CachedFeed(
        val feedUrl: String,
        val title: String,
        val artworkUrl: String?,
        val episodes: List<PodcastEpisode>,
        val fetchedAt: Long
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val root = File(context.filesDir, "podcasts").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = mutableMapOf<String, Mutex>()
    private var lastRefreshAll = 0L

    /** feedUrl -> download progress 0..1 while an episode is being fetched. */
    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    /** Bumps whenever a feed or file changes, so UI can re-read cache state. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // ---------------------------------------------------------------- feed cache

    fun loadFeed(feedUrl: String): CachedFeed? = try {
        val f = File(feedDir(feedUrl), "feed.json")
        if (f.exists()) json.decodeFromString(CachedFeed.serializer(), f.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun saveFeed(feedUrl: String, feed: PodcastFeed): CachedFeed {
        val cached = CachedFeed(feedUrl, feed.title, feed.artworkUrl, feed.episodes, System.currentTimeMillis())
        try {
            File(feedDir(feedUrl), "feed.json").writeText(json.encodeToString(CachedFeed.serializer(), cached))
        } catch (_: Exception) {
        }
        _version.update { it + 1 }
        return cached
    }

    /** Fresh feed if the network answers within [timeoutMs], else the cached one, else null. */
    suspend fun feedFor(feedUrl: String, timeoutMs: Long = 6000): CachedFeed? {
        val fresh = try {
            withTimeoutOrNull(timeoutMs) { RssParser.fetch(feedUrl) }
        } catch (_: Exception) {
            null
        }
        return if (fresh != null) saveFeed(feedUrl, fresh) else loadFeed(feedUrl)
    }

    // ---------------------------------------------------------------- episode files

    fun cachedFile(feedUrl: String, episodeUrl: String): File? =
        File(feedDir(feedUrl), sha1(episodeUrl) + ".media").takeIf { it.exists() && it.length() > 0 }

    /** The episode a card should play: newest, or the one it was pinned to. */
    fun targetEpisode(card: Card, feed: CachedFeed): PodcastEpisode? = when (card.podcastMode) {
        PodcastMode.LATEST -> feed.episodes.firstOrNull()
        PodcastMode.EPISODE -> feed.episodes.firstOrNull { it.url == card.episodeUrl } ?: feed.episodes.firstOrNull()
    }

    /**
     * Makes sure the card's target episode is downloaded (if [download]) and prunes the rest.
     * Safe to call often; a feed is only worked on by one coroutine at a time.
     */
    suspend fun ensureCurrent(card: Card, feed: CachedFeed, download: Boolean) = withContext(Dispatchers.IO) {
        val lock = synchronized(locks) { locks.getOrPut(card.uri) { Mutex() } }
        if (lock.isLocked) return@withContext
        lock.withLock {
            val target = targetEpisode(card, feed) ?: return@withLock
            if (download && cachedFile(card.uri, target.url) == null) downloadKeyed(card.uri, target.url, target.url)
            prune(card.uri, keep = setOf(target.url))
        }
    }

    /** Re-fetches the feed and downloads the newest episode for one card. */
    suspend fun refresh(card: Card, download: Boolean): CachedFeed? {
        if (card.sourceType != SourceType.PODCAST || card.uri.isBlank()) return null
        val feed = feedFor(card.uri, timeoutMs = 15000) ?: return null
        ensureCurrent(card, feed, download)
        return feed
    }

    /** Background refresh of every podcast card, at most once per [minIntervalMs]. */
    fun refreshAllAsync(cards: List<Card>, download: Boolean, minIntervalMs: Long = 30 * 60_000L) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAll < minIntervalMs) return
        lastRefreshAll = now
        scope.launch {
            cards.filter { it.sourceType == SourceType.PODCAST }.forEach { card ->
                try {
                    refresh(card, download)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Generic keyed cache, used for Yoto cards too: [group] is the card, [key] a stable id
     * (chapter/track key), [url] wherever the bytes currently live (signed URLs change hourly).
     */
    fun cachedFileKeyed(group: String, key: String): File? = cachedFile(group, key)

    suspend fun ensureKeyed(group: String, wanted: List<Pair<String, String>>, keepOnly: Set<String>?) = withContext(Dispatchers.IO) {
        val lock = synchronized(locks) { locks.getOrPut(group) { Mutex() } }
        if (lock.isLocked) return@withContext
        lock.withLock {
            for ((key, url) in wanted) {
                if (cachedFile(group, key) == null) downloadKeyed(group, key, url)
            }
            keepOnly?.let { prune(group, it) }
        }
    }

    private suspend fun downloadKeyed(feedUrl: String, key: String, url: String) = withContext(Dispatchers.IO) {
        val dir = feedDir(feedUrl)
        val target = File(dir, sha1(key) + ".media")
        val tmp = File(dir, sha1(key) + ".part")
        _downloading.update { it + (feedUrl to 0f) }
        try {
            val request = Request.Builder().url(url).header("User-Agent", "BoopBox/1.0 (Android)").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    _downloading.update { it + (feedUrl to pct / 100f) }
                                }
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            _version.update { it + 1 }
        } catch (e: Exception) {
            tmp.delete()
        } finally {
            _downloading.update { it - feedUrl }
        }
    }

    private fun prune(feedUrl: String, keep: Set<String>) {
        val keepNames = keep.map { sha1(it) + ".media" }.toSet()
        feedDir(feedUrl).listFiles()?.forEach { f ->
            if ((f.name.endsWith(".media") && f.name !in keepNames) || f.name.endsWith(".part")) f.delete()
        }
    }

    /** Total bytes of downloaded episodes, for the settings screen. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile && it.name.endsWith(".media") }.sumOf { it.length() }

    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        _version.update { it + 1 }
    }

    private fun feedDir(feedUrl: String): File = File(root, sha1(feedUrl)).apply { mkdirs() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
