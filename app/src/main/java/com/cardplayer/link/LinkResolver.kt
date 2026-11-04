package com.cardplayer.link

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.cardplayer.R
import com.cardplayer.data.LinkMetadata
import com.cardplayer.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Understands links to Spotify, YouTube Music, YouTube, Audible, Netflix, Disney+ and generic apps:
 * detects the service, fetches title/artwork, and builds the intent that launches playback.
 */
object LinkResolver {
    const val PKG_SPOTIFY = "com.spotify.music"
    const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
    const val PKG_YOUTUBE = "com.google.android.youtube"
    const val PKG_AUDIBLE = "com.audible.application"
    const val PKG_NETFLIX = "com.netflix.mediaclient"
    const val PKG_DISNEY = "com.disney.disneyplus"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun detect(link: String): SourceType {
        val l = link.trim().lowercase()
        return when {
            l.startsWith("spotify:") || l.contains("open.spotify.com") -> SourceType.SPOTIFY
            l.contains("music.youtube.com") -> SourceType.YOUTUBE_MUSIC
            l.contains("youtube.com") || l.contains("youtu.be") || l.startsWith("vnd.youtube") -> SourceType.YOUTUBE
            l.contains("audible.") || l.startsWith("audible:") -> SourceType.AUDIBLE
            videoApp(l) != null -> SourceType.VIDEO
            l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".ogg") || l.endsWith(".aac") || l.endsWith(".m3u8") -> SourceType.STREAM_URL
            else -> SourceType.APP_LINK
        }
    }

    fun packageFor(type: SourceType): String? = when (type) {
        SourceType.SPOTIFY -> PKG_SPOTIFY
        SourceType.YOUTUBE_MUSIC -> PKG_YT_MUSIC
        SourceType.YOUTUBE -> PKG_YOUTUBE
        SourceType.AUDIBLE -> PKG_AUDIBLE
        else -> null
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** spotify:track:ID / https://open.spotify.com/track/ID?si=.. -> ("track", "ID") */
    private fun spotifyParts(link: String): Pair<String, String>? {
        val l = link.trim()
        if (l.startsWith("spotify:")) {
            val parts = l.removePrefix("spotify:").split(":").filter { it.isNotBlank() && it != "play" }
            if (parts.size >= 2) return parts[parts.size - 2] to parts.last()
            return null
        }
        val uri = Uri.parse(l)
        val segs = uri.pathSegments.filter { it.isNotBlank() }
        // handles /track/ID, /intl-en/track/ID, /user/x/playlist/ID
        val typeIdx = segs.indexOfLast { it in setOf("track", "album", "playlist", "artist", "episode", "show") }
        if (typeIdx >= 0 && typeIdx + 1 < segs.size) return segs[typeIdx] to segs[typeIdx + 1]
        return null
    }

    private fun youtubeVideoId(link: String): String? {
        val l = link.trim()
        if (l.startsWith("vnd.youtube:")) return l.removePrefix("vnd.youtube:").substringBefore("?")
        val uri = Uri.parse(l)
        uri.getQueryParameter("v")?.let { return it }
        if (uri.host?.contains("youtu.be") == true) return uri.lastPathSegment
        val segs = uri.pathSegments
        val i = segs.indexOf("shorts")
        if (i >= 0 && i + 1 < segs.size) return segs[i + 1]
        return null
    }

    /** Which streaming video app a link belongs to: (package, app name), or null when it is no such link. */
    fun videoApp(link: String): Pair<String, Int>? {
        val l = link.trim().lowercase()
        return when {
            l.contains("netflix.com") || l.startsWith("nflx:") -> PKG_NETFLIX to R.string.source_netflix
            l.contains("disneyplus.com") || l.contains("disneyplus:") -> PKG_DISNEY to R.string.source_disney
            else -> null
        }
    }

    /** The package playing a card, for finding its media session. */
    fun packageFor(type: SourceType, link: String): String? =
        if (type == SourceType.VIDEO) videoApp(link)?.first else packageFor(type)

    /** netflix.com/title/ID, /il-en/title/ID, /watch/ID?trackId=.., nflx://www.netflix.com/watch/ID -> ID */
    private fun netflixId(link: String): String? {
        val segs = Uri.parse(link.trim()).pathSegments
        val i = segs.indexOfFirst { it == "title" || it == "watch" }
        return segs.getOrNull(i + 1)?.takeIf { i >= 0 && it.all(Char::isDigit) }
    }

    /** YouTube / YT Music playlist id, if the link has one. */
    fun youtubePlaylistId(link: String): String? = try {
        Uri.parse(link.trim()).getQueryParameter("list")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * A playlist link without a video (`playlist?list=…`) opens a page that doesn't autoplay.
     * Turn it into `watch?v=<first video>&list=…`, which does. Needs one network fetch.
     */
    suspend fun resolvePlayableLink(type: SourceType, link: String): String {
        if (type != SourceType.YOUTUBE && type != SourceType.YOUTUBE_MUSIC) return link
        // An album's own page has no list= yet: its page names the playlist that plays it.
        val listLink = if (isMusicAlbumPage(link)) {
            YouTubeChapters.musicList(link.trim())?.let { "https://music.youtube.com/playlist?list=${it.listId}" } ?: return link
        } else link
        val listId = youtubePlaylistId(listLink) ?: return listLink
        if (youtubeVideoId(listLink) != null) return listLink
        val first = YouTubeChapters.firstVideoInPlaylist(listId) ?: return listLink
        val host = if (type == SourceType.YOUTUBE_MUSIC) "music.youtube.com" else "www.youtube.com"
        return "https://$host/watch?v=$first&list=$listId"
    }

    /** music.youtube.com/browse/MPREb_… : an album opened from search or an artist page. */
    private fun isMusicAlbumPage(link: String): Boolean {
        val uri = Uri.parse(link.trim())
        return uri.host?.contains("music.youtube.com") == true &&
            uri.pathSegments.getOrNull(0) == "browse" && uri.pathSegments.getOrNull(1)?.startsWith("MPREb") == true
    }

    /** True for a link to a whole album / playlist rather than one song in it. */
    fun isWholeList(type: SourceType, link: String): Boolean =
        (type == SourceType.YOUTUBE || type == SourceType.YOUTUBE_MUSIC) &&
            youtubeVideoId(link) == null && (youtubePlaylistId(link) != null || isMusicAlbumPage(link))

    /**
     * Name, cover and artist of a whole YouTube / YouTube Music album or playlist (the list in [link]), rather
     * than of its first song. YouTube Music's page has the square album cover and "Album • Artist"; a plain
     * YouTube playlist falls back to oEmbed. Null when nothing could be read.
     */
    suspend fun fetchListMetadata(link: String): LinkMetadata? {
        val listId = youtubePlaylistId(link) ?: return null
        val music = YouTubeChapters.musicList("https://music.youtube.com/playlist?list=$listId")
        if (music?.title != null) return LinkMetadata(music.title, music.artworkUrl, music.author)
        return withContext(Dispatchers.IO) {
            try {
                oembed("https://www.youtube.com/oembed?format=json&url=" + enc("https://www.youtube.com/playlist?list=$listId"))
                    .takeIf { it.title != null }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** True for a link to one song / video (as opposed to an album, playlist or show). */
    fun isSingleTrack(type: SourceType, link: String): Boolean = when (type) {
        SourceType.SPOTIFY -> spotifyParts(link)?.first in setOf("track", "episode")
        SourceType.YOUTUBE, SourceType.YOUTUBE_MUSIC -> {
            val uri = Uri.parse(link.trim())
            youtubeVideoId(link) != null && (link.startsWith("vnd.youtube:") || uri.getQueryParameter("list") == null)
        }
        SourceType.LOCAL_FILE, SourceType.STREAM_URL -> true
        else -> false
    }

    /**
     * Builds the intent that opens (and ideally auto-plays) the link in its app.
     * [startMs] > 0 asks YouTube / YT Music to start the video there (`t=`), for a card with a start cut.
     */
    fun launchIntent(context: Context, type: SourceType, link: String, startMs: Long = 0L): Intent {
        if (startMs > 0L && (type == SourceType.YOUTUBE || type == SourceType.YOUTUBE_MUSIC)) {
            youtubeVideoId(link)?.let { id ->
                val pkg = if (type == SourceType.YOUTUBE) PKG_YOUTUBE else PKG_YT_MUSIC
                val host = if (type == SourceType.YOUTUBE) "www.youtube.com" else "music.youtube.com"
                // vnd.youtube: has no start time; the watch URL's t= is honoured by both apps.
                return Intent(Intent.ACTION_VIEW, Uri.parse("https://$host/watch?v=$id&t=${startMs / 1000}s")).apply {
                    if (isInstalled(context, pkg)) setPackage(pkg)
                    putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + context.packageName))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
        val referrer = Uri.parse("android-app://" + context.packageName)
        val intent = when (type) {
            SourceType.SPOTIFY -> {
                val parts = spotifyParts(link)
                val uri = if (parts != null) {
                    // ":play" makes albums/playlists start immediately; tracks auto-play anyway.
                    Uri.parse("spotify:${parts.first}:${parts.second}:play")
                } else Uri.parse(link.trim())
                Intent(Intent.ACTION_VIEW, uri).apply {
                    if (isInstalled(context, PKG_SPOTIFY)) setPackage(PKG_SPOTIFY)
                }
            }
            SourceType.YOUTUBE -> {
                val id = youtubeVideoId(link)
                val installed = isInstalled(context, PKG_YOUTUBE)
                // vnd.youtube:ID autoplays a single video; playlists keep the full URL so the list comes along.
                val uri = if (id != null && installed && youtubePlaylistId(link) == null) Uri.parse("vnd.youtube:$id") else Uri.parse(link.trim())
                Intent(Intent.ACTION_VIEW, uri).apply { if (installed) setPackage(PKG_YOUTUBE) }
            }
            SourceType.YOUTUBE_MUSIC -> {
                Intent(Intent.ACTION_VIEW, Uri.parse(link.trim())).apply {
                    if (isInstalled(context, PKG_YT_MUSIC)) setPackage(PKG_YT_MUSIC)
                }
            }
            SourceType.AUDIBLE -> {
                Intent(Intent.ACTION_VIEW, Uri.parse(link.trim())).apply {
                    if (isInstalled(context, PKG_AUDIBLE)) setPackage(PKG_AUDIBLE)
                }
            }
            SourceType.VIDEO -> {
                val pkg = videoApp(link)?.first
                // A shared Netflix link is the /title/ page; /watch/ starts it playing (a series resumes its next episode).
                val id = if (pkg == PKG_NETFLIX) netflixId(link) else null
                val uri = if (id != null) Uri.parse("https://www.netflix.com/watch/$id") else Uri.parse(link.trim())
                Intent(Intent.ACTION_VIEW, uri).apply {
                    if (pkg != null && isInstalled(context, pkg)) setPackage(pkg)
                }
            }
            else -> Intent(Intent.ACTION_VIEW, Uri.parse(link.trim()))
        }
        intent.putExtra(Intent.EXTRA_REFERRER, referrer)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** Title + artwork for a link, using public oEmbed endpoints where available, else Open Graph tags. */
    suspend fun fetchMetadata(type: SourceType, link: String): LinkMetadata = withContext(Dispatchers.IO) {
        try {
            when (type) {
                SourceType.SPOTIFY -> {
                    val parts = spotifyParts(link)
                    val webUrl = if (parts != null) "https://open.spotify.com/${parts.first}/${parts.second}" else link.trim()
                    oembed("https://open.spotify.com/oembed?url=" + enc(webUrl))
                }
                SourceType.YOUTUBE, SourceType.YOUTUBE_MUSIC -> {
                    val id = youtubeVideoId(link)
                    val webUrl = if (id != null) "https://www.youtube.com/watch?v=$id" else link.trim()
                    val meta = oembed("https://www.youtube.com/oembed?format=json&url=" + enc(webUrl))
                    if (meta.title == null) openGraph(link) else meta
                }
                // The /title/ page carries the show's name and poster; /watch/ is behind the login.
                SourceType.VIDEO -> openGraph(netflixId(link)?.takeIf { videoApp(link)?.first == PKG_NETFLIX }?.let { "https://www.netflix.com/title/$it" } ?: link)
                else -> openGraph(link)
            }
        } catch (_: Exception) {
            LinkMetadata(null, null, null)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) BoopBox/1.0")
            .build()
        return client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    private fun oembed(url: String): LinkMetadata {
        val body = get(url) ?: return LinkMetadata(null, null, null)
        val json = JSONObject(body)
        return LinkMetadata(
            title = json.optString("title").ifBlank { null },
            artworkUrl = json.optString("thumbnail_url").ifBlank { null },
            author = json.optString("author_name").ifBlank { null }
        )
    }

    private fun openGraph(link: String): LinkMetadata {
        val l = link.trim()
        if (!l.startsWith("http")) return LinkMetadata(null, null, null)
        val html = get(l) ?: return LinkMetadata(null, null, null)
        fun meta(prop: String): String? {
            val patterns = listOf(
                Regex("<meta[^>]+property=[\"']$prop[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']$prop[\"']", RegexOption.IGNORE_CASE),
                Regex("<meta[^>]+name=[\"']$prop[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            )
            return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
        }
        val title = meta("og:title")
            ?: Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        return LinkMetadata(
            title = title?.replace("&amp;", "&")?.trim(),
            artworkUrl = meta("og:image"),
            author = meta("og:site_name")
        )
    }
}
