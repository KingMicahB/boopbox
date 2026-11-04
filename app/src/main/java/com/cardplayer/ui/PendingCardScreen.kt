package com.cardplayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardplayer.R
import com.cardplayer.ReceiveState
import com.cardplayer.ReplacePrompt
import com.cardplayer.data.Card

/**
 * Shown over the player screen while a card pushed from the computer (tools/cardpush.py) or opened
 * from a .boop someone shared is waiting for an NFC card. Deliberately looks nothing like the toddler
 * player: the grown-up can see at a glance that the next card tapped will get [card].
 *
 * "Not now", "Remove", "Replace" and "Keep it" need a double tap, like the rest of the grown-up controls.
 */
@Composable
fun PendingCardScreen(
    card: Card,
    position: Int,
    total: Int,
    /** It came out of a .boop someone shared, rather than a computer push. */
    shared: Boolean,
    /** It was made from a link shared from another app (YouTube, Spotify, Audible…). */
    fromLink: Boolean,
    pixelArt: Boolean,
    /** A card already in use was tapped: ask whether to put [card] on it instead. */
    replace: ReplacePrompt?,
    onReplace: (ReplacePrompt) -> Unit,
    onKeep: () -> Unit,
    onNotNow: () -> Unit,
    onRemove: () -> Unit
) {
    val accent = Color(card.color)
    val pulse = rememberInfiniteTransition(label = "tap-pulse")
    val ring by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    val bob by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "bob"
    )
    val tracks = card.playlist
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101828))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF101828), Color(0xFF101828), accent.copy(alpha = 0.55f))
                )
            )
            // Swallow taps so nothing reaches the player screen underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (total > 1) stringResource(R.string.pending_header_position, position, total) else stringResource(R.string.pending_header),
                color = accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Text(
                text = when {
                    fromLink -> stringResource(R.string.pending_from_link, stringResource(card.sourceType.labelRes))
                    shared -> stringResource(R.string.pending_shared_with_you)
                    else -> stringResource(R.string.pending_sent_from_computer)
                },
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.weight(0.6f))

            // Cover with a ring pulsing out of it: "something is about to land on a card".
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val grow = 1f + ring * 0.35f
                    val side = size.minDimension * 0.78f * grow
                    drawRoundRect(
                        color = accent.copy(alpha = (1f - ring) * 0.8f),
                        topLeft = Offset((size.width - side) / 2, (size.height - side) / 2),
                        size = Size(side, side),
                        cornerRadius = CornerRadius(40.dp.toPx() * grow),
                        style = Stroke(width = 6.dp.toPx())
                    )
                }
                CardArtwork(
                    artwork = card.artworkUrl,
                    seed = card.uid,
                    color = card.color,
                    pixel = pixelArt,
                    pixels = 32,
                    modifier = Modifier.size(200.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = card.displayName(context),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (tracks.size) {
                    0 -> card.subtitle ?: ""
                    else -> pluralStringResource(R.plurals.pending_track_count, tracks.size, tracks.size)
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            // A strip of the per-track pictures, so it is obvious which card this is.
            if (tracks.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    val shown = tracks.take(8)
                    for (t in shown) {
                        CardArtwork(
                            artwork = card.trackArt[t.uri],
                            seed = card.uid + t.uri,
                            color = card.color,
                            pixel = pixelArt,
                            pixels = 16,
                            cornerRadius = 8.dp,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    if (tracks.size > shown.size) {
                        Text(
                            "+${tracks.size - shown.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // "Hold a card to the phone": a little card bobbing towards a phone outline.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 140.dp, height = 70.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val phoneW = 34.dp.toPx()
                    val phoneH = 60.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.8f),
                        topLeft = Offset(size.width * 0.62f, (size.height - phoneH) / 2),
                        size = Size(phoneW, phoneH),
                        cornerRadius = CornerRadius(7.dp.toPx()),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    val cardW = 44.dp.toPx()
                    val cardH = 30.dp.toPx()
                    val x = size.width * 0.12f + bob * size.width * 0.28f
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, (size.height - cardH) / 2),
                        size = Size(cardW, cardH),
                        cornerRadius = CornerRadius(5.dp.toPx())
                    )
                }
            }
            Text(
                text = stringResource(R.string.pending_hold_card),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.pending_will_get),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GrownUpButton(stringResource(R.string.common_not_now), onNotNow)
                GrownUpButton(stringResource(R.string.common_remove), onRemove)
            }
            Text(
                text = stringResource(R.string.pending_double_tap),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // A card that is already in use was tapped: replace what it plays, or keep it.
        replace?.let { prompt ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1D2939))
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pending_replace_title, prompt.existing.displayName(context)),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.pending_replace_body, prompt.incoming.displayName(context)),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
                        GrownUpButton(stringResource(R.string.pending_keep), onKeep)
                        GrownUpButton(stringResource(R.string.pending_replace), { onReplace(prompt) }, background = accent.copy(alpha = 0.7f))
                    }
                    Text(
                        text = stringResource(R.string.pending_double_tap),
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** Over everything while a shared .boop is being unpacked, or when it turned out not to be one. */
@Composable
fun ReceivingOverlay(state: ReceiveState, onDismiss: () -> Unit) {
    if (state == ReceiveState.Idle) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101828).copy(alpha = 0.96f))
            .pointerInput(Unit) { detectTapGestures { } }
            .systemBarsPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state is ReceiveState.Failed) {
                Text(
                    text = stringResource(R.string.receive_failed_title),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = state.message,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.common_ok), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                CircularProgressIndicator(color = Color.White, strokeWidth = 5.dp, modifier = Modifier.size(56.dp))
                Text(
                    text = stringResource(R.string.receive_opening),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Text(
                    text = stringResource(if (state == ReceiveState.FetchingLink) R.string.receive_fetching_link_hint else R.string.receive_opening_hint),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun GrownUpButton(label: String, onDoubleTap: () -> Unit, background: Color = Color.White.copy(alpha = 0.12f)) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .pointerInput(onDoubleTap) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .alpha(0.85f)
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
