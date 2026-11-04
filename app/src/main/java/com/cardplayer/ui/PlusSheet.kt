package com.cardplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardplayer.R
import com.cardplayer.billing.Plus
import com.cardplayer.billing.PlusReason

/** "BoopBox Plus": why it opened, what Plus adds, and the buy / restore buttons. */
@Composable
fun PlusDialog(reason: PlusReason, price: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⭐ " + stringResource(R.string.plus_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(reason.textRes, Plus.FREE_CARD_LIMIT), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.plus_includes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                listOf(
                    R.string.plus_feature_cards,
                    R.string.plus_feature_split,
                    R.string.plus_feature_bedtime,
                    R.string.plus_feature_night_light,
                    R.string.plus_feature_card_extras
                ).forEach { Text("✓  " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(R.string.plus_once),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { Plus.refresh() }) { Text(stringResource(R.string.plus_restore)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                context.findActivity()?.let { Plus.buy(it) }
                onDismiss()
            }) { Text(if (price != null) stringResource(R.string.plus_buy, price) else stringResource(R.string.plus_buy_no_price)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_not_now)) } }
    )
}

/** A little "Plus" tag next to a setting that needs it. */
@Composable
fun PlusBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            "⭐ " + stringResource(R.string.plus_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Stands in for a Plus setting on a free install: its name, the badge, and a tap opens the Plus sheet. */
@Composable
fun PlusLockedRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.plus_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PlusBadge(Modifier.padding(start = 12.dp))
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
