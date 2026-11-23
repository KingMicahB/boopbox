package com.cardplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.cardplayer.BoopBoxApp
import com.cardplayer.R
import com.cardplayer.data.Card
import com.cardplayer.data.SourceType
import com.cardplayer.util.MediaScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the car screen (Android Auto) can browse: the grown-up in the front seat picks a card there, as if the
 * child had tapped it on the phone.
 *
 *  root
 *   ├─ Cards: every card that plays inside BoopBox, last played first. Picking one plays it.
 *   └─ Songs & chapters: the cards that hold several songs; opening one lists them, picking one plays from there.
 *
 * Cards that play in another app (Spotify, Netflix…) are left out: BoopBox cannot play those through the car.
 */
class CarLibrary(private val app: BoopBoxApp) {

    private class Track(val mediaId: String?, val positionMs: Long, val title: String, val artKey: String)

    fun root(): MediaItem = folder(ROOT, app.getString(R.string.app_name), null, null)

    /**
     * Cards are a grid on the car screen, each a picture with only its name under it: the nearest a car screen
     * comes to the phone's card picker (a media app gets a list or a grid there, no carousel, and the car sizes
     * the tiles). One look, whatever the picker on the phone is set to.
     */
    private fun cardStyleExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    /** Extras for the root: the look of the lists of cards. */
    fun rootExtras(): Bundle = cardStyleExtras()

    /** The items under [parentId], or null when there is no such place. */
    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(TAB_CARDS, app.getString(R.string.car_tab_cards), resourceUri(R.drawable.ic_car_cards), cardStyleExtras()),
            folder(TAB_SONGS, app.getString(R.string.car_tab_songs), resourceUri(R.drawable.ic_car_songs), cardStyleExtras())
        )
        parentId == TAB_CARDS -> playableCards().map { cardItem(it) }
        parentId == TAB_SONGS -> playableCards().filter { hasSongList(it) }.map { card ->
            // The songs inside a card read better as a list than as a grid.
            val listStyle = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            folder(IN_CARD + card.uid, card.displayName(app), cardArt(card), listStyle)
        }
        parentId.startsWith(IN_CARD) -> app.cards.get(parentId.removePrefix(IN_CARD))?.let { card ->
            tracksOf(card).map { t ->
                playable(
                    id = TRACK + card.uid + "|" + t.positionMs + "|" + (t.mediaId ?: ""),
                    title = t.title,
                    subtitle = card.displayName(app),
                    art = trackArt(card, t.artKey)
                )
            }
        }
        else -> null
    }

    fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId.startsWith(PLAY) -> app.cards.get(mediaId.removePrefix(PLAY))?.let { cardItem(it) }
        else -> null
    }

    /** Plays what was picked on the car screen. False when [mediaId] is not (or no longer) something of ours. */
    fun play(mediaId: String): Boolean {
        when {
            mediaId.startsWith(PLAY) -> {
                val card = app.cards.get(mediaId.removePrefix(PLAY))?.takeIf { playsHere(it) } ?: return false
                app.player.playFromCar(card)
            }
            mediaId.startsWith(TRACK) -> {
                val parts = mediaId.removePrefix(TRACK).split("|", limit = 3)
                if (parts.size < 3) return false
                val card = app.cards.get(parts[0])?.takeIf { playsHere(it) } ?: return false
                app.player.playCardFrom(card, parts[2].ifEmpty { null }, parts[1].toLongOrNull() ?: 0L)
            }
            else -> return false
        }
        return true
    }

    /** "Play <something> on BoopBox": the card whose name fits best, else the one played last. */
    fun playSearch(query: String?): Boolean {
        val cards = playableCards()
        val q = query?.trim()?.lowercase().orEmpty()
        val card = if (q.isEmpty()) null else {
            cards.firstOrNull { it.displayName(app).lowercase() == q }
                ?: cards.firstOrNull { it.displayName(app).lowercase().contains(q) }
                ?: cards.firstOrNull { name -> name.displayName(app).lowercase().let { it.length > 2 && q.contains(it) } }
        }
        app.player.playFromCar(card ?: cards.firstOrNull() ?: return false)
        return true
    }

    /** The car pressed play with nothing loaded: carry on with the card played last. */
    fun resumeLast() {
        if (!CarLink.connected) return
        val card = playableCards().firstOrNull { it.lastPlayedAt > 0L } ?: return
        app.player.playFromCar(card)
    }

    // ---------------------------------------------------------------- cards

    private fun playsHere(card: Card): Boolean =
        !card.sourceType.isExternal || app.player.youtubeCache.localTracks(card) != null

    private fun playableCards(): List<Card> = app.cards.cards.value
        .filter { playsHere(it) }
        .sortedWith(compareByDescending<Card> { it.lastPlayedAt }.thenByDescending { it.createdAt })

    private fun hasSongList(card: Card): Boolean = when {
        card.sourceType.isExternal -> false
        card.chapters.size > 1 -> true
        card.sourceType == SourceType.PLAYLIST -> card.playlist.size > 1
        else -> card.sourceType == SourceType.LOCAL_FOLDER || card.sourceType == SourceType.PODCAST
    }

    private suspend fun tracksOf(card: Card): List<Track> {
        val tracks: List<Track> = when (card.sourceType) {
            SourceType.LOCAL_FILE -> listOf(Track(null, 0L, card.displayName(app), card.uri))
            SourceType.PLAYLIST -> card.playlist.map { Track(it.uri, 0L, it.title, it.uri) }
            SourceType.LOCAL_FOLDER -> withContext(Dispatchers.IO) { MediaScan.folderTracks(app, card.uri) }
                .map { Track(it.uri, 0L, it.title, it.uri) }
            SourceType.PODCAST -> app.player.podcastCache.feedFor(card.uri)?.episodes.orEmpty()
                .take(MAX_EPISODES)
                .map { Track(it.url, 0L, it.title, it.url) }
            else -> emptyList()
        }
        // One file split into songs / chapters: those are its tracks.
        if (tracks.size == 1 && card.chapters.size > 1) {
            return card.chapters.map { Track(tracks[0].mediaId, it.startMs, it.title, it.artKey) }
        }
        return tracks
    }

    // ---------------------------------------------------------------- items

    private fun cardItem(card: Card): MediaItem = playable(
        id = PLAY + card.uid,
        title = card.displayName(app),
        // Picture first: nothing under the picture but the name.
        art = cardArt(card)
    )

    private fun playable(id: String, title: String, subtitle: String? = null, art: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setArtworkUri(art)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun folder(id: String, title: String, art: Uri?, extras: Bundle?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(art)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(extras)
                    .build()
            )
            .build()

    /** A card's picture: its sticker when it has one that is still there, else its plain picture (see CardArtProvider). */
    private fun cardArt(card: Card): Uri =
        CardArtProvider.cardUri(
            app,
            card.uid,
            artVersion(card, card.stickerUrl + "|" + CardArtProvider.stickerStamp(card.stickerUrl) + "|" + card.artworkUrl)
        )

    private fun trackArt(card: Card, artKey: String): Uri =
        CardArtProvider.trackUri(app, card.uid, artKey, artVersion(card, card.trackArt[artKey] ?: card.artworkUrl))

    /** Changes when the picture drawn for [source] would look different. */
    private fun artVersion(card: Card, source: String?): String =
        Integer.toHexString("${CardArtProvider.LOOK}|$source|${card.color}|${app.settings.value.pixelArt}".hashCode())

    /**
     * Everything about the cards that the car screen shows, except when each was last played: when this changes
     * the lists on the car screen are stale. (The order is left alone while someone is looking at it.)
     */
    fun fingerprint(cards: List<Card>): Int = cards.filter { playsHere(it) }
        .map { listOf(it.uid, it.name, it.subtitle, it.artworkUrl, it.stickerUrl, it.color, it.chapters, it.playlist, it.trackArt) }
        .hashCode()

    private fun resourceUri(id: Int): Uri =
        Uri.parse("android.resource://" + app.packageName + "/" + app.resources.getResourceTypeName(id) + "/" + app.resources.getResourceEntryName(id))

    companion object {
        private const val ROOT = "boopbox:root"
        private const val TAB_CARDS = "boopbox:cards"
        private const val TAB_SONGS = "boopbox:songs"

        /** The lists that show cards: the ones to refresh when cards are added, renamed or removed. */
        val CARD_LISTS = listOf(TAB_CARDS, TAB_SONGS)
        private const val PLAY = "boopbox:play:"
        private const val IN_CARD = "boopbox:in:"
        private const val TRACK = "boopbox:track:"
        private const val MAX_EPISODES = 50

        /** True for the ids this library hands out (as opposed to the track ids of a queue that is playing). */
        fun isLibraryId(mediaId: String): Boolean = mediaId.startsWith("boopbox:")
    }
}
