package com.cardplayer.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YotoStoreTest {
    private fun row(number: Int, title: String, icon: String?, hidden: Boolean = false) =
        """<li class="Tracklist_item__Ab12c"${if (hidden) " hidden=\"\"" else ""}><div class="Tracklist_leading__zmc1d">""" +
            (if (icon != null) """<span class="Tracklist_icon__vR49v" aria-hidden="true"><img src="https://media-static.yotomedia.com/icons/$icon" alt="" class="Tracklist_iconImage__jOC1g" loading="lazy"/></span>""" else "") +
            """<span class="Tracklist_number__TaCWe p">$number</span></div>""" +
            """<span class="Tracklist_trackTitle__Qq9zz p">$title</span><span class="Tracklist_duration__x1 p">12:34</span></li>"""

    private val page = """
        <html><head>
        <meta property="og:title" content="Tom &amp; the Dragon&#x27;s Cave (US)"/>
        <meta property="og:image" content="https://media-static.yotomedia.com/yoto/pub/COVER123"/>
        </head><body><ul>
        ${row(1, "Chapter One: The Worst Birthday", "aaa")}
        ${row(2, "Chapter Two: Dobby&#x27;s Warning", "bbb")}
        ${row(3, "Chapter Three: The Burrow", "ccc", hidden = true)}
        ${row(4, "Credits", null, hidden = true)}
        </ul></body></html>
    """.trimIndent()

    private val listing = YotoStore.parse("https://us.yotoplay.com/products/tom", page)

    @Test
    fun aStoreLinkIsToldFromAPictureLink() {
        assertTrue(YotoStore.isListing("https://us.yotoplay.com/products/harry-potter-and-the-chamber-of-secrets"))
        assertTrue(YotoStore.isListing(" https://uk.yotoplay.com/collections/all/products/some-card?variant=1 "))
        assertFalse(YotoStore.isListing("https://us.yotoplay.com/collections/audiobooks"))
        assertFalse(YotoStore.isListing("https://example.com/products/picture.png"))
        assertFalse(YotoStore.isListing("not a link"))
    }

    @Test
    fun thePageGivesItsArtItsNameAndEveryTrack() {
        assertEquals("Tom & the Dragon's Cave", listing.title)
        assertEquals("https://media-static.yotomedia.com/yoto/pub/COVER123", listing.cover)
        // Hidden rows count too: "See more" on the page only unhides them.
        assertEquals(listOf("Chapter One: The Worst Birthday", "Chapter Two: Dobby's Warning", "Chapter Three: The Burrow", "Credits"), listing.tracks.map { it.title })
        assertEquals("https://media-static.yotomedia.com/icons/bbb", listing.tracks[1].icon)
        assertNull(listing.tracks[3].icon)
        assertEquals(3, listing.icons.size)
        // A page that isn't a card's: nothing, rather than a crash.
        assertEquals(0, YotoStore.parse("x", "<html></html>").tracks.size)
    }

    @Test
    fun iconsGoToTheSongsWithTheSameName() {
        // The card's own songs, in another order and written a little differently.
        val songs = listOf("k1" to "The Burrow", "k2" to "03 - Dobby's warning", "k3" to "Something else entirely", "k4" to "The worst birthday")
        val icons = YotoStore.match(listing.tracks, songs)
        assertEquals("https://media-static.yotomedia.com/icons/ccc", icons["k1"])
        assertEquals("https://media-static.yotomedia.com/icons/bbb", icons["k2"])
        assertEquals("https://media-static.yotomedia.com/icons/aaa", icons["k4"])
        // No namesake, and the lists don't line up (four songs, three tracks besides the credits): left alone.
        assertNull(icons["k3"])
    }

    @Test
    fun aBookWithBareChapterMarksIsMatchedByPlace() {
        // An audiobook file: its marks only number the chapters, with credits before and after.
        val chapters = listOf("ch:0" to "Opening Credits", "ch:10" to "Chapter 1", "ch:20" to "Chapter 2", "ch:30" to "Chapter 3", "ch:40" to "End Credits")
        val icons = YotoStore.match(listing.tracks, chapters)
        assertEquals("https://media-static.yotomedia.com/icons/aaa", icons["ch:10"])
        assertEquals("https://media-static.yotomedia.com/icons/bbb", icons["ch:20"])
        assertEquals("https://media-static.yotomedia.com/icons/ccc", icons["ch:30"])
        assertNull(icons["ch:0"])
        assertNull(icons["ch:40"])
        // A different number of chapters: nothing is guessed.
        assertTrue(YotoStore.match(listing.tracks, chapters.take(3)).isEmpty())
    }
}
