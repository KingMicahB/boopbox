package com.cardplayer.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.math.log10

/**
 * Records the phone's microphone into one .m4a file (AAC, mono): a story read aloud, a song sung, a message
 * from a grandparent. Made for the card editor: one take at a time, with pause and carry on.
 *
 * Call everything from the main thread. The microphone permission must already be granted.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    /** Recorded time before the current stretch (everything up to the last pause). */
    private var bankedMs = 0L
    /** When the current stretch began ([SystemClock.elapsedRealtime]); 0 while paused. */
    private var stretchStart = 0L

    /** A take is under way (recording or paused). */
    val active: Boolean get() = recorder != null

    var paused = false
        private set

    /** Called when the recorder stops by itself: the take is as long as it may be, the phone is full, or the microphone was lost. */
    var onStoppedItself: (() -> Unit)? = null

    /** The phone is too full to record. */
    class NoRoomException : IOException()

    /** Starts a take into [out]. Throws when the microphone can't be had, or [NoRoomException] when the phone is full. */
    fun start(out: File) {
        cancel()
        out.parentFile?.mkdirs()
        val room = (out.parentFile?.usableSpace ?: 0L) - KEEP_FREE_BYTES
        if (room < MIN_ROOM_BYTES) throw NoRoomException()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(MAX_DURATION_MS)
            r.setMaxFileSize(room)
            r.setOutputFile(out.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) onStoppedItself?.invoke()
            }
            r.setOnErrorListener { _, _, _ -> onStoppedItself?.invoke() }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try {
                r.release()
            } catch (_: Exception) {
            }
            out.delete()
            throw e
        }
        recorder = r
        file = out
        paused = false
        bankedMs = 0L
        stretchStart = SystemClock.elapsedRealtime()
    }

    fun pause() {
        val r = recorder ?: return
        if (paused) return
        try {
            r.pause()
        } catch (_: Exception) {
            return
        }
        bankedMs = elapsedMs()
        stretchStart = 0L
        paused = true
    }

    fun resume() {
        val r = recorder ?: return
        if (!paused) return
        try {
            r.resume()
        } catch (_: Exception) {
            return
        }
        stretchStart = SystemClock.elapsedRealtime()
        paused = false
    }

    /** How much has been recorded so far (pauses not counted). */
    fun elapsedMs(): Long = bankedMs + if (stretchStart > 0L) SystemClock.elapsedRealtime() - stretchStart else 0L

    /** How loud the microphone has been since the last call, 0 (silence) to 1 (as loud as it gets). */
    fun level(): Float {
        val r = recorder ?: return 0f
        if (paused) return 0f
        val peak = try {
            r.maxAmplitude
        } catch (_: Exception) {
            0
        }
        if (peak <= 0) return 0f
        val db = 20f * log10(peak / 32767f)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    /** Ends the take and keeps it. Null when nothing usable was recorded (stopped at once); the file is then gone. */
    fun finish(): File? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        paused = false
        stretchStart = 0L
        val ok = try {
            r.stop()
            true
        } catch (_: Exception) {
            // MediaRecorder throws when it was stopped before any sound arrived.
            false
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
        if (out == null) return null
        // A recorder that already stopped by itself (as long as a take may be, phone full) can refuse the stop
        // and still have written a whole file: what plays is kept.
        if (out.length() == 0L || (!ok && !plays(out))) {
            out.delete()
            return null
        }
        return out
    }

    private fun plays(f: File): Boolean {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(f.absolutePath)
            (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
        } catch (_: Exception) {
            false
        } finally {
            try {
                reader.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Ends the take and throws it away. */
    fun cancel() {
        val out = file
        finish()
        out?.delete()
    }

    companion object {
        /** Long enough for any bedtime story; a recorder left running by mistake stops here. */
        private const val MAX_DURATION_MS = 3 * 60 * 60 * 1000
        /** Never fill the phone to the brim. */
        private const val KEEP_FREE_BYTES = 50L * 1024 * 1024
        private const val MIN_ROOM_BYTES = 1L * 1024 * 1024
        /** The quietest sound the level meter shows. */
        private const val FLOOR_DB = -50f
    }
}
