package com.cardplayer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cardplayer.NfcState
import com.cardplayer.R
import com.cardplayer.ui.settings.PermissionRow
import com.cardplayer.ui.settings.isXiaomi
import com.cardplayer.ui.settings.openXiaomiPermissionEditor
import com.cardplayer.util.SystemSettings

/**
 * Shown once, on the first launch: every permission BoopBox can use, each with its own button into the
 * right system screen. Rows tick themselves off as the grown-up comes back from each one. Nothing here is
 * required; "Done" (or "Not now") closes it for good, and the same rows stay under Settings → Permissions.
 */
@Composable
fun PermissionSetupScreen(nfc: NfcState, onDone: () -> Unit) {
    val context = LocalContext.current
    // Bumped on every resume, so each row re-checks after a trip into the phone's settings.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // After one "Don't allow" Android may stop showing the prompt; the next tap opens the app's notification settings.
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifAsked = true
        tick++
    }

    val notifs = remember(tick) { postNotificationsGranted(context) }
    val listener = remember(tick) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
    val overlay = remember(tick) { Settings.canDrawOverlays(context) }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            stringResource(R.string.setup_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                if (nfc != NfcState.MISSING) {
                    item {
                        PermissionRow(
                            title = stringResource(R.string.settings_perm_nfc_title),
                            granted = nfc == NfcState.ON,
                            why = stringResource(if (nfc == NfcState.ON) R.string.settings_perm_nfc_on else R.string.settings_perm_nfc_off),
                            action = stringResource(R.string.settings_perm_nfc_action)
                        ) { SystemSettings.openNfc(context) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    item {
                        PermissionRow(
                            title = stringResource(R.string.setup_perm_post_notif_title),
                            granted = notifs,
                            why = stringResource(R.string.setup_perm_post_notif_why),
                            action = stringResource(R.string.setup_perm_post_notif_action)
                        ) {
                            if (notifAsked) openAppNotificationSettings(context)
                            else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                item {
                    PermissionRow(
                        title = stringResource(R.string.settings_perm_notif_title),
                        granted = listener,
                        why = stringResource(R.string.settings_perm_notif_why),
                        action = stringResource(R.string.settings_perm_notif_action)
                    ) { tryStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                item {
                    PermissionRow(
                        title = stringResource(R.string.settings_perm_overlay_title),
                        granted = overlay,
                        why = stringResource(R.string.settings_perm_overlay_needed),
                        action = stringResource(R.string.settings_perm_overlay_action)
                    ) {
                        tryStart(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }
                if (isXiaomi()) {
                    item {
                        PermissionRow(
                            title = stringResource(R.string.settings_perm_xiaomi_title),
                            granted = null,
                            why = stringResource(R.string.settings_perm_xiaomi_why),
                            action = stringResource(R.string.settings_perm_xiaomi_action)
                        ) { openXiaomiPermissionEditor(context) }
                    }
                }
                item {
                    PermissionRow(
                        title = stringResource(R.string.settings_perm_battery_title),
                        granted = battery,
                        why = stringResource(R.string.settings_perm_battery_why),
                        action = stringResource(R.string.settings_perm_battery_action)
                    ) {
                        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            stringResource(R.string.setup_later_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onDone, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                            Text(stringResource(R.string.common_done))
                        }
                        TextButton(onClick = onDone) { Text(stringResource(R.string.common_not_now)) }
                    }
                }
            }
        }
    }
}

private fun postNotificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openAppNotificationSettings(context: Context) {
    tryStart(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
}

/** Opens the first of [intents] the phone has a screen for. */
private fun tryStart(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}
