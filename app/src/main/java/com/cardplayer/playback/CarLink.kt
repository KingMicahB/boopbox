package com.cardplayer.playback

import android.content.Context
import androidx.car.app.connection.CarConnection

/** Whether the phone is connected to a car screen right now (Android Auto, or running in a car itself). */
object CarLink {
    @Volatile
    var connected: Boolean = false
        private set

    /** Call once, on the main thread. */
    fun start(context: Context) {
        try {
            CarConnection(context.applicationContext).type.observeForever { type ->
                connected = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
            }
        } catch (_: Exception) {
            // No car support on this phone: never connected.
        }
    }
}
