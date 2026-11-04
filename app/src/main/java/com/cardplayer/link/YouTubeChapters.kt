package com.cardplayer.link

import android.net.Uri
import android.text.Html
import com.cardplayer.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds the songs inside one long YouTube video: YouTube's own chapter markers, or the
 * "0:00 Song name" tracklist people put in the description. No API key: reads the watch page.
 */
object YouTubeChapters {
    data class Scan(val videoTitle: String?, val durationMs: Long, val chapters: List<Chapter>)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun videoId(link: String): String? {
        val l = link.trim()
        if (l.startsWith("vnd.youtube:")) return l.removePrefix("vnd.youtube:").substringBefore("?")
        val uri = Uri.parse(l)
        uri.getQueryParameter("v")?.let { return it }
        if (uri.host?.contains("youtu.be") == true) return uri.lastPathSegment
        val segs = uri.pathSegments
        val i = segs.indexOfFirst { it == "shorts" || it == "embed" || it == "live" }
        if (i >= 0 && i + 1 < segs.size) return segs[i + 1]
        return null
    }

    private val videoIdInJson = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")

    /** First video of a playlist, read from the playlist page (a playlist page itself doesn't autoplay). */
    suspend fun firstVideoInPlaylist(listId: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/playlist?list=$listId&hl=en")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        val html = try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        // The playlist's own items live in ytInitialData; take the first video id after it.
        val start = html.indexOf("ytInitialData").takeIf { it >= 0 } ?: 0
        videoIdInJson.find(html, start)?.groupValues?.get(1)
    }

    /** A YouTube Music album / playlist as its own page describes it. */
    data class MusicList(val listId: String, val title: String?, val artworkUrl: String?, val author: String?)

    private val listInUrl = Regex("""[?&]list=([A-Za-z0-9_-]+)""")

    /**
     * Reads a YouTube Music album or playlist page (`playlist?list=…`, or an album's `browse/MPREb_…`) from its
     * Open Graph tags: the list's name, its square cover, the artist ("Album • The Beatles") and, for a browse
     * link, the playlist id that plays it.
     */
    suspend fun musicList(link: String): MusicList? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        val html = try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        fun og(prop: String): String? = Regex("""<meta property="og:$prop" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.ifBlank { null }
        val listId = (listInUrl.find(link) ?: og("url")?.let { listInUrl.find(it) })?.groupValues?.get(1) ?: return@withContext null
        // "Album • The Beatles", "EP • …", "Playlist • Someone • 2024"
        MusicList(listId, og("title"), og("image"), og("description")?.split(" • ")?.getOrNull(1)?.ifBlank { null })
    }

    suspend fun scan(link: String): Scan? = withContext(Dispatchers.IO) {
        val id = videoId(link) ?: return@withContext null
        val request = Request.Builder()
            .url("https://www.youtube.com/watch?v=$id&hl=en&bpctr=9999999999&has_verified=1")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        val html = try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        var title: String? = null
        var durationMs = 0L
        var description: String? = null
        extractJson(html, "ytInitialPlayerResponse")?.let { player ->
            val details = player.optJSONObject("videoDetails")
            title = details?.optString("title")?.ifBlank { null }
            durationMs = (details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000
            description = details?.optString("shortDescription")
        }

        // 1) YouTube's own chapter markers (shown on the seek bar).
        var chapters = markerChapters(html)
        // 2) Tracklist in the description.
        if (chapters.size < 2 && description != null) chapters = descriptionChapters(description!!)
        if (chapters.size < 2) chapters = emptyList()
        if (durationMs > 0) chapters = chapters.filter { it.startMs < durationMs }
        Scan(title, durationMs, chapters)
    }

    /** Pulls the `var NAME = {...};` JSON blob out of the page, respecting braces inside strings. */
    private fun extractJson(html: String, name: String): JSONObject? {
        val start = html.indexOf("$name = ").takeIf { it >= 0 }?.plus(name.length + 3)
            ?: html.indexOf("$name=").takeIf { it >= 0 }?.plus(name.length + 1)
            ?: return null
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONObject(html.substring(start, i + 1))
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private val markerA = Regex(""""macroMarkersListItemRenderer":\{"title":\{"simpleText":"((?:[^"\\]|\\.)*)"\},"timeDescription":\{"simpleText":"([\d:]+)"\}""")
    private val markerB = Regex(""""macroMarkersListItemRenderer":\{"timeDescription":\{"simpleText":"([\d:]+)"\},"title":\{"simpleText":"((?:[^"\\]|\\.)*)"\}""")
    private val chapterRenderer = Regex(""""chapterRenderer":\{"title":\{"simpleText":"((?:[^"\\]|\\.)*)"\},"timeRangeStartMillis":(\d+)""")

    private fun markerChapters(html: String): List<Chapter> {
        val found = mutableListOf<Chapter>()
        chapterRenderer.findAll(html).forEach { m ->
            found += Chapter(m.groupValues[2].toLong(), unescape(m.groupValues[1]))
        }
        if (found.isEmpty()) {
            markerA.findAll(html).forEach { m -> parseTime(m.groupValues[2])?.let { found += Chapter(it, unescape(m.groupValues[1])) } }
            markerB.findAll(html).forEach { m -> parseTime(m.groupValues[1])?.let { found += Chapter(it, unescape(m.groupValues[2])) } }
        }
        return tidy(found)
    }

    private val timeThenTitle = Regex("""^\W*((?:\d{1,2}:)?\d{1,2}:\d{2})\W*\s*(.*?)\s*$""")
    private val titleThenTime = Regex("""^\s*(.*?\S)\s*[-–—(\[|]*\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\W*$""")

    private fun descriptionChapters(description: String): List<Chapter> {
        val found = mutableListOf<Chapter>()
        description.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            timeThenTitle.find(line)?.let { m ->
                val t = parseTime(m.groupValues[1]) ?: return@forEach
                found += Chapter(t, cleanTitle(m.groupValues[2]))
                return@forEach
            }
            titleThenTime.find(line)?.let { m ->
                val t = parseTime(m.groupValues[2]) ?: return@forEach
                found += Chapter(t, cleanTitle(m.groupValues[1]))
            }
        }
        return tidy(found)
    }

    private fun tidy(list: List<Chapter>): List<Chapter> {
        val sorted = list.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (sorted.size < 2) return emptyList()
        // A real tracklist starts at (or near) the beginning and goes up.
        return sorted.mapIndexed { i, c ->
            Chapter(c.startMs, c.title.ifBlank { "Song ${i + 1}" })
        }
    }

    private fun cleanTitle(s: String): String =
        s.trim()
            .trimStart('-', '–', '—', ':', '|', '.', ')', ']', ' ')
            .trimEnd('-', '–', '—', ':', '|', '(', '[', ' ')
            .replace(Regex("""^\d{1,2}[.)]\s+"""), "")
            .trim()

    private fun parseTime(t: String): Long? {
        val parts = t.split(":").map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000
            3 -> ((parts[0] * 60L + parts[1]) * 60 + parts[2]) * 1000
            else -> null
        }
    }

    private fun unescape(s: String): String =
        s.replace("\\u0026", "&").replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ").trim()

    fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
