package ru.namaz.safadzhay

import android.Manifest
import android.app.*
import android.content.*
import android.media.RingtoneManager
import android.net.Uri
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.*
import java.util.concurrent.TimeUnit

/** Receiver tests use the same downloaded fixture as the screen; clock is deterministic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationTest {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var nm: NotificationManager
    private lateinit var am: AlarmManager
    private val date = LocalDate.of(2026, 10, 16)
    private val zone = PrayerAlarmScheduler.zone
    private var now = date.atStartOfDay(zone).toInstant()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app); TestNetwork.published(app)
        prefs = app.getSharedPreferences("settings", 0)
        prefs.edit().clear().putBoolean("notifications_enabled", true).putString("city", "Сафаджай")
            .putInt("notify_before_min", 5).commit()
        nm = app.getSystemService(NotificationManager::class.java)
        am = app.getSystemService(AlarmManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrayerAlarmScheduler.clock = { now }
    }
    @After fun resetClock() { await(); PrayerAlarmScheduler.clock = Instant::now }
    private fun await() { PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS) }
    private fun day() = ScheduleRepository.get(app).day("safadzhay", date)!!
    private fun time(key: String) = PrayerAlarmScheduler.time(day(), key)!!
    private fun trigger(key: String) = date.atTime(LocalTime.parse(time(key))).atZone(zone).toInstant()
        .minusSeconds(prefs.getInt("notify_before_min", 5) * 60L)
    private fun intent(key: String = "fajr") = Intent(app, PrayerNotificationReceiver::class.java).apply {
        putExtra("key", key); putExtra("date", date.toString()); putExtra("time", time(key))
        putExtra("city", "Сафаджай"); putExtra("revision", ScheduleRepository.get(app).revision())
    }
    private fun fire(i: Intent = intent()) { PrayerNotificationReceiver().onReceive(app, i); await() }
    private fun only() = shadowOf(nm).allNotifications.single()

    @Test fun originalSoundVibrationNamesAndDirectLaunchArePreserved() {
        now = trigger("fajr"); fire()
        val n = only()
        assertEquals("Напоминание: Фаджр (Иртәнге намаз)", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("До намаза 5 мин. Время намаза: ${time("fajr")}", n.extras.getString(Notification.EXTRA_TEXT))
        val channel = nm.getNotificationChannel(n.channelId)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), channel.sound)
        assertTrue(channel.shouldVibrate()); assertTrue(n.contentIntent.isImmutable)
        assertEquals("ru.namaz.safadzhay.test.OPEN_PRAYER", shadowOf(n.contentIntent).savedIntent.action)
    }
    @Test fun fivePrayersHaveDistinctIdentitiesAndDurableRedeliveryGuard() {
        PrayerAlarmScheduler.keys.forEach { now = trigger(it); fire(intent(it)) }
        assertEquals(5, shadowOf(nm).allNotifications.size)
        nm.cancelAll(); now = trigger("fajr"); fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
    @Test fun disabledGlobalOrPrayerSettingSuppressesDelivery() {
        now = trigger("fajr")
        prefs.edit().putBoolean("notifications_enabled", false).commit(); fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty()); assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
        prefs.edit().putBoolean("notifications_enabled", true).putBoolean("notify_fajr", false).commit(); fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
    @Test fun permissionDenialKeepsFutureAlarmsAndDoesNotClaimDelivery() {
        now = trigger("fajr"); shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS); fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty()); assertFalse(prefs.contains("delivered_fajr"))
        assertTrue(shadowOf(am).scheduledAlarms.isNotEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS); fire(); assertNotNull(only())
    }
    @Test fun changedSoundTatarPreferenceAndZeroLeadAreReadAtDelivery() {
        val sound = Uri.parse("content://media/internal/audio/media/42")
        prefs.edit().putString("notification_sound_uri", sound.toString()).putBoolean("show_tatar_names", false)
            .putInt("notify_before_min", 0).commit()
        now = trigger("fajr"); fire()
        assertEquals("Время намаза: ${time("fajr")}", only().extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Напоминание: Фаджр", only().extras.getString(Notification.EXTRA_TITLE))
        assertEquals(sound, nm.getNotificationChannel(only().channelId).sound)
    }
    @Test fun lateInexactDeliveryUsesActualRemainingTimeAndExpiredAlarmIsDiscarded() {
        now = trigger("fajr").plusSeconds(120); fire()
        assertEquals("До намаза 3 мин. Время намаза: ${time("fajr")}", only().extras.getString(Notification.EXTRA_TEXT))
        nm.cancelAll(); now = trigger("zuhr").plusSeconds(301); fire(intent("zuhr"))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
    @Test fun earlyUnknownChangedCityRevisionAndTimeAreDiscarded() {
        now = trigger("fajr").minusSeconds(1); fire()
        now = trigger("fajr")
        listOf(intent().putExtra("key", "unknown"), intent().putExtra("city", "Москва"),
            intent().putExtra("revision", "obsolete"), intent().putExtra("time", "00:00"),
            intent().putExtra("date", "invalid")).forEach(::fire)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }
    @Test fun exactPermissionFallbackAndChangedLeadUseCityZone() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            prefs.edit().putInt("notify_before_min", 10).commit()
            PrayerAlarmScheduler.rebuild(app)
            val alarm = shadowOf(am).scheduledAlarms.first { shadowOf(it.operation).savedIntent.getStringExtra("key") == "fajr" }
            assertEquals(trigger("fajr").toEpochMilli(), alarm.triggerAtMs)
            assertNotEquals(0L, alarm.windowLengthMs)
        } finally { java.util.TimeZone.setDefault(previous) }
    }
    @Test fun bootPackageTimeDateAndTimezoneEventsRestoreFromVerifiedCacheOfflineWithoutDuplicates() {
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        var count = 0
        actions.forEach { action ->
            PrayerBootReceiver().onReceive(app, Intent(action)); await()
            val alarms = shadowOf(am).scheduledAlarms
            assertTrue(alarms.isNotEmpty())
            if (count == 0) count = alarms.size else assertEquals(count, alarms.size)
            assertTrue(alarms.all { it.triggerAtMs > now.toEpochMilli() })
        }
    }
    @Test fun missingCacheCancelsLegacyUnverifiedAlarmsInsteadOfInventingTimes() {
        PrayerAlarmScheduler.rebuild(app); assertTrue(shadowOf(am).scheduledAlarms.isNotEmpty())
        val repo = ScheduleRepository(app, { _, _ -> throw java.io.IOException() })
        java.io.File(app.filesDir, "downloaded-schedules.json").writeText("broken")
        val empty = ScheduleRepository(app, { _, _ -> throw java.io.IOException() })
        org.robolectric.util.ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", empty)
        PrayerAlarmScheduler.rebuild(app)
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty()); assertTrue(repo.hasDownloads())
    }
    @Test fun policyHandlesMidnightAndYearBoundaryWithoutPhoneTimezone() {
        val d = LocalDate.of(2027, 1, 1)
        val target = d.atTime(0, 5).atZone(zone).toInstant()
        assertEquals(10L, PrayerAlarmScheduler.remainingMinutes(d, "00:05", 10, target.minusSeconds(600)))
        assertNull(PrayerAlarmScheduler.remainingMinutes(d, "00:05", 10, target.plusSeconds(2)))
    }
    @Test fun movingClockForwardAndBackDoesNotRedeliverAcceptedDate() {
        now = trigger("fajr"); fire(); nm.cancelAll()
        prefs.edit().putString("delivered_fajr", date.plusDays(1).toString()).commit()
        fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

}
