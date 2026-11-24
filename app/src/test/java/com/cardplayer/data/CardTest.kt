package com.cardplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTest {
    @Test
    fun aCardSavedForLaterIsKnownByItsId() {
        assertTrue(Card(uid = Card.untaggedUid()).untagged)
        assertNotEquals(Card.untaggedUid(1L), Card.untaggedUid(2L))
    }

    @Test
    fun aRealCardNeverLooksSavedForLater() {
        // An NFC card's id is its tag's, in capital hex (NfcReader.uidOf).
        for (uid in listOf("04A1B2C3D4E5F6", "5E4F", "ABCDEF0123456789")) assertFalse(Card(uid = uid).untagged)
        // Nor do cards still waiting for one.
        for (uid in listOf("draft:1700000000000", "link:1700000000000", "shared:abc", "pending:abc")) assertFalse(Card(uid = uid).untagged)
    }
}
