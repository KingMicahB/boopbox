package com.cardplayer.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Recordings made with the phone's microphone in the card editor ("Record it yourself").
 *
 * Each take is one file, `files/library/rec<id>/recording.m4a`, and the card that plays it is an ordinary
 * "File" card pointing there, so backups, .boop files, stickers and the split / trim editor treat it like any
 * other song in the library.
 *
 * A take can't be had again from anywhere, and nothing else tidies the library, so takes nobody plays any more
 * are removed here: one recorded again or abandoned in the editor straight away ([discardIfUnused]), a deleted
 * or re-recorded card's when no other card uses it ([cleanUpAfter]), and whatever else was left behind (the app
 * closed mid-edit, a card replaced by another) the next time the app starts ([sweep]). A take any card on the
 * phone still points to is never touched.
 */
object Recordings {
    const val FILE_NAME = "recording.m4a"
    /** [sweep] leaves a take folder alone until it has been untouched this long. */
    private const val SETTLE_MS = 10 * 60 * 1000L

    private val folderName = Regex("rec[0-9a-f]{12}")
    private val recordingUri = Regex("/library/(rec[0-9a-f]{12})/" + Regex.escape(FILE_NAME) + "$")
    private val folderInUri = Regex("/library/(rec[0-9a-f]{12})/")

    /** Where the next take goes: a folder of its own, made now. */
    fun newFile(context: Context): File {
        val id = "rec" + UUID.randomUUID().toString().replace("-", "").take(12)
        return File(File(libraryDir(context), id).apply { mkdirs() }, FILE_NAME)
    }

    /** True when [uri] is a take made here (on this phone, or on the phone a .boop or backup came from). */
    fun isRecording(uri: String): Boolean = recordingUri.containsMatchIn(uri)

    /**
     * Throws away the take at [uri], made in the editor and then recorded again or abandoned, unless one of
     * [cards] plays it after all. Does nothing for anything that is not a take.
     */
    fun discardIfUnused(context: Context, uri: String, cards: List<Card>) {
        val folder = recordingUri.find(uri)?.groupValues?.get(1) ?: return
        if (unused(listOf(folder), cards).isNotEmpty()) File(libraryDir(context), folder).deleteRecursively()
    }

    /**
     * [gone] was deleted, thrown away or saved afresh: the takes it pointed to go too, unless one of [remaining]
     * (every card now on the phone or waiting, the new version of [gone] among them) uses them.
     */
    fun cleanUpAfter(context: Context, gone: Card, remaining: List<Card>) {
        for (folder in unused(foldersOf(gone), remaining)) File(libraryDir(context), folder).deleteRecursively()
    }

    /**
     * Removes every take none of [cards] (the cards on the phone and the ones waiting for a tap) uses.
     * For when the app starts: later on, an open editor may hold a take that is not saved yet.
     */
    fun sweep(context: Context, cards: List<Card>) {
        // A card that arrived but hasn't joined the waiting line yet may be the only one using a take.
        val arriving = File(context.filesDir, "inbox").listFiles { f -> f.isFile && f.name.endsWith(".json") }
        if (!arriving.isNullOrEmpty()) return
        // Nor one that only just appeared: a .boop being unpacked this minute has no card yet either.
        val settled = System.currentTimeMillis() - SETTLE_MS
        val folders = libraryDir(context).listFiles { f -> f.isDirectory && folderName.matches(f.name) && f.lastModified() < settled } ?: return
        for (folder in unused(folders.map { it.name }, cards)) File(libraryDir(context), folder).deleteRecursively()
    }

    /** The ones of [folders] (take folders, by name) that nothing in [cards] points into. */
    internal fun unused(folders: Collection<String>, cards: List<Card>): List<String> {
        val used = cards.flatMapTo(HashSet<String>()) { foldersOf(it) }
        return folders.filter { folderName.matches(it) && it !in used }
    }

    /** Every take folder [card] points into: its recording, and pictures a .boop unpacked next to it. */
    private fun foldersOf(card: Card): Set<String> {
        val places = ArrayList<String>()
        places.add(card.uri)
        card.artworkUrl?.let { places.add(it) }
        card.stickerUrl?.let { places.add(it) }
        card.playlist.forEach { places.add(it.uri) }
        card.trackArt.forEach { (track, picture) ->
            places.add(track)
            places.add(picture)
        }
        val folders = HashSet<String>()
        for (place in places) folderInUri.findAll(place).forEach { folders.add(it.groupValues[1]) }
        return folders
    }

    private fun libraryDir(context: Context) = File(context.filesDir, "library")
}
