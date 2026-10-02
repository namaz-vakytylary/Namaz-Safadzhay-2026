package ru.namaz.safadzhay

import android.content.Intent
import java.time.LocalDate

internal data class PrayerReminder(val date: LocalDate, val key: String, val prayer: String,
    val tatar: String, val time: String) {
    companion object {
        val keys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")
        fun parse(date: String, key: String, prayer: String, tatar: String, time: String): PrayerReminder? =
            runCatching {
                require(key in keys && prayer.isNotBlank() && prayer.length <= 100 && tatar.length <= 100)
                require(time.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")))
                val parsed = LocalDate.parse(date)
                require(parsed.year in 2026..2100 && parsed.toString() == date)
                PrayerReminder(parsed, key, prayer, tatar, time)
            }.getOrNull()

        fun fromIntent(intent: Intent): PrayerReminder? = runCatching {
            parse(intent.getStringExtra("date") ?: return null, intent.getStringExtra("key") ?: return null,
                intent.getStringExtra("prayer") ?: return null, intent.getStringExtra("tatar") ?: "",
                intent.getStringExtra("time") ?: "")
        }.getOrNull()

        fun stored(line: String): PrayerReminder? {
            if (line.length > 400) return null
            val parts = line.split("|")
            if (parts.size != 6) return null
            return parse(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }
}
