package com.cardplayer.sticker

import com.cardplayer.data.StickerDesign

/** One line of the sticker's song list. [key] is the song's key in the card (track uri, chapter key, video id). */
data class StickerRow(val key: String, val title: String, val icon: String?)

/** What the card plays, as far as the sticker's footer and starting layout care. */
enum class StickerKind { SONGS, CHAPTERS, AUDIOBOOK, PODCAST, MOVIE, LINK }

/**
 * Everything the card and its source say about themselves that a sticker can show: the starting point of every
 * sticker, before the grown-up swaps anything. Built by the card editor from what is on screen (saved or not).
 */
data class StickerSource(
    /** The card's uid: picks the pixel creature of a card with no picture. */
    val seed: String,
    val name: String,
    /** Author, artist or show. */
    val subtitle: String?,
    val picture: String?,
    val color: Long,
    val kind: StickerKind,
    val rows: List<StickerRow>,
    /** Total running time, when it could be worked out. */
    val durationMs: Long? = null,
    /** [StickerKind.LINK]: where the card plays ("Spotify", "YouTube"). */
    val linkLabel: String? = null
)

/** [StickerSource] with a [StickerDesign]'s swaps applied: what actually gets printed. */
class StickerContent(val source: StickerSource, val design: StickerDesign) {
    val title: String = (design.title ?: source.name).trim()

    /** Blank = no line. A line that only repeats the name is left out. */
    val subtitle: String = (design.subtitle ?: source.subtitle.orEmpty()).trim().let { s ->
        if (design.subtitle == null && (s.equals(title, ignoreCase = true) || title.contains(s, ignoreCase = true))) "" else s
    }

    val rows: List<StickerRow> = source.rows
        .filter { it.key !in design.hiddenRows }
        .map { row -> design.rowTitles[row.key]?.let { row.copy(title = it) } ?: row }
        .take(MAX_ROWS)

    /** Whether the song list is printed: as asked for, else as the template has it ([byDefault]). */
    fun listed(byDefault: Boolean): Boolean = rows.isNotEmpty() && (design.showList ?: byDefault)

    companion object {
        /** Two columns of thirteen is what still reads at card size. */
        const val MAX_ROWS = 26
    }
}
