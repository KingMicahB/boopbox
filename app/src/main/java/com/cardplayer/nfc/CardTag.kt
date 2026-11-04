package com.cardplayer.nfc

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.cardplayer.R
import com.cardplayer.data.Card
import com.cardplayer.data.PodcastMode
import com.cardplayer.data.SourceType
import com.cardplayer.yoto.YotoClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Card definitions written onto the NFC tag itself, so a card can be handed to someone else
 * and their BoopBox picks it up on the first tap.
 *
 * Format: one NDEF MIME record, type [MIME], payload = compact JSON ([TagPayload]).
 */
object CardTag {
    const val MIME = "application/vnd.cardplayer"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Short keys on purpose: NTAG213 stickers only hold ~144 bytes. */
    @Serializable
    data class TagPayload(
        val v: Int = 1,
        val t: String,
        val u: String,
        val n: String? = null,
        val e: String? = null,
        val c: Long? = null,
        val a: String? = null,
        val s: String? = null,
        val m: String? = null,
        val eu: String? = null,
        val et: String? = null,
        val r: Boolean? = null,
        val sh: Boolean? = null
    )

    sealed class WriteResult {
        data object Ok : WriteResult()
        data class Error(val message: String) : WriteResult()
    }

    /** Local files/folders are phone-specific content:// URIs and cannot travel on a tag. */
    fun isShareable(card: Card): Boolean =
        card.sourceType != SourceType.LOCAL_FILE &&
            card.sourceType != SourceType.LOCAL_FOLDER &&
            card.sourceType != SourceType.PLAYLIST &&
            card.uri.isNotBlank()

    fun toPayload(card: Card, slim: Boolean = false): TagPayload = TagPayload(
        t = card.sourceType.name,
        u = card.uri,
        n = card.name.ifBlank { null },
        e = card.emoji.takeIf { it != "🎵" },
        c = card.color.takeIf { it != 0xFFFFB74DL },
        a = if (slim) null else card.artworkUrl,
        s = if (slim) null else card.subtitle,
        m = card.podcastMode.takeIf { card.sourceType == SourceType.PODCAST && it != PodcastMode.LATEST }?.name,
        eu = card.episodeUrl.takeIf { card.sourceType == SourceType.PODCAST && card.podcastMode == PodcastMode.EPISODE },
        et = if (slim) null else card.episodeTitle.takeIf { card.sourceType == SourceType.PODCAST && card.podcastMode == PodcastMode.EPISODE },
        r = card.resume.takeIf { it },
        sh = card.shuffle.takeIf { it }
    )

    fun fromPayload(p: TagPayload, uid: String): Card? {
        val type = SourceType.entries.firstOrNull { it.name == p.t } ?: return null
        if (p.u.isBlank()) return null
        return Card(
            uid = uid,
            name = p.n ?: "",
            emoji = p.e ?: "🎵",
            color = p.c ?: 0xFFFFB74DL,
            sourceType = type,
            uri = p.u,
            artworkUrl = p.a,
            subtitle = p.s,
            podcastMode = PodcastMode.entries.firstOrNull { it.name == p.m } ?: PodcastMode.LATEST,
            episodeUrl = p.eu,
            episodeTitle = p.et,
            resume = p.r ?: false,
            shuffle = p.sh ?: false
        )
    }

    /**
     * Android Application Record(s): on tap Android opens the first of these that is installed, even when BoopBox is
     * closed, and sends phones without it to its Play page.
     */
    private val PACKAGES = listOf("com.boopboxapp")

    /**
     * Our MIME record first (Android dispatches on the first record), then optionally an
     * Android Application Record so the tap launches BoopBox over any other NFC app.
     */
    fun encode(card: Card, slim: Boolean = false, launch: Boolean = true): NdefMessage {
        val bytes = json.encodeToString(TagPayload.serializer(), toPayload(card, slim)).toByteArray(Charsets.UTF_8)
        val mime = NdefRecord.createMime(MIME, bytes)
        return if (launch) NdefMessage(arrayOf(mime) + PACKAGES.map { NdefRecord.createApplicationRecord(it) }) else NdefMessage(mime)
    }

    fun decode(message: NdefMessage?, uid: String): Card? {
        val record = message?.records?.firstOrNull {
            it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type, Charsets.US_ASCII) == MIME
        } ?: return null
        return try {
            fromPayload(json.decodeFromString(TagPayload.serializer(), String(record.payload, Charsets.UTF_8)), uid)
        } catch (_: Exception) {
            null
        }
    }

    /** Reads our record off a tag, if present. Safe to call from the reader-mode callback thread. */
    fun read(tag: Tag, uid: String): Card? = decode(readMessage(tag), uid)

    /**
     * A real Yoto card: its tag holds one NDEF URI record, `https://yoto.io/<cardId>?<token>`.
     * The card plays straight from that link (see [YotoClient.card]); the name and cover are
     * filled in from Yoto on the first play.
     */
    fun yotoCard(message: NdefMessage?, uid: String): Card? {
        val link = message?.records?.firstNotNullOfOrNull { r ->
            try {
                r.toUri()?.toString()?.takeIf { YotoClient.isCardLink(it) }
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return Card(uid = uid, emoji = "🎧", color = 0xFFFF7043L, sourceType = SourceType.YOTO, uri = link)
    }

    /**
     * The tag's NDEF message. Reader mode skips Android's own NDEF check (so any tag counts as a
     * card), which leaves [Ndef] unavailable on most tags; Mifare Ultralight / NTAG tags (Yoto cards,
     * stickers) are then read page by page and the NDEF TLV is parsed by hand.
     */
    fun readMessage(tag: Tag): NdefMessage? {
        Ndef.get(tag)?.let { ndef ->
            try {
                ndef.cachedNdefMessage?.let { return it }
                ndef.connect()
                ndef.ndefMessage?.let { return it }
            } catch (_: Exception) {
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            }
        }
        return readUltralight(tag)
    }

    private fun readUltralight(tag: Tag): NdefMessage? {
        val mu = MifareUltralight.get(tag) ?: return null
        return try {
            mu.connect()
            // Pages 3..6: capability container (byte 2 = data area size / 8), then the first data bytes.
            val first = mu.readPages(3)
            val dataSize = ((first[2].toInt() and 0xFF) * 8).takeIf { it > 0 } ?: 256
            val data = ByteArrayOutputStream().apply { write(first, 4, 12) }
            var page = 7
            var message = ndefFromTlv(data.toByteArray())
            while (message == null && data.size() < dataSize) {
                val chunk = try {
                    mu.readPages(page)
                } catch (_: IOException) {
                    break
                }
                data.write(chunk)
                page += 4
                message = ndefFromTlv(data.toByteArray())
            }
            message
        } catch (_: Exception) {
            null
        } finally {
            try {
                mu.close()
            } catch (_: Exception) {
            }
        }
    }

    /** First NDEF message TLV (type 0x03) in a Type 2 tag's data area, or null if not complete yet. */
    private fun ndefFromTlv(b: ByteArray): NdefMessage? {
        var i = 0
        while (i < b.size) {
            when (val type = b[i].toInt() and 0xFF) {
                0x00 -> i++
                0xFE -> return null
                else -> {
                    if (i + 1 >= b.size) return null
                    var len = b[i + 1].toInt() and 0xFF
                    var start = i + 2
                    if (len == 0xFF) {
                        if (i + 3 >= b.size) return null
                        len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                        start = i + 4
                    }
                    if (start + len > b.size) return null
                    if (type == 0x03) {
                        return try {
                            NdefMessage(b.copyOfRange(start, start + len))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    i = start + len
                }
            }
        }
        return null
    }

    /**
     * Puts the BoopBox launch link (just an Android Application Record) on a blank tag, so tapping
     * it opens BoopBox even when the app is closed. A tag that already holds anything is left alone.
     * Safe to call from the reader-mode callback thread.
     */
    fun writeLaunchLink(tag: Tag): Boolean {
        val msg = NdefMessage(PACKAGES.map { NdefRecord.createApplicationRecord(it) }.toTypedArray())
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                val current = ndef.ndefMessage
                val blank = current == null || current.records.all { it.tnf == NdefRecord.TNF_EMPTY }
                if (!blank || !ndef.isWritable || msg.byteArrayLength > ndef.maxSize) return false
                ndef.writeNdefMessage(msg)
                true
            } catch (_: Exception) {
                false
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            }
        }
        return writeUltralightIfBlank(tag, msg)
    }

    /**
     * Reader mode hides [Ndef] on most tags (see [readMessage]), so NTAG / Ultralight tags are
     * written page by page: an NDEF TLV from page 4, ended by a terminator TLV.
     */
    private fun writeUltralightIfBlank(tag: Tag, msg: NdefMessage): Boolean {
        val mu = MifareUltralight.get(tag) ?: return false
        return try {
            mu.connect()
            // Page 3 = capability container: E1 = NDEF formatted, byte 2 = data area size / 8,
            // byte 3 high nibble 0 = writable. Pages 4..6 = start of the data area.
            val first = mu.readPages(3)
            if ((first[0].toInt() and 0xFF) != 0xE1 || (first[3].toInt() and 0xF0) != 0) return false
            if (!isBlankTlv(first.copyOfRange(4, 16))) return false
            val bytes = msg.toByteArray()
            val tlv = ByteArrayOutputStream().apply {
                write(0x03)
                if (bytes.size < 0xFF) {
                    write(bytes.size)
                } else {
                    write(0xFF)
                    write(bytes.size shr 8)
                    write(bytes.size and 0xFF)
                }
                write(bytes)
                write(0xFE)
            }.toByteArray()
            if (tlv.size > (first[2].toInt() and 0xFF) * 8) return false
            val padded = tlv.copyOf((tlv.size + 3) / 4 * 4)
            for (i in padded.indices step 4) mu.writePage(4 + i / 4, padded.copyOfRange(i, i + 4))
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                mu.close()
            } catch (_: Exception) {
            }
        }
    }

    /** The data area holds no NDEF message yet: an empty NDEF TLV or a terminator comes first. */
    private fun isBlankTlv(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x00 -> i++
                0xFE -> return true
                // Lock / memory control TLVs come before the NDEF TLV on some tags.
                0x01, 0x02 -> {
                    if (i + 1 >= b.size) return false
                    i += 2 + (b[i + 1].toInt() and 0xFF)
                }
                0x03 -> return i + 1 < b.size && b[i + 1].toInt() == 0
                else -> return false
            }
        }
        return false
    }

    /** Writes the card onto the tag, slimming the payload if the tag is small. */
    fun write(context: Context, tag: Tag, card: Card): WriteResult {
        if (!isShareable(card)) return WriteResult.Error(context.getString(R.string.msg_tag_not_shareable))
        val full = encode(card, slim = false)
        val slim = encode(card, slim = true)
        // Small stickers: sharing the card matters more than auto-launching the app.
        val slimNoLaunch = encode(card, slim = true, launch = false)

        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                if (!ndef.isWritable) return WriteResult.Error(context.getString(R.string.msg_tag_read_only))
                val msg = when {
                    full.byteArrayLength <= ndef.maxSize -> full
                    slim.byteArrayLength <= ndef.maxSize -> slim
                    slimNoLaunch.byteArrayLength <= ndef.maxSize -> slimNoLaunch
                    else -> return WriteResult.Error(
                        context.getString(R.string.msg_tag_too_small, slimNoLaunch.byteArrayLength, ndef.maxSize)
                    )
                }
                ndef.writeNdefMessage(msg)
                WriteResult.Ok
            } catch (e: Exception) {
                WriteResult.Error(context.getString(R.string.msg_tag_write_failed, e.message ?: context.getString(R.string.msg_tag_lost_contact)))
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            }
        }

        NdefFormatable.get(tag)?.let { formatable ->
            return try {
                formatable.connect()
                formatable.format(full)
                WriteResult.Ok
            } catch (e: Exception) {
                try {
                    formatable.format(slim)
                    WriteResult.Ok
                } catch (_: Exception) {
                    WriteResult.Error(context.getString(R.string.msg_tag_format_failed, e.message ?: context.getString(R.string.msg_unknown_error)))
                }
            } finally {
                try {
                    formatable.close()
                } catch (_: Exception) {
                }
            }
        }

        return WriteResult.Error(context.getString(R.string.msg_tag_no_ndef))
    }
}
