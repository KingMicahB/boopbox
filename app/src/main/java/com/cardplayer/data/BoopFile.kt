package com.cardplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.cardplayer.R
import com.cardplayer.util.MediaScan
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A `.boop` file: one card, ready to send to someone else. It is a plain zip holding
 *  - `card.json`: an [InboxCard] whose phone-local paths are written as `lib:<id>/<file>`,
 *  - every file on this phone the card plays or shows (songs, pictures), next to it.
 *
 * Loading one unpacks the files into `files/library/<id>/` and hands `card.json` to the same
 * inbox [PendingCardRepository.importInbox] uses for computer pushes, so the card waits for
 * the next unknown NFC card to be tapped (or updates the card that already has this id).
 * Links, podcasts and streams stay links; folders become playlists of the bundled files.
 */
object BoopFile {
    const val EXTENSION = "boop"
    /** Marks an inbox card that came out of a .boop (see [InboxCard.origin]). */
    const val ORIGIN = "boop"
    /** Sent as a generic file: WhatsApp, Gmail and file managers hand it back as this type, and BoopBox opens it. */
    const val MIME = "application/octet-stream"
    private const val CARD_ENTRY = "card.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")

    /** Writes [card] as a .boop into [out]. Runs on a background thread. */
    fun export(context: Context, card: Card, out: File) {
        // Re-exporting a card that came from a .boop / computer push keeps its id, so loading it again updates it.
        val id = Regex("/library/([A-Za-z0-9_-]+)/").find(card.uri + " " + card.artworkUrl.orEmpty() + " " + card.playlist.joinToString(" ") { it.uri })
            ?.groupValues?.get(1)
            ?: UUID.randomUUID().toString().replace("-", "").take(12)

        val files = LinkedHashMap<String, String>() // source uri -> name inside the zip
        val usedNames = mutableSetOf(CARD_ENTRY)
        fun pack(s: String?): String? {
            if (s == null || !isLocal(s)) return s
            files[s]?.let { return "lib:$id/$it" }
            val base = sanitize(displayName(context, s))
            var name = base
            var n = 2
            while (!usedNames.add(name.lowercase())) name = base.substringBeforeLast('.') + "-${n++}" + base.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            files[s] = name
            return "lib:$id/$name"
        }

        var c = card
        if (c.sourceType == SourceType.LOCAL_FOLDER) {
            // The other phone can't see this folder: send its songs as a playlist.
            val tracks = MediaScan.folderTracks(context, c.uri)
            if (tracks.isEmpty()) throw IllegalStateException(context.getString(R.string.msg_boop_empty_folder))
            c = c.copy(sourceType = SourceType.PLAYLIST, playlist = tracks.map { PlaylistItem(it.uri, it.title) })
        }
        val packed = c.copy(
            uid = "boop:$id",
            uri = when (c.sourceType) {
                SourceType.PLAYLIST -> "lib:$id/"
                else -> pack(c.uri)!!
            },
            artworkUrl = pack(c.artworkUrl),
            playlist = c.playlist.map { it.copy(uri = pack(it.uri)!!) },
            trackArt = c.trackArt.entries.associate { (k, v) -> pack(k)!! to pack(v)!! }
        )

        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(CARD_ENTRY))
            zip.write(json.encodeToString(InboxCard.serializer(), InboxCard(id, packed)).toByteArray())
            zip.closeEntry()
            for ((source, name) in files) {
                val input = open(context, source) ?: throw IllegalStateException(context.getString(R.string.msg_boop_cant_read, displayName(context, source, context.getString(R.string.msg_boop_unnamed_file))))
                zip.putNextEntry(ZipEntry(name))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Unpacks a .boop from [uri] (a file another app handed over, or an http(s) link to one) and queues its
     * card at the front of the waiting line. Returns the card's name. Runs on a background thread.
     */
    fun load(context: Context, uri: Uri, pending: PendingCardRepository): String {
        val tmp = File(context.cacheDir, "boop-in-${System.currentTimeMillis()}")
        tmp.mkdirs()
        try {
            val input = openSource(context, uri)
            ZipInputStream(input.buffered()).use { zip ->
                val root = tmp.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(tmp, entry.name)
                    // Never write outside the temp folder ("../" tricks in a zip someone sent you).
                    if (!target.canonicalPath.startsWith(root)) throw IllegalStateException(context.getString(R.string.msg_boop_not_boop))
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val cardFile = File(tmp, CARD_ENTRY)
            if (!cardFile.isFile) throw IllegalStateException(context.getString(R.string.msg_boop_not_boop))
            val push = try {
                json.decodeFromString(InboxCard.serializer(), cardFile.readText())
            } catch (_: Exception) {
                throw IllegalStateException(context.getString(R.string.msg_boop_damaged_or_newer))
            }
            if (!idPattern.matches(push.id)) throw IllegalStateException(context.getString(R.string.msg_boop_damaged))
            cardFile.delete()

            val lib = File(context.filesDir, "library/${push.id}")
            lib.mkdirs()
            tmp.walkTopDown().filter { it.isFile }.forEach { f ->
                val dest = File(lib, f.relativeTo(tmp).path)
                dest.parentFile?.mkdirs()
                dest.delete()
                if (!f.renameTo(dest)) f.copyTo(dest, overwrite = true)
            }
            val inbox = File(context.filesDir, "inbox")
            inbox.mkdirs()
            // A .boop never targets a particular NFC card on this phone.
            File(inbox, "${push.id}.json").writeText(json.encodeToString(InboxCard.serializer(), push.copy(targetUid = null, origin = ORIGIN)))
            pending.importInbox()
            return push.card.displayName(context)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** A file name for the exported card. */
    fun fileNameFor(context: Context, card: Card): String = sanitize(card.displayName(context)).ifBlank { "card" } + ".$EXTENSION"

    /** The share sheet (WhatsApp, email, Drive, Quick Share…) for a .boop packed by [export]. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.boop_share_text))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.editor_send_file, file.name))
    }

    /** The bytes behind [uri]: a stream another app granted us, or a download when it is an http(s) link. */
    private fun openSource(context: Context, uri: Uri): InputStream {
        if (uri.scheme == "http" || uri.scheme == "https") {
            val response = OkHttpClient().newCall(Request.Builder().url(uri.toString()).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IllegalStateException(context.getString(R.string.msg_boop_download_failed, response.code))
            }
            return response.body!!.byteStream()
        }
        return context.contentResolver.openInputStream(uri) ?: throw IllegalStateException(context.getString(R.string.msg_cant_open_file))
    }

    private fun isLocal(s: String) = s.startsWith("content://") || s.startsWith("file://")

    private fun open(context: Context, s: String): InputStream? = try {
        if (s.startsWith("file://")) File(Uri.parse(s).path!!).inputStream()
        else context.contentResolver.openInputStream(Uri.parse(s))
    } catch (_: Exception) {
        null
    }

    /** The file's own name; [fallback] stands in when it has none (also the name inside the zip, so it stays plain by default). */
    private fun displayName(context: Context, s: String, fallback: String = "file"): String {
        val uri = Uri.parse(s)
        val name = if (s.startsWith("content://")) DocumentFile.fromSingleUri(context, uri)?.name else uri.lastPathSegment
        return name ?: fallback
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().take(80)
}
