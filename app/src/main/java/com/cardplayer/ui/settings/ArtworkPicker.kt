@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardplayer.R
import com.cardplayer.link.ArtworkSearch
import com.cardplayer.link.YotoIcons
import com.cardplayer.ui.CardArtwork
import kotlinx.coroutines.launch

private enum class ArtSource(@param:StringRes val labelRes: Int) {
    ICONS(R.string.art_source_icons),
    ALBUM(R.string.art_source_album),
    URL(R.string.art_source_url)
}

/**
 * Full-height picker: pixel icons from yotoicons.com (default), album / podcast art via iTunes,
 * or a pasted link to any picture. [onPick] gets the image URL and an optional credit line.
 */
@Composable
fun ArtworkPickerDialog(
    initialQuery: String,
    color: Long,
    pixel: Boolean,
    onPick: (url: String, credit: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var source by remember { mutableStateOf(ArtSource.ICONS) }
    var icons by remember { mutableStateOf<List<YotoIcons.Icon>>(emptyList()) }
    var iconPage by remember { mutableIntStateOf(1) }
    var iconsHaveMore by remember { mutableStateOf(false) }
    var iconTotal by remember { mutableIntStateOf(0) }
    var albums by remember { mutableStateOf<List<ArtworkSearch.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    fun search(reset: Boolean) {
        val q = query.trim()
        if (q.isBlank() || source == ArtSource.URL) return
        scope.launch {
            loading = true
            when (source) {
                ArtSource.ICONS -> {
                    val page = YotoIcons.search(q, page = if (reset) 1 else iconPage + 1)
                    icons = if (reset) page.icons else icons + page.icons
                    iconPage = page.page
                    iconsHaveMore = page.hasMore
                    iconTotal = page.total
                }
                ArtSource.ALBUM -> albums = ArtworkSearch.search(q)
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
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.art_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtSource.entries.forEach { s ->
                        FilterChip(
                            selected = source == s,
                            onClick = {
                                if (source != s) {
                                    source = s
                                    searched = false
                                    search(reset = true)
                                }
                            },
                            label = { Text(stringResource(s.labelRes)) }
                        )
                    }
                }
                if (source == ArtSource.URL) {
                    // A link to any picture: typed or pasted, previewed, then used as is.
                    val link = urlText.trim()
                    val usable = link.startsWith("http://") || link.startsWith("https://")
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text(stringResource(R.string.art_url_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (usable) {
                            CardArtwork(
                                artwork = link,
                                seed = link,
                                color = color,
                                modifier = Modifier.size(180.dp),
                                cornerRadius = 20.dp,
                                pixel = pixel
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { onPick(link, null) }) { Text(stringResource(R.string.art_use_url)) }
                        }
                    }
                } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = {
                            Text(stringResource(if (source == ArtSource.ICONS) R.string.art_search_icons_hint else R.string.art_search_album_hint))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(reset = true) }),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { search(reset = true) }, enabled = !loading) { Text(stringResource(R.string.art_find)) }
                }
                Spacer(Modifier.height(8.dp))

                val empty = if (source == ArtSource.ICONS) icons.isEmpty() else albums.isEmpty()
                if (loading && empty) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.art_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (searched && empty) {
                    Text(stringResource(R.string.art_nothing_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else if (source == ArtSource.ICONS && iconTotal > 0) {
                    Text(pluralStringResource(R.plurals.art_icon_count, iconTotal, iconTotal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (source) {
                        ArtSource.ICONS -> {
                            items(icons, key = { it.id }) { icon ->
                                Column(
                                    modifier = Modifier
                                        .clickable { onPick(icon.url, "icon by @${icon.author}, yotoicons.com") }
                                        .padding(2.dp)
                                ) {
                                    CardArtwork(
                                        artwork = icon.url,
                                        seed = icon.id,
                                        color = color,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f),
                                        cornerRadius = 10.dp,
                                        pixel = true
                                    )
                                    Text(
                                        "@" + icon.author,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (iconsHaveMore) {
                                item(span = { GridItemSpan(4) }) {
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
                                Column(
                                    modifier = Modifier
                                        .clickable { onPick(r.url, null) }
                                        .padding(2.dp)
                                ) {
                                    CardArtwork(
                                        artwork = r.url,
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
                                        style = MaterialTheme.typography.bodyMedium,
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
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            when (source) {
                                ArtSource.ICONS -> R.string.art_credit_icons
                                ArtSource.ALBUM -> R.string.art_credit_album
                                ArtSource.URL -> R.string.art_url_note
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}
