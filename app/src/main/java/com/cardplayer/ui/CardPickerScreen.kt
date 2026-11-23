package com.cardplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.cardplayer.R
import com.cardplayer.data.Card
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Nobody touched the picker for this long: back to the home screen (clock, night light). */
private const val PickerIdleMs = 60_000L

/**
 * "The cards aren't here": the cards' pictures to choose from; tapping one plays it as if its card had been put
 * on the phone. Opened by holding the logo on the home screen.
 *
 * [style] (settings, "Card picker"): "cards" = one big picture at a time, swiped sideways like a carousel;
 * "tiles" = a scrolling grid of small ones.
 */
@Composable
fun CardPickerScreen(
    cards: List<Card>,
    style: String,
    pixel: Boolean,
    /** Night light: shade the pictures too, not only the chrome. */
    dim: Boolean,
    haptics: Boolean,
    onPick: (Card) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentOnClose by rememberUpdatedState(onClose)
    // Last played first, like the card list in settings. Only re-sorted when cards come or go, so nothing jumps about.
    val ordered = remember(cards.map { it.uid }) {
        cards.sortedWith(compareByDescending<Card> { it.lastPlayedAt }.thenBy { it.displayName(context).lowercase() })
    }
    if (ordered.isEmpty()) {
        LaunchedEffect(Unit) { currentOnClose() }
        return
    }
    val pick = { card: Card ->
        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onPick(card)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Nothing falls through to the home screen underneath.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        if (style == "tiles") {
            PickerTiles(cards = ordered, pixel = pixel, dim = dim, onPick = pick, onIdle = { currentOnClose() })
        } else {
            PickerCarousel(cards = ordered, pixel = pixel, dim = dim, onPick = pick, onIdle = { currentOnClose() })
        }

        // Where the gear sits on the home screen.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(60.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.common_close),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

/** Width over height of a card's face: a Yoto card, 54 x 85.6 mm, which is what the stickers are printed for. */
private const val CardAspect = 638f / 1011f

/**
 * A card as it looks in the hand, as a tap target: its sticker when it has one, else its picture on a card of
 * its own colour. Always card-shaped, so a row of them lines up. Shaded under the night light.
 */
@Composable
private fun CardFace(card: Card, pixel: Boolean, dim: Boolean, corner: Dp, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(CardAspect)
            .shadow(if (corner > 20.dp) 16.dp else 8.dp, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    ) {
        if (card.stickerUrl != null) {
            // The sticker is print art with small lettering: never pixelated, whatever the pictures setting says.
            AsyncImage(
                model = card.stickerUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(card.color).copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CardArtwork(
                    artwork = card.artworkUrl,
                    seed = card.uid,
                    color = card.color,
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(1f),
                    cornerRadius = corner * 0.7f,
                    pixel = pixel
                )
            }
        }
        if (dim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            )
        }
    }
}

/** "Cards": one card at a time, about three quarters of the screen wide, its neighbours peeking in from both sides. */
@Composable
private fun PickerCarousel(cards: List<Card>, pixel: Boolean, dim: Boolean, onPick: (Card) -> Unit, onIdle: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { cards.size }
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        delay(PickerIdleMs)
        onIdle()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Three quarters of the width, unless the screen is too short for a card that wide plus what sits round it
        // (the title in portrait, the name, the dots).
        val around = if (landscape) 110.dp else 230.dp
        val cardWidth = minOf(maxWidth * 0.75f, (maxHeight - around) * CardAspect).coerceAtLeast(110.dp)
        val sidePad = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!landscape) {
                PickerTitle()
                Spacer(Modifier.height(20.dp))
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = sidePad),
                pageSpacing = 16.dp,
                key = { cards[it].uid }
            ) { page ->
                val card = cards[page]
                Column(
                    modifier = Modifier.graphicsLayer {
                        // Neighbours sit back a little: smaller and fainter.
                        val away = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                        val scale = lerp(1f, 0.86f, away)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.5f, away)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CardFace(card = card, pixel = pixel, dim = dim, corner = 24.dp, modifier = Modifier.width(cardWidth)) {
                        // A peeking neighbour comes to the middle first; only the middle card plays.
                        if (page == pagerState.currentPage) onPick(card)
                        else scope.launch { pagerState.animateScrollToPage(page) }
                    }
                    Spacer(Modifier.height(14.dp))
                    // One line always, so the row doesn't change height from card to card.
                    Text(
                        text = card.displayName(context),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(if (landscape) 8.dp else 14.dp))
            if (cards.size in 2..12) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in cards.indices) {
                        val isCurrent = i == pagerState.currentPage
                        Box(
                            Modifier
                                .size(if (isCurrent) 14.dp else 8.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                        )
                    }
                }
            } else if (cards.size > 12) {
                // Too many for dots: plain numbers instead.
                Text(
                    text = "${pagerState.currentPage + 1} / ${cards.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "Tiles": several small cards on screen at once, scrolled up and down. */
@Composable
private fun PickerTiles(cards: List<Card>, pixel: Boolean, dim: Boolean, onPick: (Card) -> Unit, onIdle: () -> Unit) {
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.isScrollInProgress) {
        delay(PickerIdleMs)
        onIdle()
    }
    LazyVerticalGrid(
        // Three across on a phone, more on anything wider.
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        // Room at the bottom so the last row clears a notice or the NFC banner.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // As tall as the close button beside it, and clear of it on both sides.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 52.dp),
                contentAlignment = Alignment.Center
            ) { PickerTitle() }
        }
        items(cards, key = { it.uid }) { card ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CardFace(card = card, pixel = pixel, dim = dim, corner = 12.dp, modifier = Modifier.fillMaxWidth()) { onPick(card) }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = card.displayName(context),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PickerTitle() {
    Text(
        text = stringResource(R.string.picker_title),
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center
    )
}
