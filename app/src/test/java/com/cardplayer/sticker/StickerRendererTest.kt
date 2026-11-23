package com.cardplayer.sticker

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cardplayer.data.StickerDesign
import com.cardplayer.data.StickerPlace
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws real stickers on the computer, so a change to the renderer can be looked at without a phone.
 *
 * [samples] needs the card manifests and art that live (untracked) in the main checkout: point STICKER_SAMPLES
 * at that checkout and the stickers land in `app/build/sticker-samples/`. Without it only the art-free tests run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerRendererTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val files = StickerRenderer.Pictures { model, _ -> BitmapFactory.decodeFile(model) }

    private fun render(source: StickerSource, design: StickerDesign = StickerDesign()) =
        runBlocking { StickerRenderer.render(context, source, design, files) }

    private fun songs(n: Int) = (1..n).map { StickerRow("k$it", "Song number $it", null) }

    private fun plain(rows: List<StickerRow> = emptyList(), kind: StickerKind = StickerKind.SONGS) =
        StickerSource(seed = "04:AB:CD", name = "Animal songs", subtitle = "Someone", picture = null, color = 0xFFFFB74DL, kind = kind, rows = rows)

    @Test
    fun aCardWithNothingStillGetsASticker() {
        val result = render(plain())
        assertEquals(StickerRenderer.W, result.bitmap.width)
        assertEquals(StickerRenderer.H, result.bitmap.height)
        assertEquals(true, result.titleShown)
        // No songs: no list to tap.
        assertNull(result.regions.firstOrNull { it.part == StickerRenderer.Part.LIST })
        assertNotNull(result.partAt(300f, 990f))
    }

    @Test
    fun theListFollowsTheCardUntilSwapped() {
        val source = plain(songs(5))
        assertEquals(5, render(source).regions.count { it.part == StickerRenderer.Part.ROW })
        // One row taken off, the list taken off, an audiobook (no list unless asked for).
        assertEquals(4, render(source, StickerDesign(hiddenRows = setOf("k2"))).regions.count { it.part == StickerRenderer.Part.ROW })
        assertEquals(0, render(source, StickerDesign(showList = false)).regions.count { it.part == StickerRenderer.Part.ROW })
        assertEquals(0, render(plain(songs(5), StickerKind.AUDIOBOOK)).regions.count { it.part == StickerRenderer.Part.ROW })
        assertEquals(5, render(plain(songs(5), StickerKind.AUDIOBOOK), StickerDesign(showList = true)).regions.count { it.part == StickerRenderer.Part.ROW })
        // A row is found where it was drawn.
        val result = render(source)
        val third = result.regions.first { it.key == "k3" }.rect
        assertEquals("k3", result.partAt(third.centerX(), third.centerY())?.key)
    }

    @Test
    fun partsGoWhereTheyAreDragged() {
        // A made-up poster (2:3) in place of a real picture.
        val poster = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val painted = StickerRenderer.Pictures { _, _ -> poster }
        val source = plain(songs(5)).copy(picture = "poster")
        fun draw(design: StickerDesign, from: StickerSource = source) = runBlocking { StickerRenderer.render(context, from, design, painted) }
        fun rows(result: StickerRenderer.Result) = result.regions.count { it.part == StickerRenderer.Part.ROW }

        // Left alone, a few songs on a poster get the poster and the list of them: the poster prints its own name.
        val auto = draw(StickerDesign())
        assertEquals("picture-list", auto.layout)
        assertEquals(0f, auto.region(StickerRenderer.Part.PICTURE)!!.rect.top, 0.01f)
        assertEquals(false, auto.titleShown)
        assertEquals(5, rows(auto))

        // A book is its cover, whole: no name printed over it, no list.
        val book = draw(StickerDesign(), source.copy(kind = StickerKind.AUDIOBOOK))
        assertEquals("picture", book.layout)
        assertEquals(false, book.titleShown)
        assertEquals(false, book.listShown)

        // Each template starts with the name and the list it is named for...
        fun has(layout: String) = draw(StickerDesign(layout = layout)).let { it.titleShown to it.listShown }
        assertEquals(false to false, has("picture"))
        assertEquals(true to false, has("picture-name"))
        assertEquals(false to true, has("picture-list"))
        assertEquals(true to true, has("picture-list-name"))
        assertEquals(true to false, has("middle"))
        // ...and either can still be added or taken off.
        assertEquals(5, rows(draw(StickerDesign(layout = "picture", showList = true))))
        assertEquals(false, draw(StickerDesign(layout = "picture-list-name", showTitle = false)).titleShown)
        // A sticker made before the templates were split keeps the look it had.
        assertEquals("picture", draw(StickerDesign(layout = "poster")).layout)
        assertEquals("picture-list-name", draw(StickerDesign(layout = "block")).layout)

        // The box behind the name: the block of colour in another colour, and a box of the name's own elsewhere.
        assertNotNull(draw(StickerDesign(layout = "picture-name", titleBoxColor = 0xFF0B1647, titleBoxOpacity = 0.5f)).region(StickerRenderer.Part.TITLE))
        assertNotNull(draw(StickerDesign(layout = "picture", showTitle = true, titleBoxOpacity = 0.7f)).region(StickerRenderer.Part.TITLE))

        // Dragged small and half off the edge: it keeps its shape, at the width it was given.
        val picture = draw(StickerDesign(picturePlace = StickerPlace(-100f, 50f, 300f, 0f))).region(StickerRenderer.Part.PICTURE)!!.rect
        assertEquals(-100f, picture.left, 0.01f)
        assertEquals(300f, picture.width(), 0.01f)
        assertEquals(450f, picture.height(), 0.01f)

        // The list in a box of its own keeps every song while there is room, and drops the last ones when there isn't.
        val boxed = draw(StickerDesign(listPlace = StickerPlace(60f, 300f, 500f, 260f)))
        assertEquals(300f, boxed.region(StickerRenderer.Part.LIST)!!.rect.top, 0.01f)
        assertEquals(5, boxed.regions.count { it.part == StickerRenderer.Part.ROW })
        assertEquals(3, draw(StickerDesign(listPlace = StickerPlace(60f, 300f, 300f, 90f))).regions.count { it.part == StickerRenderer.Part.ROW })

        // The name: put somewhere, it is there, as tall as its box says.
        val named = draw(StickerDesign(titlePlace = StickerPlace(100f, 200f, 300f, 90f)))
        val title = named.region(StickerRenderer.Part.TITLE)!!
        assertEquals(250f, title.rect.centerX(), 0.01f)
        assertEquals(245f, title.rect.centerY(), 0.01f)
        assertEquals(60f, title.textSize, 0.01f)

        // A layout that keeps room for the name prints it; every layout draws something for every part it has.
        assertEquals(true, draw(StickerDesign(layout = "picture-name")).titleShown)
        for (layout in StickerRenderer.LAYOUTS) assertNotNull(draw(StickerDesign(layout = layout)).region(StickerRenderer.Part.PICTURE))
    }

    @Test
    fun aLongNameWrapsAndItsBoxCanBeTapped() {
        fun title(source: StickerSource, design: StickerDesign = StickerDesign()) = render(source, design).region(StickerRenderer.Part.TITLE)!!
        fun rows(region: StickerRenderer.Region) = if (region.rect.height() > region.textSize * 1.6f) 2 else 1

        // A short name is one row; a long one takes two rather than being squeezed small on one.
        assertEquals(1, rows(title(plain())))
        val long = title(plain().copy(name = "Harry Potter and the Philosopher's Stone, the full-cast edition"))
        assertEquals(2, rows(long))
        assertEquals(true, long.textSize >= 30f)
        // A line break that was typed is kept.
        assertEquals(2, rows(title(plain(), StickerDesign(title = "Animal\nsongs"))))

        // The block of colour behind the name is the name's box: tapping it (off the words) finds it.
        val poster = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val named = runBlocking { StickerRenderer.render(context, plain().copy(picture = "poster"), StickerDesign(layout = "picture-name"), { _, _ -> poster }) }
        val box = named.region(StickerRenderer.Part.NAMEBOX)!!.rect
        assertEquals(StickerRenderer.Part.NAMEBOX, named.partAt(box.left + 6f, box.top + 6f)?.part)
        // A name with a box of its own (asked for on a template that has none) has one to tap too.
        val boxed = runBlocking { StickerRenderer.render(context, plain().copy(picture = "poster"), StickerDesign(layout = "picture", showTitle = true, titleBoxOpacity = 0.6f), { _, _ -> poster }) }
        assertNotNull(boxed.region(StickerRenderer.Part.NAMEBOX))
    }

    @Test
    fun whatTheCardPlaysPicksTheLayout() {
        val poster = 2f / 3f
        val square = 1f
        val wide = 16f / 9f
        fun pick(kind: StickerKind, aspect: Float?, songs: Int = 0, name: String = "Animal songs") =
            StickerRenderer.recommended(plain(songs(songs), kind).copy(name = name), aspect)

        // No picture: the small-picture template, whatever the card plays.
        assertEquals("icon", pick(StickerKind.AUDIOBOOK, null))
        assertEquals("icon", render(plain(songs(5))).layout)

        // Songs and chapters: the list while it can be read, with the name unless the picture is a poster.
        assertEquals("picture-list", pick(StickerKind.SONGS, poster, songs = 5))
        assertEquals("picture-list-name", pick(StickerKind.SONGS, square, songs = 5))
        assertEquals("picture-list-name", pick(StickerKind.CHAPTERS, wide, songs = 10))
        // Too many to read on a card, only one, or names that would all be cut short: no list.
        assertEquals("picture-name", pick(StickerKind.SONGS, square, songs = 11))
        assertEquals("picture-name", pick(StickerKind.SONGS, square, songs = 1))
        val wordy = plain((1..4).map { StickerRow("k$it", "A song with a name that goes on and on and on $it", null) })
        assertEquals("picture-name", StickerRenderer.recommended(wordy, square))
        assertEquals("middle", pick(StickerKind.SONGS, wide, songs = 1))
        assertEquals("middle", pick(StickerKind.SONGS, wide, songs = 11))

        // A book or a film: the cover or poster alone; with the name when the picture isn't a poster.
        assertEquals("picture", pick(StickerKind.AUDIOBOOK, poster, songs = 30))
        assertEquals("picture-name", pick(StickerKind.AUDIOBOOK, square))
        assertEquals("picture", pick(StickerKind.MOVIE, poster))
        assertEquals("middle", pick(StickerKind.MOVIE, wide))

        // A show or a link: the picture and the name.
        assertEquals("picture-name", pick(StickerKind.PODCAST, square))
        assertEquals("picture-name", pick(StickerKind.LINK, square))
        assertEquals("middle", pick(StickerKind.LINK, wide))

        // A very long name under a poster is left to the poster.
        assertEquals("picture", pick(StickerKind.SONGS, poster, songs = 12, name = "A very long name for a card that would never fit under its poster"))
    }

    @Test
    fun samples() {
        val root = System.getenv("STICKER_SAMPLES")?.let(::File)
        assumeTrue("STICKER_SAMPLES not set", root != null && File(root, "stickers/art").isDirectory)
        val art = File(root, "stickers/art")
        val out = File("build/sticker-samples").apply { mkdirs() }
        fun save(name: String, bitmap: Bitmap) = File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        /** A card manifest's songs, with their yotoicons icons. */
        fun card(id: String): Pair<JSONObject, List<StickerRow>> {
            val json = JSONObject(File(root, "cards/$id.json").readText())
            val tracks = json.getJSONArray("tracks")
            val rows = (0 until tracks.length()).map { i ->
                val t = tracks.getJSONObject(i)
                val icon = t.optString("icon").removePrefix("yoto:").takeIf { it.isNotEmpty() }?.let { File(art, "icons/$it.png") }?.takeIf { it.isFile }
                StickerRow("t$i", t.optString("title"), icon?.path)
            }
            return json to rows
        }
        fun color(json: JSONObject) = 0xFF000000L or json.optString("color", "#888888").removePrefix("#").toLong(16)

        val (moana, moanaRows) = card("moana-music")
        val moanaSource = StickerSource("moana", moana.getString("name"), moana.optString("artist"), File(art, "yoto-moana.png").path, color(moana), StickerKind.SONGS, moanaRows, 26 * 60_000L)
        save("moana-music", render(moanaSource).bitmap)
        save(
            "moana-music-swapped",
            render(moanaSource, StickerDesign(title = "Moana!", showTitle = true, subtitle = "Songs for the car", rowTitles = mapOf("t0" to "Welcome"), hiddenRows = setOf("t7", "t10", "t11"))).bitmap
        )
        save("moana-music-no-list", render(moanaSource, StickerDesign(showList = false)).bitmap)
        for (layout in StickerRenderer.LAYOUTS) save("moana-layout-$layout", render(moanaSource, StickerDesign(layout = layout)).bitmap)
        save(
            "moana-dragged",
            render(
                moanaSource,
                StickerDesign(
                    layout = "picture-list-name",
                    picturePlace = StickerPlace(-120f, -40f, 520f, 0f),
                    titlePlace = StickerPlace(260f, 380f, 340f, 120f), titleFont = "script", titleColor = 0xFFFFD54F,
                    subtitle = "Car songs", subtitleFont = "casual",
                    listPlace = StickerPlace(40f, 560f, 558f, 350f), listFont = "condensed"
                )
            ).bitmap
        )
        save("moana-dark-box", render(moanaSource, StickerDesign(listColor = 0xFF0B1647, listOpacity = 0.6f)).bitmap)
        // The box behind the name: the block in another colour, see-through, and a box of the name's own on a full picture.
        save("moana-name-box-navy", render(moanaSource, StickerDesign(layout = "picture-list-name", titleBoxColor = 0xFF0B1647)).bitmap)
        save("moana-name-box-glass", render(moanaSource, StickerDesign(layout = "picture-list-name", titleBoxColor = 0xFFFFFFFF, titleBoxOpacity = 0.45f, titleColor = 0xFF0B1647)).bitmap)
        val hp = StickerSource("hp", "Harry Potter and the Philosopher's Stone (Full-Cast Edition)", "J.K. Rowling", File(art, "yoto-harry-potter-4.png").path, 0xFF26A69AL, StickerKind.AUDIOBOOK, emptyList(), (20 * 60 + 14) * 60_000L)
        save("long-name-block", render(hp, StickerDesign(layout = "picture-name")).bitmap)
        save("long-name-poster-box", render(hp, StickerDesign(layout = "picture", showTitle = true, titleBoxColor = 0xFF0B1647, titleBoxOpacity = 0.7f)).bitmap)
        save("long-name-icon", render(plain().copy(name = "Harry Potter and the Philosopher's Stone (Full-Cast Edition)")).bitmap)
        save("moana-name-box-poster", render(moanaSource, StickerDesign(layout = "picture", showTitle = true, titleBoxColor = 0xFF0B1647, titleBoxOpacity = 0.6f)).bitmap)
        save("moana-no-box", render(moanaSource, StickerDesign(listOpacity = 0f, listPlace = StickerPlace(30f, 600f, 578f, 320f))).bitmap)

        val (animals, animalRows) = card("rotemama-animals")
        save("rotemama-animals", render(StickerSource("animals", animals.getString("name"), null, File(art, "rotemama/rotemama-animals.jpg").path, color(animals), StickerKind.SONGS, animalRows, 18 * 60_000L)).bitmap)

        val (aladdin, aladdinRows) = card("aladdin-storyteller")
        save("aladdin-storyteller", render(StickerSource("aladdin", aladdin.getString("name"), null, File(art, "aladdin-storyteller.jpg").path, color(aladdin), StickerKind.CHAPTERS, aladdinRows, 57 * 60_000L)).bitmap)

        save("moana-movie", render(StickerSource("movie", "Moana", null, File(art, "movie/moana.png").path, 0xFF26A69AL, StickerKind.MOVIE, emptyList())).bitmap)
        save("yoto-daily", render(StickerSource("daily", "Yoto Daily", "A new show every day", File(art, "yoto-daily.png").path, 0xFFFF7043L, StickerKind.PODCAST, emptyList())).bitmap)
        save("harry-potter-4", render(StickerSource("hp4", "Harry Potter and the Goblet of Fire", "J.K. Rowling", File(art, "yoto-harry-potter-4.png").path, 0xFF26A69AL, StickerKind.AUDIOBOOK, songs(37), (20 * 60 + 14) * 60_000L)).bitmap)

        // No photo: the card's picture is a pixel icon; and a card with no picture at all.
        val (medley, medleyRows) = card("rotemama-medley")
        save("rotemama-medley-icon", render(StickerSource("medley", medley.getString("name"), "רותמאמא", medleyRows.first().icon, color(medley), StickerKind.SONGS, medleyRows, 25 * 60_000L)).bitmap)
        save("nothing", render(plain()).bitmap)
        save("nothing-few-songs", render(plain(songs(4))).bitmap)
        save("spotify-link", render(plain(kind = StickerKind.LINK).copy(linkLabel = "Spotify", picture = File(art, "aladdin-storyteller.jpg").path)).bitmap)
    }
}
