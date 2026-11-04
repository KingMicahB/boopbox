package com.cardplayer.data

import android.content.Context
import android.net.Uri
import com.cardplayer.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Cards built on a computer and pushed over adb by `tools/cardpush.py`.
 *
 * The tool copies the audio and pictures into `files/library/<id>/` (via `run-as`, so they are
 * owned by the app) and then drops `files/inbox/<id>.json`. [importInbox] picks those up:
 *  - a card that is already mapped to this library id is updated in place,
 *  - a push aimed at a specific card (`targetUid`) replaces that card,
 *  - anything else waits in a queue and is given to the next unknown card that is tapped.
 *
 * Paths inside the pushed card use `lib:<id>/<file>`, resolved here to `file://` uris.
 */
@Serializable
data class InboxCard(
    val id: String,
    val card: Card,
    /** Put this straight onto the card with this NFC uid instead of waiting for a tap. */
    val targetUid: String? = null,
    /** [BoopFile.ORIGIN] when it came out of a .boop someone shared; null = pushed from the computer. */
    val origin: String? = null
)

class PendingCardRepository(private val context: Context, private val cards: CardRepository) {
    private val store = JsonStore(
        File(context.filesDir, "pending.json"),
        ListSerializer(Card.serializer()),
        emptyList()
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** Cards waiting for a tap, oldest first. */
    val pending: StateFlow<List<Card>> = store.state

    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Human-readable notes about pushes that just arrived. */
    val imported: SharedFlow<String> = _imported.asSharedFlow()

    private val libraryDir get() = File(context.filesDir, "library")
    private val inboxDir get() = File(context.filesDir, "inbox")

    /**
     * Hands the waiting card [which] (the one on screen; the oldest when null) to the NFC card [uid],
     * or null when nothing is waiting. [uid] may belong to a card already in use: it is replaced.
     */
    @Synchronized
    fun claim(uid: String, which: String? = null): Card? {
        val next = (if (which != null) store.value.firstOrNull { it.uid == which } else store.value.firstOrNull()) ?: return null
        store.update { list -> list.filterNot { it.uid == next.uid } }
        val card = next.copy(uid = uid, createdAt = System.currentTimeMillis())
        cards.upsert(card)
        return card
    }

    /** True for a waiting card that came out of a .boop someone shared (rather than a computer push). */
    fun isShared(card: Card) = card.uid.startsWith(SHARED_PREFIX)

    /** True for a waiting card made from a link shared from another app (YouTube, Spotify, Audible…). */
    fun isLink(card: Card) = card.uid.startsWith(LINK_PREFIX)

    /** A card made from a shared link: it goes to the front of the queue, like a .boop opened on purpose. */
    fun addLink(card: Card) {
        val now = System.currentTimeMillis()
        val waiting = card.copy(uid = LINK_PREFIX + now, createdAt = now)
        store.update { list -> listOf(waiting) + list }
        _imported.tryEmit(context.getString(R.string.msg_card_ready, card.displayName(context)))
    }

    /** Drops a waiting card without giving it to anyone. Its audio stays in the library until pushed again. */
    fun discard(uid: String) = store.update { list -> list.filterNot { it.uid == uid } }

    @Synchronized
    fun importInbox() {
        val files = inboxDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (file in files.sortedBy { it.lastModified() }) {
            val push = try {
                json.decodeFromString(InboxCard.serializer(), file.readText())
            } catch (_: Exception) {
                file.renameTo(File(file.path + ".bad"))
                continue
            }
            file.delete()
            val card = resolve(push.card, push.id)
            val marker = "/library/${push.id}/"
            val mapped = push.targetUid?.let { cards.get(it) }
                ?: cards.cards.value.firstOrNull { c -> c.libraryMarkerMatches(marker) }
            // Landing on a real card: an earlier push of the same id waiting for a tap is now stale.
            if (mapped != null || push.targetUid != null) {
                store.update { list -> list.filterNot { it.isWaitingFor(push.id) } }
            }
            when {
                mapped != null -> {
                    cards.upsert(card.copy(uid = mapped.uid, createdAt = mapped.createdAt))
                    _imported.tryEmit(context.getString(R.string.msg_updated_card, card.displayName(context)))
                }
                push.targetUid != null -> {
                    cards.upsert(card.copy(uid = push.targetUid))
                    _imported.tryEmit(context.getString(R.string.msg_added_card, card.displayName(context)))
                }
                else -> {
                    // A fresh createdAt, so a card the grown-up said "not now" to shows again when it is sent again.
                    val waiting = card.copy(uid = waitingUid(push), createdAt = System.currentTimeMillis())
                    store.update { list ->
                        val rest = list.filterNot { it.isWaitingFor(push.id) }
                        // A .boop was opened on purpose just now: it goes to the front of the queue.
                        if (push.origin == BoopFile.ORIGIN) listOf(waiting) + rest else rest + waiting
                    }
                    _imported.tryEmit(context.getString(R.string.msg_card_ready, card.displayName(context)))
                }
            }
        }
    }

    private fun Card.libraryMarkerMatches(marker: String): Boolean =
        uri.contains(marker) || artworkUrl?.contains(marker) == true || playlist.any { it.uri.contains(marker) }

    /** Waiting cards are keyed by where they came from: `pending:<id>` (computer), `shared:<id>` (.boop) or `link:<time>`. */
    private fun waitingUid(push: InboxCard) = (if (push.origin == BoopFile.ORIGIN) SHARED_PREFIX else PENDING_PREFIX) + push.id

    private fun Card.isWaitingFor(id: String) = uid == PENDING_PREFIX + id || uid == SHARED_PREFIX + id

    private companion object {
        const val PENDING_PREFIX = "pending:"
        const val SHARED_PREFIX = "shared:"
        const val LINK_PREFIX = "link:"
    }

    private fun resolve(card: Card, id: String): Card {
        fun r(s: String): String =
            if (s.startsWith("lib:")) Uri.fromFile(File(libraryDir, s.removePrefix("lib:"))).toString() else s
        return card.copy(
            uri = r(card.uri.ifBlank { "lib:$id/" }),
            artworkUrl = card.artworkUrl?.let(::r),
            playlist = card.playlist.map { it.copy(uri = r(it.uri)) },
            trackArt = card.trackArt.entries.associate { (k, v) -> r(k) to r(v) }
        )
    }
}
