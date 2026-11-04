package com.cardplayer.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Quick artwork lookup through the public iTunes Search API (songs, albums, podcasts, audiobooks). */
object ArtworkSearch {
    data class Result(val title: String, val subtitle: String, val url: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(term: String): List<Result> = withContext(Dispatchers.IO) {
        val q = term.trim()
        if (q.isBlank()) return@withContext emptyList()
        val url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(q, "UTF-8") + "&media=all&limit=30"
        val request = Request.Builder().url(url).header("User-Agent", "BoopBox/1.0").build()
        val body = try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()

        val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val art = o.optString("artworkUrl100").ifBlank { null } ?: return@mapNotNull null
            Result(
                title = o.optString("trackName").ifBlank { o.optString("collectionName") },
                subtitle = o.optString("artistName"),
                url = art.replace("100x100bb", "600x600bb")
            )
        }.distinctBy { it.url }
    }
}
