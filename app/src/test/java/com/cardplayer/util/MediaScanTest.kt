package com.cardplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaScanTest {
    @Test
    fun numbersSortAsNumbers() {
        val names = listOf("Chapter 10.mp3", "chapter 2.mp3", "Chapter 1.mp3", "Intro.mp3", "Chapter 02b.mp3", "Disc 2", "Disc 10", "Disc 1")
        assertEquals(
            listOf("Chapter 1.mp3", "chapter 2.mp3", "Chapter 02b.mp3", "Chapter 10.mp3", "Disc 1", "Disc 2", "Disc 10", "Intro.mp3"),
            names.sortedWith(MediaScan.naturalOrder)
        )
    }

    @Test
    fun leadingZerosAndTies() {
        assertEquals(listOf("01 - a", "1 - b", "002 - c", "10 - d"), listOf("10 - d", "002 - c", "1 - b", "01 - a").sortedWith(MediaScan.naturalOrder))
        assertEquals(listOf("Part.mp3", "part.mp3"), listOf("part.mp3", "Part.mp3").sortedWith(MediaScan.naturalOrder))
    }
}
