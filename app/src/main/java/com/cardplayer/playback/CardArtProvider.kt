package com.cardplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
 * PNG and serves that.
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

        val dir = File(context.cacheDir, "car_art").apply { mkdirs() }
        val file = File(dir, sha1("$source|$seed|${card.color}|$pixel") + ".png")
        if (!file.exists() || file.length() == 0L) {
            val identity = Binder.clearCallingIdentity()
            try {
                val bitmap = render(context, source, seed, card.color, pixel)
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

    private fun render(context: Context, source: String?, seed: String, color: Long, pixel: Boolean): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val base = color.toInt()
        // The tile behind the picture: the card colour, washed out (35% over white).
        canvas.drawColor(Color.rgb(wash(Color.red(base)), wash(Color.green(base)), wash(Color.blue(base))))

        val picture = source?.let { load(context, it) }
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

    private fun load(context: Context, source: String): Bitmap? = try {
        val request = ImageRequest.Builder(context)
            .data(source)
            .allowHardware(false)
            .size(SIZE)
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

    private fun wash(channel: Int): Int = 255 - ((255 - channel) * 0.35f).toInt()

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
        private const val PIXELS = 40
        private const val EYE = 0xFF2B2118.toInt()

        private fun authority(context: Context) = context.packageName + ".art"

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
