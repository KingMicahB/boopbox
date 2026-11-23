package com.cardplayer.sticker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cardplayer.audio.Audiobook
import java.io.File
import java.security.MessageDigest

/** Where finished stickers are kept, and how long a card's songs run (for the sticker's footer). */
object StickerStore {
    private const val PREFIX = "sticker-"

    /**
     * Saves a drawn sticker as the card's own and returns its `file://` uri for [com.cardplayer.data.Card.stickerUrl].
     * It lives with the covers, so it is backed up and travels in a .boop like any other picture of the card.
     * A new name every time: the picture cache never shows a stale one. Runs on a background thread.
     */
    fun write(context: Context, uid: String, sticker: Bitmap): String {
        val dir = File(context.filesDir, Audiobook.COVERS_DIR).apply { mkdirs() }
        val file = File(dir, PREFIX + hash(uid) + "-" + System.currentTimeMillis() + ".png")
        file.outputStream().use { sticker.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }

    /** Deletes a sticker [write] made (and nothing else: a sticker that came with the card is left alone). */
    fun delete(context: Context, url: String?) {
        val path = url?.takeIf { it.startsWith("file://") }?.let { Uri.parse(it).path } ?: return
        val file = File(path)
        val ours = file.name.startsWith(PREFIX) && file.parentFile == File(context.filesDir, Audiobook.COVERS_DIR)
        if (ours) file.delete()
    }

    /**
     * How long these files run together, or null when any of them can't say (a stream, a file that has gone).
     * Reads each file's header only. Runs on a background thread.
     */
    fun totalMs(context: Context, uris: List<String>): Long? {
        if (uris.isEmpty() || uris.size > 300) return null
        var total = 0L
        for (uri in uris) {
            if (!uri.startsWith("content://") && !uri.startsWith("file://")) return null
            val mmr = MediaMetadataRetriever()
            val ms = try {
                mmr.setDataSource(context, Uri.parse(uri))
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: Exception) {
                null
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
            total += ms?.takeIf { it > 0 } ?: return null
        }
        return total
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}
