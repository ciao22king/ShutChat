package dev.shutchat.app.domain.expiry

import dev.shutchat.app.core.Clock
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Invite codes carry a day, not a timestamp: the request was for a code that
 * shows "the day it turns off". Storing the day keeps the code short (15 bytes)
 * and makes expiry deterministic for every peer regardless of their timezone,
 * because the cut-off is the end of that UTC day.
 */
object Expiry {

    /** 2024-01-01T00:00Z, in whole days since the Unix epoch. */
    const val ORIGIN_DAY = 19_723

    /** Sentinel stored in the code meaning "no expiry". */
    const val NEVER = 0xFFFF

    private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)

    fun dayOf(epochMillis: Long): Int = Math.floorDiv(epochMillis, DAY_MILLIS).toInt()

    /** Last instant (exclusive upper bound) of [day], in UTC. */
    fun endOfDayMillis(day: Int): Long = (day.toLong() + 1) * DAY_MILLIS

    /** Expiry instant for a code, or null when it never expires. */
    fun expiresAtMillis(expiryDay: Int?): Long? =
        expiryDay?.let { endOfDayMillis(it + ORIGIN_DAY) }

    /** Day number stored in the code for a desired absolute deadline. */
    fun dayFromExpiryMillis(expiresAtMillis: Long): Int =
        dayOf(expiresAtMillis) - ORIGIN_DAY

    fun isExpired(expiryDay: Int?, clock: Clock): Boolean {
        val deadline = expiresAtMillis(expiryDay) ?: return false
        return clock.nowMillis() >= deadline
    }

    /** 0.0 (fresh) to 1.0 (expired). Null for permanent codes. */
    fun consumedFraction(createdAt: Long, expiryDay: Int?, clock: Clock): Float? {
        val deadline = expiresAtMillis(expiryDay) ?: return null
        val total = deadline - createdAt
        if (total <= 0) return 1f
        return ((clock.nowMillis() - createdAt).toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Presets offered in the "+" sheet. Day-granular so what the user picks is
     * exactly what gets baked into the code.
     */
    enum class Preset(val label: String, val days: Int?) {
        ONE_DAY("24 ore", 1),
        THREE_DAYS("3 giorni", 3),
        ONE_WEEK("7 giorni", 7),
        ONE_MONTH("30 giorni", 30),
        THREE_MONTHS("90 giorni", 90),
        NEVER("Mai", null),
    }

    fun presetExpiryDay(preset: Preset, clock: Clock): Int? {
        val days = preset.days ?: return null
        // "24 ore" from now, then rounded up to the containing UTC day so the
        // code is valid for at least the advertised window.
        val target = clock.nowMillis() + TimeUnit.DAYS.toMillis(days.toLong())
        return dayOf(target) - ORIGIN_DAY
    }

    /** Local-time rendering of a code deadline, e.g. "3 nov 2026, 23:59". */
    fun describeDeadline(expiryDay: Int?, clock: Clock): String {
        val millis = expiresAtMillis(expiryDay) ?: return "Mai (∞)"
        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = millis - 1
        }
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = MONTHS[calendar.get(Calendar.MONTH)]
        val year = calendar.get(Calendar.YEAR)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "%d %s %d, %02d:%02d".format(day, month, year, hour, minute)
    }

    private val MONTHS = listOf(
        "gen", "feb", "mar", "apr", "mag", "giu",
        "lug", "ago", "set", "ott", "nov", "dic",
    )
}
