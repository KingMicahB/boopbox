package com.cardplayer.podcast

import android.util.Xml
import com.cardplayer.data.PodcastEpisode
import com.cardplayer.data.PodcastFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

object RssParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(feedUrl: String): PodcastFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(feedUrl.trim())
            .header("User-Agent", "BoopBox/1.0 (Android)")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Feed returned HTTP ${response.code}")
            response.body?.string() ?: throw IllegalStateException("Empty feed")
        }
        parse(body)
    }

    fun parse(xml: String): PodcastFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedImage: String? = null
        val episodes = mutableListOf<PodcastEpisode>()

        var inItem = false
        var inImageBlock = false
        var title: String? = null
        var url: String? = null
        var guid: String? = null
        var pubDate: String? = null
        var duration: String? = null
        var image: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val ns = parser.namespace ?: ""
                    val isItunes = ns.contains("itunes", ignoreCase = true)
                    when {
                        name == "item" -> {
                            inItem = true
                            title = null; url = null; guid = null; pubDate = null; duration = null; image = null
                        }
                        name == "image" && !inItem && !isItunes -> inImageBlock = true
                        name == "image" && isItunes -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (inItem) image = href else if (feedImage == null) feedImage = href
                        }
                        name == "enclosure" && inItem -> {
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            val href = parser.getAttributeValue(null, "url")
                            if (url == null || type.startsWith("audio")) url = href
                        }
                        name == "title" -> {
                            val text = parser.nextText().trim()
                            if (inItem) title = text else if (!inImageBlock && feedTitle.isEmpty()) feedTitle = text
                        }
                        name == "url" && inImageBlock -> {
                            val text = parser.nextText().trim()
                            if (feedImage == null) feedImage = text
                        }
                        name == "guid" && inItem -> guid = parser.nextText().trim()
                        name == "pubDate" && inItem -> pubDate = parser.nextText().trim()
                        name == "duration" && inItem && isItunes -> duration = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            inItem = false
                            val u = url
                            if (u != null) {
                                episodes += PodcastEpisode(
                                    title = title ?: "Episode",
                                    url = u,
                                    guid = guid,
                                    pubDate = pubDate,
                                    durationText = duration,
                                    artworkUrl = image
                                )
                            }
                        }
                        "image" -> inImageBlock = false
                    }
                }
            }
            event = parser.next()
        }
        return PodcastFeed(title = feedTitle.ifBlank { "Podcast" }, artworkUrl = feedImage, episodes = episodes)
    }
}
