package com.cardplayer.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.cardplayer.data.Chapter
import com.cardplayer.util.MediaScan
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * What a downloaded audiobook carries inside itself: chapter marks, title, author and cover.
 *
 * Chapter marks are read straight from the file (no decoding, a few small reads):
 *  - MP4 / M4B / M4A: a QuickTime chapter text track (`tref/chap`, what iTunes, Apple Books and most
 *    M4B tools write) or Nero chapters (`moov/udta/chpl`, what ffmpeg adds as well),
 *  - MP3: ID3v2 `CHAP` frames.
 * Anything else simply has no chapters and plays as one long track.
 */
object Audiobook {
    data class Info(
        val title: String?,
        val author: String?,
        val durationMs: Long,
        val chapters: List<Chapter>,
        /** Embedded cover picture (JPEG / PNG bytes), if the file has one. */
        val cover: ByteArray?
    )

    /** Book details from one file. Blank chapter names become [chapterName] (1-based). Runs on a background thread. */
    fun readFile(context: Context, uri: Uri, chapterName: (Int) -> String): Info {
        val tags = readTags(context, uri)
        val raw = try {
            open(context, uri)?.use { ch -> readChapters(Source(ch)) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return tags.copy(chapters = tidy(raw, tags.durationMs, chapterName))
    }

    /**
     * Book details for a folder of chapter files, from the first file: its album tag names the book,
     * its embedded picture is the cover (callers prefer a picture lying in the folder, see [MediaScan.folderCover]).
     */
    fun readFolder(context: Context, tracks: List<MediaScan.Track>): Info {
        val first = tracks.firstOrNull()?.let { readTags(context, Uri.parse(it.uri), preferAlbum = true) }
        return Info(first?.title, first?.author, 0L, emptyList(), first?.cover)
    }

    /**
     * Stores an embedded cover in the app's own `covers/` folder (named by its content, so the same
     * picture is kept once) and returns its `file://` uri for [com.cardplayer.data.Card.artworkUrl].
     */
    fun saveCover(context: Context, bytes: ByteArray): String {
        val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val dir = File(context.filesDir, COVERS_DIR).apply { mkdirs() }
        val file = File(dir, hash + if (png) ".png" else ".jpg")
        if (!file.isFile) file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /** Folder (inside the app's files) that holds covers taken out of audiobook files. */
    const val COVERS_DIR = "covers"

    // ---------------------------------------------------------------- tags

    private fun readTags(context: Context, uri: Uri, preferAlbum: Boolean = false): Info {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            fun tag(key: Int) = mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            Info(
                // One-file books name the book in the title; chapter files name the chapter there and the book in the album.
                title = if (preferAlbum) album ?: title else title ?: album,
                author = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_WRITER),
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                chapters = emptyList(),
                cover = mmr.embeddedPicture
            )
        } catch (_: Exception) {
            Info(null, null, 0L, emptyList(), null)
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- chapters

    /** The chapter marks in an open file, as written (unsorted, names may be blank). */
    @VisibleForTesting
    internal fun chapterMarks(channel: FileChannel): List<Chapter> = readChapters(Source(channel))

    /** Sorted, de-duplicated, inside the file, the first one starting at 0, every one named; fewer than two = none. */
    @VisibleForTesting
    internal fun tidy(raw: List<Chapter>, durationMs: Long, chapterName: (Int) -> String): List<Chapter> {
        val sorted = raw
            .filter { it.startMs >= 0 && (durationMs <= 0 || it.startMs < durationMs) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        if (sorted.size < 2) return emptyList()
        return sorted.mapIndexed { i, ch ->
            Chapter(
                startMs = if (i == 0) 0L else ch.startMs,
                title = ch.title.trim().ifEmpty { chapterName(i + 1) }
            )
        }
    }

    private fun readChapters(src: Source): List<Chapter> {
        val head = src.read(0, 12)
        return when {
            head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3Chapters(src, head)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4Chapters(src)
            else -> emptyList()
        }
    }

    // ---- MP3: ID3v2 CHAP frames (Media3 does the frame parsing, unsynchronisation and all)

    @OptIn(UnstableApi::class)
    private fun id3Chapters(src: Source, head: ByteArray): List<Chapter> {
        val tagSize = synchsafe(head, 6) + 10 + if (head[5].toInt() and 0x10 != 0) 10 else 0
        if (tagSize > MAX_ID3_BYTES) return emptyList()
        val data = src.read(0, tagSize.toInt())
        val metadata = Id3Decoder().decode(data, data.size) ?: return emptyList()
        return (0 until metadata.length()).mapNotNull { i ->
            val frame = metadata.get(i) as? ChapterFrame ?: return@mapNotNull null
            val title = (0 until frame.subFrameCount)
                .map { frame.getSubFrame(it) }
                .filterIsInstance<TextInformationFrame>()
                .firstOrNull { it.id == "TIT2" }
                ?.values?.firstOrNull()
            Chapter(frame.startTimeMs.toLong(), title ?: "")
        }
    }

    // ---- MP4 / M4B

    private class Box(val type: String, val start: Long, val end: Long) {
        val size: Long get() = end - start
    }

    private fun mp4Chapters(src: Source): List<Chapter> {
        val moov = boxes(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        val moovKids = boxes(src, moov.start, moov.end)
        val fromTrack = try {
            quickTimeChapters(src, moovKids)
        } catch (_: Exception) {
            emptyList()
        }
        if (fromTrack.size >= 2) return fromTrack
        val chpl = moovKids.firstOrNull { it.type == "udta" }
            ?.let { udta -> boxes(src, udta.start, udta.end).firstOrNull { it.type == "chpl" } }
            ?: return fromTrack
        return neroChapters(src, chpl)
    }

    /** `chpl`: version, flags, [4 reserved bytes in version 1], count, then (start in 100 ns, name length, name). */
    private fun neroChapters(src: Source, chpl: Box): List<Chapter> {
        val d = src.read(chpl.start, chpl.size.coerceAtMost(1L shl 20).toInt())
        if (d.size < 5) return emptyList()
        var p = 4 + if (d[0].toInt() != 0) 4 else 0
        if (p >= d.size) return emptyList()
        val count = d[p++].toInt() and 0xff
        val out = mutableListOf<Chapter>()
        repeat(count) {
            if (p + 9 > d.size) return out
            val start = u64(d, p)
            val len = d[p + 8].toInt() and 0xff
            p += 9
            if (p + len > d.size) return out
            out += Chapter(start / 10_000, String(d, p, len, Charsets.UTF_8))
            p += len
        }
        return out
    }

    /** The text track an audio track points at with `tref/chap`: one sample per chapter, timed by `stts`. */
    private fun quickTimeChapters(src: Source, moovKids: List<Box>): List<Chapter> {
        val traks = moovKids.filter { it.type == "trak" }.map { trak -> trak to boxes(src, trak.start, trak.end) }
        fun trackId(kids: List<Box>): Long? = kids.firstOrNull { it.type == "tkhd" }?.let { tkhd ->
            val d = src.read(tkhd.start, 24)
            if (d.isEmpty()) null else if (d[0].toInt() == 1) u32(d, 20) else u32(d, 12)
        }
        val chapterIds = traks.flatMap { (_, kids) ->
            val tref = kids.firstOrNull { it.type == "tref" } ?: return@flatMap emptyList()
            boxes(src, tref.start, tref.end).filter { it.type == "chap" }.flatMap { chap ->
                val d = src.read(chap.start, chap.size.coerceAtMost(256).toInt())
                (0 until d.size / 4).map { u32(d, it * 4) }
            }
        }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val (_, text) = traks.firstOrNull { (_, kids) -> trackId(kids) in chapterIds } ?: return emptyList()

        val mdia = text.firstOrNull { it.type == "mdia" } ?: return emptyList()
        val mdiaKids = boxes(src, mdia.start, mdia.end)
        val timescale = mdiaKids.firstOrNull { it.type == "mdhd" }?.let { mdhd ->
            val d = src.read(mdhd.start, 32)
            if (d.isEmpty()) 0L else if (d[0].toInt() == 1) u32(d, 20) else u32(d, 12)
        } ?: 0L
        if (timescale <= 0) return emptyList()
        val minf = mdiaKids.firstOrNull { it.type == "minf" } ?: return emptyList()
        val stbl = boxes(src, minf.start, minf.end).firstOrNull { it.type == "stbl" } ?: return emptyList()
        val tables = boxes(src, stbl.start, stbl.end).associateBy { it.type }
        fun table(type: String): ByteArray? = tables[type]?.let { src.read(it.start, it.size.coerceAtMost(MAX_TABLE_BYTES).toInt()) }

        // Start time of every sample.
        val stts = table("stts") ?: return emptyList()
        val starts = mutableListOf<Long>()
        var t = 0L
        for (e in 0 until minOf(u32(stts, 4), (stts.size - 8L) / 8).toInt()) {
            val count = u32(stts, 8 + e * 8)
            val delta = u32(stts, 12 + e * 8)
            for (k in 0 until count.coerceAtMost(MAX_CHAPTERS.toLong())) {
                starts += t
                t += delta
                if (starts.size >= MAX_CHAPTERS) break
            }
            if (starts.size >= MAX_CHAPTERS) break
        }

        // Size of every sample (stsz), and where each one sits (chunk offsets + samples per chunk).
        val stsz = table("stsz") ?: return emptyList()
        val fixedSize = u32(stsz, 4)
        val sampleCount = minOf(u32(stsz, 8), starts.size.toLong()).toInt()
        fun sizeOf(i: Int): Long = if (fixedSize != 0L) fixedSize else u32(stsz, 12 + i * 4)
        val chunkOffsets: List<Long> = table("stco")?.let { d -> (0 until minOf(u32(d, 4), (d.size - 8L) / 4).toInt()).map { u32(d, 8 + it * 4) } }
            ?: table("co64")?.let { d -> (0 until minOf(u32(d, 4), (d.size - 8L) / 8).toInt()).map { u64(d, 8 + it * 8) } }
            ?: return emptyList()
        val stsc = table("stsc") ?: return emptyList()
        val runs = (0 until minOf(u32(stsc, 4), (stsc.size - 8L) / 12).toInt()).map { u32(stsc, 8 + it * 12) to u32(stsc, 12 + it * 12) }
        if (runs.isEmpty()) return emptyList()

        val out = mutableListOf<Chapter>()
        var sample = 0
        for ((c, chunkStart) in chunkOffsets.withIndex()) {
            val perChunk = runs.lastOrNull { it.first <= c + 1 }?.second ?: runs.first().second
            var offset = chunkStart
            for (k in 0 until perChunk) {
                if (sample >= sampleCount) return out
                val size = sizeOf(sample)
                out += Chapter(starts[sample] * 1000 / timescale, sampleText(src, offset, size))
                offset += size
                sample++
            }
        }
        return out
    }

    /** A QuickTime text sample: 16-bit length, then the text (UTF-8, or UTF-16 with a byte-order mark). */
    private fun sampleText(src: Source, offset: Long, size: Long): String {
        if (size < 2) return ""
        val d = src.read(offset, size.coerceAtMost(2048).toInt())
        if (d.size < 2) return ""
        val len = minOf(((d[0].toInt() and 0xff) shl 8) or (d[1].toInt() and 0xff), d.size - 2)
        if (len <= 0) return ""
        val b0 = d[2].toInt() and 0xff
        val b1 = if (len > 1) d[3].toInt() and 0xff else 0
        return when {
            b0 == 0xFE && b1 == 0xFF -> String(d, 4, len - 2, Charsets.UTF_16BE)
            b0 == 0xFF && b1 == 0xFE -> String(d, 4, len - 2, Charsets.UTF_16LE)
            else -> String(d, 2, len, Charsets.UTF_8)
        }
    }

    /** The boxes directly inside [from, to). */
    private fun boxes(src: Source, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val h = src.read(pos, 16)
            if (h.size < 8) break
            var size = u32(h, 0)
            var header = 8
            when (size) {
                1L -> {
                    if (h.size < 16) break
                    size = u64(h, 8)
                    header = 16
                }
                0L -> size = to - pos // runs to the end
            }
            if (size < header || pos + size > to) break
            out += Box(String(h, 4, 4, Charsets.ISO_8859_1), pos + header, pos + size)
            pos += size
        }
        return out
    }

    // ---------------------------------------------------------------- bytes

    /** Random access to the file (content:// files from the system picker are seekable). */
    private class Source(private val ch: FileChannel) {
        val size: Long = ch.size()

        fun read(pos: Long, n: Int): ByteArray {
            if (pos < 0 || pos >= size || n <= 0) return ByteArray(0)
            val buf = ByteBuffer.allocate(minOf(n.toLong(), size - pos).toInt())
            var p = pos
            while (buf.hasRemaining()) {
                val r = ch.read(buf, p)
                if (r <= 0) break
                p += r
            }
            return buf.array().copyOf(buf.position())
        }
    }

    private fun open(context: Context, uri: Uri): FileChannel? {
        if (uri.scheme == "file") return RandomAccessFile(uri.path ?: return null, "r").channel
        // Closing the channel closes the stream, which closes the descriptor.
        return context.contentResolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it).channel }
    }

    private fun u32(d: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > d.size) return 0L
        return ((d[p].toLong() and 0xff) shl 24) or ((d[p + 1].toLong() and 0xff) shl 16) or
            ((d[p + 2].toLong() and 0xff) shl 8) or (d[p + 3].toLong() and 0xff)
    }

    private fun u64(d: ByteArray, p: Int): Long = (u32(d, p) shl 32) or u32(d, p + 4)

    private fun synchsafe(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0x7f) shl 21) or ((d[p + 1].toLong() and 0x7f) shl 14) or
            ((d[p + 2].toLong() and 0x7f) shl 7) or (d[p + 3].toLong() and 0x7f)

    private const val MAX_CHAPTERS = 2000
    private const val MAX_TABLE_BYTES = 64L * 1024
    private const val MAX_ID3_BYTES = 32L * 1024 * 1024
}
