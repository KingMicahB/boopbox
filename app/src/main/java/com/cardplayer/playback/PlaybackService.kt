package com.cardplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.cardplayer.BoopBoxApp
import com.cardplayer.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground media service that owns the ExoPlayer. Plays local files, folders,
 * podcast episodes and plain audio streams. The UI talks to it through a MediaController.
 *
 * It is also the media library Android Auto browses: the car screen lists the cards ([CarLibrary]) and
 * controls playback through the same session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var cardPlayer: CardPlayer? = null
    private lateinit var library: CarLibrary
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        library = CarLibrary(BoopBoxApp.get(this))

        // Podcast episodes reach their audio through chains of tracking redirects that often switch between
        // http and https; ExoPlayer refuses those by default, which left episodes buffering forever.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BoopBox/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val player = CardPlayer(exo, onPlayEmpty = { library.resumeLast() })
        cardPlayer = player

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session

        // A card added, renamed or given a new picture: the car screen reloads its lists.
        scope.launch {
            BoopBoxApp.get(this@PlaybackService).cards.cards
                .map { library.fingerprint(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect { CarLibrary.CARD_LISTS.forEach { list -> session.notifyChildrenChanged(list, Int.MAX_VALUE, null) } }
        }
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        // MediaItems lose their URI when they cross the controller/session boundary,
        // so rebuild it from the request metadata / media id we set on the controller side.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /**
         * The car (or the assistant) asks for a card, a song of a card, or "play <name>". Only the
         * PlayerController knows how to start a card (resume spot, sleep timer, what the phone shows), so the
         * request is passed to it and declined here; the controller then sets the real queue as it always does.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.firstOrNull()
            val ours = when {
                first == null -> false
                CarLibrary.isLibraryId(first.mediaId) -> {
                    library.play(first.mediaId)
                    true
                }
                // No id and no uri: a voice search ("play Frozen on BoopBox"), possibly an empty one.
                first.mediaId.isEmpty() && first.requestMetadata.mediaUri == null -> {
                    library.playSearch(first.requestMetadata.searchQuery)
                    true
                }
                else -> false
            }
            if (ours) return Futures.immediateFailedFuture(UnsupportedOperationException("started by the PlayerController"))
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(library.root(), LibraryParams.Builder().setExtras(library.rootExtras()).build())
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = library.item(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val all = try {
                    library.children(parentId)
                } catch (_: Exception) {
                    null
                }
                if (all == null) {
                    future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                } else {
                    val from = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(all.size.toLong()).toInt()
                    val to = (from.toLong() + pageSize.coerceAtLeast(1)).coerceAtMost(all.size.toLong()).toInt()
                    future.set(LibraryResult.ofItemList(all.subList(from, to), params))
                }
            }
            return future
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cardPlayer?.shutDown()
        cardPlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
