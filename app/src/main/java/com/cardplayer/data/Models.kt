package com.cardplayer.data

import android.content.Context
import androidx.annotation.StringRes
import com.cardplayer.R
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType(@param:StringRes val labelRes: Int) {
    LOCAL_FILE(R.string.source_local_file),
    LOCAL_FOLDER(R.string.source_local_folder),
    PLAYLIST(R.string.source_playlist),
    PODCAST(R.string.source_podcast),
    YOTO(R.string.source_yoto),
    STREAM_URL(R.string.source_stream_url),
    SPOTIFY(R.string.source_spotify),
    YOUTUBE_MUSIC(R.string.source_youtube_music),
    YOUTUBE(R.string.source_youtube),
    AUDIBLE(R.string.source_audible),
    /** A show or movie in a streaming video app (Netflix, Disney+); the app is picked from the link. */
    VIDEO(R.string.source_video),
    APP_LINK(R.string.source_app_link);

    /** True when playback happens inside another app rather than our own player. */
    val isExternal: Boolean
        get() = this == SPOTIFY || this == YOUTUBE_MUSIC || this == YOUTUBE || this == AUDIBLE || this == VIDEO || this == APP_LINK
}

@Serializable
enum class PodcastMode(@param:StringRes val labelRes: Int) {
    LATEST(R.string.podcast_mode_latest),
    EPISODE(R.string.podcast_mode_episode)
}

@Serializable
data class Card(
    val uid: String,
    val name: String = "",
    val emoji: String = "🎵",
    val color: Long = 0xFFFFB74DL,
    val sourceType: SourceType = SourceType.LOCAL_FILE,
    /** content:// uri, folder tree uri, RSS url, stream url or app deep link, depending on [sourceType]. */
    val uri: String = "",
    val artworkUrl: String? = null,
    val subtitle: String? = null,
    val podcastMode: PodcastMode = PodcastMode.LATEST,
    val episodeUrl: String? = null,
    val episodeTitle: String? = null,
    /** Continue from where playback last stopped (audiobooks, podcasts). */
    val resume: Boolean = false,
    val shuffle: Boolean = false,
    /** Per-track picture overrides: track uri / episode url -> image url. */
    val trackArt: Map<String, String> = emptyMap(),
    /** Hand-built playlist (only for [SourceType.PLAYLIST]). Per-song pictures live in [trackArt]. */
    val playlist: List<PlaylistItem> = emptyList(),
    /**
     * Songs inside one long video (YouTube chapters / description timestamps), songs found in one file by the
     * silence scan, or an audiobook file's own chapter marks. Pictures in [trackArt] under "ch:<startMs>".
     */
    val chapters: List<Chapter> = emptyList(),
    /** e.g. "icon by @pangolinpaw, yotoicons.com" */
    val artworkCredit: String? = null,
    /** Lifting this card off the phone: "stop", "pause" or "nothing". Null = the app default ([AppSettings.cardRemovedMode]). */
    val cardRemoved: String? = null,
    /** Bedtime-story cards: start a sleep timer of this many minutes whenever the card is tapped (0 = none). */
    val sleepMinutes: Int = 0,
    /**
     * A downloaded audiobook (one M4B / MP3 file with chapter marks in [chapters], or a folder of chapter files).
     * Shows the book's cover on every chapter and gets its own "Audiobook" type in the editor.
     */
    val audiobook: Boolean = false,
    /** Playback speed for our own player (1 = normal). */
    val speed: Float = 1f,
    /**
     * Plus: start / end cuts (skip a talky intro, a long outro). Key [MAIN_TRACK] for a card that plays one
     * file or video (its [chapters], if split, sit inside the cut), else the track's own id: the YouTube video id
     * of a playlist card's song, the uri of a hand-built [playlist]'s item.
     */
    val clips: Map<String, Clip> = emptyMap(),
    /** When this card was last tapped to play (0 = never); the card list can sort by it. */
    val lastPlayedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** What to call this card in the UI; a card with no name of its own is called after its subtitle or source. */
    fun displayName(context: Context): String = name.ifBlank { subtitle ?: context.getString(sourceType.labelRes) }

    fun removedMode(settings: AppSettings): String = settings.cardRemovedOverride ?: cardRemoved ?: settings.cardRemovedMode

    /** The start / end cut of one track ([MAIN_TRACK] or a track id), when it has one. */
    fun clipFor(trackKey: String): Clip? = clips[trackKey]?.takeIf { it.isSet }

    companion object {
        /** [clips] key of the one file / video a single-track card plays. */
        const val MAIN_TRACK = ""
    }
}

/** Where a track starts and stops playing. [endMs] 0 = to the end. */
@Serializable
data class Clip(
    val startMs: Long = 0L,
    val endMs: Long = 0L
) {
    val isSet: Boolean get() = startMs > 0L || endMs > 0L
}

@Serializable
data class Chapter(
    val startMs: Long,
    val title: String
) {
    /** Key used in [Card.trackArt]. */
    val artKey: String get() = "ch:$startMs"
}

@Serializable
data class PlaylistItem(
    /** content:// uri of a file on the phone, or an http(s) stream URL. */
    val uri: String,
    val title: String
)

@Serializable
data class AppSettings(
    /** After launching Spotify/YouTube/etc, bring BoopBox back to the front. */
    val returnToApp: Boolean = true,
    /** 0 = come back straight away (no permission needed). >0 = give the other app that many seconds on screen first. */
    val returnAfterSeconds: Int = 1,
    val keepScreenOn: Boolean = true,
    /** 100 = no limit. */
    val volumeCapPercent: Int = 100,
    /** Yoto-style: lifting the card off the phone pauses playback. */
    val pauseOnCardRemoved: Boolean = false,
    /** Tapping the card that is already playing toggles pause/play instead of restarting. */
    val sameCardToggles: Boolean = true,
    /** Require a double tap on the "Set up" button when an unknown card is tapped. */
    val gateUnknownCardSetup: Boolean = true,
    /** Render artwork as chunky pixel art. */
    val pixelArt: Boolean = true,
    /** Default "continue where it left off" for newly created cards. */
    val defaultResume: Boolean = false,
    /** Pause Spotify / YouTube Music when a single-track card finishes and the app rolls on to something else. */
    val stopExternalAutoplay: Boolean = true,
    /** Keep the newest episode of every podcast card downloaded on the phone. */
    val podcastAutoDownload: Boolean = true,
    /** Download the audio of YouTube / YouTube Music cards so they play inside BoopBox (offline, no app switch). */
    val youtubeAutoCache: Boolean = true,
    /** "system", "light" or "dark". */
    val themeMode: String = "system",

    // ---- toddler safety
    /** Pin BoopBox to the screen (Android screen pinning): Home / Back / Recents do nothing. */
    val childLock: Boolean = false,
    /**
     * Default for cards that have no [Card.cardRemoved] of their own (and for new cards):
     * what happens when the card is lifted off the phone: "nothing", "pause" or "stop".
     * Null = never chosen: older installs fall back to [pauseOnCardRemoved]; fresh installs get "stop"
     * (set by SettingsRepository on first run). Read it through [cardRemovedMode].
     */
    val cardRemoved: String? = null,
    /** Lifting any card off the phone: "nothing", "pause" or "stop", beating every card's own choice. Null = no override. */
    val cardRemovedOverride: String? = null,
    /** Seconds to put the card back before the removal action happens. */
    val cardRemovedGraceSeconds: Int = 3,
    /** Tint the whole screen for a moment on every zone tap. */
    val zoneFlash: Boolean = true,
    /** A little vibration on every zone tap, on hold-to-stop and when the gear opens. */
    val zoneHaptics: Boolean = true,
    /**
     * How hard the player screen ignores hands that are only holding the phone: "off", "normal" or "strong".
     * Sets the dead strip round the edges, how quick a tap must be and how long the picture is held to stop.
     */
    val gripGuard: String = "normal",
    /** Only the picture reacts (play / pause / hold to stop); the louder / quieter / skip areas are off. */
    val pictureOnly: Boolean = false,
    /** A deliberate sideways drag on the player moves through the song (off while [pictureOnly] is on). */
    val dragToScrub: Boolean = true,
    /** Taps on the gear needed to open settings: 1 or 2. */
    val gearTaps: Int = 1,
    /** Salted SHA-256 of the settings password (see SettingsLock); null = no password. */
    val settingsPasswordHash: String? = null,
    val settingsPasswordSalt: String? = null,

    // ---- bedtime & sleep
    val bedtimeEnabled: Boolean = false,
    /** Minutes from midnight. */
    val bedtimeStart: Int = 19 * 60,
    val bedtimeEnd: Int = 7 * 60,
    /** Volume limit while it is bedtime (percent of max). */
    val bedtimeVolumeCapPercent: Int = 40,
    /** Sleep timer started automatically when a card is tapped during bedtime (0 = none). */
    val bedtimeSleepMinutes: Int = 30,

    /** Battery glyph + percentage on the player: "off", "low" (under 20% or charging) or "always". */
    val showBattery: String = "low",
    /** Order of the card list in settings: "recent" (last played), "name" or "added" (newest first). */
    val cardSort: String = "recent",

    // ---- idle screen
    val showClock: Boolean = true,
    /** "off", "on" or "bedtime" (only during bedtime hours). */
    val nightLight: String = "bedtime",
    val nightLightColor: Long = 0xFFFF8C3AL,
    /** 5..100 */
    val nightLightBrightness: Int = 25
) {
    /** Effective card-removed behaviour. */
    val cardRemovedMode: String
        get() = cardRemoved ?: if (pauseOnCardRemoved) "pause" else "nothing"
}

@Serializable
data class SavedPosition(
    val mediaId: String,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class PodcastEpisode(
    val title: String,
    val url: String,
    val guid: String?,
    val pubDate: String?,
    val durationText: String?,
    val artworkUrl: String?
)

@Serializable
data class PodcastFeed(
    val title: String,
    val artworkUrl: String?,
    val episodes: List<PodcastEpisode>
)

data class LinkMetadata(
    val title: String?,
    val artworkUrl: String?,
    val author: String?
)
