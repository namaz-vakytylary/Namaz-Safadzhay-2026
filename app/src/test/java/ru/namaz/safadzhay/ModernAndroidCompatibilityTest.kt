package ru.namaz.safadzhay

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
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
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Framework-model coverage, not a claim about physical Android 16/17 devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], qualifiers = "w360dp-h800dp-mdpi")
class ModernAndroidCompatibilityTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app); TestNetwork.published(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("settings", 0).edit().putBoolean("notifications_enabled", true).commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun reset() {
        PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS)
        PrayerAlarmScheduler.clock = Instant::now
    }
    private fun texts(view: View): List<String> = buildList {
        if (view is TextView && view.visibility == View.VISIBLE) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
    @Test fun cachedColdStartAndThemesUseProductionUiWithoutArtificialDates() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)) {
            ThemeSettings.save(app, mode)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            try {
                PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showSettingsDialog")
                val dialog = ReflectionHelpers.getField<android.app.Dialog>(c.get(), "settingsPanel")
                val content = texts(dialog.window!!.decorView)
                assertTrue(content.contains("Данные расписаний"))
                assertFalse(content.any { it.contains("TEST") || it.contains("Проверка Рамадана") })
                assertEquals(mode, ThemeSettings.read(app))
            } finally { c.pause().stop().destroy() }
        }
    }
    @Test fun bootRestoresVerifiedOfflineScheduleAndExactAccessFallback() {
        PrayerAlarmScheduler.clock = { LocalDate.of(2026, 10, 16).atStartOfDay(PrayerAlarmScheduler.zone).toInstant() }
        val manager = app.getSystemService(AlarmManager::class.java)
        PrayerBootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS)
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
        assertTrue(shadowOf(manager).scheduledAlarms.all { it.windowLengthMs == 0L })
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        PrayerBootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS)
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
        assertTrue(shadowOf(manager).scheduledAlarms.all { it.windowLengthMs != 0L })
    }
}
