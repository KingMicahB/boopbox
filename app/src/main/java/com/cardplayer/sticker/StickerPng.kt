package com.cardplayer.sticker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.cardplayer.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * A sticker as a picture to print: a PNG that says how big it is, so that printed at 100% it comes out the size
 * of an NFC card (ISO ID-1, 54 x 85.6 mm). Android writes PNGs without a size; a print dialog then guesses one
 * (often 72 or 96 dots per inch) and the sticker comes out three or four times too big.
 */
object StickerPng {
    /** Dots per metre of a sticker [StickerRenderer.W] dots across: 300 per inch, which makes it 54 mm wide. */
    private const val PER_METRE = 11811

    /** How many dots per metre make a sticker [widthPx] dots across as wide as a card. */
    fun pixelsPerMetre(widthPx: Int): Int = (PER_METRE.toDouble() * widthPx / StickerRenderer.W).roundToInt()

    /** [sticker] as a PNG that prints card-sized. */
    fun encode(sticker: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        sticker.compress(Bitmap.CompressFormat.PNG, 100, out)
        return withDensity(out.toByteArray(), pixelsPerMetre(sticker.width))
    }

    /**
     * [png] with its size written in: a `pHYs` chunk of [perMetre] dots per metre both ways, straight after the
     * header (it has to come before the picture itself). One that is already there is replaced.
     */
    fun withDensity(png: ByteArray, perMetre: Int): ByteArray {
        val headerEnd = SIGNATURE + 12 + 13 // the signature, then IHDR: length, name, 13 bytes, checksum
        require(png.size > headerEnd && String(png, SIGNATURE + 4, 4, Charsets.US_ASCII) == "IHDR") { "not a PNG" }
        val out = ByteArrayOutputStream(png.size + 21)
        out.write(png, 0, headerEnd)

        val body = ByteBuffer.allocate(13).put("pHYs".toByteArray(Charsets.US_ASCII)).putInt(perMetre).putInt(perMetre).put(1).array()
        out.write(ByteBuffer.allocate(4).putInt(9).array())
        out.write(body)
        out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(body) }.value.toInt()).array())

        var at = headerEnd
        while (at + 12 <= png.size) {
            val length = ByteBuffer.wrap(png, at, 4).int
            val whole = 12 + length
            if (length < 0 || at + whole > png.size) break
            if (String(png, at + 4, 4, Charsets.US_ASCII) != "pHYs") out.write(png, at, whole)
            at += whole
        }
        return out.toByteArray()
    }

    /** The share sheet for a sticker picture made by [com.cardplayer.MainViewModel.exportSticker]. */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // So the share sheet can show the picture.
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, context.getString(R.string.editor_send_file, file.name))
    }

    private const val SIGNATURE = 8
}
