package com.cardplayer.ui.settings

import androidx.compose.material3.OutlinedButton
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardplayer.R
import com.cardplayer.data.Clip
import com.cardplayer.data.StickerDesign
import com.cardplayer.data.StickerPlace
import com.cardplayer.sticker.StickerContent
import com.cardplayer.sticker.StickerRenderer
import com.cardplayer.sticker.StickerRenderer.Part
import com.cardplayer.sticker.StickerSource
import com.cardplayer.sticker.StickerStore
import com.cardplayer.ui.CardSticker
import com.cardplayer.ui.PlusGate
import com.cardplayer.ui.StickerAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A sticker with nothing swapped: everything as the card says. */
private val Untouched = StickerDesign()

/**
 * The sticker drawn from [source] and [design], redrawn whenever either changes. While something is being
 * dragged the changes come faster than a sticker can be drawn: the newest is always drawn next, the ones in
 * between are skipped. Null until the first one is ready.
 */
@Composable
fun rememberSticker(source: StickerSource, design: StickerDesign): StickerRenderer.Result? {
    val context = LocalContext.current.applicationContext
    var result by remember { mutableStateOf<StickerRenderer.Result?>(null) }
    val wanted = rememberUpdatedState(source to design)
    LaunchedEffect(Unit) {
        // The pictures are fetched once and kept for as long as this sticker is on screen.
        val fetch = StickerRenderer.coilPictures(context)
        val kept = HashMap<String, Bitmap>()
        val pictures = StickerRenderer.Pictures { model, maxSide ->
            kept["$maxSide $model"] ?: fetch.load(model, maxSide)?.also { kept["$maxSide $model"] = it }
        }
        snapshotFlow { wanted.value }.conflate().collect { (s, d) ->
            try {
                result = withContext(Dispatchers.Default) { StickerRenderer.render(context, s, d, pictures) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep showing the last good one.
            }
        }
    }
    return result
}

/** How long the card's [files] run together, less what the [clip] of a one-file card cuts off. Null = not known. */
@Composable
fun rememberStickerDuration(files: List<String>, clip: Clip?): Long? {
    val context = LocalContext.current
    var total by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(files) {
        total = withContext(Dispatchers.IO) { StickerStore.totalMs(context.applicationContext, files) }
    }
    val all = total ?: return null
    if (clip == null || files.size != 1) return all
    val end = if (clip.endMs > 0) min(clip.endMs, all) else all
    return (end - clip.startMs).takeIf { it > 0 }
}

/**
 * The card's sticker, small: the one made here (or the one every card starts with), else a finished sticker
 * that came with the card. Card-shaped; give it a width.
 */
@Composable
fun StickerThumb(
    source: StickerSource,
    design: StickerDesign?,
    imported: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    pixel: Boolean = true
) {
    if (design == null && imported != null) {
        CardSticker(sticker = imported, artwork = null, seed = source.seed, color = source.color, modifier = modifier, cornerRadius = cornerRadius, pixel = pixel)
        return
    }
    val result = rememberSticker(source, design ?: Untouched)
    if (result == null) {
        // While the first one is being drawn: the card's picture on its colour.
        CardSticker(sticker = null, artwork = source.picture, seed = source.seed, color = source.color, modifier = modifier, cornerRadius = cornerRadius, pixel = pixel)
    } else {
        Image(
            bitmap = remember(result) { result.bitmap.asImageBitmap() },
            contentDescription = null,
            modifier = modifier
                .aspectRatio(StickerAspect)
                .clip(RoundedCornerShape(cornerRadius)),
            contentScale = ContentScale.FillBounds
        )
    }
}

/** What is picked on the sticker. */
private sealed interface Sel {
    data object Picture : Sel
    data object Title : Sel
    data object Subtitle : Sel
    /** The song list as a whole. */
    data object Songs : Sel
    /** One song of the list. */
    data class Row(val key: String) : Sel

    val isText: Boolean get() = this is Title || this is Subtitle || this is Row
}

private fun StickerRenderer.Region.toSel(): Sel? = when (part) {
    Part.PICTURE -> Sel.Picture
    Part.TITLE -> Sel.Title
    Part.SUBTITLE -> Sel.Subtitle
    // The box behind the name is the name's: its colour and see-through are among the name's tools.
    Part.NAMEBOX -> Sel.Title
    Part.LIST -> Sel.Songs
    Part.ROW -> Sel.Row(key.orEmpty())
    Part.FOOTER -> null
}

private fun StickerRenderer.Result.find(sel: Sel): StickerRenderer.Region? = when (sel) {
    Sel.Picture -> region(Part.PICTURE)
    Sel.Title -> region(Part.TITLE)
    Sel.Subtitle -> region(Part.SUBTITLE)
    Sel.Songs -> region(Part.LIST)
    is Sel.Row -> region(Part.ROW, sel.key)
}

private const val CardW = StickerRenderer.W.toFloat()
private const val CardH = StickerRenderer.BLEED.toFloat()

/** How far a pinch (or a corner handle) may shrink and grow each kind of part. */
private fun zoomFor(sel: Sel, start: RectF, zoom: Float): Float = when (sel) {
    Sel.Picture -> zoom.coerceIn(60f / start.width(), 6000f / start.width())
    Sel.Title, Sel.Subtitle -> zoom.coerceIn(21f / start.height(), 330f / start.height())
    Sel.Songs -> {
        val least = max(220f / start.width(), 90f / start.height())
        val most = min(CardW / start.width(), CardH / start.height())
        zoom.coerceIn(min(least, most), most)
    }
    is Sel.Row -> 1f
}

/** [start] dragged by [pan] and scaled by [zoom] about its middle. The list stays on the card; the rest keep their middle on it. */
private fun moved(sel: Sel, start: RectF, pan: Offset, zoom: Float): RectF {
    val s = zoomFor(sel, start, zoom)
    val hw = start.width() * s / 2
    val hh = start.height() * s / 2
    var cx = start.centerX() + pan.x
    var cy = start.centerY() + pan.y
    if (sel == Sel.Songs) {
        cx = cx.coerceIn(hw, max(hw, CardW - hw))
        cy = cy.coerceIn(hh, max(hh, CardH - hh))
    } else {
        cx = cx.coerceIn(0f, CardW)
        cy = cy.coerceIn(0f, CardH)
    }
    return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
}

/**
 * [start] resized by a finger that went down at [from] (on a corner) and is now at [to]. The song list's box
 * follows the corner, the opposite corner staying put; a picture or a line of text grows and shrinks about its middle.
 */
private fun resized(sel: Sel, start: RectF, from: Offset, to: Offset): RectF {
    if (sel != Sel.Songs) {
        val reach = hypot(from.x - start.centerX(), from.y - start.centerY()).coerceAtLeast(1f)
        return moved(sel, start, Offset.Zero, hypot(to.x - start.centerX(), to.y - start.centerY()) / reach)
    }
    val r = RectF(start)
    if (from.x < start.centerX()) r.left = to.x.coerceIn(0f, start.right - 220f) else r.right = to.x.coerceIn(start.left + 220f, CardW)
    if (from.y < start.centerY()) r.top = to.y.coerceIn(0f, start.bottom - 90f) else r.bottom = to.y.coerceIn(start.top + 90f, CardH)
    return r
}

private fun StickerDesign.placing(sel: Sel, r: RectF): StickerDesign {
    val place = StickerPlace(r.left, r.top, r.width(), r.height())
    return when (sel) {
        Sel.Picture -> copy(picturePlace = place)
        Sel.Title -> copy(titlePlace = place)
        Sel.Subtitle -> copy(subtitlePlace = place)
        Sel.Songs -> copy(listPlace = place)
        is Sel.Row -> this
    }
}

/**
 * The sticker maker: the card's sticker as a little design canvas. It starts filled in from the card (its
 * picture, name, songs and their icons, how long it runs). Tap a part to pick it, drag it to move it, pinch
 * or pull a corner to resize it, tap text again to type on the card itself. Nothing is kept until the tick,
 * and then only in the editor: the card's Save stores it, like everything else there.
 *
 * [design] is what the card has now (null = no sticker made here yet); [imported] a finished sticker that came
 * with the card. The picture is the card's own: the three picture callbacks change it in the editor, and
 * [source] comes back with the new one. A new picture is a step like any other: undo and redo put the
 * earlier one back through [onRestorePicture], and so does closing the maker without the tick.
 */
@Composable
fun StickerMakerDialog(
    source: StickerSource,
    design: StickerDesign?,
    imported: String?,
    pixel: Boolean,
    /** BoopBox Plus is on. Without it there is no maker: the suggested sticker, and its picture to change ([FreeStickerPage]). */
    plus: Boolean,
    /** The locked maker was tapped, without Plus: say what Plus is. */
    onNeedPlus: () -> Unit,
    onFindPicture: () -> Unit,
    onGalleryPicture: () -> Unit,
    onRemovePicture: () -> Unit,
    onRestorePicture: (String?) -> Unit,
    onDone: (StickerDesign) -> Unit,
    onDismiss: () -> Unit
) {
    if (!plus) {
        FreeStickerPage(source, design, imported, pixel, onNeedPlus, onFindPicture, onGalleryPicture, onRemovePicture, onRestorePicture, onDone, onDismiss)
        return
    }
    // Null = still looking at the finished sticker that came with the card.
    var work by remember { mutableStateOf(design ?: if (imported == null) Untouched else null) }
    val undo = remember { mutableStateListOf<Step>() }
    val redo = remember { mutableStateListOf<Step>() }
    /** The card's picture as the maker last saw it, and as it was when the maker opened. */
    var seenPicture by remember { mutableStateOf(source.picture) }
    val openedPicture = remember { source.picture }
    /** The picture is being put back by undo / redo: that is not a new step. */
    var restoring by remember { mutableStateOf(false) }
    /** Remembers how the sticker is now, before something changes it. */
    fun checkpoint() {
        val now = Step(work ?: return, seenPicture)
        if (undo.lastOrNull() != now) undo += now
        if (undo.size > 80) undo.removeAt(0)
        redo.clear()
    }
    /** Goes to a remembered step: the sticker as it was, and the card's picture as it was. */
    fun goTo(step: Step) {
        work = step.design
        if (step.picture != source.picture) {
            restoring = true
            onRestorePicture(step.picture)
        }
    }
    // The card got another picture (found, from the gallery, removed): a step to undo, like any other.
    LaunchedEffect(source.picture) {
        if (source.picture != seenPicture) {
            if (!restoring) checkpoint()
            restoring = false
            seenPicture = source.picture
        }
    }
    /** Closed without the tick: the sticker stays as it was, and so does the card's picture. */
    val close = {
        if (source.picture != openedPicture) onRestorePicture(openedPicture)
        onDismiss()
    }
    fun change(next: StickerDesign) {
        if (next != work) {
            checkpoint()
            work = next
        }
    }
    var titleShown by remember { mutableStateOf(true) }
    var lineShown by remember { mutableStateOf(false) }
    var listShown by remember { mutableStateOf(true) }
    // A card with no sticker yet starts at the layouts: pick one, then change what you like.
    var showLayouts by remember { mutableStateOf(design == null && imported == null) }
    var addOpen by remember { mutableStateOf(false) }
    val newLine = stringResource(R.string.sticker_new_line)

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val current = work
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                    if (current == null) {
                        Text(stringResource(R.string.sticker_maker_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    } else {
                        val here = Step(current, source.picture)
                        IconButton(
                            enabled = undo.any { it != here },
                            onClick = {
                                // A checkpoint taken for a touch that changed nothing is skipped over.
                                while (undo.isNotEmpty() && undo.last() == here) undo.removeAt(undo.lastIndex)
                                if (undo.isNotEmpty()) {
                                    redo += here
                                    goTo(undo.removeAt(undo.lastIndex))
                                }
                            }
                        ) { Icon(StickerIcons.Undo, contentDescription = stringResource(R.string.sticker_undo)) }
                        IconButton(
                            enabled = redo.isNotEmpty(),
                            onClick = {
                                undo += here
                                goTo(redo.removeAt(redo.lastIndex))
                            }
                        ) { Icon(StickerIcons.Redo, contentDescription = stringResource(R.string.sticker_redo)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showLayouts = true }) {
                            Icon(StickerIcons.Layouts, contentDescription = stringResource(R.string.sticker_layouts))
                        }
                        Box {
                            // What isn't on the sticker and could be.
                            val content = StickerContent(source, current)
                            val addName = !titleShown && content.title.isNotEmpty()
                            val addLine = !lineShown
                            val addList = !listShown && source.rows.isNotEmpty()
                            val addRows = current.hiddenRows.isNotEmpty()
                            val addPicture = source.picture == null
                            IconButton(onClick = { addOpen = true }, enabled = addName || addLine || addList || addRows || addPicture) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sticker_add))
                            }
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                @Composable
                                fun choice(@StringRes label: Int, action: () -> Unit) {
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                        addOpen = false
                                        action()
                                    })
                                }
                                if (addPicture) {
                                    choice(R.string.editor_find_picture, onFindPicture)
                                    choice(R.string.sticker_from_gallery, onGalleryPicture)
                                }
                                if (addName) choice(R.string.sticker_add_name) { change(current.copy(showTitle = true)) }
                                if (addLine) choice(R.string.sticker_line_under) {
                                    // Under the name; with no name on the sticker, in a place of its own.
                                    val apart = if (titleShown) null else StickerPlace(CardW / 2 - 160f, CardH * 0.6f, 320f, 51f)
                                    change(current.copy(subtitle = source.subtitle?.trim()?.ifEmpty { null } ?: newLine, subtitlePlace = apart))
                                }
                                if (addList) choice(R.string.sticker_add_list) {
                                    change(current.copy(showList = true, hiddenRows = if (content.rows.isEmpty()) emptySet() else current.hiddenRows))
                                }
                                if (addRows) choice(R.string.sticker_rows_restore) { change(current.copy(hiddenRows = emptySet())) }
                            }
                        }
                        IconButton(onClick = { onDone(current) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.common_done), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(
                    stringResource(if (current == null) R.string.sticker_imported_note else R.string.sticker_maker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(10.dp))
                // No size of its own: the card grows as big as the room allows, whichever of width and height runs out first.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    if (current == null) {
                        CardSticker(
                            sticker = imported,
                            artwork = source.picture,
                            seed = source.seed,
                            color = source.color,
                            modifier = Modifier.shadow(12.dp, RoundedCornerShape(20.dp)),
                            cornerRadius = 20.dp,
                            pixel = pixel
                        )
                    } else {
                        StickerCanvas(
                            source = source,
                            design = current,
                            onCheckpoint = ::checkpoint,
                            onLive = { work = it },
                            onChange = ::change,
                            onRendered = {
                                titleShown = it.titleShown
                                listShown = it.listShown
                                lineShown = it.region(Part.SUBTITLE) != null
                            },
                            onFindPicture = onFindPicture,
                            onGalleryPicture = onGalleryPicture,
                            onRemovePicture = onRemovePicture
                        )
                    }
                }
                if (current == null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        work = Untouched
                        showLayouts = true
                    }) { Text(stringResource(R.string.sticker_make_new)) }
                }
            }
        }
    }

    val current = work
    if (showLayouts && current != null) {
        LayoutPicker(
            source = source,
            design = current,
            onPick = {
                change(it)
                showLayouts = false
            },
            onDismiss = { showLayouts = false }
        )
    }
}

/**
 * The sticker page without BoopBox Plus: the card's sticker in the suggested layout, and its picture to change.
 * That is all. What the maker does besides (layouts, colours, text, moving things about) is shown out of reach
 * behind a [PlusGate], with this card's own sticker in the other layouts.
 *
 * A sticker the card already has (one that came with it, or was made while Plus was on) is kept as it is; one
 * that is only a finished picture gives way to the suggested sticker once the card's picture is changed, or
 * the change would not show.
 */
@Composable
private fun FreeStickerPage(
    source: StickerSource,
    design: StickerDesign?,
    imported: String?,
    pixel: Boolean,
    onNeedPlus: () -> Unit,
    onFindPicture: () -> Unit,
    onGalleryPicture: () -> Unit,
    onRemovePicture: () -> Unit,
    onRestorePicture: (String?) -> Unit,
    onDone: (StickerDesign) -> Unit,
    onDismiss: () -> Unit
) {
    val openedPicture = remember { source.picture }
    val shown = design ?: if (imported == null || source.picture != openedPicture) Untouched else null
    /** Closed without the tick: the card's picture stays as it was. */
    val close = {
        if (source.picture != openedPicture) onRestorePicture(openedPicture)
        onDismiss()
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                    Text(stringResource(R.string.sticker_maker_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (shown != null) onDone(shown) else onDismiss() }) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.common_done), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                // The sticker itself is the card's own and free: sharp, as big as the room allows.
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    StickerThumb(source = source, design = shown, imported = imported, modifier = Modifier.shadow(12.dp, RoundedCornerShape(20.dp)), cornerRadius = 20.dp, pixel = pixel)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onFindPicture) { Text(stringResource(R.string.editor_find_picture)) }
                    OutlinedButton(onClick = onGalleryPicture) { Text(stringResource(R.string.sticker_gallery_short)) }
                }
                if (source.picture != null) {
                    TextButton(onClick = onRemovePicture) { Text(stringResource(R.string.sticker_picture_remove)) }
                }
                Spacer(Modifier.height(6.dp))
                PlusGate(
                    what = stringResource(R.string.plus_gate_sticker),
                    detail = stringResource(R.string.plus_gate_sticker_detail),
                    onUpgrade = onNeedPlus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    // What Plus adds, with this very card in it: the other layouts.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (layout in StickerRenderer.LAYOUTS.take(3)) {
                            StickerThumb(source = source, design = Untouched.copy(layout = layout), imported = null, modifier = Modifier.weight(1f), pixel = pixel)
                        }
                    }
                }
            }
        }
    }
}

/** One step of the sticker maker's undo history: the sticker, and the card's picture it was drawn with. */
private data class Step(val design: StickerDesign, val picture: String?)

/** The ready-made layouts, each shown with this card's own picture and songs: tap one to start from it. */
@Composable
private fun LayoutPicker(source: StickerSource, design: StickerDesign, onPick: (StickerDesign) -> Unit, onDismiss: () -> Unit) {
    // A template puts every part in its own place and decides on the name and the list afresh: what was dragged
    // about goes back, and what was added or taken off no longer counts.
    val loose = design.copy(picturePlace = null, titlePlace = null, subtitlePlace = null, listPlace = null, showTitle = null, showList = null)
    val options = listOf<Pair<String?, Int>>(null to R.string.sticker_layout_auto) + StickerRenderer.LAYOUTS.map { it to LayoutNames.getValue(it) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.96f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.sticker_layouts), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sticker_layouts_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((key, label) in options) {
                        val chosen = design.layout == key
                        // No words under them: each is this card drawn that way. The name is there for a screen reader.
                        val name = stringResource(label)
                        Column(
                            modifier = Modifier
                                .width(132.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClickLabel = name) { onPick(loose.copy(layout = key)) }
                                .semantics { contentDescription = name }
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            StickerThumb(
                                source = source,
                                design = loose.copy(layout = key),
                                imported = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(if (chosen) 3.dp else 0.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onPick(Untouched) }) { Text(stringResource(R.string.sticker_start_over)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}

/** What each template is called (read out by a screen reader; the picker shows them as pictures). */
private val LayoutNames = mapOf(
    "picture" to R.string.sticker_layout_picture,
    "picture-name" to R.string.sticker_layout_name,
    "picture-list" to R.string.sticker_layout_list,
    "picture-list-name" to R.string.sticker_layout_list_name,
    "middle" to R.string.sticker_layout_middle,
    "icon" to R.string.sticker_layout_icon
)

/** The colours the song list's box can be given (the first = a pale tint of the card's own). */
private val BoxColors = listOf<Long?>(null, 0xFFFFFFFF, 0xFFFFF3E0, 0xFFFFE0EC, 0xFFE1F5FE, 0xFFE8F5E9, 0xFF0B1647, 0xFF000000)

/** The colours a line of text can be given (the first = the layout's own). */
private val TextColors = listOf<Long?>(null, 0xFFFFFFFF, 0xFFFFD54F, 0xFFFF8A65, 0xFFFF6F91, 0xFF4FC3F7, 0xFF0B1647, 0xFF000000)

/**
 * The sticker itself, as the thing being edited.
 * [onCheckpoint] is called before a drag or typing starts changing it, [onLive] with every step of that
 * change (not worth an undo step each), [onChange] for a change made in one go.
 */
@Composable
private fun StickerCanvas(
    source: StickerSource,
    design: StickerDesign,
    onCheckpoint: () -> Unit,
    onLive: (StickerDesign) -> Unit,
    onChange: (StickerDesign) -> Unit,
    onRendered: (StickerRenderer.Result) -> Unit,
    onFindPicture: () -> Unit,
    onGalleryPicture: () -> Unit,
    onRemovePicture: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val result = rememberSticker(source, design)
    LaunchedEffect(result) { result?.let(onRendered) }

    var selected by remember { mutableStateOf<Sel?>(null) }
    /** The text being typed on the card, and the box it was last seen in (it has none while it is empty). */
    var editing by remember { mutableStateOf<Sel?>(null) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var editBox by remember { mutableStateOf<StickerRenderer.Region?>(null) }
    var dragging by remember { mutableStateOf(false) }
    /** The part being dragged sits on the card's middle line. */
    var snapped by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // The newest of everything, for the touch code (it lives across redraws).
    val resultNow by rememberUpdatedState(result)
    val designNow by rememberUpdatedState(design)
    val sourceNow by rememberUpdatedState(source)
    val live by rememberUpdatedState(onLive)
    val check by rememberUpdatedState(onCheckpoint)

    fun textOf(sel: Sel): String = when (sel) {
        Sel.Title -> designNow.title ?: sourceNow.name
        Sel.Subtitle -> designNow.subtitle ?: sourceNow.subtitle.orEmpty()
        is Sel.Row -> designNow.rowTitles[sel.key] ?: sourceNow.rows.firstOrNull { it.key == sel.key }?.title.orEmpty()
        else -> ""
    }
    fun startTyping(sel: Sel) {
        check()
        val text = textOf(sel)
        field = TextFieldValue(text, TextRange(text.length))
        editBox = resultNow?.find(sel)
        selected = sel
        editing = sel
    }
    /** Typing is over: text typed back to what the card says follows the card again, text emptied comes off. */
    fun stopTyping() {
        val sel = editing ?: return
        editing = null
        keyboard?.hide()
        val d = designNow
        live(
            when (sel) {
                Sel.Title -> {
                    val typed = d.title?.trim()
                    when {
                        typed == null -> d
                        typed.isEmpty() -> d.copy(title = null, showTitle = false)
                        typed == sourceNow.name.trim() -> d.copy(title = null)
                        else -> d.copy(title = typed)
                    }
                }
                Sel.Subtitle -> d.copy(subtitle = d.subtitle?.trim())
                is Sel.Row -> {
                    val typed = d.rowTitles[sel.key]?.trim()
                    val own = sourceNow.rows.firstOrNull { it.key == sel.key }?.title
                    if (typed.isNullOrEmpty() || typed == own) d.copy(rowTitles = d.rowTitles - sel.key) else d.copy(rowTitles = d.rowTitles + (sel.key to typed))
                }
                else -> d
            }
        )
        if (sel == Sel.Title && d.title?.isBlank() == true) selected = null
    }
    BackHandler(enabled = editing != null || selected != null) {
        if (editing != null) stopTyping() else selected = null
    }
    LaunchedEffect(editing) {
        if (editing != null) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // The part that was picked is gone (removed, or its layout doesn't draw it): nothing is picked.
    LaunchedEffect(result, selected) {
        val sel = selected
        if (result != null && sel != null && editing == null && result.find(sel) == null) selected = null
    }

    BoxWithConstraints(
        modifier = Modifier
            .aspectRatio(StickerAspect)
            .shadow(12.dp, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        val cardWidth = maxWidth
        val cardHeight = maxHeight
        /** Sticker pixels per screen pixel. */
        val k = StickerRenderer.W / with(density) { cardWidth.toPx() }
        val accent = MaterialTheme.colorScheme.primary

        if (result == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(Modifier.size(32.dp)) }
            return@BoxWithConstraints
        }

        Image(
            bitmap = remember(result) { result.bitmap.asImageBitmap() },
            contentDescription = stringResource(R.string.editor_sticker_cd),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
        )

        // ---- touch: tap picks, drag moves, two fingers (or a corner) resize
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val res = resultNow ?: return@awaitEachGesture
                        val scale = StickerRenderer.W / size.width.toFloat()
                        val at = down.position * scale
                        val before = selected
                        val frame = before?.let { res.find(it) }?.rect
                        // The handles sit on the corners of what shows of the frame (a picture's own corners may be off the card).
                        val shown = frame?.let { RectF(max(it.left, 0f), max(it.top, 0f), min(it.right, CardW), min(it.bottom, StickerRenderer.H.toFloat())) }
                        val reach = 26.dp.toPx() * scale
                        val onHandle = before != null && before !is Sel.Row && shown != null && listOf(
                            Offset(shown.left, shown.top), Offset(shown.right, shown.top), Offset(shown.right, shown.bottom), Offset(shown.left, shown.bottom)
                        ).any { (it - at).getDistance() <= reach }
                        val hit = res.partAt(at.x, at.y)
                        // What a drag from here moves: the picked part if the finger is on it (even where another lies over it), else what is under the finger. A song drags the whole list.
                        val grab: Sel? = when {
                            onHandle -> before
                            before != null && before !is Sel.Row && frame?.contains(at.x, at.y) == true -> before
                            else -> hit?.toSel()?.let { if (it is Sel.Row) Sel.Songs else it }
                        }
                        val start = grab?.let { res.find(it) }?.rect?.let(::RectF)
                        var moving = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        do {
                            val event = awaitPointerEvent()
                            zoom *= event.calculateZoom()
                            pan += event.calculatePan()
                            if (!moving) {
                                val spread = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                                if (spread > slop || pan.getDistance() > slop) {
                                    moving = true
                                    if (grab != null && start != null) {
                                        if (editing != null) stopTyping()
                                        check()
                                        selected = grab
                                        dragging = true
                                    }
                                }
                            }
                            if (moving && grab != null && start != null) {
                                val finger = event.changes.first().position * scale
                                var box = if (onHandle && event.changes.size == 1) resized(grab, start, at, finger) else moved(grab, start, pan * scale, zoom)
                                // A dragged part clicks onto the card's middle line.
                                val near = grab != Sel.Songs && !onHandle && abs(box.centerX() - CardW / 2) < 9f
                                if (near) box = RectF(box).apply { offset(CardW / 2 - centerX(), 0f) }
                                snapped = near
                                live(designNow.placing(grab, box))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        dragging = false
                        snapped = false
                        if (!moving) {
                            // A tap: pick; on text that is already picked, type. A song is reached through its list.
                            val target = hit?.toSel()
                            val typingOn = editing
                            if (typingOn != null && target != typingOn) stopTyping()
                            when {
                                target == null -> selected = null
                                target is Sel.Row -> when {
                                    before == target -> startTyping(target)
                                    before == Sel.Songs || before is Sel.Row -> selected = target
                                    else -> selected = Sel.Songs
                                }
                                target == before && target.isText -> startTyping(target)
                                else -> selected = target
                            }
                        }
                    }
                }
        )

        // ---- the frame round what is picked, with a handle on each corner
        val picked = selected
        val box = picked?.let { result.find(it) } ?: editBox?.takeIf { editing != null }
        Canvas(Modifier.fillMaxSize()) {
            if (snapped) drawLine(accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.5.dp.toPx())
            val r = box?.rect ?: return@Canvas
            val left = max(r.left, 0f) / k
            val top = max(r.top, 0f) / k
            val right = min(r.right, CardW) / k
            val bottom = min(r.bottom, StickerRenderer.H.toFloat()) / k
            if (right <= left || bottom <= top) return@Canvas
            val corner = CornerRadius(4.dp.toPx())
            drawRoundRect(Color.White, Offset(left, top), Size(right - left, bottom - top), corner, style = Stroke(4.dp.toPx()))
            drawRoundRect(accent, Offset(left, top), Size(right - left, bottom - top), corner, style = Stroke(2.dp.toPx()))
            if (picked != null && picked !is Sel.Row && editing == null) {
                for (p in listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom))) {
                    drawCircle(Color.White, 8.dp.toPx(), p)
                    drawCircle(accent, 8.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                }
            }
        }

        // ---- typing on the card: an unseen text box over the words, so the cursor sits in them and the sticker shows what is typed
        val typing = editing
        if (typing != null) {
            val region = result.find(typing)?.also { editBox = it } ?: editBox
            val words = region?.text ?: region?.rect
            if (words != null) {
                val sizePx = (region?.textSize ?: 40f) / k
                val centred = typing !is Sel.Row
                val hebrew = remember(source, design) { StickerContent(source, design).let { c -> (c.title + c.rows.joinToString("") { it.title }).any { ch -> ch in '֐'..'׿' } } }
                val fontName = when (typing) {
                    Sel.Title -> design.titleFont
                    Sel.Subtitle -> design.subtitleFont
                    else -> design.listFont
                }
                val family = remember(fontName, typing) { FontFamily(StickerRenderer.typeface(context, fontName, bold = typing != Sel.Subtitle)) }
                val fieldHeight = with(density) { (sizePx * 1.5f).toDp() }
                BasicTextField(
                    value = field,
                    onValueChange = { typed ->
                        val text = typed.text.replace("\n", " ")
                        field = typed.copy(text = text)
                        live(
                            when (typing) {
                                Sel.Title -> designNow.copy(title = text)
                                Sel.Subtitle -> designNow.copy(subtitle = text)
                                is Sel.Row -> designNow.copy(rowTitles = designNow.rowTitles + (typing.key to text))
                                else -> designNow
                            }
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontSize = with(density) { sizePx.toSp() },
                        fontFamily = family,
                        fontWeight = if (typing != Sel.Subtitle) FontWeight.Bold else FontWeight.Normal,
                        // A song's name is printed from the right on a Hebrew sticker.
                        textAlign = if (centred) TextAlign.Center else if (hebrew) TextAlign.Right else TextAlign.Left
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { stopTyping() }),
                    modifier = Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset {
                            IntOffset(
                                if (centred) 0 else (words.left / k).roundToInt(),
                                (words.centerY() / k - sizePx * 0.75f).roundToInt()
                            )
                        }
                        .width(if (centred) cardWidth else with(density) { (words.width() / k).toDp() })
                        .height(fieldHeight)
                        .focusRequester(focus)
                )
            }
        }

        // ---- the few things that can be done to what is picked, in a little bar next to it
        if (picked != null && box != null && editing == null && !dragging) {
            val barHeight = 46.dp
            val gap = 12.dp
            val top = with(density) { (max(box.rect.top, 0f) / k).toDp() }
            val bottom = with(density) { (min(box.rect.bottom, StickerRenderer.H.toFloat()) / k).toDp() }
            val y = when {
                top - barHeight - gap >= 0.dp -> top - barHeight - gap
                bottom + gap + barHeight <= cardHeight -> bottom + gap
                else -> top + gap
            }.coerceIn(4.dp, max(4.dp.value, (cardHeight - barHeight - 4.dp).value).dp)
            var fontsOpen by remember(picked) { mutableStateOf(false) }
            var coloursOpen by remember(picked) { mutableStateOf(false) }
            var boxOpen by remember(picked) { mutableStateOf(false) }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = y)
                    .height(barHeight),
                shape = RoundedCornerShape(23.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable
                    fun tool(icon: ImageVector, @StringRes label: Int, action: () -> Unit) {
                        IconButton(onClick = action, modifier = Modifier.size(44.dp)) {
                            Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(22.dp))
                        }
                    }

                    @Composable
                    fun fontTool(current: String?, pick: (String?) -> Unit) {
                        Box {
                            TextButton(onClick = { fontsOpen = true }, modifier = Modifier.height(44.dp)) {
                                Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(expanded = fontsOpen, onDismissRequest = { fontsOpen = false }) {
                                StickerRenderer.FONTS.forEachIndexed { i, name ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(FontNames[i]),
                                                fontFamily = FontFamily(StickerRenderer.typeface(context, name, bold = true)),
                                                fontWeight = if ((current ?: StickerRenderer.FONTS.first()) == name) FontWeight.Bold else FontWeight.Normal,
                                                color = if ((current ?: StickerRenderer.FONTS.first()) == name) accent else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            fontsOpen = false
                                            pick(name.takeIf { i > 0 })
                                        }
                                    )
                                }
                            }
                        }
                    }

                    @Composable
                    fun colourTool(current: Long?, pick: (Long?) -> Unit) {
                        Box {
                            IconButton(onClick = { coloursOpen = true }, modifier = Modifier.size(44.dp)) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(current ?: 0xFFFFFFFF))
                                        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                )
                            }
                            DropdownMenu(expanded = coloursOpen, onDismissRequest = { coloursOpen = false }) {
                                Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    for (colour in TextColors) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(colour?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant)
                                                .border(if (colour == current) 3.dp else 1.dp, if (colour == current) accent else MaterialTheme.colorScheme.outline, CircleShape)
                                                .clickable {
                                                    coloursOpen = false
                                                    pick(colour)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            // The first one: back to the layout's own colour.
                                            if (colour == null) Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sticker_use_card_text), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    /** A box's colour and how see-through it is: the song list's, or the one behind the name. */
                    @Composable
                    fun boxTool(current: Long?, opacity: Float, withColour: (Long?) -> StickerDesign, withOpacity: (Float) -> StickerDesign) {
                        Box {
                            IconButton(onClick = { boxOpen = true }, modifier = Modifier.size(44.dp)) {
                                // A little box, in the box's colour.
                                Box(
                                    Modifier
                                        .size(24.dp, 20.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(current?.let { Color(it) } ?: Color(source.color).copy(alpha = 0.35f))
                                        .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp))
                                )
                            }
                            DropdownMenu(expanded = boxOpen, onDismissRequest = { boxOpen = false }) {
                                Column(Modifier.padding(horizontal = 12.dp).width(292.dp)) {
                                    Text(stringResource(R.string.sticker_box_colour), style = MaterialTheme.typography.labelLarge)
                                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        for (colour in BoxColors) {
                                            val chosen = colour == current
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape)
                                                    .background(colour?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant)
                                                    .border(if (chosen) 3.dp else 1.dp, if (chosen) accent else MaterialTheme.colorScheme.outline, CircleShape)
                                                    .clickable { onChange(withColour(colour)) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                // The first one: back to the tint of the card's own colour.
                                                if (colour == null) Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sticker_put_back), modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                    Text(stringResource(R.string.sticker_box_opacity), style = MaterialTheme.typography.labelLarge)
                                    // One undo step for the whole slide, not one for every point along it.
                                    var sliding by remember { mutableStateOf(false) }
                                    Slider(
                                        value = opacity,
                                        onValueChange = {
                                            if (!sliding) {
                                                sliding = true
                                                check()
                                            }
                                            live(withOpacity(it))
                                        },
                                        onValueChangeFinished = { sliding = false },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                        }
                    }

                    when (picked) {
                        Sel.Picture -> {
                            tool(Icons.Default.Search, if (source.picture == null) R.string.editor_find_picture else R.string.editor_change_picture, onFindPicture)
                            tool(StickerIcons.Picture, R.string.sticker_from_gallery, onGalleryPicture)
                            if (design.picturePlace != null) tool(Icons.Default.Refresh, R.string.sticker_put_back) { onChange(design.copy(picturePlace = null)) }
                            if (source.picture != null) tool(Icons.Default.Delete, R.string.editor_remove_picture_cd) {
                                selected = null
                                onChange(design.copy(picturePlace = null))
                                onRemovePicture()
                            }
                        }
                        Sel.Title -> {
                            tool(Icons.Default.Edit, R.string.sticker_change_text) { startTyping(Sel.Title) }
                            fontTool(design.titleFont) { onChange(design.copy(titleFont = it)) }
                            colourTool(design.titleColor) { onChange(design.copy(titleColor = it)) }
                            // The box behind the name: the block of colour of "Picture and name", or one of its own round the words.
                            val band = result.geometry == "block" && design.titlePlace == null
                            boxTool(
                                design.titleBoxColor,
                                design.titleBoxOpacity ?: if (band) 1f else if (design.titleBoxColor != null) StickerRenderer.LIST_OPACITY else 0f,
                                { design.copy(titleBoxColor = it) },
                                { design.copy(titleBoxOpacity = it) }
                            )
                            if (design.title != null || design.titlePlace != null || design.titleFont != null || design.titleColor != null || design.titleBoxColor != null || design.titleBoxOpacity != null) {
                                tool(Icons.Default.Refresh, R.string.sticker_use_card_text) {
                                    onChange(design.copy(title = null, titlePlace = null, titleFont = null, titleColor = null, titleBoxColor = null, titleBoxOpacity = null))
                                }
                            }
                            tool(Icons.Default.Delete, R.string.sticker_remove_text) {
                                selected = null
                                onChange(design.copy(showTitle = false))
                            }
                        }
                        Sel.Subtitle -> {
                            tool(Icons.Default.Edit, R.string.sticker_change_text) { startTyping(Sel.Subtitle) }
                            fontTool(design.subtitleFont) { onChange(design.copy(subtitleFont = it)) }
                            colourTool(design.subtitleColor) { onChange(design.copy(subtitleColor = it)) }
                            if (design.subtitlePlace != null || design.subtitleFont != null || design.subtitleColor != null) {
                                tool(Icons.Default.Refresh, R.string.sticker_put_back) { onChange(design.copy(subtitlePlace = null, subtitleFont = null, subtitleColor = null)) }
                            }
                            tool(Icons.Default.Delete, R.string.sticker_remove_text) {
                                selected = null
                                onChange(design.copy(subtitle = "", subtitlePlace = null))
                            }
                        }
                        Sel.Songs -> {
                            fontTool(design.listFont) { onChange(design.copy(listFont = it)) }
                            boxTool(design.listColor, design.listOpacity ?: StickerRenderer.LIST_OPACITY, { design.copy(listColor = it) }, { design.copy(listOpacity = it) })
                            if (design.listPlace != null || design.listFont != null || design.listColor != null || design.listOpacity != null) {
                                tool(Icons.Default.Refresh, R.string.sticker_put_back) { onChange(design.copy(listPlace = null, listFont = null, listColor = null, listOpacity = null)) }
                            }
                            tool(Icons.Default.Delete, R.string.sticker_list_remove) {
                                selected = null
                                onChange(design.copy(showList = false))
                            }
                        }
                        is Sel.Row -> {
                            tool(Icons.Default.Edit, R.string.sticker_change_text) { startTyping(picked) }
                            if (picked.key in design.rowTitles) {
                                tool(Icons.Default.Refresh, R.string.sticker_use_card_text) { onChange(design.copy(rowTitles = design.rowTitles - picked.key)) }
                            }
                            tool(Icons.Default.Delete, R.string.sticker_row_remove) {
                                selected = Sel.Songs
                                onChange(design.copy(hiddenRows = design.hiddenRows + picked.key))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What each of [StickerRenderer.FONTS] is called, in the same order. */
private val FontNames = listOf(
    R.string.sticker_font_round,
    R.string.sticker_font_heavy,
    R.string.sticker_font_narrow,
    R.string.sticker_font_book,
    R.string.sticker_font_handwriting,
    R.string.sticker_font_script,
    R.string.sticker_font_typewriter
)
