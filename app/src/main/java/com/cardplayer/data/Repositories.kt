package com.cardplayer.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

class CardRepository(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "cards.json"),
        ListSerializer(Card.serializer()),
        emptyList()
    )

    val cards: StateFlow<List<Card>> = store.state

    fun get(uid: String): Card? = store.value.firstOrNull { it.uid == uid }

    fun upsert(card: Card) = store.update { list ->
        val others = list.filterNot { it.uid == card.uid }
        (others + card).sortedBy { it.createdAt }
    }

    fun delete(uid: String) = store.update { list -> list.filterNot { it.uid == uid } }

    fun reload() = store.reload()
}

class SettingsRepository(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "settings.json"),
        AppSettings.serializer(),
        AppSettings()
    )

    init {
        // Fresh install: Yoto-style "card off = stop". Existing installs keep whatever they had.
        if (!store.existedAtLoad) store.update { it.copy(cardRemoved = "stop") }
    }

    val settings: StateFlow<AppSettings> = store.state
    val value: AppSettings get() = store.value

    fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

    fun reload() = store.reload()
}

class PositionStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "positions.json"),
        MapSerializer(String.serializer(), SavedPosition.serializer()),
        emptyMap()
    )

    fun get(cardUid: String): SavedPosition? = store.value[cardUid]

    fun save(cardUid: String, mediaId: String, positionMs: Long) = store.update { map ->
        map + (cardUid to SavedPosition(mediaId, positionMs))
    }

    fun clear(cardUid: String) = store.update { map -> map - cardUid }

    fun reload() = store.reload()
}
