package com.cardplayer.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Covers for a card, looked up by name in three public catalogues that need no key:
 *  - the iTunes Search API (albums, audiobooks, podcasts, films),
 *  - Open Library (the covers of printed books, picture books among them),
 *  - Deezer (albums, in big pictures).
 * Which of them are asked depends on what the card plays ([Kind]). They are asked together and each one's
 * covers are shown as soon as it answers: iTunes in under a second, Open Library often after five.
 */
object ArtworkSearch {
    /** [url] is the cover at a size fit for a card; [thumb] a small one of it, quick to load into a grid of results. */
    data class Result(val title: String, val subtitle: String, val url: String, val thumb: String = url)

    /** What the card plays, and so where its cover is most likely to be. */
    enum class Kind { BOOK, MUSIC, PODCAST, ANY }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Covers for [term], as they come in: every list sent holds all that was found so far (what was there stays
     * where it was, later answers go underneath). The last thing sent is the whole answer, or null when no
     * catalogue could be reached at all: that is no connection, not "nothing found".
     *
     * A search of one [kind] that finds nothing is tried again everywhere (a "music" card may be a story).
     */
    fun search(term: String, kind: Kind = Kind.ANY): Flow<List<Result>?> = channelFlow {
        val q = term.trim()
        if (q.isBlank()) {
            send(emptyList())
            return@channelFlow
        }
        var found = ask(q, kind)
        if (found != null && found.isEmpty() && kind != Kind.ANY) found = ask(q, Kind.ANY)
        send(found)
    }

    /** Asks the catalogues for [kind], sending on what is found each time one answers. Null = none could be reached. */
    private suspend fun ProducerScope<List<Result>?>.ask(q: String, kind: Kind): List<Result>? = coroutineScope {
        val e = URLEncoder.encode(q, "UTF-8")
        val itunes = "https://itunes.apple.com/search?term=$e"
        val deezer = "https://api.deezer.com/search/album?q=$e&limit=20" to ::parseDeezer
        val openLibrary = "https://openlibrary.org/search.json?q=$e&limit=20&fields=title,author_name,cover_i" to ::parseOpenLibrary
        val sources = when (kind) {
            Kind.BOOK -> listOf("$itunes&media=audiobook&limit=20" to ::parseItunes, openLibrary)
            Kind.MUSIC -> listOf("$itunes&media=music&entity=album&limit=20" to ::parseItunes, deezer)
            Kind.PODCAST -> listOf("$itunes&media=podcast&limit=20" to ::parseItunes)
            Kind.ANY -> listOf("$itunes&media=all&limit=30" to ::parseItunes, deezer, openLibrary)
        }
        val all = ArrayList<Result>()
        val seen = HashSet<String>()
        var reached = false
        val turn = Mutex()
        sources.map { (url, parse) ->
            launch(Dispatchers.IO) {
                val answer = fetch(url)?.let { body ->
                    try {
                        parse(body)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: return@launch
                turn.withLock {
                    reached = true
                    val fresh = answer.filter { seen.add(it.url) }
                    if (fresh.isNotEmpty()) {
                        all += fresh
                        send(all.toList())
                    }
                }
            }
        }.joinAll()
        if (reached) all.toList() else null
    }

    private fun fetch(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", "BoopBox/1.0").build()
        client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun rows(body: String, key: String): List<JsonObject> =
        ((json.parseToJsonElement(body) as? JsonObject)?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** iTunes: `results[]`, each with a 100 px picture whose address also serves it bigger. */
    fun parseItunes(body: String): List<Result> = rows(body, "results").mapNotNull { o ->
        val art = o.text("artworkUrl100").ifBlank { return@mapNotNull null }
        Result(
            title = o.text("trackName").ifBlank { o.text("collectionName") },
            subtitle = o.text("artistName"),
            url = art.replace("100x100bb", "600x600bb"),
            thumb = art.replace("100x100bb", "300x300bb")
        )
    }

    /** Deezer: `data[]` of albums. An album without a cover still gets an address, with nothing where its id goes. */
    fun parseDeezer(body: String): List<Result> = rows(body, "data").mapNotNull { o ->
        val art = o.text("cover_xl").ifBlank { o.text("cover_big") }
        if (art.isBlank() || art.contains("/cover//")) return@mapNotNull null
        Result(
            title = o.text("title"),
            subtitle = (o["artist"] as? JsonObject)?.text("name").orEmpty(),
            url = art,
            thumb = o.text("cover_medium").ifBlank { o.text("cover_big") }.ifBlank { art }
        )
    }

    /** Open Library: `docs[]` of books; the ones with a cover carry its number. */
    fun parseOpenLibrary(body: String): List<Result> = rows(body, "docs").mapNotNull { o ->
        val cover = o.text("cover_i").ifBlank { return@mapNotNull null }
        val authors = (o["author_name"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // The big cover can take seconds to arrive; the middle size is there at once.
        val address = "https://covers.openlibrary.org/b/id/$cover"
        Result(title = o.text("title"), subtitle = authors.take(2).joinToString(", "), url = "$address-L.jpg", thumb = "$address-M.jpg")
    }
}
