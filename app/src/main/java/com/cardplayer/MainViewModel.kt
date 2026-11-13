package com.cardplayer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardplayer.billing.Plus
import com.cardplayer.billing.PlusReason
import com.cardplayer.data.AppSettings
import com.cardplayer.data.Backup
import com.cardplayer.data.BoopFile
import com.cardplayer.data.Card
import com.cardplayer.data.SavedPosition
import com.cardplayer.data.SharedMedia
import com.cardplayer.nfc.CardTag
import com.cardplayer.playback.NowPlaying
import com.cardplayer.link.SharedLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen {
    data object Player : Screen()
    data object Settings : Screen()
    data object Scan : Screen()
    data class Editor(val uid: String) : Screen()
}

/** State of "write this card's data onto the NFC tag". */
sealed class WriteState {
    data object Idle : WriteState()
    data class Waiting(val card: Card) : WriteState()
    data class Done(val card: Card) : WriteState()
    data class Failed(val card: Card, val message: String) : WriteState()
}

/** A line flashed at the bottom of the player; [error] picks the colour. */
data class Notice(val text: String, val error: Boolean = false)

/** Whether cards can be read at all right now. */
enum class NfcState { MISSING, OFF, ON }

/** State of "put this card onto a different NFC tag" (move) or "onto a second tag as well" (copy). */
sealed class MoveState {
    data object Idle : MoveState()
    data class Waiting(val card: Card, val copy: Boolean) : MoveState()
    data class Done(val card: Card, val copy: Boolean) : MoveState()
    data class Failed(val card: Card, val copy: Boolean, val message: String) : MoveState()
}

/** State of "a .boop file someone shared is being opened". */
sealed class ReceiveState {
    data object Idle : ReceiveState()
    data object Unpacking : ReceiveState()
    /** A link shared from another app: fetching its title, picture and songs. */
    data object FetchingLink : ReceiveState()
    data class Failed(val message: String) : ReceiveState()
}

/** A card already in use was tapped while [incoming] waits for a card: replace [existing] with it? */
data class ReplacePrompt(val existing: Card, val incoming: Card)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = BoopBoxApp.get(application)
    val cardRepo get() = app.cards
    val settingsRepo get() = app.settings
    val player get() = app.player

    val cards: StateFlow<List<Card>> = app.cards.cards
    val settings: StateFlow<AppSettings> = app.settings.settings
    val nowPlaying: StateFlow<NowPlaying?> = app.player.nowPlaying
    val busy: StateFlow<Boolean> = app.player.busy

    private val _nfc = MutableStateFlow(NfcState.ON)
    /** Set by MainActivity whenever the phone's NFC adapter changes; the player shows a banner when it is off. */
    val nfc: StateFlow<NfcState> = _nfc.asStateFlow()

    fun setNfcState(state: NfcState) {
        _nfc.value = state
    }

    /** Cards pushed from the computer, waiting for an NFC card (oldest first). */
    val pendingCards: StateFlow<List<Card>> = app.pending.pending

    /** Waiting cards the grown-up said "not now" to, keyed by uid+createdAt so a re-push shows again. */
    private val _snoozedPending = MutableStateFlow<Set<String>>(emptySet())
    val snoozedPending: StateFlow<Set<String>> = _snoozedPending.asStateFlow()

    fun pendingKey(card: Card) = card.uid + "@" + card.createdAt

    fun snoozePending(card: Card) {
        _replacePrompt.value = null
        _snoozedPending.value = _snoozedPending.value + pendingKey(card)
    }

    fun discardPending(card: Card) {
        _replacePrompt.value = null
        app.pending.discard(card.uid)
    }

    /** The waiting card on screen right now: the first one the grown-up hasn't said "not now" to. */
    fun visiblePending(): Card? = pendingCards.value.firstOrNull { pendingKey(it) !in _snoozedPending.value }

    /** True when [card] (a waiting card) came out of a .boop someone shared, not a computer push. */
    fun isSharedPending(card: Card) = app.pending.isShared(card)

    /** True for a waiting card made from a link shared from another app. */
    fun isLinkPending(card: Card) = app.pending.isLink(card)

    private val _replacePrompt = MutableStateFlow<ReplacePrompt?>(null)
    /** "This card already plays X. Put Y on it instead?" while a card is waiting. */
    val replacePrompt: StateFlow<ReplacePrompt?> = _replacePrompt.asStateFlow()

    /** The used card the grown-up chose to keep; it keeps being re-read while it lies on the phone. */
    private var dismissedReplaceUid: String? = null

    /** "Replace": the waiting card goes onto the used card that was tapped, and plays. */
    fun replaceWithPending(prompt: ReplacePrompt) {
        _replacePrompt.value = null
        val card = app.pending.claim(prompt.existing.uid, prompt.incoming.uid) ?: return
        app.positions.clear(card.uid)
        _notices.tryEmit(Notice(app.getString(R.string.msg_card_set_up, card.displayName(app))))
        app.player.playCard(card)
    }

    /** "Keep": leave the used card as it is, and ignore it until a different card is tapped. */
    fun dismissReplacePrompt() {
        dismissedReplaceUid = _replacePrompt.value?.existing?.uid ?: dismissedReplaceUid
        _replacePrompt.value = null
    }

    private val _receive = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    /** A shared .boop being unpacked (or one that couldn't be), shown over everything. */
    val receive: StateFlow<ReceiveState> = _receive.asStateFlow()

    fun dismissReceiveError() {
        _receive.value = ReceiveState.Idle
    }

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)

    /** Everything worth flashing on the player: our own notices, computer pushes, player errors and hints. */
    val notices: Flow<Notice> = merge(
        _notices.asSharedFlow(),
        app.pending.imported.map { Notice(it) },
        app.player.messages,
        Plus.messages.map { Notice(it) }
    )

    // ---- BoopBox Plus

    /** True when the paid extras are on. */
    val plus: StateFlow<Boolean> = Plus.active
    val plusPrice: StateFlow<String?> = Plus.price

    private val _paywall = MutableStateFlow<PlusReason?>(null)
    /** The "BoopBox Plus" sheet, and why it opened. */
    val paywall: StateFlow<PlusReason?> = _paywall.asStateFlow()

    fun showPaywall(reason: PlusReason) {
        _paywall.value = reason
    }

    fun dismissPaywall() {
        dismissedPaywallUid = paywallUid
        _paywall.value = null
    }

    /** The tag whose tap opened the sheet; after "Not now" it stays quiet while it lies on the phone. */
    private var paywallUid: String? = null
    private var dismissedPaywallUid: String? = null

    /**
     * True when the grown-up may set up one more card of their own; otherwise the Plus sheet opens.
     * Playing cards that already exist is never limited.
     */
    private fun allowNewCard(tappedUid: String? = null): Boolean {
        if (Plus.canAddCard(app.cards.cards.value.size)) return true
        if (tappedUid != null && tappedUid == dismissedPaywallUid) return false
        paywallUid = tappedUid
        _paywall.value = PlusReason.CARDS
        return false
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Player)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _unknownCard = MutableStateFlow<String?>(null)
    val unknownCard: StateFlow<String?> = _unknownCard.asStateFlow()

    private val _writeState = MutableStateFlow<WriteState>(WriteState.Idle)
    val writeState: StateFlow<WriteState> = _writeState.asStateFlow()

    private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    val moveState: StateFlow<MoveState> = _moveState.asStateFlow()

    fun startMove(card: Card, copy: Boolean) {
        if (copy && !allowNewCard()) return
        _moveState.value = MoveState.Waiting(card, copy)
    }

    fun endMove() {
        _moveState.value = MoveState.Idle
    }

    /** The next tag tapped while a move / copy is waiting becomes the card's (other) home. */
    private fun landMove(mv: MoveState.Waiting, uid: String): MoveState {
        val card = mv.card
        val other = app.cards.get(uid)
        return when {
            uid == card.uid -> MoveState.Failed(card, mv.copy, app.getString(R.string.msg_move_same_card))
            other != null -> MoveState.Failed(card, mv.copy, app.getString(R.string.msg_move_card_in_use, other.displayName(app)))
            else -> {
                val landed = card.copy(uid = uid, createdAt = if (mv.copy) System.currentTimeMillis() else card.createdAt)
                app.cards.upsert(landed)
                if (!mv.copy) {
                    app.positions.get(card.uid)?.let { app.positions.save(uid, it.mediaId, it.positionMs) }
                    app.positions.clear(card.uid)
                    app.cards.delete(card.uid)
                }
                MoveState.Done(landed, mv.copy)
            }
        }
    }

    /** A known card tapped while on the settings screen: ask whether to edit it. */
    private val _editPrompt = MutableStateFlow<Card?>(null)
    val editPrompt: StateFlow<Card?> = _editPrompt.asStateFlow()

    /**
     * The card the grown-up answered "Not now" to. A card left lying on the phone is re-read by
     * reader mode every few seconds, so without this the question would keep coming back.
     * Forgotten when another card is tapped or the screen changes.
     */
    private var dismissedEditUid: String? = null

    fun dismissEditPrompt() {
        dismissedEditUid = _editPrompt.value?.uid ?: dismissedEditUid
        _editPrompt.value = null
    }

    private fun setScreen(screen: Screen) {
        dismissedEditUid = null
        _screen.value = screen
    }

    /** Card data read off a tag while on the scan screen, waiting for the editor to pick it up. */
    private val prefills = mutableMapOf<String, Card>()

    private var lastTapUid: String? = null
    private var lastTapAt = 0L

    /**
     * @param shared card definition read from the tag itself (see [CardTag]), if it had one.
     */
    fun onCardTapped(uid: String, shared: Card? = null) {
        val now = System.currentTimeMillis()
        if (uid == lastTapUid && now - lastTapAt < 1500) return
        lastTapUid = uid
        lastTapAt = now

        // The editor is waiting for a tag to move / copy a card onto: this tap is that tag.
        (_moveState.value as? MoveState.Waiting)?.let { mv ->
            _moveState.value = landMove(mv, uid)
            return
        }

        when (_screen.value) {
            Screen.Scan -> {
                if (shared != null && app.cards.get(uid) == null) prefills[uid] = shared
                setScreen(Screen.Editor(uid))
                return
            }
            Screen.Settings -> {
                // Grown-up is in settings: a tap means "I want to edit this card", not "play it".
                val known = app.cards.get(uid)
                if (known != null) {
                    // Same card still lying on the phone after "Not now": stay quiet.
                    if (uid == dismissedEditUid) return
                    dismissedEditUid = null
                    _editPrompt.value = known
                } else {
                    if (shared == null && !allowNewCard(uid)) return
                    if (shared != null) prefills[uid] = shared
                    setScreen(Screen.Editor(uid))
                }
                return
            }
            is Screen.Editor -> return
            else -> Unit
        }

        // A different card than the one the grown-up chose to keep: the question may be asked again.
        if (uid != dismissedReplaceUid) dismissedReplaceUid = null
        val local = app.cards.get(uid)
        val waiting = visiblePending()
        when {
            local != null && waiting != null -> {
                // A card already in use, while a card is waiting for one: offer to put the new one on it.
                if (uid != dismissedReplaceUid) _replacePrompt.value = ReplacePrompt(existing = local, incoming = waiting)
            }
            local != null -> {
                _unknownCard.value = null
                app.player.playCard(local)
            }
            shared != null -> {
                // A card someone else wrote: adopt it and play straight away.
                app.cards.upsert(shared)
                _unknownCard.value = null
                _notices.tryEmit(Notice(app.getString(R.string.msg_added_from_card, shared.displayName(app))))
                app.player.playCard(shared)
            }
            else -> {
                // A card pushed from the computer (tools/cardpush.py) is waiting: this card gets it.
                // Shared .boop cards always land; a link shared from another app counts as a card of your own.
                val next = waiting ?: pendingCards.value.firstOrNull()
                if (next != null && !app.pending.isShared(next) && !allowNewCard(uid)) return
                val pushed = app.pending.claim(uid, waiting?.uid)
                if (pushed != null) {
                    _unknownCard.value = null
                    _notices.tryEmit(Notice(app.getString(R.string.msg_card_set_up, pushed.displayName(app))))
                    app.player.playCard(pushed)
                } else {
                    _unknownCard.value = uid
                }
            }
        }
    }

    fun onCardRemoved(uid: String) = app.player.onCardRemoved(uid)

    fun consumePrefill(uid: String): Card? = prefills.remove(uid)

    // ---- links shared from other apps

    /**
     * A link shared from YouTube, Spotify, Audible…: made into a card (title, picture, songs fetched) that waits
     * on the player for a tap, exactly like an opened .boop.
     */
    fun receiveLink(link: String) {
        if (_boopBusy.value) return
        _boopBusy.value = true
        _receive.value = ReceiveState.FetchingLink
        _writeState.value = WriteState.Idle
        _moveState.value = MoveState.Idle
        _editPrompt.value = null
        _replacePrompt.value = null
        _unknownCard.value = null
        setScreen(Screen.Player)
        viewModelScope.launch {
            try {
                app.pending.addLink(SharedLink.toCard(app, link))
            } finally {
                _receive.value = ReceiveState.Idle
                _boopBusy.value = false
            }
        }
    }

    fun noLinkShared() {
        _notices.tryEmit(Notice(app.getString(R.string.msg_no_link_shared), error = true))
    }

    // ---- writing to tags

    fun pendingWriteCard(): Card? = (_writeState.value as? WriteState.Waiting)?.card

    fun startWrite(card: Card) {
        _writeState.value = WriteState.Waiting(card)
    }

    fun onWriteResult(card: Card, result: CardTag.WriteResult) {
        _writeState.value = when (result) {
            CardTag.WriteResult.Ok -> WriteState.Done(card)
            is CardTag.WriteResult.Error -> WriteState.Failed(card, result.message)
        }
    }

    fun endWrite() {
        _writeState.value = WriteState.Idle
    }

    // ---- navigation

    fun dismissUnknownCard() {
        _unknownCard.value = null
    }

    fun openSettings() = setScreen(Screen.Settings)

    fun openScan() {
        if (allowNewCard()) setScreen(Screen.Scan)
    }

    fun openEditor(uid: String) {
        if (app.cards.get(uid) == null && !allowNewCard()) return
        _unknownCard.value = null
        _editPrompt.value = null
        setScreen(Screen.Editor(uid))
    }

    fun goToPlayer() = setScreen(Screen.Player)

    fun back() {
        _writeState.value = WriteState.Idle
        _moveState.value = MoveState.Idle
        setScreen(
            when (_screen.value) {
                Screen.Player -> Screen.Player
                Screen.Settings -> Screen.Player
                Screen.Scan -> Screen.Settings
                is Screen.Editor -> Screen.Settings
            }
        )
    }

    fun saveCard(card: Card) {
        app.cards.upsert(card)
        // A YouTube card starts downloading as soon as it is saved, so it plays offline from its first tap.
        if (app.settings.value.youtubeAutoCache) app.player.youtubeCache.ensureAsync(card)
    }

    fun deleteCard(uid: String) {
        app.cards.delete(uid)
        app.positions.clear(uid)
    }

    fun playNow(card: Card) {
        setScreen(Screen.Player)
        app.player.playCard(card, tapped = false)
    }

    /** The back gesture on the player screen. */
    fun playerBack() = app.player.backToHome()

    /** Plays [card] from a chosen chapter / track (the editor's "play from here"). */
    fun playFrom(card: Card, mediaId: String?, positionMs: Long) {
        setScreen(Screen.Player)
        app.player.playCardFrom(card, mediaId, positionMs)
    }

    /** Where a "continue where it left off" card stopped, if anywhere. */
    fun savedPosition(uid: String): SavedPosition? = app.positions.get(uid)

    /** "Start over": forget where the card stopped. */
    fun clearPosition(uid: String) = app.positions.clear(uid)

    fun updateSettings(transform: (AppSettings) -> AppSettings) = app.settings.update(transform)

    /** The reason an operation failed, in words for a notice. */
    private fun errorText(e: Exception): String = e.message ?: app.getString(R.string.msg_something_went_wrong)

    // ---- .boop files

    private val _boopBusy = MutableStateFlow(false)
    /** True while a .boop is being packed or unpacked (songs can be big). */
    val boopBusy: StateFlow<Boolean> = _boopBusy.asStateFlow()

    /** Packs [card] into a .boop in the cache and hands it to [onReady] (on the main thread). */
    fun exportBoop(card: Card, onReady: (File) -> Unit) {
        if (_boopBusy.value) return
        _boopBusy.value = true
        _notices.tryEmit(Notice(app.getString(R.string.msg_boop_packing, card.displayName(app))))
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "boop-out").apply { deleteRecursively(); mkdirs() }
                    File(dir, BoopFile.fileNameFor(app, card)).also { BoopFile.export(app, card, it) }
                }
                onReady(file)
            } catch (e: Exception) {
                _notices.tryEmit(Notice(app.getString(R.string.msg_boop_make_failed, errorText(e)), error = true))
            } finally {
                _boopBusy.value = false
            }
        }
    }

    /** Copies a .boop already packed by [exportBoop] to a place the grown-up picked. */
    fun saveBoopTo(file: File, target: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IllegalStateException(app.getString(R.string.msg_cant_write_there))
                }
                _notices.tryEmit(Notice(app.getString(R.string.msg_saved_file, file.name)))
            } catch (e: Exception) {
                _notices.tryEmit(Notice(app.getString(R.string.msg_save_failed, errorText(e)), error = true))
            }
        }
    }

    /**
     * A .boop someone shared was opened (from WhatsApp, email, Files… or "Load a .boop" in settings): unpack it
     * and show "hold a card to the phone" on the player, whatever the grown-up was doing.
     */
    fun receiveBoop(uri: Uri) {
        if (_boopBusy.value) return
        _boopBusy.value = true
        _receive.value = ReceiveState.Unpacking
        _writeState.value = WriteState.Idle
        _moveState.value = MoveState.Idle
        _editPrompt.value = null
        _replacePrompt.value = null
        _unknownCard.value = null
        setScreen(Screen.Player)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { BoopFile.load(app, uri, app.pending) }
                _receive.value = ReceiveState.Idle
            } catch (e: Exception) {
                _receive.value = ReceiveState.Failed(errorText(e))
            } finally {
                _boopBusy.value = false
            }
        }
    }

    /**
     * Songs, an audiobook (M4B / M4A…) or videos shared from another app: copied into the app and made into a
     * card that waits on the player for a tap, like a shared link.
     */
    fun receiveMedia(uris: List<Uri>) {
        if (uris.isEmpty() || _boopBusy.value) return
        _boopBusy.value = true
        _receive.value = ReceiveState.Unpacking
        _writeState.value = WriteState.Idle
        _moveState.value = MoveState.Idle
        _editPrompt.value = null
        _replacePrompt.value = null
        _unknownCard.value = null
        setScreen(Screen.Player)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { SharedMedia.load(app, uris, app.pending) }
                _receive.value = ReceiveState.Idle
            } catch (e: Exception) {
                _receive.value = ReceiveState.Failed(errorText(e))
            } finally {
                _boopBusy.value = false
            }
        }
    }

    // ---- whole-phone backup

    /** Writes every card, the settings, positions, Yoto sign-in and the library into [target]. */
    fun exportBackup(target: Uri) {
        if (_boopBusy.value) return
        _boopBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(target)?.use { Backup.export(app, it) }
                        ?: throw IllegalStateException(app.getString(R.string.msg_cant_write_there))
                }
                _notices.tryEmit(Notice(app.getString(R.string.msg_backup_saved)))
            } catch (e: Exception) {
                _notices.tryEmit(Notice(app.getString(R.string.msg_backup_failed, errorText(e)), error = true))
            } finally {
                _boopBusy.value = false
            }
        }
    }

    /** Replaces this phone's cards and settings with a backup's, then reloads everything. */
    fun importBackup(uri: Uri) {
        if (_boopBusy.value) return
        _boopBusy.value = true
        viewModelScope.launch {
            try {
                app.player.stop()
                val count = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { Backup.import(app, it) }
                        ?: throw IllegalStateException(app.getString(R.string.msg_cant_open_file))
                }
                app.cards.reload()
                app.settings.reload()
                app.positions.reload()
                app.player.yoto.reloadTokens()
                _notices.tryEmit(Notice(app.resources.getQuantityString(R.plurals.msg_restored_cards, count, count)))
            } catch (e: Exception) {
                _notices.tryEmit(Notice(app.getString(R.string.msg_restore_failed, errorText(e)), error = true))
            } finally {
                _boopBusy.value = false
            }
        }
    }
}
