package com.cardplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsTest {
    private val lib = "file:///data/user/0/com.boopboxapp/files/library"
    private val a = "rec0123456789ab"
    private val b = "recffffffffffff"
    private val c = "recabcdefabcdef"

    private fun take(folder: String) = "$lib/$folder/${Recordings.FILE_NAME}"

    @Test
    fun knowsItsOwnTakes() {
        assertTrue(Recordings.isRecording(take(a)))
        // Other things in the library, and files picked from the phone, are not takes.
        assertFalse(Recordings.isRecording("$lib/media0123456789ab/song.m4a"))
        assertFalse(Recordings.isRecording("$lib/$a/cover.jpg"))
        assertFalse(Recordings.isRecording("$lib/recording/${Recordings.FILE_NAME}"))
        assertFalse(Recordings.isRecording("content://com.android.providers.media.documents/document/audio%3A12"))
        assertFalse(Recordings.isRecording(""))
    }

    @Test
    fun onlyTakesNoCardPointsToAreUnused() {
        val cards = listOf(
            Card(uid = "1", uri = take(a)),
            Card(uid = "2", sourceType = SourceType.SPOTIFY, uri = "https://open.spotify.com/track/x")
        )
        assertEquals(listOf(b, c), Recordings.unused(listOf(a, b, c), cards))
        assertEquals(listOf(a, b, c), Recordings.unused(listOf(a, b, c), emptyList()))
    }

    @Test
    fun aCopyOnASecondTagKeepsTheTake() {
        val original = Card(uid = "1", uri = take(a))
        val copy = original.copy(uid = "2")
        // The original is deleted: the copy still plays it.
        assertEquals(emptyList<String>(), Recordings.unused(listOf(a), listOf(copy)))
    }

    @Test
    fun picturesUnpackedNextToATakeKeepItsFolder() {
        // A card that came in a .boop keeps its cover and sticker next to the recording; recorded again here,
        // it still shows those until they are replaced.
        val cards = listOf(
            Card(uid = "1", uri = take(b), artworkUrl = "$lib/$a/cover.jpg"),
            Card(uid = "2", uri = "https://example.com/stream.mp3", stickerUrl = "$lib/$c/sticker.png")
        )
        assertEquals(emptyList<String>(), Recordings.unused(listOf(a, b, c), cards))
        val perTrack = Card(uid = "3", sourceType = SourceType.PLAYLIST, uri = "playlist:3", trackArt = mapOf("ch:0" to "$lib/$a/icon.png"))
        assertEquals(listOf(b), Recordings.unused(listOf(a, b), listOf(perTrack)))
    }

    @Test
    fun neverNamesAnythingThatIsNotATakeFolder() {
        assertEquals(emptyList<String>(), Recordings.unused(listOf("media0123456789ab", "phone", "rec", "recXYZ"), emptyList()))
    }
}
