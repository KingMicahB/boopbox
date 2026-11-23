package com.cardplayer.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * A card's page in the Yoto store (uk.yotoplay.com/products/<card>, us., ca., au. ...): its card art and the
 * pixel icon of every track. Pasted where a picture is asked for, such a link dresses a whole card at once:
 * the art becomes the card's picture and each song or chapter gets its icon.
 *
 * The store has no API for this; the page is server-rendered, with one `<li class="Tracklist_item…">` per
 * track (the ones past the sixth are only hidden) holding the icon and the title, and the art in `og:image`.
 * The class names end in a suffix that changes between deploys, so only their beginnings are matched.
 */
object YotoStore {
    data class Track(val title: String, val icon: String?)

    data class Listing(val url: String, val title: String?, val cover: String?, val tracks: List<Track>) {
        val icons: List<String> get() = tracks.mapNotNull { it.icon }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** True for a link to a card's page in the Yoto store. */
    fun isListing(link: String): Boolean = try {
        val uri = URI(link.trim())
        val host = uri.host?.lowercase().orEmpty()
        (uri.scheme == "https" || uri.scheme == "http") &&
            (host == "yotoplay.com" || host.endsWith(".yotoplay.com")) &&
            uri.path.orEmpty().contains("/products/")
    } catch (_: Exception) {
        false
    }

    /** Reads the page behind [link]; null when it can't be fetched or holds neither art nor tracks. */
    suspend fun fetch(link: String): Listing? = withContext(Dispatchers.IO) {
        val url = link.trim()
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) BoopBox/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else parse(url, response.body?.string().orEmpty()).takeIf { it.cover != null || it.tracks.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private val item = Regex("""<li class="Tracklist_item[^"]*"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
    private val trackTitle = Regex("""Tracklist_trackTitle[^"]*"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val trackIcon = Regex("""<img[^>]*\ssrc="(https://[^"]*/icons/[^"]+)"""")
    private val ogTitle = Regex("""<meta property="og:title" content="([^"]*)"""")
    private val ogImage = Regex("""<meta property="og:image" content="([^"]*)"""")
    /** "… (US)", "… (UK)": the store's region, not part of the card's name. */
    private val region = Regex("""\s*\((?:US|UK|CA|AU|EU|FR|DE|IE)\)\s*$""")
    private val tag = Regex("<[^>]+>")

    fun parse(url: String, page: String): Listing {
        val tracks = item.findAll(page).mapNotNull { m ->
            val li = m.groupValues[1]
            val title = trackTitle.find(li)?.groupValues?.get(1)?.let { unescape(tag.replace(it, "")).trim() }
            if (title.isNullOrEmpty()) null else Track(title, trackIcon.find(li)?.groupValues?.get(1)?.let(::unescape))
        }.toList()
        return Listing(
            url = url,
            title = ogTitle.find(page)?.groupValues?.get(1)?.let { region.replace(unescape(it), "").trim() }?.ifEmpty { null },
            cover = ogImage.find(page)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.startsWith("http") },
            tracks = tracks
        )
    }

    /**
     * Which icon goes on which of the card's songs: [songs] are the card's own (key to title), the result maps
     * a song's key to an icon's address.
     *
     * A song takes the icon of the track with the same name ("Chapter 3: The Burrow" is "The Burrow"); the
     * names needn't match to the letter. Songs that find no namesake take the icon in their place in the list,
     * as long as the two lists line up: the same number of tracks once credits are set aside. That is what
     * dresses a book whose own chapter marks only say "Chapter 1", "Chapter 2".
     */
    fun match(tracks: List<Track>, songs: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val free = tracks.toMutableList()
        for ((key, title) in songs) {
            val name = plain(title)
            if (name.isEmpty()) continue
            val hit = free.firstOrNull { plain(it.title) == name }
                ?: free.map { it to likeness(name, plain(it.title)) }.filter { it.second >= 0.6f }.maxByOrNull { it.second }?.first
            if (hit != null) {
                free -= hit
                hit.icon?.let { out[key] = it }
            }
        }
        val ownSongs = songs.filterNot { isCredits(it.second) }
        val ownTracks = tracks.filterNot { isCredits(it.title) }
        if (ownSongs.size == ownTracks.size) {
            ownSongs.forEachIndexed { i, (key, _) -> if (key !in out) ownTracks[i].icon?.let { out[key] = it } }
        }
        return out
    }

    private val lead = Regex("""^\s*(?:(?:chapter|ch\.?|part|track|episode)\s+\S+|\d+)\s*[:.)\-–—]\s*""", RegexOption.IGNORE_CASE)
    private val notWord = Regex("""[^\p{L}\p{N}]+""")

    /** A title as it is compared: no "Chapter Three:" or "03 -" in front, no capitals, no punctuation. */
    private fun plain(title: String): String =
        notWord.replace(lead.replace(title, "").ifBlank { title }.lowercase(), " ").trim()

    private fun isCredits(title: String): Boolean = plain(title).let { it.contains("credits") }

    /** How alike two plain titles are, 0..1: the share of their words they have in common, or one inside the other. */
    private fun likeness(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a.length >= 5 && b.length >= 5 && (a.contains(b) || b.contains(a))) return 0.9f
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        return (wa intersect wb).size.toFloat() / (wa union wb).size
    }

    private val entity = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    /** The few HTML entities a title or an address carries. */
    private fun unescape(text: String): String = entity.replace(text) { m ->
        val code = m.groupValues[1]
        when {
            code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> when (code) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                else -> null
            }
        } ?: m.value
    }
}
