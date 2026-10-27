package dev.shutchat.app.core

import java.util.Locale

/**
 * Human readable formatting helpers shared by the UI. Kept free of Android
 * dependencies so they can be unit tested on the JVM.
 */
object Format {

    private val UNITS = listOf(
        60L to "min",
        60L to "h",
        24L to "d",
    )

    /** "fra 4 giorni", "fra 3 ore", "scaduto". Used on invite cards. */
    fun describeRemaining(expiresAt: Long?, now: Long): String {
        if (expiresAt == null) return "senza scadenza"
        val delta = expiresAt - now
        if (delta <= 0) return "scaduto"
        return "fra " + describeDuration(delta)
    }

    /** Coarse duration, biggest unit only: "2 giorni", "5 ore", "12 minuti". */
    fun describeDuration(millis: Long): String {
        var value = millis / 1000
        if (value < 60) return plural(value, "secondo", "secondi")
        value /= 60
        if (value < 60) return plural(value, "minuto", "minuti")
        value /= 60
        if (value < 24) return plural(value, "ora", "ore")
        value /= 24
        if (value < 30) return plural(value, "giorno", "giorni")
        if (value < 365) return plural(value / 30, "mese", "mesi")
        return plural(value / 365, "anno", "anni")
    }

    private fun plural(value: Long, singular: String, plural: String): String =
        if (value == 1L) "1 $singular" else "$value $plural"

    fun shortTime(epochMillis: Long): String {
        val date = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val hour = date.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = date.get(java.util.Calendar.MINUTE)
        return String.format(Locale.ITALY, "%02d:%02d", hour, minute)
    }

    /** Timestamp separator inside a conversation. */
    fun daySeparator(epochMillis: Long, now: Long): String {
        val zone = java.util.TimeZone.getDefault()
        val dayMillis = 24L * 60 * 60 * 1000
        val dayOf = { millis: Long -> (millis + zone.getOffset(millis)) / dayMillis }
        return when (dayOf(now) - dayOf(epochMillis)) {
            0L -> "Oggi"
            1L -> "Ieri"
            else -> {
                val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
                val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
                val month = MONTHS[calendar.get(java.util.Calendar.MONTH)]
                val year = calendar.get(java.util.Calendar.YEAR)
                val currentYear = java.util.Calendar.getInstance()
                    .apply { timeInMillis = now }
                    .get(java.util.Calendar.YEAR)
                if (year == currentYear) "$day $month" else "$day $month $year"
            }
        }
    }

    private val MONTHS = listOf(
        "gen", "feb", "mar", "apr", "mag", "giu",
        "lug", "ago", "set", "ott", "nov", "dic",
    )

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ITALY, "%.0f kB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ITALY, "%.1f MB", mb)
        return String.format(Locale.ITALY, "%.2f GB", mb / 1024.0)
    }

    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ITALY, "%d:%02d", minutes, seconds)
    }

    /** Stable pastel index derived from a chat id, used for avatar tints. */
    fun colorSeed(seed: String): Int {
        var hash = 7
        for (char in seed) hash = hash * 31 + char.code
        return hash and 0x7FFFFFFF
    }

    /** Up to two initials for avatar fallbacks. */
    fun initials(name: String): String {
        val words = name.trim().split(' ', '.', '-', '_').filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase(Locale.ITALY)
            else -> (words[0].take(1) + words[1].take(1)).uppercase(Locale.ITALY)
        }
    }

    fun messagePreview(body: String, kind: String): String = when (kind) {
        "IMAGE" -> "📷 Foto"
        "FILE" -> "📎 " + body.ifBlank { "File" }
        "AUDIO" -> "🎤 Messaggio vocale"
        "SYSTEM" -> body
        else -> body.replace('\n', ' ').trim()
    }
}
