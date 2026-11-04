package com.cardplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardplayer.R
import com.cardplayer.data.Card
import com.cardplayer.link.YouTubeAudioCache

/**
 * Whether a YouTube / YT Music card's audio is on the phone: a tick when it is (the card plays in BoopBox),
 * a percentage and bar while it downloads, otherwise a note that the card opens the app for now.
 * [onDownload] adds a "Download now" button while it is missing. Draws nothing for other cards.
 */
@Composable
fun YouTubeCacheStatus(
    cache: YouTubeAudioCache,
    card: Card,
    autoCache: Boolean,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null
) {
    if (!cache.handles(card)) return
    val version by cache.version.collectAsStateWithLifecycle()
    val downloading by cache.downloading.collectAsStateWithLifecycle()
    val progress = downloading[card.uri]
    val saved = remember(version, card.uri) { cache.localTracks(card) != null }
    val appName = stringResource(card.sourceType.labelRes)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                saved -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                progress != null -> CircularProgressIndicator(progress = { progress }, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                else -> Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    saved -> stringResource(R.string.yt_cache_saved)
                    progress != null -> stringResource(R.string.yt_cache_downloading, (progress * 100).toInt())
                    autoCache -> stringResource(R.string.yt_cache_missing, appName)
                    else -> stringResource(R.string.yt_cache_off, appName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (saved || progress != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            if (onDownload != null && !saved && progress == null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.yt_cache_download_now)) }
            }
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
