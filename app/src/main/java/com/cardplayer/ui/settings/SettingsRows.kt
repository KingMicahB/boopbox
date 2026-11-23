package com.cardplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * What every settings section is built from, so they all line up:
 *
 *   SettingsGroup   a small label, then one rounded card of rows with a hairline between them
 *   SettingRow      name on the left · ⓘ · control on the right; a wide control (segments, a slider) goes below
 *
 * The same surface as the tiles on the settings home and the card list.
 */

/**
 * A group of settings that belong together. [title] is left out when the first row already names the group.
 * Rows are separated by a hairline of the screen's background showing through.
 */
@Composable
internal fun SettingsGroup(title: String? = null, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) GroupLabel(title, info)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

/**
 * A group whose inside is free-form (text fields, a list being edited, buttons) rather than rows: the same
 * label and the same card, with room round what is in it. The card editor's "what does it play" is one.
 */
@Composable
internal fun ContentGroup(title: String? = null, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) GroupLabel(title, info)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** The small label over a group, starting where the rows' text starts. */
@Composable
private fun GroupLabel(title: String, info: String?) {
    InfoTitle(
        title = title,
        info = info,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .padding(start = 16.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * One setting. [note] = a short line under the name (a state, or the few words a name can't carry);
 * [info] = the full explanation, behind an ⓘ; [control] sits at the right; [below] takes the full width under the name.
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    info: String? = null,
    onClick: (() -> Unit)? = null,
    /** False = can't be used right now: the name is greyed and a tap does nothing. */
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    control: (@Composable RowScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (info != null) InfoIcon(text = info, title = title)
            if (control != null) {
                Spacer(Modifier.width(4.dp))
                control()
            }
        }
        if (below != null) {
            below()
            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }
}

/** A setting that is on or off; the whole row flips it. */
@Composable
internal fun SwitchSetting(
    title: String,
    checked: Boolean,
    note: String? = null,
    info: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit
) {
    SettingRow(title = title, note = note, info = info, onClick = { onChange(!checked) }, leading = leading) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Two to four choices side by side, each the same width: goes in a row's `below`.
 * Drawn here rather than with Material's segmented buttons, whose wide padding cuts three labels short on a phone.
 */
@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(50)
    val line = MaterialTheme.colorScheme.outline
    // Three or more with a long word among them ("Keep playing", "Follow phone"): a size smaller, so none is cut short.
    val tight = options.size >= 3 && options.any { it.second.length > 9 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(1.dp, line, shape)
    ) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(line)
                )
            }
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = if (tight) 3.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = if (tight) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A slider with stops but without a dot drawn on each: the dots made a row of them look busy. */
@Composable
internal fun SettingSlider(value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int, onValueChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    )
}

/** One value picked from a short list: the value with a little arrow, at the right of a row. */
@Composable
internal fun <T> ValueMenu(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
            Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}
