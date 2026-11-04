package com.cardplayer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Finds the playable files inside a folder picked with the system folder picker. */
object MediaScan {
    data class Track(val uri: String, val title: String)

    private val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka", "aiff", "amr", "mid",
        "mp4", "m4v", "mkv", "webm", "3gp", "ts"
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    /** Names that say "this picture is the cover", best first. */
    private val coverNames = listOf("cover", "folder", "front", "albumart", "album", "poster")

    private class Entry(val uri: Uri, val docId: String, val name: String, val mime: String) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /**
     * Every audio / video file in the folder (and up to three levels of sub-folders, e.g. "Disc 1"),
     * in natural name order: "Chapter 2" comes before "Chapter 10".
     */
    fun folderTracks(context: Context, treeUri: String): List<Track> {
        val tree = Uri.parse(treeUri)
        val out = mutableListOf<Entry>()
        fun walk(docId: String, depth: Int) {
            for (child in children(context, tree, docId)) {
                if (child.isDir) {
                    if (depth < 3) walk(child.docId, depth + 1)
                } else if (child.mime.startsWith("audio/") || child.mime.startsWith("video/") || child.ext in audioExtensions) {
                    out += child
                }
            }
        }
        walk(rootId(context, tree) ?: return emptyList(), 0)
        return out.map { Track(it.uri.toString(), it.name.substringBeforeLast('.').ifEmpty { it.name }) }
    }

    /** A cover picture lying in the folder itself (cover.jpg, folder.png…, else the only / first picture), as a content uri. */
    fun folderCover(context: Context, treeUri: String): String? {
        val tree = Uri.parse(treeUri)
        val images = children(context, tree, rootId(context, tree) ?: return null)
            .filter { !it.isDir && (it.mime.startsWith("image/") || it.ext in imageExtensions) }
        if (images.isEmpty()) return null
        val named = coverNames.firstNotNullOfOrNull { want -> images.firstOrNull { it.name.substringBeforeLast('.').lowercase() == want } }
        return (named ?: images.first()).uri.toString()
    }

    private fun rootId(context: Context, tree: Uri): String? = try {
        if (DocumentsContract.isDocumentUri(context, tree)) DocumentsContract.getDocumentId(tree) else DocumentsContract.getTreeDocumentId(tree)
    } catch (_: Exception) {
        null
    }

    /**
     * One query for a whole folder (asking each file for its name separately is very slow on a
     * book with hundreds of parts). Uris are built exactly like DocumentFile builds them, so
     * pictures and resume positions saved against older track uris still match.
     */
    private fun children(context: Context, tree: Uri, docId: String): List<Entry> {
        val list = mutableListOf<Entry>()
        try {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    list += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, c.getString(1) ?: "", c.getString(2) ?: "")
                }
            }
        } catch (_: Exception) {
        }
        return list.sortedWith(compareBy(naturalOrder) { it.name })
    }

    /** Case-insensitive, with runs of digits compared as numbers ("2" < "10", "007" = "7"). */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return@Comparator cmp
                i = ei
                j = ej
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) rest else a.compareTo(b, ignoreCase = true).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
