package com.cardplayer.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three catalogues' answers, cut down from real ones. */
class ArtworkSearchTest {
    @Test
    fun itunesGivesABiggerPictureAndTheRightName() {
        val body = """{"resultCount":3,"results":[
            {"wrapperType":"audiobook","artistName":"Julia Donaldson","collectionName":"The Gruffalo (Unabridged)","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/b.jpg/100x100bb.jpg"},
            {"wrapperType":"track","kind":"podcast","artistName":"Stinky Dragon","collectionName":"Tales","trackName":"Tales from the Stinky Dragon","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/c/d.jpg/100x100bb.jpg"},
            {"wrapperType":"track","artistName":"No picture","trackName":"Left out"}
        ]}"""
        val found = ArtworkSearch.parseItunes(body)
        assertEquals(2, found.size)
        assertEquals("The Gruffalo (Unabridged)", found[0].title)
        assertEquals("Julia Donaldson", found[0].subtitle)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/a/b.jpg/600x600bb.jpg", found[0].url)
        // A song or a podcast goes by its own name, not its collection's.
        assertEquals("Tales from the Stinky Dragon", found[1].title)
    }

    @Test
    fun deezerLeavesOutAlbumsWithoutACover() {
        val body = """{"data":[
            {"title":"Moana","cover_big":"https://cdn-images.dzcdn.net/images/cover/8cd3/500x500-000000-80-0-0.jpg","cover_xl":"https://cdn-images.dzcdn.net/images/cover/8cd3/1000x1000-000000-80-0-0.jpg","artist":{"name":"Lin-Manuel Miranda"}},
            {"title":"Only a middling picture","cover_big":"https://cdn-images.dzcdn.net/images/cover/717f/500x500-000000-80-0-0.jpg","cover_xl":null,"artist":{"name":"Disney"}},
            {"title":"No cover","cover_big":"https://cdn-images.dzcdn.net/images/cover//500x500-000000-80-0-0.jpg","cover_xl":"https://cdn-images.dzcdn.net/images/cover//1000x1000-000000-80-0-0.jpg","artist":{"name":"Nobody"}}
        ],"total":3}"""
        val found = ArtworkSearch.parseDeezer(body)
        assertEquals(listOf("Moana", "Only a middling picture"), found.map { it.title })
        assertTrue(found[0].url.contains("1000x1000"))
        assertTrue(found[1].url.contains("500x500"))
        assertEquals("Lin-Manuel Miranda", found[0].subtitle)
        // Asked too often, Deezer answers with an error and no list.
        assertTrue(ArtworkSearch.parseDeezer("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""").isEmpty())
    }

    @Test
    fun openLibraryGivesBooksThatHaveACover() {
        val body = """{"numFound":3,"docs":[
            {"author_name":["Julia Donaldson","Axel Scheffler","Somebody Else"],"cover_i":8561698,"title":"The Gruffalo"},
            {"author_name":["Julia Donaldson"],"title":"No cover on file"},
            {"cover_i":575575,"title":"The Gruffalo's Child"}
        ]}"""
        val found = ArtworkSearch.parseOpenLibrary(body)
        assertEquals(2, found.size)
        assertEquals("https://covers.openlibrary.org/b/id/8561698-L.jpg", found[0].url)
        // The grid of results shows a smaller one: the big cover is slow to come.
        assertEquals("https://covers.openlibrary.org/b/id/8561698-M.jpg", found[0].thumb)
        assertEquals("Julia Donaldson, Axel Scheffler", found[0].subtitle)
        assertEquals("", found[1].subtitle)
    }
}
