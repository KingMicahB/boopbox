package com.cardplayer.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the media session shows to the outside world (car screen, lock screen, headset buttons).
 * It makes those behave like the BoopBox player screen:
 *  - one file split into songs / chapters: next and previous move between chapters, and the chapter's own
 *    title and picture are what is "now playing";
 *  - several tracks: next / previous wrap around, and previous never just restarts the track;
 *  - a card with one song has no next / previous at all;
 *  - "play" with nothing loaded asks [onPlayEmpty] (the car pressing play before any card was tapped).
 *
 * What a card's chapters are travels with the media item (see [tag]), so the service needs no other state.
 */
@OptIn(UnstableApi::class)
class CardPlayer(private val exo: Player, private val onPlayEmpty: () -> Unit) : ForwardingPlayer(exo) {

    private val handler = Handler(exo.applicationLooper)
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    /** Media id + chapter last announced, so every chapter is announced once. */
    private var announced: String? = null

    private val chapterWatch = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            announceChapter()
            if (exo.isPlaying && chapterStarts() != null) handler.postDelayed(this, 500)
        }
    }

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED
                    )
                ) handler.post(chapterWatch)
            }
        })
    }

    // ---------------------------------------------------------------- chapters

    private fun extras(): Bundle? = exo.currentMediaItem?.requestMetadata?.extras

    /** Where each chapter of the one file being played starts (player positions), or null when it has none. */
    private fun chapterStarts(): LongArray? =
        if (exo.mediaItemCount == 1) extras()?.getLongArray(KEY_CHAPTER_STARTS)?.takeIf { it.size > 1 } else null

    private fun chapterAt(starts: LongArray): Int = starts.indexOfLast { it <= exo.currentPosition }.coerceAtLeast(0)

    /** Tells the session (and with it the car) that another chapter began: the wrapped player sees no change. */
    private fun announceChapter() {
        val starts = chapterStarts()
        val key = if (starts == null) null else exo.currentMediaItem?.mediaId + "#" + chapterAt(starts)
        if (key == announced) return
        announced = key
        if (key == null) return
        val metadata = mediaMetadata
        listeners.forEach { it.onMediaMetadataChanged(metadata) }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val extras = extras() ?: return base
        val out = base.buildUpon()
        var title = base.title
        chapterStarts()?.let { starts ->
            val i = chapterAt(starts)
            extras.getStringArray(KEY_CHAPTER_TITLES)?.getOrNull(i)?.let {
                title = it
                out.setTitle(it)
            }
            extras.getStringArray(KEY_CHAPTER_ART)?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                out.setArtworkData(null, null).setArtworkUri(Uri.parse(it))
            }
        }
        // Second line on the car screen / notification: which card this song belongs to.
        val cardName = extras.getString(KEY_CARD_NAME)
        if (cardName != null && base.artist == null && cardName != title?.toString()) out.setArtist(cardName)
        return out.build()
    }

    // ---------------------------------------------------------------- next / previous

    private fun canSkip(): Boolean = chapterStarts() != null || exo.mediaItemCount > 1

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands().buildUpon()
        val skip = canSkip()
        SKIP_COMMANDS.forEach { if (skip) commands.add(it) else commands.remove(it) }
        return commands.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (command in SKIP_COMMANDS) canSkip() else super.isCommandAvailable(command)

    override fun seekToNext() = skip(+1)
    override fun seekToNextMediaItem() = skip(+1)
    override fun seekToPrevious() = skip(-1)
    override fun seekToPreviousMediaItem() = skip(-1)

    private fun skip(delta: Int) {
        val starts = chapterStarts()
        if (starts != null) {
            exo.seekTo(starts[(chapterAt(starts) + delta).mod(starts.size)])
            exo.play()
            return
        }
        val count = exo.mediaItemCount
        if (count <= 1) return
        if (delta > 0) {
            if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() else exo.seekTo(0, 0L)
        } else {
            if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem() else exo.seekTo(count - 1, 0L)
        }
        exo.play()
    }

    // ---------------------------------------------------------------- play with nothing loaded

    override fun prepare() {
        // Preparing an empty queue would only report "ended" to the app, which reads that as "the card finished".
        if (exo.mediaItemCount > 0) super.prepare()
    }

    override fun play() {
        if (exo.mediaItemCount == 0) handler.post(onPlayEmpty) else super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && exo.mediaItemCount == 0) handler.post(onPlayEmpty) else super.setPlayWhenReady(playWhenReady)
    }

    fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        listeners.clear()
    }

    companion object {
        private const val KEY_CARD_NAME = "boopbox.cardName"
        private const val KEY_CHAPTER_STARTS = "boopbox.chapterStarts"
        private const val KEY_CHAPTER_TITLES = "boopbox.chapterTitles"
        private const val KEY_CHAPTER_ART = "boopbox.chapterArt"

        private val SKIP_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )

        /**
         * Marks [item] as belonging to the card called [cardName]. For one file split into chapters also pass where
         * each chapter starts (player positions, so after any start cut), its title and its picture ("" = none).
         */
        fun tag(
            item: MediaItem,
            cardName: String,
            chapterStarts: LongArray? = null,
            chapterTitles: Array<String>? = null,
            chapterArt: Array<String>? = null
        ): MediaItem {
            val extras = Bundle().apply {
                putString(KEY_CARD_NAME, cardName)
                if (chapterStarts != null) {
                    putLongArray(KEY_CHAPTER_STARTS, chapterStarts)
                    putStringArray(KEY_CHAPTER_TITLES, chapterTitles)
                    putStringArray(KEY_CHAPTER_ART, chapterArt)
                }
            }
            return item.buildUpon()
                .setRequestMetadata(item.requestMetadata.buildUpon().setExtras(extras).build())
                .build()
        }
    }
}
