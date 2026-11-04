package com.cardplayer

import android.app.Application
import android.content.Context
import com.cardplayer.billing.Plus
import com.cardplayer.data.CardRepository
import com.cardplayer.data.PendingCardRepository
import com.cardplayer.data.PositionStore
import com.cardplayer.data.SettingsRepository
import com.cardplayer.playback.PlayerController

class BoopBoxApp : Application() {
    lateinit var cards: CardRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var positions: PositionStore
        private set
    lateinit var player: PlayerController
        private set
    lateinit var pending: PendingCardRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Plus.init(this)
        cards = CardRepository(this)
        settings = SettingsRepository(this)
        positions = PositionStore(this)
        pending = PendingCardRepository(this, cards)
        player = PlayerController(this, cards, settings, positions)
    }

    companion object {
        fun get(context: Context): BoopBoxApp = context.applicationContext as BoopBoxApp
    }
}
