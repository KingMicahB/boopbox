package com.cardplayer.audio

import com.cardplayer.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Chapter marks read straight out of real files. The fixtures in `resources/audiobook/` were made with ffmpeg 8
 * from a 30 s tone and three chapters ("Opening", a Hebrew name with an emoji, "The End"):
 *  - book.m4b: QuickTime chapter track + Nero `chpl` (ffmpeg's default)
 *  - book-qt-only.m4b: `-movflags +disable_chpl`
 *  - book-nero-only.m4b: book.m4b with its `tref/chap` renamed, so only `chpl` is left
 *  - book.mp3: ID3v2.3 CHAP frames
 *  - long.m4b: 300 chapters of 200 ms (more than `chpl`'s 255 limit)
 *  - plain.m4a: no chapters
 */
class AudiobookTest {
    private val three = listOf(
        Chapter(0, "Opening"),
        Chapter(10_000, "פרק שני 🐻"),
        Chapter(20_000, "The End")
    )

    private fun marks(name: String): List<Chapter> {
        val url = javaClass.getResource("/audiobook/$name") ?: error("missing fixture $name")
        return RandomAccessFile(File(url.toURI()), "r").channel.use { Audiobook.chapterMarks(it) }
    }

    @Test
    fun m4bWithChapterTrackAndNero() = assertEquals(three, marks("book.m4b"))

    @Test
    fun m4bWithChapterTrackOnly() = assertEquals(three, marks("book-qt-only.m4b"))

    @Test
    fun m4bWithNeroChaptersOnly() = assertEquals(three, marks("book-nero-only.m4b"))

    @Test
    fun mp3WithId3Chapters() = assertEquals(three, marks("book.mp3"))

    @Test
    fun moreChaptersThanNeroCanHold() {
        val long = marks("long.m4b")
        assertEquals(300, long.size)
        assertEquals(Chapter(299 * 200L, "Part 300"), long.last())
        assertEquals(long.sortedBy { it.startMs }, long)
    }

    @Test
    fun fileWithoutChapters() = assertTrue(marks("plain.m4a").isEmpty())

    @Test
    fun tidySortsNamesAndStartsAtZero() {
        val raw = listOf(Chapter(9_000, ""), Chapter(2_000, "Intro"), Chapter(9_000, "dupe"), Chapter(99_000, "past the end"))
        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(9_000, "Chapter 2")),
            Audiobook.tidy(raw, durationMs = 60_000) { "Chapter $it" }
        )
    }

    @Test
    fun oneChapterIsNoChapters() = assertTrue(Audiobook.tidy(listOf(Chapter(0, "All")), 60_000) { "Chapter $it" }.isEmpty())
}
