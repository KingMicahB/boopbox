package com.cardplayer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Artwork tile. Real artwork (URL, content uri, bytes or bitmap) is shown pixelated when
 * [pixel] is on. With no artwork, a generated pixel sprite unique to [seed] is drawn instead,
 * so every track still has a picture a child can recognise.
 */
@Composable
fun CardArtwork(
    artwork: Any?,
    seed: String,
    color: Long,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    pixel: Boolean = true,
    pixels: Int = 40
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(color).copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            artwork == null -> PixelSprite(seed = seed, color = color, modifier = Modifier.fillMaxSize())
            pixel -> PixelImage(model = artwork, pixels = pixels, seed = seed, color = color, modifier = Modifier.fillMaxSize())
            else -> AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** Loads [model], centre-crops to a square, shrinks to [pixels]×[pixels], and draws it blocky. */
@Composable
fun PixelImage(model: Any, pixels: Int, seed: String, color: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Not produceState: that starts each new picture from null, so the sprite flashes up while it loads.
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(model, pixels) {
        image = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(model)
                    .allowHardware(false)
                    .size(coil.size.Size.ORIGINAL)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val source = result?.drawable?.toBitmap() ?: return@withContext null
                val side = minOf(source.width, source.height)
                val square = Bitmap.createBitmap(
                    source,
                    (source.width - side) / 2,
                    (source.height - side) / 2,
                    side,
                    side
                )
                // Already tiny pixel art (e.g. a 16x16 yotoicons icon)? Keep every pixel as is.
                if (side <= pixels) square.asImageBitmap()
                else Bitmap.createScaledBitmap(square, pixels, pixels, true).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
    } else {
        PixelSprite(seed = seed, color = color, modifier = modifier)
    }
}

/** A deterministic 8×8 mirrored "creature" sprite, in shades of the card colour. */
@Composable
fun PixelSprite(seed: String, color: Long, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val rnd = Random(seed.hashCode())
        val n = 8
        val cw = size.width / n
        val ch = size.height / n
        val base = Color(color)
        val dark = Color(base.red * 0.55f, base.green * 0.55f, base.blue * 0.55f)
        val light = Color(
            base.red + (1f - base.red) * 0.5f,
            base.green + (1f - base.green) * 0.5f,
            base.blue + (1f - base.blue) * 0.5f
        )
        val palette = listOf(base, base, dark, light)
        val cells = Array(n) { arrayOfNulls<Color>(n) }
        for (y in 1 until n - 1) {
            for (x in 1 until n / 2) {
                if (rnd.nextFloat() < 0.6f) {
                    val c = palette[rnd.nextInt(palette.size)]
                    cells[y][x] = c
                    cells[y][n - 1 - x] = c
                }
            }
        }
        // Eyes, so it reads as a little creature.
        cells[2][2] = Color(0xFF2B2118)
        cells[2][5] = Color(0xFF2B2118)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val c = cells[y][x] ?: continue
                drawRect(
                    color = c,
                    topLeft = Offset(x * cw, y * ch),
                    size = Size(cw + 0.5f, ch + 0.5f)
                )
            }
        }
    }
}

@Composable
fun PauseIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(color)
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(color)
        )
    }
}

@Composable
fun StopIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
    )
}
