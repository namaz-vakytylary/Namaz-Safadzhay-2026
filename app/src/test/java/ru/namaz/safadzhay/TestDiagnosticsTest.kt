package ru.namaz.safadzhay

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestDiagnosticsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val manager get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val now = LocalDate.of(2026, 10, 16).atStartOfDay(PrayerAlarmScheduler.zone).toInstant()
    @Before fun setup() {
        TestNetwork.offline(app); TestNetwork.published(app)
        prefs.edit().clear().putString("city", "Москва").putBoolean("notifications_enabled", true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrayerAlarmScheduler.clock = { now }
    }
    @After fun cleanup() {
        settle(); PrayerAlarmScheduler.clock = Instant::now
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun settle() {
        ScheduleUpdateChecker.worker.submit {}.get(30, TimeUnit.SECONDS)
        PrayerAlarmScheduler.worker.submit {}.get(30, TimeUnit.SECONDS)
        TestDiagnosticsUi.reader.submit {}.get(30, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun start() = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().also { settle() }
    private fun open(a: MainActivity, route: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(a, "showTestDiagnostics", ReflectionHelpers.ClassParameter.from(String::class.java, route))
        settle()
    }
    private fun root(a: MainActivity): View = ReflectionHelpers.getField<Dialog>(a, "settingsPanel").window!!.decorView
    private fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
    private fun labels(v: View) = walk(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(v: View, title: String) {
        var target: View = walk(v).filterIsInstance<TextView>().first { it.text.toString() == title }
        while (!target.isClickable && target.parent is View) target = target.parent as View
        assertTrue(target.performClick()); settle()
    }

    @Test fun settingsHasSingleDataCardAndSharedDiagnosticsNavigation() {
        val c = start(); val a = c.get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(a, "showSettingsDialog")
            assertEquals(1, labels(root(a)).count { it == "Данные расписаний" })
            assertEquals(1, labels(root(a)).count { it == "Диагностика · TEST" })
            click(root(a), "Диагностика · TEST")
            assertTrue(labels(root(a)).any { it == "Проверка уведомлений · TEST" })
            assertTrue(labels(root(a)).any { it.contains("ru.namaz.safadzhay.test") })
            assertTrue(labels(root(a)).any { it == "Геолокация · TEST" })
            assertTrue(labels(root(a)).any { it == "Проверка Рамадана" })
            click(root(a), "Проверка Рамадана")
            assertTrue(labels(root(a)).any { it.contains("окончание сухура") })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun cachedOfflineScheduleHasNoRetryButtonOrAutomaticProgressWindow() {
        val c = start(); val a = c.get()
        try {
            val retry = a.findViewById<View>(android.R.id.content).findViewWithTag<View>("schedule_retry")
            assertEquals(View.GONE, retry.visibility)
            ReflectionHelpers.callInstanceMethod<Unit>(a, "showScheduleUpdateDialog",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 2026),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 12))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(ReflectionHelpers.getField<Dialog?>(a, "scheduleUpdateDialog"))
            val actual = ScheduleRepository.get(app).day("moscow", LocalDate.now(PrayerAlarmScheduler.zone))!!
            assertTrue(labels(a.window.decorView).any { it == actual.fajr })
        } finally { c.pause().stop().destroy() }
    }

    @Test fun dataCardUsesActualValidatedCoverageAndDoesNotRewrite13Cache() {
        val c = start(); val a = c.get()
        val file = File(app.filesDir, "downloaded-schedules.json")
        val before = file.readBytes()
        try {
            open(a, "test_data")
            val text = labels(root(a)).joinToString("\n")
            assertTrue(text, text.contains("2026 · версия данных"))
            assertTrue(text, text.contains("Москва: 2026-08-01 — 2026-12-31, дней: 153"))
            assertTrue(text, text.contains("Сафаджай: 2026-08-01 — 2026-12-31, дней: 153"))
            assertTrue(text, text.contains("namaz-vakytylary/namaz-schedules"))
            assertFalse(text, text.contains("2027"))
            assertArrayEquals(before, file.readBytes())
            val repo = ScheduleRepository(app, { _, _ -> throw java.io.IOException("offline") })
            assertFalse(repo.sync(false).successful)
            assertEquals(153, repo.days("moscow").size)
            assertEquals(153, repo.days("safadzhay").size)
            assertArrayEquals(before, file.readBytes())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun readOnlyNotificationScreenAndRefreshDoNotMutateAlarmsSettingsOrCache() {
        val c = start(); val a = c.get()
        val alarms = shadowOf(manager).scheduledAlarms.toList()
        val settings = prefs.all.toMap()
        val cache = File(app.filesDir, "downloaded-schedules.json").readBytes()
        try {
            open(a, "test_alarms")
            val text = labels(root(a)).joinToString("\n")
            assertTrue(text, text.contains("Фаджр · Москва"))
            assertTrue(text, text.contains("МСК"))
            assertTrue(text, text.contains("Граница проверки"))
            click(root(a), "Обновить сведения")
            assertEquals(alarms, shadowOf(manager).scheduledAlarms.toList())
            assertEquals(settings, prefs.all)
            assertArrayEquals(cache, File(app.filesDir, "downloaded-schedules.json").readBytes())
        } finally { c.pause().stop().destroy() }
    }
    @Test fun journalMatchesActualAlarmManagerCallsAndUsesOnlyScheduledHorizon() {
        PrayerAlarmScheduler.rebuild(app)
        val trace = TestAlarmTrace.snapshot()
        val alarms = shadowOf(manager).scheduledAlarms
        assertEquals("complete", trace.status)
        assertEquals(alarms.size, trace.entries.size)
        assertTrue(trace.entries.isNotEmpty())
        trace.entries.forEach { record ->
            val actual = alarms.single { shadowOf(it.operation).savedIntent.let { i ->
                i.getStringExtra("date") == record.date && i.getStringExtra("key") == record.key
            } }
            assertEquals(actual.triggerAtMs, record.triggerMillis)
            assertEquals(actual.windowLengthMs == 0L, record.exact)
            assertEquals("Москва", record.city)
            assertFalse(LocalDate.parse(record.date).isAfter(LocalDate.of(2026, 10, 30)))
        }
    }
    @Test fun disabledNotificationsEmptyJournalAndBootRestoresActualEntries() {
        prefs.edit().putBoolean("notifications_enabled", false).commit()
        PrayerAlarmScheduler.rebuild(app)
        assertTrue(TestAlarmTrace.snapshot().entries.isEmpty())
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
        prefs.edit().putBoolean("notifications_enabled", true).commit()
        PrayerBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)); settle()
        assertEquals(shadowOf(manager).scheduledAlarms.size, TestAlarmTrace.snapshot().entries.size)
        assertTrue(TestAlarmTrace.snapshot().entries.isNotEmpty())
    }
    @Test fun deniedPermissionsAreReadWithoutChangingAlarmsOrRequestingAccess() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        PrayerAlarmScheduler.rebuild(app)
        val before = shadowOf(manager).scheduledAlarms.toList()
        assertTrue(TestAlarmTrace.snapshot().entries.all { !it.exact })
        val facts = TestDiagnosticsUi.permissionFacts(app).toMap()
        assertEquals("Не разрешено", facts["Разрешение уведомлений"])
        assertEquals("Не разрешён", facts["Доступ к точным будильникам"])
        assertEquals(before, shadowOf(manager).scheduledAlarms.toList())
    }
    @Test @Config(sdk = [23]) @GraphicsMode(GraphicsMode.Mode.LEGACY) fun permissionsOnAndroid6DoNotReadNewerPlatformApis() {
        val facts = TestDiagnosticsUi.permissionFacts(app).toMap()
        assertTrue(facts.getValue("Разрешение уведомлений").contains("не требуется"))
        assertTrue(facts.getValue("Доступ к точным будильникам").contains("не требуется"))
    }
    @Test fun geographicScreenReadsManualGuardWithoutStartingProviderRequests() {
        val c = start(); val a = c.get()
        try {
            val settings = ReflectionHelpers.getField<CitySelectionSettings>(a, "citySelection")
            settings.selectManual(CityCatalog.all.last())
            ReflectionHelpers.getField<CityLocationController>(a, "cityLocation").selectionChanged()
            val location = app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val listeners = shadowOf(location).getLocationUpdateListeners().toList()
            val before = prefs.all.toMap()
            open(a, "test_geo")
            val text = labels(root(a)).joinToString("\n")
            assertTrue(text, text.contains("Ручной"))
            assertTrue(text, text.contains("Включена до конца запуска"))
            assertTrue(text, text.contains("Источник не записан"))
            assertFalse(text, text.contains("55.7558"))
            click(root(a), "Обновить сведения")
            assertEquals(listeners, shadowOf(location).getLocationUpdateListeners().toList())
            assertEquals(before, prefs.all)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun newCardsWrapOnCompactScreensInBothThemesAndLargeFont() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ThemeSettings.save(app, mode)
            val c = start(); val a = c.get()
            try {
                for (route in listOf("test_data", "test_diagnostics", "test_geo", "test_alarms")) {
                    open(a, route)
                    val v = root(a)
                    repeat(3) {
                        v.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                        v.layout(0, 0, 320, 640); shadowOf(Looper.getMainLooper()).idle()
                    }
                    walk(v).filterIsInstance<android.widget.ImageView>().forEach {
                        assertEquals(ThemeSettings.colors(app).primary, it.imageTintList?.defaultColor)
                    }
                    val texts = walk(v).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && it.isShown }
                    assertTrue(texts.isNotEmpty())
                    texts.forEach { t ->
                        assertNotNull(t.layout)
                        for (line in 0 until t.layout.lineCount) assertEquals(t.text.toString(), 0, t.layout.getEllipsisCount(line))
                        assertTrue(t.text.toString(), t.layout.height <= t.height - t.compoundPaddingTop - t.compoundPaddingBottom + 2)
                    }
                    val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                    v.draw(Canvas(bitmap))
                    File("build/reports/theme-ui/diagnostics").apply { mkdirs() }.resolve("$route-${mode.storedValue}-320-font1.5.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            } finally { c.pause().stop().destroy() }
        }
    }
}
