package com.cardplayer.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Reader-mode NFC: works with any tag type (NTAG, Mifare, hotel cards, toys...)
 * because the hardware UID is the key. The tag is handed over too, so the caller can
 * read or write our shareable card record ([CardTag]) when it wants to.
 */
class NfcReader(
    private val activity: Activity,
    /** Called on a background thread. */
    private val onTag: (uid: String, tag: Tag) -> Unit,
    private val onTagRemoved: (uid: String) -> Unit,
    private val wantRemovalEvents: (uid: String) -> Boolean
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Reader mode is on. Off while another app (YouTube, Spotify…) is in front. */
    @Volatile
    private var reading = false

    /** The card whose lifting is still to be reported. */
    @Volatile
    private var watchedUid: String? = null

    /** The system is still watching [watchedUid] for us (its removed-callback hasn't come yet). */
    @Volatile
    private var watchLive = false

    /** The last card seen since reader mode came back on. */
    @Volatile
    private var seenSinceEnable: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** The watch was lost while reader mode was off (screen off, another app in front): is the card still there? */
    private val recheck = Runnable {
        val uid = watchedUid ?: return@Runnable
        if (reading && !watchLive && seenSinceEnable != uid) {
            watchedUid = null
            onTagRemoved(uid)
        }
    }

    private val callback = NfcAdapter.ReaderCallback { tag ->
        val uid = uidOf(tag)
        seenSinceEnable = uid
        onTag(uid, tag)
        watch(uid, tag)
    }

    /**
     * Reports [tag] being lifted, if its card wants that. Also for a card that launched the app from outside:
     * it stays on the phone, so reader mode never hands it over again.
     */
    fun watch(uid: String, tag: Tag) {
        if (!wantRemovalEvents(uid)) {
            if (watchedUid == uid) watchedUid = null
            return
        }
        watchedUid = uid
        watchLive = true
        val watching = try {
            adapter?.ignore(tag, 1000, { removed(uid) }, null) == true
        } catch (_: Exception) {
            false
        }
        // Lifted while the tap was still being handled: the system has dropped the tag and will never call back.
        if (!watching) removed(uid)
    }

    private fun removed(uid: String) {
        if (watchedUid != uid) return
        watchLive = false
        // Switching reader mode off (another app opened, screen off) also loses the tag: that isn't the card
        // being lifted. Keep it; [enable] checks whether it is still there.
        if (!reading) return
        watchedUid = null
        onTagRemoved(uid)
    }

    fun enable() {
        val a = adapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        try {
            seenSinceEnable = null
            a.enableReaderMode(activity, callback, flags, extras)
            reading = true
            // A card lying on the phone is found again within moments; one that isn't was lifted while we weren't looking.
            if (watchedUid != null && !watchLive) handler.postDelayed(recheck, 2000)
        } catch (_: Exception) {
        }
    }

    fun disable() {
        handler.removeCallbacks(recheck)
        reading = false
        try {
            adapter?.disableReaderMode(activity)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun uidOf(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }
    }
}
