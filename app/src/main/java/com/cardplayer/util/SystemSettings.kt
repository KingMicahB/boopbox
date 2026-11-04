package com.cardplayer.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Shortcuts into the phone's own settings screens. */
object SystemSettings {
    /** The NFC toggle: a quick panel on Android 10+, the full NFC settings page otherwise. */
    fun openNfc(context: Context) {
        val attempts = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(Intent(Settings.Panel.ACTION_NFC))
            add(Intent(Settings.ACTION_NFC_SETTINGS))
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }
}
