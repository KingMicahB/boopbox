package com.cardplayer.util

import com.cardplayer.billing.Plus
import com.cardplayer.data.AppSettings
import java.util.Calendar

object Bedtime {
    /** Minutes since midnight, local time. */
    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** True when the current time falls inside the bedtime window (which may cross midnight). Bedtime hours are Plus. */
    fun isBedtime(s: AppSettings, nowMin: Int = nowMinutes()): Boolean {
        if (!s.bedtimeEnabled || !Plus.isActive) return false
        val start = s.bedtimeStart
        val end = s.bedtimeEnd
        return if (start <= end) nowMin in start until end else (nowMin >= start || nowMin < end)
    }

    fun nightLightActive(s: AppSettings): Boolean = when (s.nightLight) {
        "on" -> true
        "bedtime" -> isBedtime(s)
        else -> false
    }

    /** The night light's colour and brightness: the grown-up's choice with Plus, the defaults without. */
    fun nightLightColor(s: AppSettings): Long = if (Plus.isActive) s.nightLightColor else AppSettings().nightLightColor

    fun nightLightBrightness(s: AppSettings): Int = if (Plus.isActive) s.nightLightBrightness else AppSettings().nightLightBrightness

    fun formatMinutes(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)
}
