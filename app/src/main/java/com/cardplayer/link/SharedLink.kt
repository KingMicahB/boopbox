package com.cardplayer.link

import android.content.Context
import com.cardplayer.data.Card
import com.cardplayer.data.SourceType

/**
 * A link shared to BoopBox from YouTube, Spotify, Audible… made into a card, filled in the way the editor
 * fills a pasted link: title, picture, author and, for a long video, its songs.
 */
object SharedLink {
    suspend fun toCard(context: Context, link: String): Card {
        val type = LinkResolver.detect(link)
        // A playlist without a video doesn't autoplay: start it at its first video.
        val playable = LinkResolver.resolvePlayableLink(type, link)
        // A whole album / playlist: named, pictured and credited after the list, not its first song,
        // and that song's chapters don't belong on it. The songs come with the downloaded list.
        val wholeList = LinkResolver.isWholeList(type, link)
        val meta = (if (wholeList) LinkResolver.fetchListMetadata(playable) else null) ?: LinkResolver.fetchMetadata(type, playable)
        val isVideo = type == SourceType.YOUTUBE || type == SourceType.YOUTUBE_MUSIC
        val scan = if (isVideo && !wholeList) runCatching { YouTubeChapters.scan(playable) }.getOrNull() else null
        return Card(
            uid = "",
            name = meta.title ?: scan?.videoTitle ?: context.getString(type.labelRes),
            sourceType = type,
            uri = playable,
            artworkUrl = meta.artworkUrl,
            subtitle = meta.author,
            chapters = scan?.chapters ?: emptyList()
        )
    }
}
