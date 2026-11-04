package com.cardplayer.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pixel icons from yotoicons.com, the community library of 16x16 icons made for Yoto cards.
 * The site has no API; its tag page embeds one `populate_icon_modal(...)` call per icon with
 * id, category, tags and author, which is all we need.
 */
object YotoIcons {
    const val HOST = "yotoicons.com"

    data class Icon(
        val id: String,
        val url: String,
        val category: String,
        val tags: String,
        val author: String,
        val downloads: Int
    )

    data class Page(val icons: List<Icon>, val total: Int, val page: Int) {
        val hasMore: Boolean get() = icons.size >= 25
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // populate_icon_modal('278', 'animals', 'dinosaur', 't-rex', 'pangolinpaw', '2944');
    private val modalRegex = Regex(
        """populate_icon_modal\('([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)'\)"""
    )
    private val totalRegex = Regex("""We've got (\d+) icons""")

    fun isIconUrl(url: String?): Boolean = url?.contains(HOST) == true

    fun iconUrl(id: String) = "https://$HOST/static/uploads/$id.png"

    suspend fun search(term: String, page: Int = 1, newest: Boolean = false): Page = withContext(Dispatchers.IO) {
        val q = term.trim().lowercase()
        if (q.isBlank()) return@withContext Page(emptyList(), 0, page)
        val url = "https://$HOST/icons?tag=" + URLEncoder.encode(q, "UTF-8") +
            "&page=$page&type=singles" + (if (newest) "&sort=new" else "")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) BoopBox/1.0")
            .build()
        val html = try {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        } ?: return@withContext Page(emptyList(), 0, page)

        val icons = modalRegex.findAll(html).map { m ->
            val (id, category, tag1, tag2, author, downloads) = m.destructured
            Icon(
                id = id,
                url = iconUrl(id),
                category = category,
                tags = listOf(tag1, tag2).filter { it.isNotBlank() }.joinToString(", "),
                author = author,
                downloads = downloads.toIntOrNull() ?: 0
            )
        }.distinctBy { it.id }.toList()
        val total = totalRegex.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: icons.size
        Page(icons, total, page)
    }
}
