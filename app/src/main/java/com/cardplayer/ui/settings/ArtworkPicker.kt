@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardplayer.R
import com.cardplayer.link.ArtworkSearch
import com.cardplayer.link.YotoIcons
import com.cardplayer.link.YotoStore
import com.cardplayer.ui.CardArtwork
import com.cardplayer.ui.CardSticker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ArtSource(@param:StringRes val labelRes: Int) {
    ICONS(R.string.art_source_icons),
    ALBUM(R.string.art_source_album),
    URL(R.string.art_source_url)
}

/**
 * Full-height picker: pixel icons from yotoicons.com (default), covers ([ArtworkSearch]: albums, books, podcasts), or a pasted
 * link. [onPick] gets the image URL and an optional credit line.
 *
 * The pasted link may be to any picture, or to a card's page in the Yoto store: that one brings the card's art
 * and the icon of every track. With [onPickListing] (the card's own picture is being chosen, not one song's)
 * the whole listing is handed over, so the icons can go on the card's songs; without it only the art is used.
 *
 * [startTab] / [startLink] open it on another tab with a link already in (test builds, for screenshots).
 */
@Composable
fun ArtworkPickerDialog(
    initialQuery: String,
    color: Long,
    pixel: Boolean,
    onPick: (url: String, credit: String?) -> Unit,
    onDismiss: () -> Unit,
    onPickListing: ((YotoStore.Listing) -> Unit)? = null,
    /** What the card plays: books are looked for among books, albums among albums. */
    kind: ArtworkSearch.Kind = ArtworkSearch.Kind.ANY,
    startTab: String? = null,
    startLink: String = ""
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var source by remember { mutableStateOf(ArtSource.entries.firstOrNull { it.name == startTab } ?: ArtSource.ICONS) }
    var icons by remember { mutableStateOf<List<YotoIcons.Icon>>(emptyList()) }
    var iconPage by remember { mutableIntStateOf(1) }
    var iconsHaveMore by remember { mutableStateOf(false) }
    var iconTotal by remember { mutableIntStateOf(0) }
    var albums by remember { mutableStateOf<List<ArtworkSearch.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    /** The last search couldn't reach anything: no connection, rather than nothing found. */
    var unreachable by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(startLink) }
    /** The search that is running: a new one takes its place, so an old answer can't land on top of a new one. */
    var searching by remember { mutableStateOf<Job?>(null) }

    fun search(reset: Boolean) {
        val q = query.trim()
        if (q.isBlank() || source == ArtSource.URL) return
        searching?.cancel()
        searching = scope.launch {
            loading = true
            when (source) {
                ArtSource.ICONS -> {
                    val page = YotoIcons.search(q, page = if (reset) 1 else iconPage + 1)
                    unreachable = page.failed
                    icons = if (reset) page.icons else icons + page.icons
                    iconPage = page.page
                    iconsHaveMore = page.hasMore
                    iconTotal = page.total
                }
                ArtSource.ALBUM -> {
                    albums = emptyList()
                    // Each catalogue's covers show as soon as it answers; the last list is the whole answer.
                    ArtworkSearch.search(q, kind).collect { found ->
                        unreachable = found == null
                        albums = found.orEmpty()
                    }
                }
                ArtSource.URL -> Unit
            }
            loading = false
            searched = true
        }
    }

    LaunchedEffect(Unit) { if (query.isNotBlank()) search(reset = true) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.art_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close)) }
                }
                // Where to look: three choices of equal width, like the settings' own.
                Segments(options = ArtSource.entries.map { it to stringResource(it.labelRes) }, selected = source) { s ->
                    if (source != s) {
                        source = s
                        searched = false
                        search(reset = true)
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (source == ArtSource.URL) {
                    LinkTab(
                        link = urlText,
                        onLink = { urlText = it },
                        color = color,
                        pixel = pixel,
                        onPick = onPick,
                        onPickListing = onPickListing,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = {
                            Text(stringResource(if (source == ArtSource.ICONS) R.string.art_search_icons_hint else R.string.art_search_album_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        singleLine = true,
                        // The button that searches sits in the field: no second button squeezing it.
                        trailingIcon = {
                            IconButton(onClick = { search(reset = true) }, enabled = !loading) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.art_find))
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(reset = true) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    val empty = if (source == ArtSource.ICONS) icons.isEmpty() else albums.isEmpty()
                    if (loading && empty) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.art_searching), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (searched && empty) {
                        Text(stringResource(if (unreachable) R.string.art_unreachable else R.string.art_nothing_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    } else if (source == ArtSource.ICONS && iconTotal > 0) {
                        Text(pluralStringResource(R.plurals.art_icon_count, iconTotal, iconTotal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (source == ArtSource.ICONS) 4 else 3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (source) {
                            ArtSource.ICONS -> {
                                items(icons, key = { it.id }) { icon ->
                                    CardArtwork(
                                        artwork = icon.url,
                                        seed = icon.id,
                                        color = color,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clickable { onPick(icon.url, "icon by @${icon.author}, yotoicons.com") },
                                        cornerRadius = 10.dp,
                                        pixel = true
                                    )
                                }
                                if (iconsHaveMore) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        OutlinedButton(
                                            onClick = { search(reset = false) },
                                            enabled = !loading,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text(stringResource(if (loading) R.string.art_loading else R.string.art_more_icons)) }
                                    }
                                }
                            }
                            ArtSource.ALBUM -> {
                                items(albums, key = { it.url }) { r ->
                                    Column(modifier = Modifier.clickable { onPick(r.url, null) }) {
                                        CardArtwork(
                                            artwork = r.thumb,
                                            seed = r.url,
                                            color = color,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f),
                                            cornerRadius = 10.dp,
                                            pixel = pixel
                                        )
                                        Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            r.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            ArtSource.URL -> Unit
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(if (source == ArtSource.ICONS) R.string.art_credit_icons else R.string.art_credit_album),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * "Paste a link": to any picture, or to a card in the Yoto store. A store link is read as soon as it is in:
 * the card's art is shown with the icons of its tracks, and one button takes the lot.
 */
@Composable
private fun LinkTab(
    link: String,
    onLink: (String) -> Unit,
    color: Long,
    pixel: Boolean,
    onPick: (url: String, credit: String?) -> Unit,
    onPickListing: ((YotoStore.Listing) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val address = link.trim()
    val usable = address.startsWith("http://") || address.startsWith("https://")
    val store = usable && YotoStore.isListing(address)
    var listing by remember { mutableStateOf<YotoStore.Listing?>(null) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(address, store) {
        listing = null
        reading = false
        if (store) {
            reading = true
            // A link being typed changes with every letter: wait for it to settle before asking the store.
            delay(400)
            listing = YotoStore.fetch(address)
            reading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = link,
            onValueChange = onLink,
            label = { Text(stringResource(R.string.art_url_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val found = listing
            when {
                !usable -> Text(
                    stringResource(R.string.art_url_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                store && reading -> {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.art_listing_reading), style = MaterialTheme.typography.bodyMedium)
                }
                store && found == null -> Text(stringResource(R.string.art_listing_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                store && found != null -> {
                    // The card as the store shows it: its art is already card-shaped.
                    CardSticker(
                        sticker = found.cover,
                        artwork = null,
                        seed = found.url,
                        color = color,
                        modifier = Modifier.height(230.dp),
                        cornerRadius = 14.dp,
                        pixel = pixel
                    )
                    found.title?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val trackIcons = found.icons
                    if (trackIcons.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pluralStringResource(R.plurals.art_listing_icons, trackIcons.size, trackIcons.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            trackIcons.forEach { icon ->
                                CardArtwork(artwork = icon, seed = icon, color = color, modifier = Modifier.size(36.dp), cornerRadius = 8.dp, pixel = true)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val cover = found.cover
                    if (onPickListing != null) {
                        Button(onClick = { onPickListing(found) }, enabled = cover != null || trackIcons.isNotEmpty()) {
                            Text(stringResource(if (trackIcons.isEmpty()) R.string.art_use_url else R.string.art_use_listing))
                        }
                    } else if (cover != null) {
                        Button(onClick = { onPick(cover, YotoCredit) }) { Text(stringResource(R.string.art_use_url)) }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CardArtwork(
                            artwork = address,
                            seed = address,
                            color = color,
                            modifier = Modifier.size(200.dp),
                            cornerRadius = 20.dp,
                            pixel = pixel
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { onPick(address, null) }) { Text(stringResource(R.string.art_use_url)) }
                }
            }
        }
    }
}

/** The credit line of a picture taken from a card's page in the Yoto store. */
const val YotoCredit = "Yoto"
