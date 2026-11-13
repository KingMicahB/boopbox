package com.cardplayer.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.cardplayer.R
import com.cardplayer.audio.Audiobook
import com.cardplayer.util.MediaScan
import java.io.File
import java.util.UUID

/**
 * Audio / video files shared to BoopBox from another app (Files, WhatsApp, Drive, a downloads app…): an M4A,
 * an M4B audiobook, MP3s, a video. The sharing app only lends us the file for a moment, so each one is copied
 * into `files/library/<id>/` and made into a card that waits on the player for the next card tapped, like a
 * shared link.
 *  - one file with chapter marks, or any .m4b: an audiobook card (keeps its place, the file's cover and title),
 *  - one other file: a "File" card,
 *  - several files: a playlist in the order they were shared.
 */
object SharedMedia {
    private val mediaExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "aiff", "aif", "amr", "mid",
        "midi", "3ga", "mp4", "m4v", "mkv", "webm", "3gp", "ts", "mov"
    )

    /** True when [uri] (handed over as [type]) is a song / book / video rather than a .boop. */
    fun isMedia(context: Context, uri: Uri, type: String?): Boolean {
        val name = nameOf(context, uri)
        if (name?.endsWith(".${BoopFile.EXTENSION}", ignoreCase = true) == true) return false
        if (extension(name) in mediaExtensions) return true
        val mime = (context.contentResolver.getType(uri) ?: type).orEmpty().lowercase()
        return mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/ogg"
    }

    /** Copies [uris] into the app and queues the card they make. Returns its name. Runs on a background thread. */
    fun load(context: Context, uris: List<Uri>, pending: PendingCardRepository): String {
        require(uris.isNotEmpty())
        val id = "media" + UUID.randomUUID().toString().replace("-", "").take(12)
        val lib = File(context.filesDir, "library/$id").apply { mkdirs() }
        try {
            val used = mutableSetOf<String>()
            val files = uris.mapIndexed { i, uri ->
                val original = nameOf(context, uri) ?: "track${i + 1}.${guessExtension(context, uri)}"
                val base = sanitize(original).ifBlank { "track${i + 1}" }
                var name = base
                var n = 2
                while (!used.add(name.lowercase())) {
                    name = base.substringBeforeLast('.') + "-${n++}" + extension(base).let { if (it.isEmpty()) "" else ".$it" }
                }
                val dest = File(lib, name)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.msg_cant_open_file))
                input.use { src -> dest.outputStream().use { src.copyTo(it) } }
                Triple(dest, name, original.substringBeforeLast('.'))
            }

            val card = if (files.size == 1) {
                val (file, name, title) = files.single()
                val info = try {
                    Audiobook.readFile(context, Uri.fromFile(file)) { n -> context.getString(R.string.book_chapter_number, n) }
                } catch (_: Exception) {
                    null
                }
                val book = extension(name) == "m4b" || info?.chapters?.isNotEmpty() == true
                Card(
                    uid = "media:$id",
                    name = info?.title?.takeIf { book } ?: title,
                    subtitle = info?.author,
                    sourceType = SourceType.LOCAL_FILE,
                    uri = Uri.fromFile(File(lib, name)).toString(),
                    artworkUrl = info?.cover?.let { Audiobook.saveCover(context, it) },
                    chapters = info?.chapters.orEmpty(),
                    audiobook = book,
                    resume = book
                )
            } else {
                // The first file's album tag names the playlist; failing that, the first file's own name.
                val first = try {
                    Audiobook.readFolder(context, listOf(MediaScan.Track(Uri.fromFile(files.first().first).toString(), "")))
                } catch (_: Exception) {
                    null
                }
                Card(
                    uid = "media:$id",
                    name = first?.title ?: files.first().third,
                    subtitle = first?.author,
                    sourceType = SourceType.PLAYLIST,
                    uri = Uri.fromFile(lib).toString(),
                    artworkUrl = first?.cover?.let { Audiobook.saveCover(context, it) },
                    playlist = files.map { (_, name, title) -> PlaylistItem(Uri.fromFile(File(lib, name)).toString(), title) }
                )
            }

            // Waits at the front of the queue like a shared link: a card of the grown-up's own.
            pending.addLink(card)
            return card.displayName(context)
        } catch (e: Exception) {
            lib.deleteRecursively()
            throw e
        }
    }

    private fun nameOf(context: Context, uri: Uri): String? = try {
        if (uri.scheme == "file") uri.lastPathSegment else DocumentFile.fromSingleUri(context, uri)?.name
    } catch (_: Exception) {
        null
    }

    private fun guessExtension(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "m4a"

    private fun extension(name: String?) = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    private fun sanitize(name: String) = name.replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_").trim().take(80)
}
