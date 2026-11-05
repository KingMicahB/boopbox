package com.cardplayer

import android.Manifest
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Rational
import android.view.KeyEvent
import com.cardplayer.playback.PlayerController
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import com.cardplayer.nfc.CardTag
import kotlin.concurrent.thread
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cardplayer.nfc.NfcReader
import com.cardplayer.ui.AppRoot
import com.cardplayer.ui.theme.BoopBoxTheme
import com.cardplayer.util.FirstRun

class MainActivity : ComponentActivity(), PlayerController.ActivityHooks {

    private val viewModel: MainViewModel by viewModels()
    private var nfcReader: NfcReader? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        BoopBoxApp.get(this).player.mainTaskId = taskId
        BoopBoxApp.get(this).player.hooks = this

        nfcReader = NfcReader(
            activity = this,
            onTag = { uid, tag -> handleTag(uid, tag, ndefFromIntent = null) },
            onTagRemoved = { uid -> runOnUiThread { viewModel.onCardRemoved(uid) } },
            wantRemovalEvents = { uid ->
                val card = viewModel.cardRepo.get(uid)
                val s = viewModel.settings.value
                (card?.removedMode(s) ?: s.cardRemovedOverride ?: s.cardRemovedMode) != "nothing"
            }
        )

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            BoopBoxTheme(themeMode = settings.themeMode) {
                AppRoot(
                    viewModel = viewModel,
                    setImmersive = ::applyImmersive,
                    setKeepScreenOn = ::setKeepScreenOn,
                    setChildLock = ::setChildLock
                )
            }
        }

        handleNfcIntent(intent)
        // Only on a real launch: a recreated activity would unpack the same .boop a second time.
        if (savedInstanceState == null) handleBoopIntent(intent)
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
        handleBoopIntent(intent)
    }

    /** NFC switched on or off while we are on screen (quick-settings tile): keep the banner honest. */
    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publishNfcState()
    }

    private fun publishNfcState() {
        val reader = nfcReader
        viewModel.setNfcState(
            when {
                reader == null || !reader.isAvailable -> NfcState.MISSING
                !reader.isEnabled -> NfcState.OFF
                else -> NfcState.ON
            }
        )
    }

    /**
     * The phone's volume keys obey the volume limit too. On the toddler screen they are always ours
     * (it draws its own volume bars, and the system slider must not pop up over it); on the grown-up
     * screens only while a limit is set.
     */
    private fun ownsVolumeKeys(): Boolean =
        viewModel.screen.value == Screen.Player || BoopBoxApp.get(this).player.volumeCapActive()

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && ownsVolumeKeys()) {
            val player = BoopBoxApp.get(this).player
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) player.volumeUp() else player.volumeDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the release too, or the system volume panel still shows for it.
        if (isVolumeKey(keyCode) && ownsVolumeKeys()) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        nfcReader?.enable()
        publishNfcState()
        ContextCompat.registerReceiver(
            this,
            nfcStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        BoopBoxApp.get(this).player.applyVolumeCap()
        thread { BoopBoxApp.get(this).pending.importInbox() }
        // Keep podcast cards current: re-fetch feeds and download the newest episodes (throttled).
        BoopBoxApp.get(this).let {
            it.player.podcastCache.refreshAllAsync(it.cards.cards.value, it.settings.value.podcastAutoDownload)
            it.player.refreshYotoAsync(it.cards.cards.value)
            it.player.youtubeCache.refreshAllAsync(it.cards.cards.value, it.settings.value.youtubeAutoCache)
        }
    }

    override fun onPause() {
        nfcReader?.disable()
        try {
            unregisterReceiver(nfcStateReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        val player = BoopBoxApp.get(this).player
        if (player.hooks === this) player.hooks = null
        super.onDestroy()
    }

    /**
     * Child lock = Android screen pinning. Home, Back and Recents stop working until unpinned.
     * Android asks the user to confirm the first pin; unpinning from inside the app is silent.
     */
    override fun setChildLock(on: Boolean) {
        try {
            val am = getSystemService(ActivityManager::class.java)
            val pinned = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            if (on && !pinned) startLockTask()
            if (!on && pinned) stopLockTask()
        } catch (_: Exception) {
        }
    }

    override fun isChildLocked(): Boolean = try {
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (_: Exception) {
        false
    }

    /** Shrink to a PiP bubble so Android still counts us as visible while another app opens. */
    override fun enterPip(): Boolean = try {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .build()
        enterPictureInPictureMode(params)
    } catch (_: Exception) {
        false
    }

    /** A card tapped while the app was closed launches us with the tag in the intent. */
    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) return
        @Suppress("DEPRECATION")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val uid = tag?.let { NfcReader.uidOf(it) } ?: return
        @Suppress("DEPRECATION")
        val ndef: NdefMessage? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.firstOrNull()
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.firstOrNull() as? NdefMessage
        }
        thread {
            handleTag(uid, tag, ndefFromIntent = ndef)
            nfcReader?.watch(uid, tag)
        }
    }

    /**
     * Runs on a background thread (tag I/O is slow). Either writes a pending card onto the tag,
     * or reads any shared card data off it, then hands the tap to the view model.
     */
    private fun handleTag(uid: String, tag: Tag, ndefFromIntent: NdefMessage?) {
        val pending = viewModel.pendingWriteCard()
        if (pending != null) {
            val result = CardTag.write(this, tag, pending)
            runOnUiThread { viewModel.onWriteResult(pending, result) }
            return
        }
        // Only bother reading the tag's data when we don't already know this card,
        // or when the grown-up is on the "add a card" screen.
        val isNew = viewModel.cardRepo.get(uid) == null
        val wantData = isNew || viewModel.screen.value == Screen.Scan
        val message = if (!wantData) null else (ndefFromIntent ?: CardTag.readMessage(tag))
        // A BoopBox-shared card, or a real Yoto card (plays from the yoto.io link on its tag).
        val shared = CardTag.decode(message, uid) ?: CardTag.yotoCard(message, uid)
        runOnUiThread { viewModel.onCardTapped(uid, shared) }
        // Self-healing, after the tap is handed over so playback doesn't wait on it: a blank card gets the BoopBox
        // launch link, and a card whose link names an old package (or none) is fixed, so a tap opens the app from anywhere.
        CardTag.heal(tag, if (wantData) message else (ndefFromIntent ?: CardTag.readMessage(tag)))
    }

    /**
     * A .boop file opened from another app (WhatsApp, email, Files, a link…) or shared to BoopBox:
     * unpack it and ask for a card. The `boopbox://` link from the Yoto sign-in page is not one.
     */
    private fun handleBoopIntent(intent: Intent?) {
        // A link shared as text (YouTube, Spotify, Audible…). The share text often wraps it in a sentence.
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("""(https?://|spotify:)\S+""").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
            when {
                link == null -> viewModel.noLinkShared()
                Uri.parse(link).path?.endsWith(".boop") == true -> viewModel.receiveBoop(Uri.parse(link))
                else -> viewModel.receiveLink(link)
            }
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme in setOf("content", "file", "http", "https") }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) viewModel.receiveBoop(uri)
    }

    private fun requestNotificationPermissionIfNeeded() {
        // On the first launch the permission setup screen asks for it, along with everything else.
        if (!FirstRun.permissionsDone(this)) return
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyImmersive(immersive: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
