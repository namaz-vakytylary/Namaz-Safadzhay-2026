package ru.namaz.safadzhay

import android.Manifest
import android.app.*
import android.content.*
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Executes production receivers/Activity against Android API 33 shadows.
 * Does not emulate OEM battery management, hardware vibration or audible sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationTest {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var nm: NotificationManager
    private lateinit var am: AlarmManager
    private val zone = ZoneId.of("Europe/Moscow")
    private val today get() = LocalDate.now(zone)
    private val tomorrow get() = today.plusDays(1)

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("notifications_enabled", true).putInt("notify_before_min", 5).apply()
        nm = app.getSystemService(NotificationManager::class.java)
        am = app.getSystemService(AlarmManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun fire(key: String = "fajr", name: String = "Фаджр", date: LocalDate = today) {
        PrayerNotificationReceiver().onReceive(app, Intent(app, PrayerNotificationReceiver::class.java).apply {
            putExtra("prayer", name); putExtra("tatar", "Иртәнге намаз")
            putExtra("time", "04:10"); putExtra("key", key); putExtra("date", date.toString())
        })
    }
    private fun stored(date: LocalDate, key: String = "fajr", time: String = "04:10") =
        "$date|$key|Фаджр|Иртәнге намаз|$time|Сафаджай"
    private fun onlyNotification() = shadowOf(nm).allNotifications.single()
    private fun launchIntent(): Intent { fire(); return shadowOf(onlyNotification().contentIntent).savedIntent }

    @Test fun notificationHasOriginalTextSoundVibrationAndDirectActivityAction() {
        fire()
        val n = onlyNotification()
        assertEquals("Напоминание: Фаджр (Иртәнге намаз)", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("До намаза 5 мин. Время намаза: 04:10", n.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val channel = nm.getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), channel.sound)
        assertTrue(channel.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 500, 250, 500), channel.vibrationPattern)
        assertTrue(n.contentIntent.isImmutable)
        val pending = shadowOf(n.contentIntent)
        assertTrue(pending.isActivityIntent)
        n.contentIntent.send()
        val launched = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, launched.component!!.className)
        assertEquals("ru.namaz.safadzhay.OPEN_PRAYER", launched.action)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun fivePrayersRemainDistinctAndRedeliveryDoesNotDuplicate() {
        listOf("fajr", "zuhr", "asr", "maghrib", "isha").forEach { fire(it) }
        assertEquals(5, shadowOf(nm).allNotifications.size)
        fire("fajr")
        assertEquals(5, shadowOf(nm).allNotifications.size)
    }

    @Test fun disabledNotificationsSuppressDeliveryAndNextAlarm() {
        prefs.edit().putBoolean("notifications_enabled", false).putString("scheduled_prayers", stored(tomorrow)).apply()
        fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test fun disabledPrayerSuppressesDeliveryAndNextAlarm() {
        prefs.edit().putBoolean("notify_fajr", false).putString("scheduled_prayers", stored(tomorrow)).apply()
        fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test fun deniedPermissionDoesNotCrashAndKeepsNextAlarm() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().putString("scheduled_prayers", stored(tomorrow)).apply()
        fire()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
    }

    @Test fun changedSoundCreatesMatchingChannelAndZeroLeadText() {
        fire()
        val oldChannel = onlyNotification().channelId
        val sound = Uri.parse("content://media/internal/audio/media/42")
        prefs.edit().putString("notification_sound_uri", sound.toString()).putInt("notify_before_min", 0).apply()
        fire()
        val n = onlyNotification()
        assertNotEquals(oldChannel, n.channelId)
        assertEquals(sound, nm.getNotificationChannel(n.channelId).sound)
        assertEquals("Время намаза: 04:10", n.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun closedAppReceiverSchedulesNextPrayerAtConfiguredLeadTime() {
        prefs.edit().putInt("notify_before_min", 10).putString("scheduled_prayers", stored(tomorrow)).apply()
        fire()
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(tomorrow.atTime(4, 0).atZone(zone).toInstant().toEpochMilli(), alarm.triggerAtMs)
        assertEquals(0L, alarm.windowLengthMs)
        val i = shadowOf(alarm.operation).savedIntent
        assertEquals(tomorrow.toString(), i.getStringExtra("date"))
        assertEquals("fajr", i.getStringExtra("key"))
    }

    @Test fun withoutExactAlarmPermissionFallsBackWithoutCrash() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        prefs.edit().putString("scheduled_prayers", stored(tomorrow)).apply()
        fire()
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertNotEquals(0L, shadowOf(am).scheduledAlarms.single().windowLengthMs)
    }

    private fun checkRestore(action: String) {
        prefs.edit().putBoolean("notify_isha", false).putString("scheduled_prayers", listOf(
            stored(today.minusDays(1)), stored(tomorrow), stored(tomorrow, "isha"), stored(today.plusDays(30))
        ).joinToString("\n")).apply()
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        val alarms = shadowOf(am).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(tomorrow.toString(), shadowOf(alarms.single().operation).savedIntent.getStringExtra("date"))
        assertEquals(tomorrow.atTime(4, 5).atZone(zone).toInstant().toEpochMilli(), alarms.single().triggerAtMs)
    }
    @Test fun bootRestoresOnlyEnabledFutureAlarms() = checkRestore(Intent.ACTION_BOOT_COMPLETED)
    @Test fun appUpdateRestoresStoredOriginalFormat() = checkRestore(Intent.ACTION_MY_PACKAGE_REPLACED)
    @Test fun grantingExactAlarmPermissionRestoresAlarms() = checkRestore(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)

    private fun waitForScheduler(activity: MainActivity) {
        ReflectionHelpers.getField<ExecutorService>(activity, "alarmExecutor").submit {}.get(15, TimeUnit.SECONDS)
    }
    private fun assertToday(activity: MainActivity) {
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "scheduleTabSelected"))
        assertEquals(today, ReflectionHelpers.getField<LocalDate>(activity, "selectedDate"))
        assertNull(ReflectionHelpers.getField<Dialog?>(activity, "settingsPanel"))
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)
    }
    @Test fun coldNotificationLaunchOverridesRestoredScheduleAndSettings() {
        val state = Bundle().apply {
            putBoolean("schedule_tab", true); putString("selected_date", today.minusDays(2).toString())
            putString("panel_route", "notifications")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent()).create(state)
        try { waitForScheduler(controller.get()); assertToday(controller.get()) } finally { controller.destroy() }
    }
    @Test fun warmNotificationLaunchClosesSettingsAndAboutAndSelectsToday() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            waitForScheduler(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSettingsDialog")
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "showAboutDialog")
            assertTrue(ReflectionHelpers.getField<Dialog>(activity, "settingsPanel").isShowing)
            ReflectionHelpers.setField(activity, "scheduleTabSelected", true)
            ReflectionHelpers.setField(activity, "selectedDate", today.minusDays(2))
            controller.newIntent(launchIntent())
            shadowOf(Looper.getMainLooper()).idle() // Dialog dismissal listeners run on the UI queue.
            assertToday(activity)
            assertNull(ReflectionHelpers.getField<Dialog?>(activity, "aboutDialog"))
        } finally { controller.destroy() }
    }
}
