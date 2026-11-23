package com.cardplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.cardplayer.R

/**
 * The settings and the card editor say what a thing is in its title, and keep the how-and-why out of the way:
 * a small ⓘ beside the title opens it. So a screen is a list of short names, not paragraphs.
 *
 * [text] is the explanation; [title] (the setting's own name) heads the pop-up.
 */
@Composable
internal fun InfoIcon(text: String, title: String? = null, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = stringResource(R.string.common_more_info),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
    if (open) InfoDialog(title = title, text = text) { open = false }
}

/**
 * For an explanation with no title of its own to sit beside (a note under a button, say): one small
 * "More info" line that opens it.
 */
@Composable
internal fun InfoLink(text: String, title: String? = null) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.common_more_info),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (open) InfoDialog(title = title, text = text) { open = false }
}

@Composable
private fun InfoDialog(title: String?, text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_ok)) } }
    )
}

/** A title with its explanation ([info], when there is one) behind an ⓘ right after it. */
@Composable
internal fun InfoTitle(
    title: String,
    info: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = style, color = color, modifier = Modifier.weight(1f, fill = false))
        if (info != null) InfoIcon(text = info, title = title)
    }
}
