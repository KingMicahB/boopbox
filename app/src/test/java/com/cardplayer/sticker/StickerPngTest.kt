package com.cardplayer.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

class StickerPngTest {
    private fun chunk(name: String, data: ByteArray): ByteArray {
        val body = name.toByteArray(Charsets.US_ASCII) + data
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(body).putInt(CRC32().apply { update(body) }.value.toInt()).array()
    }

    /** A real PNG the size of a sticker (plain grey), as the phone would write it: no size inside. */
    private fun plainPng(width: Int = StickerRenderer.W, height: Int = StickerRenderer.H): ByteArray {
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(0).put(0).put(0).put(0).array()
        // Each row: a filter byte, then one grey byte for every dot.
        val rows = ByteArray(height * (width + 1)) { if (it % (width + 1) == 0) 0 else 0x80.toByte() }
        val packer = Deflater().apply { setInput(rows); finish() }
        val packed = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!packer.finished()) packed.write(buffer, 0, packer.deflate(buffer))
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return signature + chunk("IHDR", header) + chunk("IDAT", packed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    /** The chunks of [png] in order: name to data. Fails on a checksum that doesn't fit. */
    private fun chunks(png: ByteArray): List<Pair<String, ByteArray>> {
        val found = ArrayList<Pair<String, ByteArray>>()
        var at = 8
        while (at < png.size) {
            val length = ByteBuffer.wrap(png, at, 4).int
            val name = String(png, at + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().apply { update(png, at + 4, 4 + length) }.value.toInt()
            assertEquals("checksum of $name", crc, ByteBuffer.wrap(png, at + 8 + length, 4).int)
            found += name to png.copyOfRange(at + 8, at + 8 + length)
            at += 12 + length
        }
        return found
    }

    @Test
    fun aStickerIsThreeHundredDotsToTheInch() {
        // 638 dots across at 300 per inch: 54 mm, the width of an NFC card.
        assertEquals(11811, StickerPng.pixelsPerMetre(StickerRenderer.W))
        val widthMm = StickerRenderer.W * 1000.0 / StickerPng.pixelsPerMetre(StickerRenderer.W)
        val heightMm = StickerRenderer.H * 1000.0 / StickerPng.pixelsPerMetre(StickerRenderer.W)
        assertEquals(54.0, widthMm, 0.05)
        assertEquals(85.6, heightMm, 0.05)
        // A sticker drawn with twice the dots is still one card wide.
        assertEquals(23622, StickerPng.pixelsPerMetre(StickerRenderer.W * 2))
    }

    @Test
    fun theSizeGoesInRightAfterTheHeaderAndOnlyOnce() {
        val plain = plainPng()
        assertTrue(chunks(plain).none { it.first == "pHYs" })

        val sized = StickerPng.withDensity(plain, 11811)
        val names = chunks(sized).map { it.first }
        assertEquals("IHDR", names[0])
        assertEquals("pHYs", names[1])
        assertEquals("IEND", names.last())
        val size = ByteBuffer.wrap(chunks(sized)[1].second)
        assertEquals(11811, size.int)
        assertEquals(11811, size.int)
        assertEquals(1, size.get().toInt()) // per metre

        // Given a size again, the old one goes: there is never more than one.
        val again = StickerPng.withDensity(sized, 23622)
        assertEquals(1, chunks(again).count { it.first == "pHYs" })
        assertEquals(23622, ByteBuffer.wrap(chunks(again)[1].second).int)

        // The picture itself is untouched.
        assertTrue(chunks(plain).first { it.first == "IDAT" }.second.contentEquals(chunks(sized).first { it.first == "IDAT" }.second))
        assertTrue(chunks(plain)[0].second.contentEquals(chunks(sized)[0].second))

        // Kept where a look from outside can check it (build/sticker-samples/card-size.png).
        File("build/sticker-samples").apply { mkdirs() }.resolve("card-size.png").writeBytes(sized)
    }
}
