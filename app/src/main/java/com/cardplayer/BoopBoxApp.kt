package com.cardplayer

import android.app.Application
import android.content.Context
import com.cardplayer.billing.Plus
import com.cardplayer.data.CardRepository
import com.cardplayer.data.PendingCardRepository
import com.cardplayer.data.PositionStore
import com.cardplayer.data.SettingsRepository
import com.cardplayer.playback.CarLink
import com.cardplayer.playback.PlayerController

class BoopBoxApp : Application() {
    // Made on first use, by whoever asks first: the car asks CardArtProvider for a picture on another thread, and
    // when that is what starts the app (it was closed, or the phone killed it) the question can arrive before
    // onCreate has run. Stores made in onCreate were not there yet, and that first picture failed.
    val cards: CardRepository by lazy { CardRepository(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val positions: PositionStore by lazy { PositionStore(this) }
    val pending: PendingCardRepository by lazy { PendingCardRepository(this, cards) }
    lateinit var player: PlayerController
        private set

    override fun onCreate() {
        super.onCreate()
        Plus.init(this)
        player = PlayerController(this, cards, settings, positions)
        CarLink.start(this)
    }

    companion object {
        fun get(context: Context): BoopBoxApp = context.applicationContext as BoopBoxApp
    }
}
