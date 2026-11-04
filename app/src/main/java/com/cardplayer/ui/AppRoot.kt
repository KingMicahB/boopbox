package com.cardplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cardplayer.util.FirstRun
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardplayer.MainViewModel
import com.cardplayer.Screen
import com.cardplayer.ui.settings.CardEditorScreen
import com.cardplayer.ui.settings.ScanScreen
import com.cardplayer.ui.settings.SettingsScreen

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    setImmersive: (Boolean) -> Unit,
    setKeepScreenOn: (Boolean) -> Unit,
    setChildLock: (Boolean) -> Unit
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPlayer = screen == Screen.Player
    val pending by viewModel.pendingCards.collectAsStateWithLifecycle()
    val snoozed by viewModel.snoozedPending.collectAsStateWithLifecycle()
    val receive by viewModel.receive.collectAsStateWithLifecycle()
    val replacePrompt by viewModel.replacePrompt.collectAsStateWithLifecycle()
    val nfc by viewModel.nfc.collectAsStateWithLifecycle()

    LaunchedEffect(isPlayer) { setImmersive(isPlayer) }
    LaunchedEffect(isPlayer, settings.keepScreenOn) { setKeepScreenOn(isPlayer && settings.keepScreenOn) }
    // Child lock: pin the app whenever the toddler screen is showing and the lock is on.
    // Turning the setting off unpins; the grown-up screens stay pinned (the gear still works).
    LaunchedEffect(isPlayer, settings.childLock) {
        if (settings.childLock) {
            if (isPlayer) setChildLock(true)
        } else {
            setChildLock(false)
        }
    }

    BackHandler(enabled = !isPlayer) { viewModel.back() }
    val saveable = rememberSaveableStateHolder()
    // Back on the player: the next trip into settings starts at its home page again.
    LaunchedEffect(isPlayer) { if (isPlayer) saveable.removeState("settings") }

    // First launch: walk through every permission before anything else. Cards shared meanwhile wait behind it.
    val context = LocalContext.current
    var setupDone by rememberSaveable { mutableStateOf(FirstRun.permissionsDone(context)) }
    if (!setupDone) {
        PermissionSetupScreen(nfc = nfc) {
            FirstRun.markPermissionsDone(context)
            setupDone = true
        }
        return
    }

    when (val s = screen) {
        Screen.Player -> Box {
            PlayerScreen(viewModel)
            // A card pushed from the computer or shared as a .boop is waiting: its own screen, on top of the player.
            val waiting = pending.filterNot { viewModel.pendingKey(it) in snoozed }
            waiting.firstOrNull()?.let { card ->
                PendingCardScreen(
                    card = card,
                    position = pending.indexOf(card) + 1,
                    total = pending.size,
                    shared = viewModel.isSharedPending(card),
                    fromLink = viewModel.isLinkPending(card),
                    pixelArt = settings.pixelArt,
                    replace = replacePrompt,
                    onReplace = { viewModel.replaceWithPending(it) },
                    onKeep = { viewModel.dismissReplacePrompt() },
                    onNotNow = { viewModel.snoozePending(card) },
                    onRemove = { viewModel.discardPending(card) }
                )
            }
            // A shared .boop being unpacked (or one that couldn't be opened): on top of everything.
            ReceivingOverlay(receive, onDismiss = { viewModel.dismissReceiveError() })
        }
        // Held across screens so coming back from the editor lands in the same section (and scroll spot), not settings home.
        Screen.Settings -> saveable.SaveableStateProvider("settings") { SettingsScreen(viewModel) }
        Screen.Scan -> ScanScreen(nfc = nfc, onBack = viewModel::back)
        // Keyed so editing a different card (e.g. after a move) starts the editor's state afresh.
        is Screen.Editor -> key(s.uid) { CardEditorScreen(viewModel, s.uid) }
    }

    // "BoopBox Plus": opened by the card limit or a Plus setting, over whichever screen is showing.
    val paywall by viewModel.paywall.collectAsStateWithLifecycle()
    val plusPrice by viewModel.plusPrice.collectAsStateWithLifecycle()
    paywall?.let { PlusDialog(reason = it, price = plusPrice, onDismiss = { viewModel.dismissPaywall() }) }
}
