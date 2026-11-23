package com.cardplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cardplayer.R

/**
 * The four kinds of card the editor asks about first. What exactly a card is inside its kind (a Spotify link or a
 * podcast feed, one file or a folder) the editor works out from what was pasted or picked.
 */
internal enum class CardKind(@param:StringRes val labelRes: Int, @param:StringRes val hintRes: Int) {
    LINK(R.string.editor_kind_link, R.string.editor_kind_link_hint),
    PHONE(R.string.editor_kind_phone, R.string.editor_kind_phone_hint),
    RECORD(R.string.editor_kind_record, R.string.editor_cat_recording_hint),
    YOTO(R.string.editor_kind_yoto, R.string.editor_cat_yoto_hint);

    val icon: ImageVector
        get() = when (this) {
            LINK -> CardKindIcons.Link
            PHONE -> CardKindIcons.Phone
            RECORD -> CardKindIcons.Mic
            YOTO -> CardKindIcons.Card
        }
}

/** The tiles' pictures: Material's own shapes, drawn here because the app ships only the small built-in icon set. */
internal object CardKindIcons {
    private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            )
            .build()

    val Link: ImageVector by lazy {
        icon(
            "Link",
            "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z"
        )
    }

    /** A phone with a music note on its screen. */
    val Phone: ImageVector by lazy {
        icon(
            "Phone",
            "M17,1H7C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3C19,1.9 18.1,1 17,1z" +
                "M17,19H7V5h10V19z" +
                "M12,7v5.55C11.7,12.37 11.37,12.25 11,12.25c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2V9.5h2V7H12z",
            evenOdd = true
        )
    }

    val Mic: ImageVector by lazy {
        icon(
            "Mic",
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
        )
    }

    /** A card with a dot in it: a Yoto card, without anyone's logo. */
    val Card: ImageVector by lazy {
        icon(
            "Card",
            "M7,2h10c1.1,0 2,0.9 2,2v16c0,1.1 -0.9,2 -2,2H7c-1.1,0 -2,-0.9 -2,-2V4C5,2.9 5.9,2 7,2z" +
                "M12,8.5c-1.93,0 -3.5,1.57 -3.5,3.5s1.57,3.5 3.5,3.5s3.5,-1.57 3.5,-3.5S13.93,8.5 12,8.5z",
            evenOdd = true
        )
    }
}

/**
 * "On this phone": one way in for files, songs, folders and downloaded books. One file is a file card (an .m4b a
 * book), several files a playlist, a folder a folder card. [playsAs] names what was picked once something was.
 */
@Composable
internal fun PhonePickers(onFiles: () -> Unit, onFolder: () -> Unit, playsAs: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onFiles, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_phone_choose_files), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onFolder, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_phone_choose_folder), textAlign = TextAlign.Center)
        }
    }
    if (playsAs != null) {
        Text(
            stringResource(R.string.editor_plays_as_value, playsAs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(stringResource(R.string.editor_phone_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Plays as": what the pasted link was taken for, with the other link types a tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> LinkTypeChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Text(stringResource(R.string.editor_link_plays_as), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

/** Four tiles side by side, one per [CardKind]: the first thing "Add a card" asks. */
@Composable
internal fun CardKindTiles(selected: CardKind, onPick: (CardKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CardKind.entries.forEach { kind ->
            val on = kind == selected
            Surface(
                onClick = { onPick(kind) },
                shape = RoundedCornerShape(16.dp),
                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 84.dp)
                    .semantics {
                        role = Role.Tab
                        this.selected = on
                    }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                ) {
                    Icon(kind.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(
                        stringResource(kind.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
