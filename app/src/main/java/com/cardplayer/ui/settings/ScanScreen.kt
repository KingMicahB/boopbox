@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardplayer.NfcState
import com.cardplayer.R
import com.cardplayer.util.SystemSettings

/** "Hold a card to the phone", or, when NFC is off or missing, why nothing will happen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(nfc: NfcState, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (nfc == NfcState.ON) {
                Text("📶", fontSize = 80.sp)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.scan_hold_card),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.scan_existing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                CircularProgressIndicator()
            } else {
                Text("⚠️", fontSize = 80.sp)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (nfc == NfcState.MISSING) R.string.settings_perm_nfc_missing else R.string.scan_nfc_off),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                if (nfc == NfcState.OFF) {
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { SystemSettings.openNfc(context) }) { Text(stringResource(R.string.settings_perm_nfc_action)) }
                }
            }
        }
    }
}
