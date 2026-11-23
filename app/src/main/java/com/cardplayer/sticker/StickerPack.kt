package com.cardplayer.sticker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cardplayer.R
import com.cardplayer.data.BoopFile
import com.cardplayer.data.Card
import com.cardplayer.data.SourceType
import com.cardplayer.data.StickerDesign
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Every card's sticker at once: a .zip of card-sized PNGs, or a PDF with the stickers laid out side by side,
 * ready to print at 100% and cut out.
 *
 * A card's sticker is the finished one it has (made in the sticker maker, or one that came with the card), else
 * one drawn here from what the card says about itself: the template the sticker maker would start it with.
 */
object StickerPack {
    /** Points (1/72 inch) per millimetre: a PDF page is measured in points. */
    private const val PT_PER_MM = 72f / 25.4f
    private const val CARD_W_MM = 54f
    private const val CARD_H_MM = 85.6f
    /** Room between stickers for the scissors. */
    private const val GAP_MM = 4f
    /** Printers can't print to the edge of the paper. Small enough that A4 and US Letter both take 3 x 3. */
    private const val MARGIN_MM = 6f

    /** The card's sticker as a picture. Fetching pictures may take a moment: call from a background thread. */
    suspend fun stickerFor(context: Context, card: Card, pictures: StickerRenderer.Pictures = StickerRenderer.coilPictures(context)): Bitmap {
        card.stickerIfThere()?.let { url -> pictures.load(url, StickerRenderer.H)?.let { return it } }
        return StickerRenderer.render(context, sourceOf(context, card), card.sticker ?: StickerDesign(), pictures).bitmap
    }

    /**
     * What the card says about itself, as far as a sticker cares, from the saved card alone (the card editor
     * knows more: the songs of a folder, how long they run). Good enough for the template a sticker starts with.
     */
    fun sourceOf(context: Context, card: Card): StickerSource {
        val rows = when {
            card.playlist.isNotEmpty() -> card.playlist.map { StickerRow(it.uri, it.title, card.trackArt[it.uri]) }
            else -> card.chapters.map { StickerRow(it.artKey, it.title, card.trackArt[it.artKey]) }
        }
        val kind = when {
            card.sourceType == SourceType.PODCAST -> StickerKind.PODCAST
            card.sourceType == SourceType.VIDEO -> StickerKind.MOVIE
            card.audiobook || card.sourceType == SourceType.AUDIBLE -> StickerKind.AUDIOBOOK
            rows.isEmpty() && (card.sourceType.isExternal || card.sourceType == SourceType.STREAM_URL) -> StickerKind.LINK
            card.resume -> StickerKind.CHAPTERS
            else -> StickerKind.SONGS
        }
        return StickerSource(
            seed = card.uid,
            name = card.displayName(context).trim(),
            subtitle = card.subtitle,
            picture = card.artworkUrl?.trim()?.ifBlank { null },
            color = card.color,
            kind = kind,
            rows = rows,
            linkLabel = context.getString(card.sourceType.labelRes)
        )
    }

    /** A fresh, empty folder for one export (shared through the app's file provider). */
    fun outDir(context: Context): File = File(context.cacheDir, "boop-out").apply { deleteRecursively(); mkdirs() }

    /** File names for these cards' stickers, none used twice. */
    fun fileNames(context: Context, cards: List<Card>): List<String> {
        val used = HashSet<String>()
        return cards.map { card ->
            val base = BoopFile.fileNameFor(context, card).substringBeforeLast('.') + " sticker"
            var name = "$base.png"
            var n = 2
            while (!used.add(name.lowercase(Locale.ROOT))) name = "$base ${n++}.png"
            name
        }
    }

    /** Writes the stickers, one card-sized PNG each, into a .zip at [out]. [next] hands over one sticker at a time. */
    suspend fun writeZip(out: File, names: List<String>, next: suspend (Int) -> Bitmap?) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // PNGs are squeezed already.
            zip.setLevel(0)
            for (i in names.indices) {
                val sticker = next(i) ?: continue
                zip.putNextEntry(ZipEntry(names[i]))
                zip.write(StickerPng.encode(sticker))
                zip.closeEntry()
            }
        }
    }

    /** A page size in points (A4, or US Letter where that is the paper in the tray). */
    data class Paper(val widthPt: Float, val heightPt: Float)

    val A4 = Paper(210f * PT_PER_MM, 297f * PT_PER_MM)
    val LETTER = Paper(8.5f * 72f, 11f * 72f)

    /** Letter in the US, Canada and the countries that follow them; A4 everywhere else. */
    fun paperFor(locale: Locale): Paper = if (locale.country.uppercase(Locale.ROOT) in setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "PR")) LETTER else A4

    /** Where each sticker sits on a page, in points: as many card-sized boxes as fit, centred, row by row. */
    fun slots(paper: Paper): List<RectF> {
        val w = CARD_W_MM * PT_PER_MM
        val h = CARD_H_MM * PT_PER_MM
        val gap = GAP_MM * PT_PER_MM
        val margin = MARGIN_MM * PT_PER_MM
        val cols = ((paper.widthPt - 2 * margin + gap) / (w + gap)).toInt().coerceAtLeast(1)
        val rows = ((paper.heightPt - 2 * margin + gap) / (h + gap)).toInt().coerceAtLeast(1)
        val left = (paper.widthPt - (cols * w + (cols - 1) * gap)) / 2
        val top = (paper.heightPt - (rows * h + (rows - 1) * gap)) / 2
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c ->
                val x = left + c * (w + gap)
                val y = top + r * (h + gap)
                RectF(x, y, x + w, y + h)
            }
        }
    }

    /**
     * Writes a PDF to [out] with [count] stickers laid out on [paper], each exactly card-sized when printed at 100%,
     * with a thin grey line round each to cut along. [next] hands over one sticker at a time.
     */
    suspend fun writePdf(out: File, paper: Paper, count: Int, next: suspend (Int) -> Bitmap?) {
        val slots = slots(paper)
        val pdf = PdfDocument()
        try {
            val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
                color = Color.rgb(170, 170, 170)
            }
            var page: PdfDocument.Page? = null
            var placed = 0
            for (i in 0 until count) {
                val sticker = next(i) ?: continue
                val at = placed % slots.size
                if (at == 0) {
                    page?.let { pdf.finishPage(it) }
                    val info = PdfDocument.PageInfo.Builder(paper.widthPt.toInt(), paper.heightPt.toInt(), placed / slots.size + 1).create()
                    page = pdf.startPage(info)
                }
                val canvas = page!!.canvas
                val box = slots[at]
                canvas.drawBitmap(sticker, null, box, picture)
                canvas.drawRect(box, cut)
                placed++
            }
            page?.let { pdf.finishPage(it) }
            check(placed > 0) { "no stickers" }
            out.outputStream().buffered().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /** The share sheet (Files, Drive, email, a print app…) for a pack made here. */
    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, context.getString(R.string.editor_send_file, file.name))
    }

    const val MIME_ZIP = "application/zip"
    const val MIME_PDF = "application/pdf"
}
