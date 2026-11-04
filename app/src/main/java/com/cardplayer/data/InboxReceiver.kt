package com.cardplayer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cardplayer.BoopBoxApp

/**
 * `adb shell am broadcast -n com.boopboxapp/com.cardplayer.data.InboxReceiver` after `tools/cardpush.py` has
 * dropped a card into the inbox. Guarded by the DUMP permission in the manifest, which the adb
 * shell holds and normal apps cannot get, so only a connected computer can poke it.
 */
class InboxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BoopBoxApp.get(context).pending.importInbox()
    }
}
