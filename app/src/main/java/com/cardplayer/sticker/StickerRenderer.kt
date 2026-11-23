package com.cardplayer.sticker

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.cardplayer.R
import com.cardplayer.data.StickerDesign
import com.cardplayer.data.StickerPlace
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Draws a card's sticker: print-ready, a Yoto card's face (54 x 85.6 mm at 300 dpi = 638 x 1011 px).
 * The same drawing is the preview in the sticker maker and the picture that gets printed.
 *
 * Layers, bottom to top: a background, the picture, the name and a line under it, the song list on a frosted
 * panel, the navy BoopBox bar. A layout ([StickerDesign.layout], else picked from the picture's shape) says
 * where each starts out; every part can then be dragged and resized anywhere ([StickerDesign.picturePlace] ...).
 *  - "poster": a poster or book cover fills the card and runs on behind the list; it carries its own title,
 *  - "block": the picture across the top, the name on a block of the card's colour under it,
 *  - "middle": a wide video still across the middle of a blurred, darkened copy of itself,
 *  - "icon": a pixel icon (or the card's pixel creature) big on a checker of the card's colour.
 */
object StickerRenderer {
    const val W = 638
    const val H = 1011
    private const val FOOT = 64
    /** Everything above the BoopBox bar. */
    const val BLEED = H - FOOT
    private val NAVY = Color.rgb(11, 22, 71)

    /** A picture this small is pixel art (a 16 x 16 yotoicons icon): drawn with hard edges, never as a photo. */
    private const val TINY = 96
    /** Wider than this and the picture is a video still, not a cover. */
    private const val WIDE = 1.25f

    val LAYOUTS = listOf("poster", "block", "middle", "icon")
    /** Font names a design may use; the first is the sticker's own. The rest are the phone's. */
    val FONTS = listOf("rubik", "black", "condensed", "serif", "casual", "script", "mono")

    /** The parts of a sticker that can be picked, the one on top first. */
    enum class Part { ROW, LIST, TITLE, SUBTITLE, PICTURE, FOOTER }

    /**
     * Where a part was drawn, in sticker pixels (a picture's box may hang off the card).
     * [key] = the song's key, for a [Part.ROW]. [text] / [textSize] = where its words are and how big: the
     * sticker maker lays its typing cursor over them.
     */
    data class Region(val part: Part, val rect: RectF, val key: String? = null, val text: RectF? = null, val textSize: Float = 0f)

    class Result(val bitmap: Bitmap, val regions: List<Region>, val titleShown: Boolean) {
        /** What is at this spot (sticker pixels). */
        fun partAt(x: Float, y: Float): Region? =
            Part.entries.firstNotNullOfOrNull { part -> regions.firstOrNull { it.part == part && it.rect.contains(x, y) } }

        fun region(part: Part, key: String? = null): Region? = regions.firstOrNull { it.part == part && (key == null || it.key == key) }
    }

    /** Fetches a picture (a link, a file, a content uri), no bigger than needed. */
    fun interface Pictures {
        suspend fun load(model: String, maxSide: Int): Bitmap?
    }

    /** Draws the sticker. Fetching its pictures may take a moment: call from a background thread. */
    suspend fun render(
        context: Context,
        source: StickerSource,
        design: StickerDesign,
        pictures: Pictures = coilPictures(context)
    ): Result {
        val content = StickerContent(source, design)
        val picture = source.picture?.let { pictures.load(it, 1600) }
        val icons = HashMap<String, Bitmap>()
        // Every song's icon is wanted even when the list is off: they decorate a card that has no photo.
        for (url in source.rows.mapNotNull { it.icon }.distinct().take(StickerContent.MAX_ROWS)) {
            pictures.load(url, 256)?.let { icons[url] = it }
        }
        return draw(context, content, picture, icons)
    }

    fun coilPictures(context: Context) = Pictures { model, maxSide ->
        try {
            val request = ImageRequest.Builder(context)
                .data(model)
                .allowHardware(false)
                .size(maxSide)
                .scale(Scale.FIT)
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The typeface behind a font name of [FONTS] (null / unknown = the sticker's own). */
    fun typeface(context: Context, name: String?, bold: Boolean): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (name) {
            "black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
            "condensed" -> Typeface.create("sans-serif-condensed", style)
            "serif" -> Typeface.create(Typeface.SERIF, style)
            "casual" -> Typeface.create("casual", style)
            "script" -> Typeface.create("cursive", style)
            "mono" -> Typeface.create(Typeface.MONOSPACE, style)
            else -> fonts(context).let { if (bold) it.bold else it.regular }
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun draw(context: Context, content: StickerContent, picture: Bitmap?, icons: Map<String, Bitmap>): Result {
        val source = content.source
        val design = content.design
        val color = (source.color.toInt() and 0xFFFFFF) or (0xFF shl 24)
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        val regions = mutableListOf<Region>()
        val w = W.toFloat()
        val bleedH = BLEED.toFloat()

        val rows = content.rows
        val listed = content.listed
        val title = content.title
        val rtl = HEBREW.containsMatchIn(title + rows.joinToString("") { it.title })
        fun iconOf(row: StickerRow): Bitmap? = row.icon?.let { icons[it] }

        // ---- the song list, where the layout puts it, decides how much room the top gets
        val autoCols = if (rows.size <= 7) 1 else 2
        val autoPer = if (listed) (rows.size + autoCols - 1) / autoCols else 0
        val autoRowH = if (autoCols == 1) 50f else if (autoPer <= 7) 44f else 37f
        val autoListH = if (listed) autoPer * autoRowH + 2 * LIST_PAD else 0f
        val artH = bleedH - autoListH
        val listRect = if (!listed) null else design.listPlace?.let(::box) ?: RectF(0f, artH, w, bleedH)

        val tiny = picture != null && max(picture.width, picture.height) <= TINY
        val photo = picture?.takeIf { !tiny }
        val aspect = photo?.let { it.width.toFloat() / it.height } ?: 1f
        val ph = w / aspect // a photo's height when it spans the card
        val mode = when {
            photo == null -> "icon"
            design.layout in LAYOUTS -> design.layout!!
            aspect >= WIDE && !listed -> "middle"
            artH - ph >= 40f -> "block"
            else -> "poster"
        }

        // ---- background, and where the layout puts the picture
        c.save()
        c.clipRect(0f, 0f, w, bleedH)
        var pic: RectF
        var picClip: RectF? = null
        /** Block layout: where the picture stops and the block of colour starts. */
        var blockTop = 0f
        when (mode) {
            "icon" -> {
                checker(c, 0f, bleedH, color)
                val side = min(288f, artH * 0.46f)
                pic = RectF((w - side) / 2, artH * 0.37f - side / 2, (w + side) / 2, artH * 0.37f + side / 2)
            }
            "middle" -> {
                backdrop(c, photo!!, bleedH)
                c.drawRect(0f, 0f, w, bleedH, Paint().apply { this.color = NAVY; alpha = 110 })
                val pw = min(w, artH * 0.5f * aspect)
                val top = artH * 0.17f
                pic = RectF((w - pw) / 2, top, (w + pw) / 2, top + pw / aspect)
            }
            "block" -> {
                // A picture too tall to leave room is cut short, so the block always has space for the name.
                blockTop = if (artH - ph >= 40f) ph else max(artH - 200f, artH * 0.5f)
                if (listed) checker(c, 0f, bleedH, color) else c.drawRect(0f, 0f, w, bleedH, Paint().apply { this.color = color })
                pic = RectF(0f, 0f, w, ph)
                picClip = RectF(0f, 0f, w, blockTop)
            }
            else -> {
                backdrop(c, photo!!, bleedH)
                // Across the top at full width, running on behind the list; a picture too short for that fills the card instead.
                pic = if (ph >= artH - 40f) RectF(0f, 0f, w, ph) else (bleedH * aspect).let { pw -> RectF((w - pw) / 2, 0f, (w + pw) / 2, bleedH) }
            }
        }
        val square = mode == "icon"
        val moved = design.picturePlace
        if (moved != null) {
            pic = RectF(moved.x, moved.y, moved.x + moved.w, moved.y + if (square) moved.w else moved.w / aspect)
            picClip = null
        }

        // ---- the picture
        if (square) {
            val big = picture ?: rows.firstNotNullOfOrNull(::iconOf)
            if (artH >= 420f && moved == null) {
                // The songs' icons round the big one.
                val others = rows.mapNotNull(::iconOf).filter { it !== big }.distinct()
                val spots = listOf(0.12f to 0.11f, 0.88f to 0.11f, 0.11f to 0.47f, 0.89f to 0.47f, 0.11f to 0.93f, 0.89f to 0.93f)
                for ((icon, spot) in others.zip(spots)) stamp(c, icon, spot.first * w - 48f, min(spot.second * artH - 48f, artH - 96f - 14f), 96f)
            }
            if (big != null) {
                if (max(big.width, big.height) <= TINY) stamp(c, big, pic.left + pic.width() * 0.03f, pic.top + pic.width() * 0.035f, pic.width(), shadow = true)
                stamp(c, big, pic.left, pic.top, pic.width())
            } else {
                sprite(c, source.seed, color, pic.left, pic.top, pic.width())
            }
        } else if (photo != null) {
            c.save()
            picClip?.let { c.clipRect(it) }
            if (mode == "middle") {
                c.drawRect(pic, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = NAVY
                    setShadowLayer(14f, 0f, 10f, Color.argb(120, 0, 0, 0))
                })
            }
            c.drawBitmap(photo, null, pic, SMOOTH)
            c.restore()
            if (mode == "block" && listed && moved == null) c.drawRect(0f, blockTop, w, blockTop + 5f, Paint().apply { this.color = NAVY })
        }
        regions += Region(Part.PICTURE, RectF(pic).apply { picClip?.let { intersect(it) } })
        c.restore()

        // ---- the name and the line under it
        val ownLine = !design.subtitle.isNullOrBlank()
        val room = artH - blockTop // block layout: the height of the block of colour
        val strip = if (mode == "block" && listed && room >= 220f && !ownLine && design.titlePlace == null && design.subtitlePlace == null)
            rows.mapNotNull(::iconOf).distinct().take(5) else emptyList()
        // Where the layout puts the name, how big at most, and whether it prints it unasked.
        val nameCy: Float
        val nameMax: Int
        val nameByDefault: Boolean
        // The line under the name: how far under, its size, weight and colour, and whether the layout prints the card's author unasked.
        val lineDrop: Float
        val lineSize: Int
        val lineBold: Boolean
        val lineFill: Int
        val lineByDefault: Boolean
        when (mode) {
            "icon" -> {
                nameCy = artH * 0.74f
                nameMax = min(64, (artH * 0.12f).toInt()).coerceAtLeast(34)
                nameByDefault = true
                lineDrop = 66f; lineSize = 34; lineBold = true; lineFill = mix(color, Color.WHITE, 0.75f)
                lineByDefault = artH >= 420f
            }
            "middle" -> {
                nameCy = max(artH * 0.76f, artH * 0.17f + min(w, artH * 0.5f * aspect) / aspect + 100f).coerceAtMost(artH - 110f)
                nameMax = if (artH >= 700f) 120 else 72
                nameByDefault = true
                lineDrop = 96f; lineSize = 34; lineBold = false; lineFill = Color.WHITE
                lineByDefault = false
            }
            "block" -> {
                val line = content.subtitle.isNotEmpty() && strip.isEmpty() && room >= 150f
                nameCy = when {
                    strip.isNotEmpty() -> blockTop + room * 0.36f
                    line -> blockTop + room / 2 - 28f
                    else -> blockTop + room / 2
                }
                nameMax = min(64, (room * 0.42f).toInt()).coerceAtLeast(30)
                nameByDefault = room >= 96f
                lineDrop = 68f; lineSize = 32; lineBold = false; lineFill = Color.WHITE
                lineByDefault = line
            }
            else -> {
                // A cover usually prints its own title; the name goes over it only when asked for.
                nameCy = artH * 0.84f
                nameMax = 64
                nameByDefault = false
                lineDrop = 62f; lineSize = 32; lineBold = false; lineFill = Color.WHITE
                lineByDefault = false
            }
        }

        /** One centred, outlined line of text; returns where it ended up. */
        fun label(part: Part, text: String, cx: Float, cy: Float, maxSize: Int, minSize: Int, typeface: Typeface, fill: Int): Region {
            val (shown, size) = fit(text, w - 40f, maxSize, minSize, typeface)
            outlined(c, shown, cx, cy, size, typeface, fill, if (luminance(fill) > 0.55f) NAVY else Color.WHITE)
            val half = paint(size.toFloat(), typeface, fill, Paint.Align.CENTER).measureText(shown) / 2 + 14f
            val box = RectF(cx - half, cy - size * 0.75f, cx + half, cy + size * 0.75f)
            return Region(part, box, text = box, textSize = size.toFloat()).also { regions += it }
        }

        var titleShown = false
        var titleBox: RectF? = null
        val titlePlace = design.titlePlace
        if (title.isNotEmpty() && (design.showTitle ?: (nameByDefault || titlePlace != null))) {
            val face = typeface(context, design.titleFont, bold = true)
            val fill = design.titleColor?.toInt() ?: Color.WHITE
            titleBox = if (titlePlace != null) {
                val size = (titlePlace.h / 1.5f).roundToInt().coerceIn(MIN_TEXT, MAX_TEXT)
                label(Part.TITLE, title, titlePlace.x + titlePlace.w / 2, titlePlace.y + titlePlace.h / 2, size, min(size, MIN_TEXT), face, fill).rect
            } else {
                label(Part.TITLE, title, w / 2, nameCy, nameMax, 30, face, fill).rect
            }
            titleShown = true
        }
        val linePlace = design.subtitlePlace
        if (content.subtitle.isNotEmpty() && (linePlace != null || (titleBox != null && (ownLine || lineByDefault)))) {
            val face = typeface(context, design.subtitleFont, bold = lineBold)
            val fill = design.subtitleColor?.toInt() ?: lineFill
            if (linePlace != null) {
                val size = (linePlace.h / 1.5f).roundToInt().coerceIn(MIN_TEXT, MAX_TEXT)
                label(Part.SUBTITLE, content.subtitle, linePlace.x + linePlace.w / 2, linePlace.y + linePlace.h / 2, size, min(size, MIN_TEXT), face, fill)
            } else if (titleBox != null) {
                // Under the name, wherever the name is.
                val drop = if (titlePlace != null) titleBox.height() / 2 + lineSize * 0.9f else lineDrop
                label(Part.SUBTITLE, content.subtitle, titleBox.centerX(), titleBox.centerY() + drop, lineSize, lineSize - 8, face, fill)
            }
        }
        if (strip.isNotEmpty() && titleBox != null) {
            // A row of the songs' icons under the name.
            val side = min(64f, artH - (nameCy + 56f) - 16f)
            val between = 22f
            var x = (w - (strip.size * side + (strip.size - 1) * between)) / 2
            for (icon in strip) {
                stamp(c, icon, x, nameCy + 56f, side)
                x += side + between
            }
        }

        // ---- the song list: a frosted, lightly see-through panel over whatever is behind it
        if (listRect != null) drawList(c, bitmap, context, content, listRect, color, rtl, icons, regions)

        // ---- the BoopBox bar: the logo bottom left, the same size and spot on every card
        c.drawRect(0f, bleedH, w, H.toFloat(), Paint().apply { this.color = NAVY })
        val logoSide = 46f
        val logoTop = bleedH + (FOOT - logoSide) / 2
        logo(context)?.let { logo ->
            val box = RectF(18f, logoTop, 18f + logoSide, logoTop + logoSide)
            c.save()
            c.clipPath(Path().apply { addRoundRect(box, logoSide * 0.22f, logoSide * 0.22f, Path.Direction.CW) })
            c.drawBitmap(logo, null, box, SMOOTH)
            c.restore()
        }
        val fonts = fonts(context)
        val mid = bleedH + FOOT / 2f + 1f
        write(c, "BoopBox", 18f + logoSide + 12f, mid, paint(28f, fonts.bold, Color.WHITE, Paint.Align.LEFT))
        write(c, footer(context, content, rtl), w - 22f, mid, paint(24f, fonts.regular, mix(NAVY, Color.WHITE, 0.75f), Paint.Align.RIGHT))
        regions += Region(Part.FOOTER, RectF(0f, bleedH, w, H.toFloat()))

        return Result(bitmap, regions, titleShown)
    }

    /**
     * The song list in [rect]: one column for a few songs, two for more, the rows as tall as the box allows
     * (so a box dragged smaller prints smaller, and one too small for every song prints the first ones).
     */
    private fun drawList(
        c: Canvas,
        bitmap: Bitmap,
        context: Context,
        content: StickerContent,
        rect: RectF,
        color: Int,
        rtl: Boolean,
        icons: Map<String, Bitmap>,
        regions: MutableList<Region>
    ) {
        val rows = content.rows
        val w = W.toFloat()
        val gap = 18f
        val pad = if (rect.height() >= 120f) LIST_PAD else rect.height() * 0.15f
        val inner = rect.height() - 2 * pad
        var cols = if (rows.size <= 7 || rect.width() < 400f) 1 else 2
        var perCol = (rows.size + cols - 1) / cols
        // One column squeezed too tight: two, where there is the width for it.
        if (cols == 1 && inner / perCol < 30f && rect.width() >= 400f && rows.size > 1) {
            cols = 2
            perCol = (rows.size + 1) / 2
        }
        var rowH = min(inner / perCol, 56f)
        if (rowH < MIN_ROW) {
            rowH = MIN_ROW
            perCol = max(1, (inner / MIN_ROW).toInt())
        }
        val shown = rows.take(perCol * cols)

        // The panel: square where it runs edge to edge, rounded when it floats.
        val floats = rect.left > 1f || rect.right < w - 1f
        val shape = Path().apply { if (floats) addRoundRect(rect, 22f, 22f, Path.Direction.CW) else addRect(rect, Path.Direction.CW) }
        c.save()
        c.clipRect(0f, 0f, w, BLEED.toFloat())
        c.clipPath(shape)
        val panel = content.design.listColor?.toInt() ?: mix(color, Color.WHITE, 0.86f)
        val solid = (content.design.listOpacity ?: LIST_OPACITY).coerceIn(0f, 1f)
        // Turned all the way down there is no box at all: the songs sit straight on the picture.
        if (solid > 0.03f) {
            val behind = Rect(max(0, rect.left.toInt()), max(0, rect.top.toInt()), min(W, rect.right.roundToInt()), min(BLEED, rect.bottom.roundToInt()))
            if (behind.width() > 8 && behind.height() > 8) {
                c.drawBitmap(soften(Bitmap.createBitmap(bitmap, behind.left, behind.top, behind.width(), behind.height()), 16), behind.left.toFloat(), behind.top.toFloat(), null)
            }
            c.drawRect(rect, Paint().apply {
                this.color = panel
                alpha = (solid * 255).roundToInt()
            })
            c.drawRect(rect.left, rect.top, rect.right, rect.top + 7f, Paint().apply { this.color = color })
        }
        // Dark words on a light box, light words on a dark one. With next to no box the words sit on the
        // picture itself: white with a dark edge, like the name, so they read on anything.
        val bare = solid < 0.4f
        val ink = if (bare || luminance(panel) <= 0.5f) Color.WHITE else NAVY
        fun put(text: String, x: Float, cy: Float, p: Paint) {
            if (bare) {
                write(c, text, x, cy, Paint(p).apply {
                    this.color = NAVY
                    style = Paint.Style.STROKE
                    strokeWidth = max(4f, p.textSize / 5f)
                    strokeJoin = Paint.Join.ROUND
                })
            }
            write(c, text, x, cy, p)
        }
        regions += Region(Part.LIST, RectF(rect))

        val face = typeface(context, content.design.listFont, bold = true)
        val colW = (rect.width() - 2 * pad - gap * (cols - 1)) / cols
        val iconSide = min(rowH * 0.64f, 34f)
        val size = (rowH * if (cols == 1) 0.6f else 0.55f).roundToInt().coerceIn(10, 30)
        val number = paint(size.toFloat(), face, if (bare) Color.WHITE else mix(color, ink, 0.45f), Paint.Align.CENTER)
        val numW = number.measureText(shown.size.toString())
        // Number, icon, then the name; no gap for icons when no song has one.
        val lead = if (shown.any { r -> r.icon?.let { icons[it] } != null }) numW + 8f + iconSide + 10f else numW + 14f
        val top = rect.top + (rect.height() - min(perCol, shown.size.coerceAtLeast(1)) * rowH) / 2
        shown.forEachIndexed { i, row ->
            var col = i / perCol
            if (rtl) col = cols - 1 - col // right to left: the first column is on the right
            val x0 = rect.left + pad + col * (colW + gap)
            val x1 = x0 + colW
            val mid = top + (i % perCol) * rowH + rowH / 2
            val cy = mid + 3f
            val (text, textSize) = fit(row.title, colW - lead, size, max(8, size - 5), face)
            val icon = row.icon?.let { icons[it] }
            if (rtl) {
                put((i + 1).toString(), x1 - numW / 2, cy, number)
                if (icon != null) stamp(c, icon, x1 - numW - 8f - iconSide, cy - iconSide / 2, iconSide)
                put(text, x1 - lead, cy, paint(textSize.toFloat(), face, ink, Paint.Align.RIGHT))
            } else {
                put((i + 1).toString(), x0 + numW / 2, cy, number)
                if (icon != null) stamp(c, icon, x0 + numW + 8f, cy - iconSide / 2, iconSide)
                put(text, x0 + lead, cy, paint(textSize.toFloat(), face, ink, Paint.Align.LEFT))
            }
            val words = if (rtl) RectF(x0, mid - rowH / 2, x1 - lead, mid + rowH / 2) else RectF(x0 + lead, mid - rowH / 2, x1, mid + rowH / 2)
            regions += Region(Part.ROW, RectF(x0, mid - rowH / 2, x1, mid + rowH / 2), row.key, words, textSize.toFloat())
        }
        c.restore()
    }

    /** "12 songs · 26 min", "Audiobook · 20 hr 14 min", "Podcast", "Movie": in the language the card is written in. */
    private fun footer(context: Context, content: StickerContent, hebrew: Boolean): String {
        val res = wordsIn(context, if (hebrew) Locale.forLanguageTag("he") else Locale.ENGLISH)
        val source = content.source
        val time = source.durationMs?.takeIf { it > 0 }?.let { ms ->
            val minutes = max(1, (ms / 60_000.0).roundToInt())
            if (minutes >= 100) res.getString(R.string.sticker_foot_hours, minutes / 60, minutes % 60)
            else res.getString(R.string.sticker_foot_minutes, minutes)
        }
        val count = source.rows.size.coerceAtLeast(1)
        val what = when (source.kind) {
            StickerKind.PODCAST -> return res.getString(R.string.sticker_foot_podcast)
            StickerKind.MOVIE -> return res.getString(R.string.sticker_foot_movie)
            StickerKind.LINK -> return source.linkLabel.orEmpty()
            StickerKind.AUDIOBOOK -> res.getString(R.string.sticker_foot_audiobook)
            StickerKind.CHAPTERS -> res.getQuantityString(R.plurals.sticker_foot_chapters, count, count)
            StickerKind.SONGS -> res.getQuantityString(R.plurals.sticker_foot_songs, count, count)
        }
        return if (time == null) what else "$what · $time"
    }

    private fun wordsIn(context: Context, locale: Locale): Resources = try {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources
    } catch (_: Exception) {
        context.resources
    }

    // ---------------------------------------------------------------- pieces

    private const val LIST_PAD = 26f
    /** How solid the song list's box is when nobody changed it: frosted glass. */
    const val LIST_OPACITY = 0.78f
    /** Rows shorter than this can't be read at card size. */
    private const val MIN_ROW = 18f
    private const val MIN_TEXT = 14
    private const val MAX_TEXT = 220

    private val HEBREW = Regex("[֐-׿]")
    private val SMOOTH = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** For pixel art: a Paint smooths bitmaps unless told not to. */
    private val HARD = Paint().apply { isFilterBitmap = false }

    private fun box(p: StickerPlace) = RectF(p.x, p.y, p.x + p.w, p.y + p.h)

    private class Fonts(val bold: Typeface, val regular: Typeface)

    @Volatile
    private var fonts: Fonts? = null

    /** Rubik: round, friendly, and it draws Hebrew as well as Latin. */
    private fun fonts(context: Context): Fonts = fonts ?: run {
        fun load(id: Int, fallback: Typeface): Typeface = try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (_: Exception) {
            fallback
        }
        Fonts(load(R.font.rubik_bold, Typeface.DEFAULT_BOLD), load(R.font.rubik_regular, Typeface.DEFAULT)).also { fonts = it }
    }

    @Volatile
    private var logo: Bitmap? = null

    private fun logo(context: Context): Bitmap? = logo ?: try {
        BitmapFactory.decodeResource(context.resources, R.drawable.boopbox_logo, BitmapFactory.Options().apply { inSampleSize = 4 })
            ?.also { logo = it }
    } catch (_: Exception) {
        null
    }

    private fun paint(size: Float, typeface: Typeface, color: Int, align: Paint.Align) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.typeface = typeface
        this.color = color
        textAlign = align
    }

    /** Text with its middle (not its baseline) at [cy]. */
    private fun write(c: Canvas, text: String, x: Float, cy: Float, paint: Paint) {
        c.drawText(text, x, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }

    /** Centred text with a thick outline, readable on any picture. */
    private fun outlined(c: Canvas, text: String, x: Float, cy: Float, size: Int, typeface: Typeface, fill: Int, stroke: Int) {
        val edge = paint(size.toFloat(), typeface, stroke, Paint.Align.CENTER).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * max(3f, size / 9f)
            strokeJoin = Paint.Join.ROUND
        }
        write(c, text, x, cy, edge)
        write(c, text, x, cy, paint(size.toFloat(), typeface, fill, Paint.Align.CENTER))
    }

    /** The largest size from [size] down to [minSize] at which [text] fits [maxW]; an ellipsis if even the smallest doesn't. */
    private fun fit(text: String, maxW: Float, size: Int, minSize: Int, typeface: Typeface): Pair<String, Int> {
        val p = paint(size.toFloat(), typeface, Color.BLACK, Paint.Align.LEFT)
        for (s in size downTo minSize) {
            p.textSize = s.toFloat()
            if (p.measureText(text) <= maxW) return text to s
        }
        p.textSize = minSize.toFloat()
        var cut = text
        while (cut.isNotEmpty() && p.measureText("$cut…") > maxW) cut = cut.dropLast(1)
        return (cut.trimEnd() + "…") to minSize
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
    )

    private fun luminance(color: Int): Float = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    /** A soft two-tone pixel checker of the card's colour, from [top] to [bottom]. */
    private fun checker(c: Canvas, top: Float, bottom: Float, color: Int) {
        c.drawRect(0f, top, W.toFloat(), bottom, Paint().apply { this.color = color })
        val light = Paint().apply { this.color = mix(color, Color.WHITE, 0.12f) }
        val cell = 32
        c.save()
        c.clipRect(0f, top, W.toFloat(), bottom)
        var y = (top.toInt() / cell) * cell
        while (y < bottom) {
            var x = 0
            while (x < W) {
                if ((x / cell + y / cell) % 2 == 1) c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), light)
                x += cell
            }
            y += cell
        }
        c.restore()
    }

    /** [src] filling [dst], cropped evenly at the sides and from the top down by [focus] (0 = keep the top). */
    private fun cover(c: Canvas, src: Bitmap, dst: RectF, focus: Float) {
        val scale = max(dst.width() / src.width, dst.height() / src.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sx = (src.width - sw) / 2
        val sy = (src.height - sh) * focus
        c.drawBitmap(src, Rect(sx.roundToInt(), sy.roundToInt(), (sx + sw).roundToInt(), (sy + sh).roundToInt()), dst, SMOOTH)
    }

    /** A blurred, enlarged copy of the picture behind everything down to the footer. */
    private fun backdrop(c: Canvas, photo: Bitmap, bleedH: Float) {
        // Drawn small and blown up: that is the blur.
        val small = Bitmap.createBitmap(W / 16, (bleedH / 16).toInt(), Bitmap.Config.ARGB_8888)
        cover(Canvas(small), photo, RectF(0f, 0f, small.width.toFloat(), small.height.toFloat()), 0.5f)
        c.drawBitmap(soften(small, 2), null, RectF(0f, 0f, W.toFloat(), bleedH), SMOOTH)
    }

    /** A blur: shrunk by [factor] in halving steps, then stretched back. */
    private fun soften(src: Bitmap, factor: Int): Bitmap {
        var w = src.width
        var h = src.height
        val minW = max(1, src.width / factor)
        val minH = max(1, src.height / factor)
        var cur = src
        while (w / 2 >= minW && h / 2 >= minH) {
            w /= 2
            h /= 2
            cur = Bitmap.createScaledBitmap(cur, w, h, true)
        }
        return Bitmap.createScaledBitmap(cur, src.width, src.height, true)
    }

    /**
     * A song's (or the card's) icon in a square: pixel art keeps its hard edges, a photo is cropped to the square.
     * [shadow] draws its shape only, dark and see-through, to sit under the icon itself.
     */
    private fun stamp(c: Canvas, icon: Bitmap, x: Float, y: Float, side: Float, shadow: Boolean = false) {
        val dst = RectF(x, y, x + side, y + side)
        val pixels = max(icon.width, icon.height) <= TINY
        if (shadow) {
            val p = Paint().apply {
                isFilterBitmap = !pixels
                colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                alpha = 70
            }
            c.drawBitmap(icon, null, dst, p)
        } else if (pixels) {
            c.drawBitmap(icon, null, dst, HARD)
        } else {
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, side * 0.12f, side * 0.12f, Path.Direction.CW) })
            cover(c, icon, dst, 0.5f)
            c.restore()
        }
    }

    /** The card's own 8 x 8 pixel creature (the same one the app shows for a card with no picture). */
    private fun sprite(c: Canvas, seed: String, color: Int, x: Float, y: Float, side: Float) {
        val rnd = Random(seed.hashCode())
        val n = 8
        val cell = side / n
        val dark = Color.rgb((Color.red(color) * 0.55f).toInt(), (Color.green(color) * 0.55f).toInt(), (Color.blue(color) * 0.55f).toInt())
        val light = mix(color, Color.WHITE, 0.5f)
        // On the checker of its own colour the creature needs an edge to stand out: a white tile under it.
        c.drawRoundRect(RectF(x, y, x + side, y + side), cell * 0.6f, cell * 0.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        val palette = listOf(color, color, dark, light)
        val cells = Array(n) { arrayOfNulls<Int>(n) }
        for (cy in 1 until n - 1) {
            for (cx in 1 until n / 2) {
                if (rnd.nextFloat() < 0.6f) {
                    val shade = palette[rnd.nextInt(palette.size)]
                    cells[cy][cx] = shade
                    cells[cy][n - 1 - cx] = shade
                }
            }
        }
        cells[2][2] = Color.rgb(0x2B, 0x21, 0x18)
        cells[2][5] = Color.rgb(0x2B, 0x21, 0x18)
        val p = Paint()
        for (cy in 0 until n) {
            for (cx in 0 until n) {
                p.color = cells[cy][cx] ?: continue
                c.drawRect(x + cx * cell, y + cy * cell, x + (cx + 1) * cell, y + (cy + 1) * cell, p)
            }
        }
    }
}
