package com.cardplayer.util

import android.content.Context

/**
 * Whether the first-launch permission setup has been finished (or skipped). Kept in shared prefs, not in
 * AppSettings, so a backup restored onto a new phone still walks through that phone's permissions.
 */
object FirstRun {
    private const val PREFS = "first_run"
    private const val KEY_PERMISSIONS = "permissions_done"

    fun permissionsDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PERMISSIONS, false)

    fun markPermissionsDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PERMISSIONS, true).apply()
    }
}
