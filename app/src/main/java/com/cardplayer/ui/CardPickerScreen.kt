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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
 * [style] (settings, "Card picker"): "cards" = one big card at a time, swiped sideways like a carousel;
 * "tiles" = [tileCols] × [tileRows] small ones per screen.
 */
@Composable
fun CardPickerScreen(
    cards: List<Card>,
    style: String,
    tileCols: Int,
    tileRows: Int,
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
            PickerTiles(cards = ordered, cols = tileCols, rows = tileRows, pixel = pixel, dim = dim, onPick = pick, onIdle = { currentOnClose() })
        } else {
            PickerCarousel(cards = ordered, pixel = pixel, dim = dim, onPick = pick, onIdle = { currentOnClose() })
        }

        // Where the gear sits on the home screen.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeTopPadding()
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
        // A sticker that is gone or won't open (file deleted, link dead): the card falls back to its plain picture.
        val sticker = remember(card.stickerUrl) { card.stickerIfThere() }
        var stickerFailed by remember(card.stickerUrl) { mutableStateOf(false) }
        if (sticker != null && !stickerFailed) {
            // The sticker is print art with small lettering: never pixelated, whatever the pictures setting says.
            AsyncImage(
                model = sticker,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { stickerFailed = true }
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
                .safeDrawingPadding(),
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
            PageMarks(count = cards.size, current = pagerState.currentPage)
        }
    }
}

/**
 * "Tiles": small cards, [cols] across and [rows] down per screen (the grown-up's choice in settings), each as big
 * as its share of the screen allows. More cards are a smooth scroll down.
 */
@Composable
private fun PickerTiles(cards: List<Card>, cols: Int, rows: Int, pixel: Boolean, dim: Boolean, onPick: (Card) -> Unit, onIdle: () -> Unit) {
    val context = LocalContext.current
    val across = cols.coerceIn(1, 6)
    val down = rows.coerceIn(1, 6)
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.isScrollInProgress) {
        delay(PickerIdleMs)
        onIdle()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // As tall as the close button beside it, and clear of it on both sides. A short screen keeps the room for cards.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (landscape) 12.dp else 76.dp)
                    .padding(horizontal = 56.dp),
                contentAlignment = Alignment.Center
            ) { if (!landscape) PickerTitle() }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val gap = if (across >= 5 || down >= 5) 6.dp else 12.dp
                val edge = 12.dp
                // Exactly [down] rows fill what is on screen; the rest scrolls into it.
                val rowHeight = ((maxHeight - edge * 2 - gap * (down - 1)) / down).coerceAtLeast(48.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(across),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = edge),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    items(cards, key = { it.uid }) { card ->
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            // The card is as big as its place allows, over one line for its name.
                            val nameRoom = 24.dp
                            val width = minOf(maxWidth, (maxHeight - nameRoom) * CardAspect).coerceAtLeast(20.dp)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CardFace(
                                    card = card,
                                    pixel = pixel,
                                    dim = dim,
                                    corner = (width * 0.08f).coerceIn(4.dp, 18.dp),
                                    modifier = Modifier.width(width)
                                ) { onPick(card) }
                                Box(Modifier.height(nameRoom), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = card.displayName(context),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where in the row of screens (or cards) the picker is: dots, or plain numbers when there are too many for dots. */
@Composable
private fun PageMarks(count: Int, current: Int) {
    if (count in 2..12) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                val isCurrent = i == current
                Box(
                    Modifier
                        .size(if (isCurrent) 14.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                )
            }
        }
    } else if (count > 12) {
        Text(
            text = "${current + 1} / $count",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
