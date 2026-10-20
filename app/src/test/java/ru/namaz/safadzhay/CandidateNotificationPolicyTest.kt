package ru.namaz.safadzhay

import android.app.AlarmManager
import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30])
class CandidateNotificationPolicyTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app)
        TestNetwork.published(app)
        app.getSharedPreferences("settings", 0).edit().clear().putString("city", "Москва").commit()
        PrayerAlarmScheduler.clock = { LocalDate.of(2026, 10, 16).atStartOfDay(PrayerAlarmScheduler.zone).toInstant() }
    }
    @After fun reset() { PrayerAlarmScheduler.clock = Instant::now }

    @Test fun legacyDefaultKeepsExactFutureAlarmsWithoutAnExplicitPreference() {
        PrayerAlarmScheduler.rebuild(app)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue(alarms.isNotEmpty())
        assertTrue(alarms.all { it.windowLengthMs == 0L })
        assertTrue(alarms.all { shadowOf(it.operation).savedIntent.getStringExtra("city") == "Москва" })
    }

    @Test fun explicitOptOutCancelsPreviouslyScheduledAlarms() {
        PrayerAlarmScheduler.rebuild(app)
        app.getSharedPreferences("settings", 0).edit().putBoolean("notifications_enabled", false).commit()
        PrayerAlarmScheduler.rebuild(app)
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test @Config(sdk = [33]) fun modernExactPermissionDenialUsesFallbackAndGrantRestoresExactness() {
        val manager = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        PrayerAlarmScheduler.rebuild(app)
        assertTrue(shadowOf(manager).scheduledAlarms.isNotEmpty())
        assertTrue(shadowOf(manager).scheduledAlarms.all { it.windowLengthMs != 0L })
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrayerAlarmScheduler.rebuild(app)
        assertTrue(shadowOf(manager).scheduledAlarms.all { it.windowLengthMs == 0L })
    }

    @Test fun onlyLocalContentSoundUrisAreAccepted() {
        assertTrue(isLocalNotificationSound(android.net.Uri.parse("content://media/internal/audio/media/42")))
        for (uri in listOf("https://example.invalid/sound", "file:///tmp/sound", "content://user:pass@media/sound", "content:///sound")) {
            assertFalse(uri, isLocalNotificationSound(android.net.Uri.parse(uri)))
        }
    }
}
