package com.cardplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.cardplayer.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Splits one long audio file into songs by finding the pauses between them.
 *
 * [analyze] builds a loudness timeline (one dBFS value per 50 ms) as fast as it can:
 *  1. cached result from an earlier run, instantly;
 *  2. MP3: header-only scan of every frame's global_gain (no decoding), well under a second;
 *  3. anything else: full decode split across all CPU cores.
 * It reports partial timelines while working so the waveform can draw progressively.
 * Everything after that ([findSilences], [chaptersFrom]) is instant.
 */
object SilenceScanner {
    const val STEP_MS = 50L

    /** Loudness per 50 ms window. NaN = not analysed yet. */
    class Timeline(val levelsDb: FloatArray, val durationMs: Long) {
        val size: Int get() = levelsDb.size
        fun timeMs(i: Int): Long = i * STEP_MS
        val progress: Float
            get() {
                if (levelsDb.isEmpty()) return 0f
                var done = 0
                for (v in levelsDb) if (!v.isNaN()) done++
                return done.toFloat() / levelsDb.size
            }
    }

    data class Silence(val startMs: Long, val endMs: Long)

    data class Options(
        /** Quieter than this (dBFS) counts as silent. */
        val thresholdDb: Float = -40f,
        /** Quiet stretches at least this long become split points. */
        val minSilenceMs: Long = 1000,
        /** Parts shorter than this merge into the next one. */
        val minSongMs: Long = 10_000
    )

    // ---------------------------------------------------------------- entry point

    suspend fun analyze(
        context: Context,
        uri: Uri,
        onPartial: (Timeline) -> Unit = {}
    ): Timeline = withContext(Dispatchers.Default) {
        val cacheFile = cacheFileFor(context, uri)
        readCache(cacheFile)?.let { cached ->
            onPartial(cached)
            return@withContext cached
        }

        val (durationUs, mime) = probe(context, uri)
        val durationMs = durationUs / 1000
        if (durationMs <= 0) throw IllegalStateException("couldn't read the length of this file")
        val windows = (durationMs / STEP_MS).toInt() + 1
        val levels = FloatArray(windows) { Float.NaN }

        var done = false
        if (mime == "audio/mpeg") {
            done = try {
                mp3HeaderScan(context, uri, levels, durationMs)
            } catch (_: Exception) {
                false
            }
            if (done) onPartial(Timeline(levels.copyOf(), durationMs))
        }
        if (!done) {
            java.util.Arrays.fill(levels, Float.NaN)
            parallelDecode(context, uri, durationUs, levels) { onPartial(Timeline(levels.copyOf(), durationMs)) }
        }

        // Windows nothing wrote to (gaps between segments, end of file) count as quiet.
        for (i in levels.indices) if (levels[i].isNaN()) levels[i] = -120f
        val result = Timeline(levels, durationMs)
        writeCache(cacheFile, result)
        onPartial(result)
        result
    }

    private fun probe(context: Context, uri: Uri): Pair<Long, String?> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                    return dur to mime
                }
            }
            throw IllegalStateException("no audio track in this file")
        } finally {
            extractor.release()
        }
    }

    // ---------------------------------------------------------------- cache

    private fun cacheFileFor(context: Context, uri: Uri): File {
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) size = c.getLong(0)
            }
        } catch (_: Exception) {
        }
        val key = MessageDigest.getInstance("SHA-1").digest("$uri|$size".toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "timelines").apply { mkdirs() }
        return File(dir, "$key.v1.bin")
    }

    private fun readCache(file: File): Timeline? = try {
        if (!file.exists()) null else DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val duration = input.readLong()
            val n = input.readInt()
            if (n <= 0 || n > 10_000_000) return null
            val arr = FloatArray(n) { input.readFloat() }
            Timeline(arr, duration)
        }
    } catch (_: Exception) {
        null
    }

    private fun writeCache(file: File, t: Timeline) {
        try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeLong(t.durationMs)
                out.writeInt(t.size)
                for (v in t.levelsDb) out.writeFloat(v)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- MP3 header-only scan

    private val mp3Bitrates = arrayOf(
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),  // MPEG1 L3
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)      // MPEG2/2.5 L3
    )
    private val mp3SampleRates = arrayOf(
        intArrayOf(44100, 48000, 32000), // MPEG1
        intArrayOf(22050, 24000, 16000), // MPEG2
        intArrayOf(11025, 12000, 8000)   // MPEG2.5
    )

    /**
     * Reads each MP3 frame's global_gain from the side info (no audio decoding). global_gain
     * steps are 1.5 dB, so it is a good relative loudness curve; we calibrate the loudest
     * frames of this file to ~0 dBFS. Returns false if the stream doesn't look like clean MP3.
     */
    private fun mp3HeaderScan(context: Context, uri: Uri, levels: FloatArray, durationMs: Long): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        val frameGain = IntArray((durationMs / 20 + 64).toInt()) // ~26 ms per frame, generous
        val frameTimeMs = LongArray(frameGain.size)
        var frames = 0
        var timeUs = 0L

        BufferedInputStream(input, 1 shl 20).use { stream ->
            val buf = ByteArray(1 shl 20)
            var len = 0
            var pos = 0

            fun fill(): Boolean {
                if (pos > 0) {
                    System.arraycopy(buf, pos, buf, 0, len - pos)
                    len -= pos
                    pos = 0
                }
                while (len < buf.size) {
                    val n = stream.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                    if (len >= 65536) break
                }
                return len > 0
            }

            fill()
            // Skip ID3v2 tag.
            if (len >= 10 && buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() && buf[2] == '3'.code.toByte()) {
                val size = ((buf[6].toInt() and 0x7F) shl 21) or ((buf[7].toInt() and 0x7F) shl 14) or
                    ((buf[8].toInt() and 0x7F) shl 7) or (buf[9].toInt() and 0x7F)
                var skip = 10L + size
                while (skip > 0) {
                    if (pos >= len && !fill()) return false
                    val step = minOf(skip, (len - pos).toLong()).toInt()
                    pos += step
                    skip -= step
                }
            }

            var junk = 0
            while (true) {
                if (len - pos < 64 && !fill()) break
                if (len - pos < 4) break
                val b0 = buf[pos].toInt() and 0xFF
                val b1 = buf[pos + 1].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) {
                    pos++
                    junk++
                    if (junk > 2_000_000) return false
                    continue
                }
                val b2 = buf[pos + 2].toInt() and 0xFF
                val b3 = buf[pos + 3].toInt() and 0xFF
                val versionBits = (b1 shr 3) and 0x3      // 0=2.5, 2=2, 3=1
                val layerBits = (b1 shr 1) and 0x3        // 1 = Layer III
                val protection = b1 and 0x1               // 0 = CRC present
                val bitrateIdx = (b2 shr 4) and 0xF
                val srIdx = (b2 shr 2) and 0x3
                val padding = (b2 shr 1) and 0x1
                val channelMode = (b3 shr 6) and 0x3      // 3 = mono
                if (versionBits == 1 || layerBits != 1 || bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) {
                    pos++
                    junk++
                    continue
                }
                val mpeg1 = versionBits == 3
                val bitrate = mp3Bitrates[if (mpeg1) 0 else 1][bitrateIdx] * 1000
                val sampleRate = mp3SampleRates[when (versionBits) { 3 -> 0; 2 -> 1; else -> 2 }][srIdx]
                val frameLen = (if (mpeg1) 144 else 72) * bitrate / sampleRate + padding
                if (frameLen < 24) {
                    pos++
                    continue
                }
                if (len - pos < frameLen && !fill()) break
                if (len - pos < frameLen) break

                val mono = channelMode == 3
                val sideStart = pos + 4 + if (protection == 0) 2 else 0
                val nch = if (mono) 1 else 2
                var gain = 0
                var silentFrame = true
                if (mpeg1) {
                    val base = 9 + (if (mono) 5 else 3) + 4 * nch
                    for (gr in 0 until 2) for (ch in 0 until nch) {
                        val off = base + (gr * nch + ch) * 59
                        val part23 = readBits(buf, sideStart, off, 12)
                        val bigValues = readBits(buf, sideStart, off + 12, 9)
                        val g = readBits(buf, sideStart, off + 21, 8)
                        if (part23 > 0 && bigValues > 0) {
                            silentFrame = false
                            if (g > gain) gain = g
                        }
                    }
                } else {
                    val base = 8 + (if (mono) 1 else 2)
                    for (ch in 0 until nch) {
                        val off = base + ch * 63
                        val part23 = readBits(buf, sideStart, off, 12)
                        val bigValues = readBits(buf, sideStart, off + 12, 9)
                        val g = readBits(buf, sideStart, off + 21, 8)
                        if (part23 > 0 && bigValues > 0) {
                            silentFrame = false
                            if (g > gain) gain = g
                        }
                    }
                }
                if (frames < frameGain.size) {
                    frameGain[frames] = if (silentFrame) -1 else gain
                    frameTimeMs[frames] = timeUs / 1000
                    frames++
                }
                val samples = if (mpeg1) 1152 else 576
                timeUs += samples * 1_000_000L / sampleRate
                pos += frameLen
                junk = 0
            }
        }

        // Sanity: we should have covered most of the file.
        if (frames < 20 || timeUs / 1000 < durationMs * 0.8) return false

        // Calibrate: the loudest ~2% of frames sit at 0 dBFS.
        val gains = IntArray(frames)
        var n = 0
        for (i in 0 until frames) if (frameGain[i] >= 0) gains[n++] = frameGain[i]
        if (n == 0) return false
        val sorted = gains.copyOf(n).apply { sort() }
        val ref = sorted[(n * 0.98).toInt().coerceIn(0, n - 1)].toFloat()

        // Window max over frames.
        val windowMax = FloatArray(levels.size) { Float.NEGATIVE_INFINITY }
        for (i in 0 until frames) {
            val idx = (frameTimeMs[i] / STEP_MS).toInt()
            if (idx !in levels.indices) continue
            val db = if (frameGain[i] < 0) -120f else ((frameGain[i] - ref) * 1.505f).coerceAtMost(0f)
            if (db > windowMax[idx]) windowMax[idx] = db
        }
        var last = -120f
        for (i in levels.indices) {
            levels[i] = if (windowMax[i] == Float.NEGATIVE_INFINITY) last else windowMax[i].also { last = it }
        }
        return true
    }

    private fun readBits(buf: ByteArray, byteStart: Int, bitOffset: Int, count: Int): Int {
        var value = 0
        var bit = bitOffset
        repeat(count) {
            val b = buf[byteStart + (bit shr 3)].toInt() and 0xFF
            value = (value shl 1) or ((b shr (7 - (bit and 7))) and 1)
            bit++
        }
        return value
    }

    // ---------------------------------------------------------------- parallel full decode

    private suspend fun parallelDecode(
        context: Context,
        uri: Uri,
        durationUs: Long,
        levels: FloatArray,
        onTick: () -> Unit
    ) = coroutineScope {
        val cores = Runtime.getRuntime().availableProcessors()
        val segments = when {
            durationUs < 60_000_000L -> 1
            else -> cores.coerceIn(2, 6)
        }
        val segUs = durationUs / segments
        val workers = (0 until segments).map { s ->
            async(Dispatchers.Default) {
                val start = s * segUs
                val end = if (s == segments - 1) durationUs + 1_000_000 else (s + 1) * segUs
                decodeSegment(context, uri, start, end, levels)
            }
        }
        val ticker = launch {
            while (isActive) {
                delay(250)
                onTick()
            }
        }
        workers.forEach { it.await() }
        ticker.cancel()
    }

    private fun decodeSegment(context: Context, uri: Uri, startUs: Long, endUs: Long, levels: FloatArray) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val stride = 4 // analyse every 4th frame: plenty for a loudness envelope

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var curIdx = -1
            var acc = 0.0
            var count = 0
            val windowMax = FloatArray(levels.size) { Float.NEGATIVE_INFINITY }

            fun flush() {
                if (curIdx in levels.indices && count > 0) {
                    val db = toDb(sqrt(acc / count))
                    if (db > windowMax[curIdx]) windowMax[curIdx] = db
                    levels[curIdx] = windowMax[curIdx]
                }
                acc = 0.0
                count = 0
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(2_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t >= endUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= startUs) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val ch = channels.coerceAtLeast(1)
                            val step = ch * stride
                            if (pcmFloat) {
                                val fb = buf.asFloatBuffer()
                                if (floats.size < fb.remaining()) floats = FloatArray(fb.remaining())
                                val n = fb.remaining()
                                fb.get(floats, 0, n)
                                var i = 0
                                while (i < n) {
                                    val frame = i / ch
                                    val idx = ((info.presentationTimeUs + frame * 1_000_000L / sampleRate) / 1000 / STEP_MS).toInt()
                                    if (idx != curIdx) {
                                        flush()
                                        curIdx = idx
                                    }
                                    val v = floats[i]
                                    acc += (v * v).toDouble()
                                    count++
                                    i += step
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val n = sb.remaining()
                                if (shorts.size < n) shorts = ShortArray(n)
                                sb.get(shorts, 0, n)
                                var i = 0
                                while (i < n) {
                                    val frame = i / ch
                                    val idx = ((info.presentationTimeUs + frame * 1_000_000L / sampleRate) / 1000 / STEP_MS).toInt()
                                    if (idx != curIdx) {
                                        flush()
                                        curIdx = idx
                                    }
                                    val v = shorts[i] / 32768.0
                                    acc += v * v
                                    count++
                                    i += step
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
            flush()
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun toDb(rms: Double): Float = if (rms <= 1e-9) -120f else (20.0 * log10(rms)).toFloat()

    // ---------------------------------------------------------------- splitting

    /** Quiet stretches at least [Options.minSilenceMs] long. Unanalysed (NaN) windows never count as silent. */
    fun findSilences(timeline: Timeline, options: Options): List<Silence> {
        val out = mutableListOf<Silence>()
        var start: Long? = null
        for (i in 0 until timeline.size) {
            val tMs = timeline.timeMs(i)
            val v = timeline.levelsDb[i]
            if (!v.isNaN() && v < options.thresholdDb) {
                if (start == null) start = tMs
            } else {
                val s = start
                if (s != null && tMs - s >= options.minSilenceMs) out += Silence(s, tMs)
                start = null
            }
        }
        start?.let { s -> if (timeline.durationMs - s >= options.minSilenceMs) out += Silence(s, timeline.durationMs) }
        return out
    }

    /** Where to cut inside a silence: near its end so the next song starts clean. */
    fun cutPoint(s: Silence): Long = s.startMs + (s.endMs - s.startMs) * 3 / 4

    /**
     * Builds the song list from cut points (automatic + manual), merging parts shorter than
     * [Options.minSongMs]. [names] keeps titles the user typed for a given start time.
     */
    fun chaptersFrom(
        cuts: Collection<Long>,
        totalMs: Long,
        options: Options,
        names: Map<Long, String> = emptyMap()
    ): List<Chapter> {
        val starts = mutableListOf(0L)
        for (c in cuts.filter { it > 0 && it < totalMs }.sorted()) {
            if (c - starts.last() >= options.minSongMs) starts += c
        }
        if (totalMs > 0 && starts.size > 1 && totalMs - starts.last() < options.minSongMs) starts.removeAt(starts.lastIndex)
        return starts.mapIndexed { i, s -> Chapter(s, names[s] ?: "Song ${i + 1}") }
    }
}
