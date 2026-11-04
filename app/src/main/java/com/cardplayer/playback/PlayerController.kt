package com.cardplayer.playback

import android.app.ActivityManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cardplayer.MainActivity
import com.cardplayer.Notice
import com.cardplayer.R
import com.cardplayer.billing.Plus
import com.cardplayer.data.Card
import com.cardplayer.data.CardRepository
import com.cardplayer.data.Clip
import com.cardplayer.data.PodcastMode
import com.cardplayer.data.PositionStore
import com.cardplayer.data.SettingsRepository
import com.cardplayer.data.SourceType
import com.cardplayer.link.LinkResolver
import com.cardplayer.link.YouTubeAudioCache
import com.cardplayer.podcast.PodcastCache
import com.cardplayer.util.MediaScan
import com.cardplayer.yoto.YotoClient
import com.cardplayer.yoto.yotoPrettyTitle
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.cardplayer.util.Bedtime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class NowPlaying(
    val card: Card,
    val title: String,
    /** Artwork for the current track (not the card): URL or content uri, if the track has one. */
    val artworkUrl: String?,
    val isExternal: Boolean,
    val isPlaying: Boolean,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val status: String? = null,
    /** Embedded cover art read from the file's tags, if any. */
    val artworkBytes: ByteArray? = null,
    /** Artwork reported by another app's media session (needs notification access). */
    val artworkBitmap: Bitmap? = null,
    /** Stable per-track key used to generate a pixel sprite when there is no artwork. */
    val trackSeed: String = card.uid,
    val trackIndex: Int = 0,
    val trackCount: Int = 1
)

/**
 * One place that knows how to "play a card":
 *  - local files / folders / podcasts / streams go through our own ExoPlayer service
 *  - Spotify / YouTube / Audible / other apps are launched by deep link, then BoopBox
 *    brings itself back to the front and controls them through their media session
 *    (with notification access) or media key events.
 */
class PlayerController(
    private val app: Application,
    private val cards: CardRepository,
    private val settings: SettingsRepository,
    private val positions: PositionStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val mediaSessionManager = app.getSystemService(MediaSessionManager::class.java)

    /** Feed + newest-episode cache for podcast cards. */
    val podcastCache = PodcastCache(app)

    /** Downloaded audio for YouTube / YouTube Music cards (played here instead of in the app once it is on the phone). */
    val youtubeCache = YouTubeAudioCache(app)

    /** Yoto account + API for Yoto cards. */
    val yoto = YotoClient(app)

    /** The card being played asked for "no autoadvance" (Yoto Daily): stop after the current chapter. */
    private var stopAfterItem = false

    private fun yotoGroup(card: Card) = "yoto:" + card.uri

    /**
     * Keeps a Yoto card playable offline: podcast cards (Yoto Daily) keep only the newest episode,
     * everything else keeps all its tracks.
     */
    suspend fun downloadYoto(card: Card, detail: YotoClient.CardDetail) {
        val group = yotoGroup(card)
        val chapters = if (detail.category == "podcast") detail.chapters.take(1) else detail.chapters
        val wanted = chapters.flatMap { ch -> ch.tracks.map { t -> (ch.key + "/" + t.key) to t.trackUrl } }
        val keepOnly = if (detail.category == "podcast") wanted.map { it.first }.toSet() else null
        podcastCache.ensureKeyed(group, wanted, keepOnly)
    }

    private var lastYotoRefresh = 0L

    /** Background: re-check every Yoto card and download what it needs (throttled). */
    fun refreshYotoAsync(all: List<Card>, minIntervalMs: Long = 30 * 60_000L) {
        if (!settings.value.podcastAutoDownload) return
        val playable = all.filter { it.sourceType == SourceType.YOTO && (yoto.isConnected || YotoClient.isCardLink(it.uri)) }
        if (playable.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastYotoRefresh < minIntervalMs) return
        lastYotoRefresh = now
        scope.launch(Dispatchers.IO) {
            playable.forEach { card ->
                try {
                    yoto.card(card.uri)?.let { downloadYoto(card, it) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val messages: SharedFlow<Notice> = _messages.asSharedFlow()

    /** (current level, highest level allowed by the volume limit). */
    private val _volume = MutableStateFlow(readVolume())
    val volume: StateFlow<Pair<Int, Int>> = _volume.asStateFlow()

    private var currentCard: Card? = null
    /** Where the next local start begins instead of the saved position (track media id, position). */
    private var startAt: Pair<String?, Long>? = null
    /** Start cut of the one file our player plays (0 = none, or several files): file position = player position + this. */
    private var itemOffsetMs = 0L
    /** Bumped on every external start: a guessed end-cut pause from an earlier tap then does nothing. */
    private var clipStopToken = 0
    /** When our own player was last paused (elapsedRealtime), 0 while playing. */
    private var pausedAt = 0L
    private var externalPlaying = false
    private var progressJob: Job? = null
    private var pendingReturn: Runnable? = null
    private var playJob: Job? = null

    /** Task id of MainActivity, used to bring it back to the front. */
    @Volatile
    var mainTaskId: Int = -1

    /** Things only the Activity can do for us. */
    interface ActivityHooks {
        /** Shrink into a picture-in-picture bubble so we stay "visible" while another app opens. */
        fun enterPip(): Boolean
        /** Android screen pinning on/off. */
        fun setChildLock(on: Boolean)
        fun isChildLocked(): Boolean
    }

    // ---------------------------------------------------------------- sleep timer

    /** Epoch millis when the sleep timer pauses playback, or null. */
    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()

    /** Pause when the current song / episode ends. */
    private val _sleepAtTrackEnd = MutableStateFlow(false)
    val sleepAtTrackEnd: StateFlow<Boolean> = _sleepAtTrackEnd.asStateFlow()

    private var sleepJob: Job? = null
    private var streamVolumeBeforeFade: Int? = null

    fun setSleepTimer(minutes: Int) {
        restoreAfterFade()
        _sleepAtTrackEnd.value = false
        _sleepEndsAt.value = System.currentTimeMillis() + minutes * 60_000L
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (true) {
                val end = _sleepEndsAt.value ?: break
                val remaining = end - System.currentTimeMillis()
                if (remaining <= 0) {
                    goToSleep(app.getString(R.string.msg_sleep_timer_finished))
                    break
                }
                // Last 20 seconds: fade out gently.
                if (remaining <= 20_000) applyFade(remaining / 20_000f)
                delay(1000)
            }
        }
    }

    fun setSleepAtTrackEnd() {
        sleepJob?.cancel()
        restoreAfterFade()
        _sleepEndsAt.value = null
        _sleepAtTrackEnd.value = true
    }

    fun cancelSleep() {
        sleepJob?.cancel()
        _sleepEndsAt.value = null
        _sleepAtTrackEnd.value = false
        restoreAfterFade()
    }

    private fun applyFade(fraction: Float) {
        val f = fraction.coerceIn(0.05f, 1f)
        if (!externalPlaying) {
            controller?.volume = f
        } else {
            val base = streamVolumeBeforeFade ?: audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also { streamVolumeBeforeFade = it }
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (base * f).roundToInt().coerceAtLeast(1), 0)
            } catch (_: Exception) {
            }
        }
    }

    private fun restoreAfterFade() {
        controller?.volume = 1f
        streamVolumeBeforeFade?.let { v ->
            streamVolumeBeforeFade = null
            // Give the other app a moment to actually pause before the volume comes back.
            mainHandler.postDelayed({
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceAtMost(volumeBounds().second), 0)
                } catch (_: Exception) {
                }
                _volume.value = readVolume()
            }, 800)
        }
    }

    private fun goToSleep(why: String) {
        pause()
        _sleepEndsAt.value = null
        _sleepAtTrackEnd.value = false
        restoreAfterFade()
        _messages.tryEmit(Notice(why))
    }

    // ---------------------------------------------------------------- card lifted off

    private var removalRunnable: Runnable? = null

    /**
     * The playing card was tapped and still lies on the phone. Only known for cards whose lifting stops or
     * pauses them: a "keep playing" card asks for no removal events, so it never counts as lying there.
     */
    private var cardOnPhone = false

    /** The card the toddler is listening to was lifted off; act after the grace period. */
    fun onCardRemoved(uid: String) {
        val s = settings.value
        val card = currentCard?.takeIf { it.uid == uid } ?: return
        cardOnPhone = false
        val mode = card.removedMode(s)
        if (mode == "nothing") return
        cancelRemoval()
        val r = Runnable {
            removalRunnable = null
            if (currentCard?.uid == uid) {
                if (mode == "stop") stop() else pause()
            }
        }
        removalRunnable = r
        mainHandler.postDelayed(r, s.cardRemovedGraceSeconds.coerceIn(0, 30) * 1000L)
    }

    private fun cancelRemoval() {
        removalRunnable?.let { mainHandler.removeCallbacks(it) }
        removalRunnable = null
    }

    @Volatile
    var hooks: ActivityHooks? = null

    // ---------------------------------------------------------------- public API

    /** [tapped] = the card itself was put on the phone (not "play now" from settings). */
    fun playCard(card: Card, tapped: Boolean = true) {
        val s = settings.value
        val cur = currentCard
        cardOnPhone = tapped && card.removedMode(s) != "nothing"
        // Card lifted and put straight back: just keep going.
        if (removalRunnable != null && cur?.uid == card.uid) {
            cancelRemoval()
            return
        }
        // The card already playing in another app, seen again: NFC reader mode restarts every time we come back
        // to the front, so a card left on the phone "taps" again. Relaunching the app would bounce between the
        // two forever (and a toggle would pause it on every return).
        if (externalPlaying && cur?.uid == card.uid && _nowPlaying.value?.isExternal == true) {
            cancelRemoval()
            return
        }
        if (s.sameCardToggles && cur?.uid == card.uid && _nowPlaying.value != null) {
            togglePlayPause()
            return
        }
        startAt = null
        startCard(card)
    }

    /**
     * Plays [card] from a chosen spot (the chapter list in the card editor), whatever was saved:
     * [mediaId] is the track (null = the first one), [positionMs] the place inside it.
     */
    fun playCardFrom(card: Card, mediaId: String?, positionMs: Long) {
        cancelRemoval()
        cardOnPhone = false
        startAt = mediaId to positionMs
        startCard(card)
    }

    private fun startCard(card: Card) {
        val s = settings.value
        val cur = currentCard
        playJob?.cancel()
        playJob = scope.launch {
            _busy.value = true
            try {
                applyVolumeCap()
                when {
                    // A bedtime-story card brings its own timer, restarted on every fresh tap.
                    card.sleepMinutes > 0 && Plus.isActive -> setSleepTimer(card.sleepMinutes)
                    Bedtime.isBedtime(s) && s.bedtimeSleepMinutes > 0 && _sleepEndsAt.value == null && !_sleepAtTrackEnd.value ->
                        setSleepTimer(s.bedtimeSleepMinutes)
                }
                saveCurrentPosition()
                if (externalPlaying) {
                    pauseExternal(cur?.sourceType)
                    externalPlaying = false
                }
                cancelPendingReturn()
                currentCard = card
                // Remember the tap: the card list in settings can sort by "recently played".
                cards.get(card.uid)?.let { cards.upsert(it.copy(lastPlayedAt = System.currentTimeMillis())) }
                if (youtubeCache.localTracks(card) != null) {
                    // A YouTube card whose audio is on the phone plays right here: offline, in the background, no app switch.
                    playLocal(card)
                } else if (card.sourceType.isExternal) {
                    startAt = null
                    // Not downloaded yet: open the app this time, fetch the audio for the next tap.
                    if (settings.value.youtubeAutoCache) youtubeCache.ensureAsync(card)
                    playExternal(card)
                } else {
                    playLocal(card)
                }
            } catch (e: Exception) {
                _nowPlaying.value = null
                currentCard = null
                _messages.tryEmit(
                    Notice(
                        app.getString(R.string.msg_cant_play_card, card.displayName(app), e.message ?: app.getString(R.string.msg_unknown_error)),
                        error = true
                    )
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun togglePlayPause() {
        val np = _nowPlaying.value ?: return
        if (np.isExternal) {
            val session = externalSession(np.card.sourceType)
            if (session != null) {
                if (isSessionPlaying(session)) session.transportControls.pause() else session.transportControls.play()
            } else {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                _nowPlaying.update { it?.copy(isPlaying = !it.isPlaying) }
            }
        } else {
            val c = controller ?: return
            if (c.isPlaying) {
                c.pause()
            } else {
                stepBackAfterBreak(c)
                c.play()
            }
        }
    }

    /** A "continue where it left off" card carrying on after a pause replays the last few seconds. */
    private fun stepBackAfterBreak(c: MediaController) {
        if (currentCard?.resume != true || pausedAt == 0L) return
        val back = resumeRewindMs(SystemClock.elapsedRealtime() - pausedAt)
        if (back > 0) c.seekTo((c.currentPosition - back).coerceAtLeast(0L))
    }

    /** The longer the break, the more of the story to hear again, so a listener picks up the thread. */
    private fun resumeRewindMs(breakMs: Long): Long = when {
        breakMs < 10_000 -> 0L
        breakMs < 5 * 60_000 -> 5_000L
        breakMs < 60 * 60_000 -> 15_000L
        else -> 30_000L
    }

    fun pause() {
        val np = _nowPlaying.value ?: return
        if (np.isExternal) {
            pauseExternal(np.card.sourceType)
            _nowPlaying.update { it?.copy(isPlaying = false) }
        } else {
            controller?.pause()
        }
    }

    fun stop() {
        cancelPendingReturn()
        saveCurrentPosition()
        if (externalPlaying) {
            pauseExternal(currentCard?.sourceType)
            externalPlaying = false
        }
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        progressJob?.cancel()
        _nowPlaying.value = null
        currentCard = null
        cardOnPhone = false
    }

    /** Back on the player: no card lying on the phone means back to the home screen, music off. */
    fun backToHome() {
        if (_nowPlaying.value == null || cardOnPhone) return
        playJob?.cancel()
        stop()
    }

    /** A card that holds one song only: next/previous must not wander off into other music. */
    private fun isSingleTrackCard(np: NowPlaying): Boolean = when {
        np.card.chapters.size > 1 -> false
        np.isExternal -> LinkResolver.isSingleTrack(np.card.sourceType, np.card.uri)
        else -> np.trackCount <= 1
    }

    /** Best guess of the other app's current position (its reported position plus elapsed time). */
    private fun sessionPositionMs(session: android.media.session.MediaController): Long {
        val st = session.playbackState ?: return 0L
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0L)
    }

    private fun chapterIndexAt(card: Card, positionMs: Long): Int =
        card.chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /** The Plus start / end cut of one track, or null (no cut, or no Plus). */
    private fun clipOf(card: Card, trackKey: String): Clip? = if (Plus.isActive) card.clipFor(trackKey) else null

    /**
     * Where song [idx] of a split file / video really plays, in ms from the start of the file: its chapter,
     * kept inside the card's start / end cut. [durationMs] 0 = not known yet (end then 0 for the last song).
     */
    private fun chapterSpan(card: Card, idx: Int, durationMs: Long): Pair<Long, Long> {
        val clip = clipOf(card, Card.MAIN_TRACK)
        val start = maxOf(card.chapters[idx].startMs, clip?.startMs ?: 0L)
        val next = card.chapters.getOrNull(idx + 1)?.startMs ?: durationMs.coerceAtLeast(0L)
        val clipEnd = clip?.endMs ?: 0L
        val end = if (clipEnd > 0L && (next <= 0L || clipEnd < next)) clipEnd else next
        return start to end
    }

    /** One local file split into songs by the silence scan. */
    private fun localChaptersActive(card: Card?): Boolean {
        val c = controller ?: return false
        return card != null && !externalPlaying && card.chapters.size > 1 && c.mediaItemCount == 1
    }

    private fun seekToLocalChapter(card: Card, delta: Int) {
        val c = controller ?: return
        val chapters = card.chapters
        val i = chapterIndexAt(card, c.currentPosition + itemOffsetMs)
        val target = ((i + delta) % chapters.size + chapters.size) % chapters.size
        c.seekTo((chapterSpan(card, target, 0L).first - itemOffsetMs).coerceAtLeast(0L))
        c.play()
    }

    /** Jump to another song inside a long video by seeking its app's media session. */
    private fun seekToChapter(np: NowPlaying, delta: Int) {
        val chapters = np.card.chapters
        val session = externalSession(np.card.sourceType)
        if (session == null) {
            _messages.tryEmit(Notice(app.getString(R.string.msg_need_notification_access)))
            return
        }
        val i = chapterIndexAt(np.card, sessionPositionMs(session))
        val target = ((i + delta) % chapters.size + chapters.size) % chapters.size
        session.transportControls.seekTo(chapterSpan(np.card, target, 0L).first)
        if (!isSessionPlaying(session)) session.transportControls.play()
    }

    fun next() {
        val np = _nowPlaying.value ?: return
        if (isSingleTrackCard(np)) return
        if (np.isExternal) {
            if (np.card.chapters.size > 1) {
                seekToChapter(np, +1)
                return
            }
            val session = externalSession(np.card.sourceType)
            if (session != null) session.transportControls.skipToNext() else sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        } else {
            if (localChaptersActive(np.card)) {
                seekToLocalChapter(np.card, +1)
                return
            }
            val c = controller ?: return
            // Wrap around: next on the last track goes to the first.
            if (c.hasNextMediaItem()) c.seekToNextMediaItem() else c.seekTo(0, 0L)
            c.play()
        }
    }

    /** Previous *track*, never "restart the current one". */
    fun previous() {
        val np = _nowPlaying.value ?: return
        if (isSingleTrackCard(np)) return
        if (np.isExternal) {
            if (np.card.chapters.size > 1) {
                seekToChapter(np, -1)
                return
            }
            val session = externalSession(np.card.sourceType)
            if (session != null) {
                val pos = session.playbackState?.position ?: 0L
                session.transportControls.skipToPrevious()
                // Most apps restart the track when more than ~3s in; a second skip goes back for real.
                if (pos > 3000) mainHandler.postDelayed({ session.transportControls.skipToPrevious() }, 300)
            } else {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
        } else {
            if (localChaptersActive(np.card)) {
                seekToLocalChapter(np.card, -1)
                return
            }
            val c = controller ?: return
            // Wrap around: previous on the first track goes to the last.
            if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(c.mediaItemCount - 1, 0L)
            c.play()
        }
    }

    /**
     * Drag-to-scrub: jump to [positionMs] inside the song on screen. Like [NowPlaying.positionMs], it counts
     * from the start of the current chapter when one file or video holds several songs.
     */
    fun seekInTrack(positionMs: Long) {
        val np = _nowPlaying.value ?: return
        // Where the song on screen starts in the file: its chapter, or the start cut of a cut video in its app.
        val chapterStart = when {
            np.card.chapters.size > 1 -> chapterSpan(np.card, np.trackIndex.coerceIn(np.card.chapters.indices), 0L).first
            np.isExternal -> clipOf(np.card, Card.MAIN_TRACK)?.startMs ?: 0L
            else -> 0L
        }
        if (np.isExternal) {
            val session = externalSession(np.card.sourceType)
            if (session == null) {
                _messages.tryEmit(Notice(app.getString(R.string.msg_need_notification_access)))
                return
            }
            session.transportControls.seekTo(chapterStart + positionMs)
        } else {
            val c = controller ?: return
            c.seekTo(if (localChaptersActive(np.card)) (chapterStart + positionMs - itemOffsetMs).coerceAtLeast(0L) else positionMs)
        }
        // Show the new spot straight away instead of waiting for the next progress tick.
        _nowPlaying.update { it?.copy(positionMs = positionMs) }
    }

    /** Podcast cards queue episodes newest-first: jump back to the top of the queue. */
    fun jumpToLatest() {
        val np = _nowPlaying.value ?: return
        if (np.isExternal || np.card.sourceType != SourceType.PODCAST) return
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        c.seekTo(0, 0L)
        c.play()
    }

    fun volumeUp() = adjustVolume(+1)
    fun volumeDown() = adjustVolume(-1)

    /** True while a volume limit (normal or bedtime) is below the phone's maximum. */
    fun volumeCapActive(): Boolean = volumeBounds().let { (max, cap) -> cap < max }

    /** Called from MainActivity.onResume so the app can re-enforce the volume limit. */
    fun applyVolumeCap() {
        val (max, cap) = volumeBounds()
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur > cap) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
            } catch (_: Exception) {
            }
        }
        _volume.value = readVolume()
        if (max <= 0) return
    }

    /** True once the user has granted BoopBox "notification access" (grown-up settings). */
    fun hasMediaSessionAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    // ---------------------------------------------------------------- volume

    private fun volumeBounds(): Pair<Int, Int> {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val s = settings.value
        val limit = if (Bedtime.isBedtime(s)) minOf(s.volumeCapPercent, s.bedtimeVolumeCapPercent) else s.volumeCapPercent
        val pct = limit.coerceIn(5, 100)
        val cap = if (pct >= 100) max else (max * pct / 100).coerceAtLeast(1)
        return max to cap
    }

    private fun readVolume(): Pair<Int, Int> {
        val (_, cap) = volumeBounds()
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtMost(cap) to cap
    }

    private fun adjustVolume(delta: Int) {
        val (_, cap) = volumeBounds()
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (cur + delta).coerceIn(0, cap)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {
        }
        _volume.value = target to cap
    }

    // ---------------------------------------------------------------- local playback

    private suspend fun playLocal(card: Card) {
        val items: List<MediaItem>
        var startIndex = 0
        var startPos = 0L

        fun artFor(trackKey: String, fallback: String?): String? = card.trackArt[trackKey] ?: fallback
        stopAfterItem = false
        val from = startAt
        startAt = null

        when (card.sourceType) {
            SourceType.LOCAL_FILE, SourceType.STREAM_URL -> {
                val clip = if (card.sourceType == SourceType.LOCAL_FILE) clipOf(card, Card.MAIN_TRACK) else null
                items = listOf(mediaItem(card.uri, card.displayName(app), artFor(card.uri, card.artworkUrl), clip = clip))
            }
            SourceType.YOTO -> {
                // Physical Yoto cards carry their own access link; library cards need the account.
                if (!YotoClient.isCardLink(card.uri) && !yoto.isConnected) throw IllegalStateException(app.getString(R.string.msg_err_yoto_not_connected))
                val detail = try {
                    yoto.card(card.uri)
                } catch (e: Exception) {
                    throw IllegalStateException(e.message ?: app.getString(R.string.msg_err_yoto_unreachable))
                } ?: throw IllegalStateException(app.getString(R.string.msg_err_yoto_needs_internet))
                if (detail.chapters.isEmpty()) throw IllegalStateException(app.getString(R.string.msg_err_yoto_no_chapters))
                // A real Yoto card adopted on its first tap has no name yet: take Yoto's title and cover.
                cards.get(card.uid)?.takeIf { it.name.isBlank() }?.let { saved ->
                    val named = saved.copy(name = yotoPrettyTitle(detail.title), artworkUrl = saved.artworkUrl ?: detail.coverUrl)
                    cards.upsert(named)
                    currentCard = named
                }
                // One media item per track; Yoto lists podcast chapters newest first already.
                // Downloaded copies (keyed by chapter/track id) win over the hourly signed URLs.
                val group = yotoGroup(card)
                items = detail.chapters.flatMap { ch ->
                    val multi = ch.tracks.size > 1
                    ch.tracks.mapIndexed { i, t ->
                        val id = ch.key + "/" + t.key
                        val local = podcastCache.cachedFileKeyed(group, id)
                        val title = yotoPrettyTitle(ch.title) + if (multi) " · " + (t.title ?: app.getString(R.string.msg_part_number, i + 1)) else ""
                        mediaItem(
                            uri = local?.let { Uri.fromFile(it).toString() } ?: t.trackUrl,
                            title = title,
                            artwork = artFor(ch.key, ch.iconUrl ?: detail.coverUrl ?: card.artworkUrl),
                            mediaId = id
                        )
                    }
                }
                stopAfterItem = detail.autoAdvance == "none"
                if (settings.value.podcastAutoDownload) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            downloadYoto(card, detail)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            SourceType.PLAYLIST -> {
                if (card.playlist.isEmpty()) throw IllegalStateException(app.getString(R.string.msg_err_playlist_empty))
                // An audiobook folder sent as a .boop arrives as a playlist: keep its cover on every part.
                items = card.playlist.map { p -> mediaItem(p.uri, p.title, artFor(p.uri, card.artworkUrl.takeIf { card.audiobook }), clip = clipOf(card, p.uri)) }
            }
            SourceType.LOCAL_FOLDER -> {
                // Per-track pictures: the picture chosen for that track, else embedded tag art, else a sprite.
                // An audiobook's chapter files fall back to the book's cover instead.
                val tracks = withContext(Dispatchers.IO) { MediaScan.folderTracks(app, card.uri) }
                val single = tracks.size == 1
                items = tracks.map { t -> mediaItem(t.uri, t.title, artFor(t.uri, if (single || card.audiobook) card.artworkUrl else null)) }
            }
            SourceType.PODCAST -> {
                // Fresh feed when online (short timeout), cached feed otherwise.
                val feed = podcastCache.feedFor(card.uri)
                if (feed == null) {
                    val saved = card.episodeUrl ?: throw IllegalStateException(app.getString(R.string.msg_err_no_saved_episode))
                    val local = podcastCache.cachedFile(card.uri, saved)
                    items = listOf(
                        mediaItem(local?.let { Uri.fromFile(it).toString() } ?: saved, card.episodeTitle ?: card.displayName(app), artFor(saved, card.artworkUrl), mediaId = saved)
                    )
                } else {
                    val many = feed.episodes.size > 1
                    items = feed.episodes.map { ep ->
                        // Play the downloaded copy when we have it; the media id stays the episode URL
                        // so resume positions and per-track pictures keep working either way.
                        val local = podcastCache.cachedFile(card.uri, ep.url)
                        mediaItem(
                            uri = local?.let { Uri.fromFile(it).toString() } ?: ep.url,
                            title = ep.title,
                            artwork = artFor(ep.url, ep.artworkUrl ?: if (many) null else (feed.artworkUrl ?: card.artworkUrl)),
                            mediaId = ep.url
                        )
                    }
                    if (card.podcastMode == PodcastMode.EPISODE && card.episodeUrl != null) {
                        startIndex = items.indexOfFirst { it.mediaId == card.episodeUrl }.coerceAtLeast(0)
                    }
                    // Make sure the episode this card wants is on the phone for next time.
                    val download = settings.value.podcastAutoDownload
                    scope.launch(Dispatchers.IO) {
                        try {
                            podcastCache.ensureCurrent(card, feed, download)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            SourceType.YOUTUBE, SourceType.YOUTUBE_MUSIC -> {
                val tracks = youtubeCache.localTracks(card) ?: throw IllegalStateException(app.getString(R.string.msg_err_nothing_to_play))
                val single = tracks.size == 1
                items = tracks.map { t ->
                    mediaItem(
                        uri = Uri.fromFile(t.file).toString(),
                        title = if (single) card.displayName(app) else t.title,
                        artwork = artFor(t.id, if (single) card.artworkUrl else "https://i.ytimg.com/vi/${t.id}/hqdefault.jpg"),
                        mediaId = t.id,
                        clip = clipOf(card, if (single) Card.MAIN_TRACK else t.id)
                    )
                }
            }
            else -> throw IllegalStateException(app.getString(R.string.msg_err_unsupported_source))
        }

        if (items.isEmpty()) throw IllegalStateException(app.getString(R.string.msg_err_nothing_to_play))

        if (from != null) {
            startIndex = from.first?.let { id -> items.indexOfFirst { it.mediaId == id } }?.takeIf { it >= 0 } ?: 0
            // Asked for a spot in the file; a cut track's positions count from its start cut.
            startPos = (from.second - items[startIndex].clippingConfiguration.startPositionMs).coerceAtLeast(0L)
        } else if (card.resume) {
            positions.get(card.uid)?.let { saved ->
                val idx = items.indexOfFirst { it.mediaId == saved.mediaId }
                if (idx >= 0) {
                    startIndex = idx
                    startPos = (saved.positionMs - resumeRewindMs(System.currentTimeMillis() - saved.updatedAt)).coerceAtLeast(0L)
                }
            }
        }

        // A cut single file plays from its start cut: the player's positions then start there, chapters don't.
        itemOffsetMs = if (items.size == 1) items[0].clippingConfiguration.startPositionMs else 0L

        val c = awaitController()
        c.shuffleModeEnabled = card.shuffle
        c.repeatMode = Player.REPEAT_MODE_OFF // stop at the end, no looping
        c.setMediaItems(items, startIndex, startPos)
        c.setPlaybackSpeed(if (Plus.isActive) card.speed.coerceIn(0.5f, 2f) else 1f)
        c.prepare()
        c.play()
        externalPlaying = false
        pausedAt = 0L

        val first = items[startIndex]
        val splitFile = items.size == 1 && card.chapters.size > 1
        _nowPlaying.value = NowPlaying(
            card = card,
            title = first.mediaMetadata.title?.toString() ?: card.displayName(app),
            artworkUrl = first.mediaMetadata.artworkUri?.toString(),
            isExternal = false,
            isPlaying = true,
            positionMs = startPos,
            trackSeed = card.uid + first.mediaId,
            trackIndex = if (splitFile) chapterIndexAt(card, startPos + itemOffsetMs) else startIndex,
            trackCount = if (splitFile) card.chapters.size else items.size
        )
        startProgressLoop()
    }

    /** [clip]: play only that part of the file (positions then count from the clip's start). */
    private fun mediaItem(uri: String, title: String, artwork: String?, mediaId: String = uri, clip: Clip? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip?.startMs?.coerceAtLeast(0L) ?: 0L)
                    .setEndPositionMs(clip?.endMs?.takeIf { it > clip.startMs } ?: C.TIME_END_OF_SOURCE)
                    .build()
            )
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(artwork?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    // ---------------------------------------------------------------- external apps

    private suspend fun playExternal(card: Card) {
        controller?.let {
            if (it.isPlaying) it.pause()
        }
        progressJob?.cancel()

        val s = settings.value
        val isVideo = card.sourceType == SourceType.YOUTUBE || card.sourceType == SourceType.YOUTUBE_MUSIC
        val delayMs = s.returnAfterSeconds * 1000L
        // A bare playlist link would open a page instead of playing; swap in its first video if needed.
        val link = withTimeoutOrNull(5000) { LinkResolver.resolvePlayableLink(card.sourceType, card.uri) } ?: card.uri
        // Plus: a cut video opens at its start cut; the watch below stops it at its end cut.
        val clip = if (isVideo) clipOf(card, Card.MAIN_TRACK) else null
        val intent = LinkResolver.launchIntent(app, card.sourceType, link, clip?.startMs ?: 0L)

        // Android 10+ blocks an app from starting an activity once it is no longer visible,
        // unless it holds "Display over other apps". Without that permission we stay visible
        // the only way we can: shrink into a picture-in-picture bubble before the other app
        // opens, then relaunch ourselves from the bubble after the delay.
        // A pinned (child-locked) app cannot start another app; unpin first. The lock comes back
        // when the toddler screen is next shown with the lock still on (Android asks to confirm).
        if (hooks?.isChildLocked() == true) hooks?.setChildLock(false)

        val usePip = s.returnToApp && card.sourceType != SourceType.VIDEO && delayMs > 0 && !Settings.canDrawOverlays(app)
        if (usePip) hooks?.enterPip()

        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw IllegalStateException(app.getString(R.string.msg_err_no_app_for_link))
        }
        externalPlaying = true
        _nowPlaying.value = NowPlaying(
            card = card,
            title = card.displayName(app),
            artworkUrl = card.artworkUrl,
            isExternal = true,
            isPlaying = true,
            // Without notification access the play state below is a guess: say so.
            status = app.getString(
                if (hasMediaSessionAccess()) R.string.msg_playing_in else R.string.msg_opened_in_no_access,
                appLabel(card)
            ),
            trackSeed = card.uid
        )
        startExternalWatch(card)
        // Without notification access we can't see where the video is: the end cut is a best guess,
        // the cut's length after the app has had a moment to load.
        if (clip != null && clip.endMs > clip.startMs && !hasMediaSessionAccess()) {
            val token = ++clipStopToken
            mainHandler.postDelayed({
                if (token == clipStopToken && externalPlaying && currentCard?.uid == card.uid) {
                    pauseExternal(card.sourceType)
                    _nowPlaying.update { np -> np?.copy(isPlaying = false, status = app.getString(R.string.msg_finished)) }
                }
            }, clip.endMs - clip.startMs + 2500L)
        }

        if (card.sourceType == SourceType.AUDIBLE) {
            // Audible opens the book page; nudge it with a play key once it has had time to load.
            mainHandler.postDelayed({ sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 2500)
        }
        // Netflix / Disney+ stop the moment they leave the screen, so a video card stays in its app.
        if (s.returnToApp && card.sourceType != SourceType.VIDEO) {
            // Delay 0: relaunch ourselves right now, while we are still the visible app
            // (always allowed). The other app's deep link is still processed behind us.
            if (delayMs <= 0) returnToFront()
            // Delayed (or backup) attempt: allowed because we are either still visible as a
            // PiP bubble, or hold "Display over other apps".
            val r = Runnable { returnToFront() }
            pendingReturn = r
            val returnMs = delayMs.coerceIn(700, 15000)
            mainHandler.postDelayed(r, returnMs)
            // YouTube / YT Music without Premium pause the moment we come back over them, but do take a play
            // command from the background. PLAY (never play/pause), so a video that kept going is left alone.
            if (isVideo) {
                for (afterMs in listOf(1000L, 2500L)) {
                    mainHandler.postDelayed({ keepExternalPlaying(card) }, returnMs + afterMs)
                }
            }
        }
    }

    private fun keepExternalPlaying(card: Card) {
        if (!externalPlaying || currentCard?.uid != card.uid) return
        val session = externalSession(card.sourceType)
        when {
            session == null -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            !isSessionPlaying(session) -> session.transportControls.play()
        }
    }

    private fun pauseExternal(type: SourceType?) {
        val session = type?.let { externalSession(it) }
        if (session != null) session.transportControls.pause() else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    /** The other app's media session, when we are allowed to see it. */
    private fun externalSession(type: SourceType): android.media.session.MediaController? {
        if (!hasMediaSessionAccess()) return null
        return try {
            val sessions = mediaSessionManager.getActiveSessions(ComponentName(app, MediaNotificationListener::class.java))
            val pkg = LinkResolver.packageFor(type, currentCard?.uri.orEmpty())
            val byPackage = if (pkg != null) sessions.firstOrNull { it.packageName == pkg } else null
            byPackage ?: sessions.firstOrNull { it.packageName != app.packageName && isSessionPlaying(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The other app hands over a fresh copy of its cover on every poll. Keep the one already on screen while it
     * shows the same picture: a new object would make the artwork reload (and flash) every 0.7 s.
     */
    private fun sameOrNew(old: Bitmap?, new: Bitmap?): Bitmap? = when {
        new == null -> old
        old != null && old.sameAs(new) -> old
        else -> new
    }

    private fun isSessionPlaying(session: android.media.session.MediaController): Boolean {
        val state = session.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    /**
     * Keeps the screen honest while another app plays: real play/pause state, and (with
     * notification access) the real title, artwork and progress from that app's session.
     * Also stops the other app's autoplay once a single-track card has finished.
     */
    private fun startExternalWatch(card: Card) {
        progressJob?.cancel()
        progressJob = scope.launch {
            delay(1500) // give the other app a moment to actually start
            val singleTrack = LinkResolver.isSingleTrack(card.sourceType, card.uri)
            val isVideo = card.sourceType == SourceType.YOUTUBE || card.sourceType == SourceType.YOUTUBE_MUSIC
            val clip = if (isVideo) clipOf(card, Card.MAIN_TRACK) else null
            var firstTitle: String? = null
            var polls = 0
            while (externalPlaying) {
                var nextPollMs = 700L
                val session = externalSession(card.sourceType)
                if (session != null) {
                    val md = session.metadata
                    val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    val art = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                    val duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    val position = sessionPositionMs(session)
                    val playing = isSessionPlaying(session)

                    if (title != null) {
                        if (firstTitle == null) {
                            firstTitle = title
                        } else if (title != firstTitle && _sleepAtTrackEnd.value) {
                            goToSleep(app.getString(R.string.msg_sleep_song_finished))
                            break
                        } else if (singleTrack && title != firstTitle && settings.value.stopExternalAutoplay) {
                            // The track we were asked to play is over and the app rolled on. Stop it.
                            session.transportControls.pause()
                            _nowPlaying.update { np -> np?.copy(isPlaying = false, status = app.getString(R.string.msg_finished)) }
                            break
                        }
                    }

                    if (clip != null) {
                        // Plus start / end cut. The app was opened at the start cut (t=); one that ignored it, or
                        // picked up where it last was, is moved there while the video is just starting.
                        if (polls < 6 && position < clip.startMs - 1500L) session.transportControls.seekTo(clip.startMs)
                        if (clip.endMs > 0L && position >= clip.endMs) {
                            session.transportControls.pause()
                            _nowPlaying.update { np -> np?.copy(isPlaying = false, status = app.getString(R.string.msg_finished)) }
                            if (_sleepAtTrackEnd.value) goToSleep(app.getString(R.string.msg_sleep_song_finished))
                            break
                        }
                        // Poll faster close to the end cut so the song stops on it, not up to 0.7 s later.
                        if (clip.endMs > 0L && playing) nextPollMs = (clip.endMs - position).coerceIn(80L, 700L)
                    }

                    val chapters = card.chapters
                    if (chapters.size > 1) {
                        // One long video, many songs: follow the current chapter.
                        val idx = chapterIndexAt(card, position)
                        val ch = chapters[idx]
                        val (start, end) = chapterSpan(card, idx, duration)
                        val chapterArt = card.trackArt[ch.artKey]
                        _nowPlaying.update { np ->
                            np?.copy(
                                isPlaying = playing,
                                title = ch.title,
                                artworkUrl = chapterArt ?: np.card.artworkUrl,
                                artworkBitmap = if (chapterArt != null) null else sameOrNew(np.artworkBitmap, art),
                                trackSeed = np.card.uid + ch.artKey,
                                trackIndex = idx,
                                trackCount = chapters.size,
                                positionMs = (position - start).coerceAtLeast(0),
                                durationMs = if (end > start) end - start else 0L
                            )
                        }
                    } else {
                        // A cut video shows only the cut: its progress runs from the start cut to the end cut.
                        val from = clip?.startMs ?: 0L
                        val to = clip?.endMs?.takeIf { it > 0L } ?: duration.coerceAtLeast(0)
                        _nowPlaying.update { np ->
                            np?.copy(
                                isPlaying = playing,
                                title = title ?: np.card.displayName(app),
                                artworkBitmap = sameOrNew(np.artworkBitmap, art),
                                trackSeed = np.card.uid + (title ?: ""),
                                positionMs = (position - from).coerceAtLeast(0),
                                durationMs = (to - from).coerceAtLeast(0)
                            )
                        }
                    }
                } else {
                    val playing = try {
                        audioManager.isMusicActive
                    } catch (_: Exception) {
                        true
                    }
                    _nowPlaying.update { np -> np?.copy(isPlaying = playing) }
                }
                polls++
                delay(nextPollMs)
            }
        }
    }

    private fun appLabel(card: Card): String = when (card.sourceType) {
        SourceType.SPOTIFY, SourceType.YOUTUBE_MUSIC, SourceType.YOUTUBE, SourceType.AUDIBLE -> app.getString(card.sourceType.labelRes)
        SourceType.VIDEO -> app.getString(LinkResolver.videoApp(card.uri)?.second ?: R.string.msg_another_app)
        else -> app.getString(R.string.msg_another_app)
    }

    private fun returnToFront() {
        pendingReturn = null
        try {
            if (mainTaskId > 0) {
                app.getSystemService(ActivityManager::class.java).moveTaskToFront(mainTaskId, 0)
            }
        } catch (_: Exception) {
        }
        try {
            val intent = Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            app.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun cancelPendingReturn() {
        pendingReturn?.let { mainHandler.removeCallbacks(it) }
        pendingReturn = null
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- controller plumbing

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        val future = controllerFuture ?: run {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            MediaController.Builder(app, token).buildAsync().also { controllerFuture = it }
        }
        return suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    val c = future.get()
                    if (controller == null) {
                        controller = c
                        c.addListener(playerListener)
                    }
                    cont.resume(c)
                } catch (e: Exception) {
                    controllerFuture = null
                    cont.resumeWithException(IllegalStateException(app.getString(R.string.msg_err_player_service)))
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (externalPlaying) return
            _nowPlaying.update { it?.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveCurrentPosition()
            // Only a real pause counts as a break (not a moment of buffering).
            pausedAt = if (isPlaying || controller?.playWhenReady == true) 0L else SystemClock.elapsedRealtime()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (externalPlaying || mediaItem == null) return
            if (_sleepAtTrackEnd.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                mainHandler.post { goToSleep(app.getString(R.string.msg_sleep_song_finished)) }
            } else if (stopAfterItem && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // Yoto Daily style: one episode, then stop (the next one stays a tap away).
                mainHandler.post {
                    controller?.let {
                        it.pause()
                        it.seekTo(0L)
                    }
                }
            }
            val c = controller
            _nowPlaying.update {
                it?.copy(
                    title = mediaItem.mediaMetadata.title?.toString() ?: it.card.displayName(app),
                    artworkUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
                    artworkBytes = null,
                    trackSeed = it.card.uid + mediaItem.mediaId,
                    trackIndex = c?.currentMediaItemIndex ?: it.trackIndex,
                    trackCount = c?.mediaItemCount ?: it.trackCount,
                    positionMs = 0L
                )
            }
        }

        /** ExoPlayer fills this in from ID3 / MP4 tags: embedded cover art and a proper title. */
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (externalPlaying) return
            val art = mediaMetadata.artworkData
            val title = mediaMetadata.title?.toString()
            val split = localChaptersActive(currentCard)
            _nowPlaying.update { np ->
                np?.copy(
                    // A file split into songs keeps its chapter titles / pictures.
                    title = if (split) np.title else (title ?: np.title),
                    artworkBytes = if (split && np.artworkUrl != null) np.artworkBytes else (art ?: np.artworkBytes)
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (externalPlaying) return
            if (playbackState == Player.STATE_ENDED) {
                // Playlist finished: nothing else starts. Back to the idle screen.
                if (_sleepAtTrackEnd.value) cancelSleep()
                currentCard?.let { if (it.resume) positions.clear(it.uid) }
                progressJob?.cancel()
                _nowPlaying.value = null
                currentCard = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = _nowPlaying.value?.title
            val reason = when (error.errorCode) {
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    app.getString(R.string.msg_play_err_not_audio)
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    app.getString(R.string.msg_play_err_cant_open)
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    app.getString(R.string.msg_play_err_offline)
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    app.getString(R.string.msg_play_err_gone)
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    app.getString(R.string.msg_play_err_format)
                else -> app.getString(R.string.msg_play_err_other, error.errorCodeName)
            }
            val text = if (title != null) app.getString(R.string.msg_cant_play_titled, title, reason)
            else app.getString(R.string.msg_cant_play_this, reason)
            _messages.tryEmit(Notice(text, error = true))
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var ticks = 0
            while (true) {
                val c = controller ?: break
                if (externalPlaying) break
                val card = currentCard
                if (card != null && localChaptersActive(card)) {
                    // One file, many songs: follow the current chapter. A cut file counts from its start cut.
                    val pos = c.currentPosition.coerceAtLeast(0) + itemOffsetMs
                    val idx = chapterIndexAt(card, pos)
                    val ch = card.chapters[idx]
                    val (start, end) = chapterSpan(card, idx, c.duration.takeIf { it > 0 }?.plus(itemOffsetMs) ?: 0L)
                    val chapterArt = card.trackArt[ch.artKey]
                    _nowPlaying.update {
                        it?.copy(
                            title = ch.title,
                            // Songs get a sprite each; an audiobook keeps its cover on every chapter.
                            artworkUrl = chapterArt ?: card.artworkUrl.takeIf { card.audiobook },
                            artworkBytes = if (chapterArt != null) null else it.artworkBytes,
                            trackSeed = card.uid + ch.artKey,
                            trackIndex = idx,
                            trackCount = card.chapters.size,
                            positionMs = (pos - start).coerceAtLeast(0),
                            durationMs = if (end > start) end - start else 0L
                        )
                    }
                } else {
                    _nowPlaying.update {
                        it?.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.coerceAtLeast(0)
                        )
                    }
                }
                ticks++
                if (ticks % 10 == 0) {
                    saveCurrentPosition()
                    applyVolumeCap()
                }
                delay(500)
            }
        }
    }

    private fun saveCurrentPosition() {
        val card = currentCard ?: return
        if (!card.resume || externalPlaying) return
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val pos = c.currentPosition
        if (pos > 0) positions.save(card.uid, item.mediaId, pos)
    }
}
