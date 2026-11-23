package com.cardplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cardplayer.BoopBoxApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Hands card pictures to the car screen. Android Auto loads pictures itself, from a content uri, so it cannot
 * see a picture stored inside BoopBox or one BoopBox draws (the pixel creature of a card without artwork).
 * This provider draws each picture the way the phone shows it (pixel art when that setting is on) into a small
 * PNG and serves that. A card is drawn card-shaped, as its sticker when it has one, like in the phone's card picker.
 *
 * It only ever serves the picture of a card, or of a song of a card, looked up by the card's id: no paths.
 */
class CardArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        if (mode != "r") throw FileNotFoundException("read only")
        val segments = uri.pathSegments
        if (segments.size != 2) throw FileNotFoundException(uri.toString())
        val app = BoopBoxApp.get(context)
        val card = app.cards.get(segments[1]) ?: throw FileNotFoundException(uri.toString())
        val trackKey = if (segments[0] == TRACK) uri.getQueryParameter(KEY) else null

        // The same picture the phone shows: a song's own, else (audiobooks) the cover, else a creature of its own.
        val source = if (trackKey != null) card.trackArt[trackKey] ?: card.artworkUrl.takeIf { card.audiobook } else card.artworkUrl
        val seed = if (trackKey != null) card.uid + trackKey else card.uid
        val pixel = app.settings.value.pixelArt

        // A card is shown as its sticker (the printed face of the real card) when it has one; its songs keep their own pictures.
        val sticker = card.stickerIfThere().takeIf { trackKey == null }

        val dir = File(context.cacheDir, "car_art").apply { mkdirs() }
        val file = File(dir, sha1("$LOOK|$trackKey|$sticker|${stickerStamp(sticker)}|$source|$seed|${card.color}|$pixel") + ".png")
        if (!file.exists() || file.length() == 0L) {
            val identity = Binder.clearCallingIdentity()
            try {
                // A card looks like a card, as in the phone's card picker; a song keeps its plain square picture.
                val bitmap = if (trackKey == null) renderCard(context, sticker, source, seed, card.color, pixel)
                else render(context, source, seed, card.color, pixel)
                val tmp = File.createTempFile("art", ".tmp", dir)
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(file)) tmp.delete()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * A card the way the phone's card picker shows it (CardFace there): card-shaped and upright, its sticker when
     * it has one that opens, else its plain picture on a card of its own colour.
     *
     * The car screen only takes square pictures, so the card stands in the middle of a square that is see-through
     * everywhere else: nothing is drawn round the card, and the car's own background shows beside it. The sticker
     * is never pixelated (it is print art with small lettering) and never cropped (that would cut its track list).
     */
    private fun renderCard(context: Context, sticker: String?, source: String?, seed: String, color: Long, pixel: Boolean): Bitmap {
        val out = Bitmap.createBitmap(CARD_SIZE, CARD_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val h = CARD_SIZE.toFloat()
        val w = h * CARD_ASPECT
        val box = RectF((CARD_SIZE - w) / 2, 0f, (CARD_SIZE + w) / 2, h)
        // Rounded like the real card.
        val corner = w * 0.07f
        val smooth = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        canvas.clipPath(Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) })

        val face = sticker?.let { load(context, it, CARD_SIZE) }
        if (face != null) {
            // Fill the card: a sticker of slightly different proportions loses a sliver at the edges, not its shape.
            val scale = maxOf(w / face.width, h / face.height)
            val fw = face.width * scale
            val fh = face.height * scale
            canvas.drawBitmap(face, null, RectF(box.centerX() - fw / 2, box.centerY() - fh / 2, box.centerX() + fw / 2, box.centerY() + fh / 2), smooth)
            return out
        }
        // No sticker, or one that is gone or won't open: the plain picture, on a card of the card's colour.
        val base = color.toInt()
        canvas.drawColor(Color.rgb(wash(Color.red(base), 0.45f), wash(Color.green(base), 0.45f), wash(Color.blue(base), 0.45f)))
        val side = w * 0.84f
        val inner = RectF(box.centerX() - side / 2, box.centerY() - side / 2, box.centerX() + side / 2, box.centerY() + side / 2)
        canvas.clipPath(Path().apply { addRoundRect(inner, corner * 0.7f, corner * 0.7f, Path.Direction.CW) })
        // Pixel art stays blocky when blown up.
        canvas.drawBitmap(render(context, source, seed, color, pixel), null, inner, Paint().apply { isFilterBitmap = !pixel; isAntiAlias = true })
        return out
    }

    private fun render(context: Context, source: String?, seed: String, color: Long, pixel: Boolean): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val base = color.toInt()
        // The tile behind the picture: the card colour, washed out (35% over white).
        canvas.drawColor(Color.rgb(wash(Color.red(base)), wash(Color.green(base)), wash(Color.blue(base))))

        val picture = source?.let { load(context, it, SIZE) }
        if (picture == null) {
            drawSprite(canvas, seed, base)
            return out
        }
        val side = minOf(picture.width, picture.height)
        val square = Bitmap.createBitmap(picture, (picture.width - side) / 2, (picture.height - side) / 2, side, side)
        // Pixel art: shrink to 40×40 and blow it up blocky. Already tiny pixel art keeps every pixel as is.
        val small = if (pixel && side > PIXELS) Bitmap.createScaledBitmap(square, PIXELS, PIXELS, true) else square
        val paint = Paint().apply { isFilterBitmap = !pixel && side > PIXELS }
        canvas.drawBitmap(small, null, Rect(0, 0, SIZE, SIZE), paint)
        return out
    }

    /** [source] scaled to fit inside [size] × [size], or null when it is missing or not a picture. */
    private fun load(context: Context, source: String, size: Int): Bitmap? = try {
        val request = ImageRequest.Builder(context)
            .data(source)
            .allowHardware(false)
            .size(size)
            .build()
        val result = runBlocking { context.imageLoader.execute(request) } as? SuccessResult
        result?.drawable?.toBitmap()
    } catch (_: Exception) {
        null
    }

    /** The creature of [com.cardplayer.ui.PixelSprite], cell for cell. */
    private fun drawSprite(canvas: Canvas, seed: String, base: Int) {
        val rnd = Random(seed.hashCode())
        val n = 8
        val cell = SIZE / n
        val dark = Color.rgb((Color.red(base) * 0.55f).toInt(), (Color.green(base) * 0.55f).toInt(), (Color.blue(base) * 0.55f).toInt())
        val light = Color.rgb(
            Color.red(base) + (255 - Color.red(base)) / 2,
            Color.green(base) + (255 - Color.green(base)) / 2,
            Color.blue(base) + (255 - Color.blue(base)) / 2
        )
        val solid = Color.rgb(Color.red(base), Color.green(base), Color.blue(base))
        val palette = intArrayOf(solid, solid, dark, light)
        val cells = Array(n) { arrayOfNulls<Int>(n) }
        for (y in 1 until n - 1) {
            for (x in 1 until n / 2) {
                if (rnd.nextFloat() < 0.6f) {
                    val c = palette[rnd.nextInt(palette.size)]
                    cells[y][x] = c
                    cells[y][n - 1 - x] = c
                }
            }
        }
        cells[2][2] = EYE
        cells[2][5] = EYE
        val paint = Paint()
        for (y in 0 until n) {
            for (x in 0 until n) {
                paint.color = cells[y][x] ?: continue
                canvas.drawRect((x * cell).toFloat(), (y * cell).toFloat(), ((x + 1) * cell).toFloat(), ((y + 1) * cell).toFloat(), paint)
            }
        }
    }

    /** [channel] at [strength] over white. */
    private fun wash(channel: Int, strength: Float = 0.35f): Int = 255 - ((255 - channel) * strength).toInt()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val CARD = "card"
        private const val TRACK = "track"
        private const val KEY = "k"
        private const val VERSION = "v"
        private const val SIZE = 320
        /** Cards are drawn bigger: the card only fills part of the square, and a sticker's lettering is small. */
        private const val CARD_SIZE = 512
        /** Width over height of a card's face (a Yoto card, 54 × 85.6 mm), as in the phone's card picker. */
        private const val CARD_ASPECT = 638f / 1011f
        /** Names how cards are drawn. Change it when that changes: pictures already drawn, and the car's own copies, go stale. */
        const val LOOK = "card-shaped-1"
        private const val PIXELS = 40
        private const val EYE = 0xFF2B2118.toInt()

        private fun authority(context: Context) = context.packageName + ".art"

        /**
         * Whether the sticker file is there, and which copy of it: part of what makes a card's picture, so a
         * sticker that goes missing (the card falls back to its plain picture) or is replaced gets drawn afresh.
         */
        fun stickerStamp(sticker: String?): String {
            if (sticker == null || !sticker.startsWith("file://")) return ""
            val file = Uri.parse(sticker).path?.let { File(it) }
            return if (file != null && file.isFile) file.lastModified().toString() + ":" + file.length() else "missing"
        }

        /**
         * The picture of a card. [version] changes whenever the picture would (the car keeps pictures by uri,
         * so a new picture needs a new uri); the provider itself ignores it.
         */
        fun cardUri(context: Context, uid: String, version: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(CARD).appendPath(uid)
                .appendQueryParameter(VERSION, version).build()

        /** The picture of one song / chapter of a card; [trackKey] is its key in the card's trackArt. */
        fun trackUri(context: Context, uid: String, trackKey: String, version: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(TRACK).appendPath(uid)
                .appendQueryParameter(KEY, trackKey).appendQueryParameter(VERSION, version).build()
    }
}
