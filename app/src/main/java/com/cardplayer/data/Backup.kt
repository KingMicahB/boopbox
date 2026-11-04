package com.cardplayer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cardplayer.R
import com.cardplayer.audio.Audiobook
import com.cardplayer.util.MediaScan
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A whole-phone backup: one zip with every card, the settings, resume positions, the Yoto sign-in
 * `library/` (songs and pictures pushed from the computer or loaded from .boop files) and `covers/`
 * (covers taken out of audiobook files).
 *
 * Songs, audiobooks and pictures picked straight from the phone's own folders are copied into `phone/`
 * (listed in `phone.json`): their `content://` grants don't survive a move to another phone or a reinstall.
 * Restoring keeps a card on the phone's own file while that still opens, and only switches it to the copy
 * (a folder card becomes a playlist of its songs) when it doesn't.
 *
 * Not included: the podcast cache (re-downloaded on demand).
 */
object Backup {
    private val FILES = listOf("cards.json", "settings.json", "positions.json", "yoto.json")
    /** Folders copied whole: songs and pictures from pushes / .boop files, and covers taken out of audiobook files. */
    private val FOLDERS = listOf("library", Audiobook.COVERS_DIR)
    /** Copies of the phone's own files, inside the zip; restored under `library/phone/`. */
    private const val PHONE_DIR = "phone"
    private const val PHONE_INDEX = "phone.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val cardsSerializer = ListSerializer(Card.serializer())
    private val positionsSerializer = MapSerializer(String.serializer(), SavedPosition.serializer())

    @Serializable
    private data class PhoneFiles(
        /** `content://` uri -> its copy inside the zip (`phone/<name>`). */
        val files: Map<String, String> = emptyMap(),
        /** Folder card's tree uri -> its songs, in play order, as they were when backed up. */
        val folders: Map<String, List<PlaylistItem>> = emptyMap()
    )

    fun fileName(): String =
        "boopbox-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"

    /** Writes the backup to [out]. Runs on a background thread. */
    fun export(context: Context, out: OutputStream) {
        val root = context.filesDir
        ZipOutputStream(out.buffered()).use { zip ->
            for (name in FILES) {
                val f = File(root, name)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (folder in FOLDERS) {
                val dir = File(root, folder)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("$folder/$rel"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val cards = try {
                File(root, "cards.json").takeIf { it.isFile }?.let { json.decodeFromString(cardsSerializer, it.readText()) }
            } catch (_: Exception) {
                null
            }
            val phone = packPhoneFiles(context, cards.orEmpty(), zip)
            if (phone.files.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(PHONE_INDEX))
                zip.write(json.encodeToString(PhoneFiles.serializer(), phone).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** Copies every phone file the cards play or show into `phone/` in [zip]. A file that no longer opens is left out. */
    private fun packPhoneFiles(context: Context, cards: List<Card>, zip: ZipOutputStream): PhoneFiles {
        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashMap<String, List<PlaylistItem>>()
        fun add(s: String?) {
            if (s == null || !s.startsWith("content://") || s in files) return
            val input = try {
                context.contentResolver.openInputStream(Uri.parse(s))
            } catch (_: Exception) {
                null
            } ?: return
            val name = "$PHONE_DIR/" + shortHash(s) + "-" + sanitize(DocumentFile.fromSingleUri(context, Uri.parse(s))?.name ?: "file")
            input.use {
                zip.putNextEntry(ZipEntry(name))
                it.copyTo(zip)
                zip.closeEntry()
            }
            files[s] = name
        }
        for (card in cards) {
            if (card.sourceType == SourceType.LOCAL_FOLDER) {
                val tracks = MediaScan.folderTracks(context, card.uri)
                if (tracks.isNotEmpty()) folders[card.uri] = tracks.map { PlaylistItem(it.uri, it.title) }
                tracks.forEach { add(it.uri) }
            } else {
                add(card.uri)
            }
            add(card.artworkUrl)
            card.playlist.forEach { add(it.uri) }
            card.trackArt.forEach { (k, v) -> add(k); add(v) }
        }
        return PhoneFiles(files, folders.filterValues { list -> list.any { it.uri in files } })
    }

    /**
     * Puts the backup's files in place of this phone's. The caller stops playback first and
     * reloads the stores afterwards. Runs on a background thread. Returns the number of cards.
     */
    fun import(context: Context, input: InputStream): Int {
        val root = context.filesDir
        val tmp = File(context.cacheDir, "backup-in-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(input.buffered()).use { zip ->
                val base = tmp.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(tmp, entry.name)
                    // Never write outside the temp folder ("../" tricks in a zip someone sent you).
                    if (!target.canonicalPath.startsWith(base)) throw IllegalStateException(context.getString(R.string.msg_backup_not_backup))
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            val cardsFile = File(tmp, "cards.json")
            if (!cardsFile.isFile) throw IllegalStateException(context.getString(R.string.msg_backup_not_backup))
            val cards = try {
                json.decodeFromString(cardsSerializer, cardsFile.readText())
            } catch (_: Exception) {
                throw IllegalStateException(context.getString(R.string.msg_backup_damaged_or_newer))
            }
            // Library paths are absolute; on a phone with a different user profile the app folder moves.
            val filesUri = Uri.fromFile(root).toString().trimEnd('/')
            val phone = try {
                File(tmp, PHONE_INDEX).takeIf { it.isFile }?.let { json.decodeFromString(PhoneFiles.serializer(), it.readText()) }
            } catch (_: Exception) {
                null
            } ?: PhoneFiles()
            // Phone files that don't open here (another phone, or the app was reinstalled): play the copies instead.
            val moved = HashMap<String, String>()
            val restored = cards.map { relocate(it, filesUri) }.map { card ->
                if (needsCopies(context, card, phone)) useCopies(card, phone, root, moved) else card
            }
            cardsFile.writeText(json.encodeToString(cardsSerializer, restored))
            // Resume positions point at the track's uri.
            File(tmp, "positions.json").takeIf { moved.isNotEmpty() && it.isFile }?.let { f ->
                try {
                    val positions = json.decodeFromString(positionsSerializer, f.readText())
                    f.writeText(json.encodeToString(positionsSerializer, positions.mapValues { (_, p) -> moved[p.mediaId]?.let { p.copy(mediaId = it) } ?: p }))
                } catch (_: Exception) {
                }
            }
            val usedCopies = phone.files.filterKeys { it in moved }.values.toSet()
            for (path in usedCopies) {
                val src = File(tmp, path)
                if (!src.isFile) continue
                val dest = File(root, "library/$path")
                dest.parentFile?.mkdirs()
                dest.delete()
                if (!src.renameTo(dest)) src.copyTo(dest, overwrite = true)
            }

            for (name in FILES) {
                val src = File(tmp, name)
                if (!src.isFile) continue
                val dst = File(root, name)
                dst.delete()
                if (!src.renameTo(dst)) src.copyTo(dst, overwrite = true)
            }
            for (folder in FOLDERS) {
                val src = File(tmp, folder)
                if (!src.isDirectory) continue
                val dst = File(root, folder)
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    val dest = File(dst, f.relativeTo(src).path)
                    dest.parentFile?.mkdirs()
                    dest.delete()
                    if (!f.renameTo(dest)) f.copyTo(dest, overwrite = true)
                }
            }
            return cards.size
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** True when the card plays a backed-up phone file (or folder) that this phone can't open. */
    private fun needsCopies(context: Context, card: Card, phone: PhoneFiles): Boolean {
        if (card.sourceType == SourceType.LOCAL_FOLDER) {
            return card.uri in phone.folders && MediaScan.folderTracks(context, card.uri).isEmpty()
        }
        return (listOf(card.uri) + card.playlist.map { it.uri }).any { it in phone.files && !opens(context, it) }
    }

    /** Points [card] at the restored copies; every swapped uri goes into [moved] (old -> new). */
    private fun useCopies(card: Card, phone: PhoneFiles, root: File, moved: MutableMap<String, String>): Card {
        var c = card
        if (c.sourceType == SourceType.LOCAL_FOLDER) {
            // The folder itself can't come back: play its songs as a playlist.
            val songs = phone.folders[c.uri]?.filter { it.uri in phone.files } ?: return c
            c = c.copy(sourceType = SourceType.PLAYLIST, uri = "playlist:${c.uid}", playlist = songs)
        }
        fun r(s: String): String {
            val path = phone.files[s] ?: return s
            return Uri.fromFile(File(root, "library/$path")).toString().also { moved[s] = it }
        }
        return c.copy(
            uri = r(c.uri),
            artworkUrl = c.artworkUrl?.let(::r),
            playlist = c.playlist.map { it.copy(uri = r(it.uri)) },
            trackArt = c.trackArt.entries.associate { (k, v) -> r(k) to r(v) },
            clips = c.clips.mapKeys { (k, _) -> r(k) }
        )
    }

    private fun opens(context: Context, s: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(s))?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().take(80)

    private val folderPath = Regex("^file://.*?/files(?=/(${FOLDERS.joinToString("|")})/)")

    private fun relocate(card: Card, filesUri: String): Card {
        fun r(s: String): String = folderPath.replace(s, filesUri)
        return card.copy(
            uri = r(card.uri),
            artworkUrl = card.artworkUrl?.let(::r),
            playlist = card.playlist.map { it.copy(uri = r(it.uri)) },
            trackArt = card.trackArt.entries.associate { (k, v) -> r(k) to r(v) }
        )
    }
}
