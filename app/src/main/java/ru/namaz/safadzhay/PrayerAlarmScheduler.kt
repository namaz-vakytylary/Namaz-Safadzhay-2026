package ru.namaz.safadzhay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** All UI, receiver and boot requests share this queue and read current settings at execution. */
internal object PrayerAlarmScheduler {
    val worker = Executors.newSingleThreadExecutor()
    val zone: ZoneId = ZoneId.of("Europe/Moscow")
    val keys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")
    val russian = listOf("Фаджр", "Зухр", "Аср", "Магриб", "Иша")
    val tatar = listOf("Иртәнге намаз", "Өйлә намазы", "Икенде намазы", "Ахшам намазы", "Ястү намазы")
    internal var clock: () -> Instant = Instant::now
    fun time(day: PrayerDay, key: String): String? = when (key) {
        "fajr" -> day.fajr; "zuhr" -> day.zuhr; "asr" -> day.asr
        "maghrib" -> day.maghrib; "isha" -> day.isha; else -> null
    }
    fun requestCode(date: LocalDate, key: String) =
        ((date.toEpochDay() % 100000L) * 10 + keys.indexOf(key)).toInt()

    @Synchronized fun rebuild(context: Context) {
        val app = context.applicationContext
        try {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val city = prefs.getString("city", "Сафаджай") ?: "Сафаджай"
        val cityId = if (city == "Москва") "moscow" else "safadzhay"
        val repo = ScheduleRepository.get(app)
        repo.loadSavedIfNeeded()
        val now = clock()
        val today = now.atZone(zone).toLocalDate()
        val enabled = prefs.getBoolean("notifications_enabled", true)
        val lead = prefs.getInt("notify_before_min", 5).coerceIn(0, 180)
        val entries = if (!enabled) emptyList() else repo.days(cityId).flatMap { day ->
            val date = LocalDate.parse(day.date)
            if (date.isBefore(today)) emptyList() else keys.mapIndexedNotNull { index, key ->
                if (!prefs.getBoolean("notify_$key", true)) null else
                    listOf(day.date, key, russian[index], tatar[index], time(day, key)!!, city).joinToString("|")
            }
        }
        val old = prefs.getString("scheduled_prayers", "").orEmpty()
        // Durable projection is for recovery/cancellation only; never a source of prayer times.
        check(prefs.edit().putString("scheduled_prayers", entries.joinToString("\n")).commit())
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        old.lineSequence().forEach { line ->
            val parts = line.split('|')
            val date = parts.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (parts.size >= 2 && date != null && parts[1] in keys) {
                PendingIntent.getBroadcast(app, requestCode(date, parts[1]), Intent(app, PrayerNotificationReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
            }
        }
        entries.forEach { line ->
            val p = line.split('|'); val date = LocalDate.parse(p[0]); val key = p[1]
            val trigger = date.atTime(LocalTime.parse(p[4])).atZone(zone).toInstant().minusSeconds(lead * 60L)
            if (!trigger.isAfter(now) || date.isAfter(today.plusDays(14))) return@forEach
            val intent = Intent(app, PrayerNotificationReceiver::class.java).apply {
                putExtra("date", p[0]); putExtra("key", key); putExtra("time", p[4])
                putExtra("prayer", p[2]); putExtra("tatar", p[3]); putExtra("city", city)
                putExtra("revision", repo.revision())
            }
            val pending = PendingIntent.getBroadcast(app, requestCode(date, key), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            var exact = false
            try {
                if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
                    exact = true
                } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
            } catch (_: SecurityException) {
                // Exact access can be revoked between the check and the binder call.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
            }
        }
        } catch (error: Exception) {
            throw error
        }
    }

    /** A late inexact alarm is useful only before this prayer starts (up to 2 min at zero lead). */
    fun remainingMinutes(date: LocalDate, time: String, lead: Int, now: Instant): Long? {
        val prayer = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
        val trigger = prayer.minusSeconds(lead.coerceIn(0, 180) * 60L)
        if (now.isBefore(trigger) || !now.isBefore(prayer.plusSeconds(if (lead == 0) 120 else 1))) return null
        return ((java.time.Duration.between(now, prayer).seconds.coerceAtLeast(0) + 59) / 60)
    }
}
