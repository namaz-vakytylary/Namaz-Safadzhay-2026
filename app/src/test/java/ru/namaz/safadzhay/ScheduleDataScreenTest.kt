package ru.namaz.safadzhay

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp-mdpi")
class ScheduleDataScreenTest {
    private fun texts(view: View): List<String> = buildList {
        if (view is TextView && view.visibility == View.VISIBLE) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
    private fun waitForData() {
        val field = Class.forName("ru.namaz.safadzhay.MainActivityKt").getDeclaredField("scheduleDataWorker")
        field.isAccessible = true
        (field.get(null) as ExecutorService).submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun ordinarySettingsExposeRealCacheFactsInBothThemesWithoutTestToolsOrRewritingCache() {
        val app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app); TestNetwork.published(app)
        val cache = File(app.filesDir, "downloaded-schedules.json")
        val before = cache.readBytes()
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ThemeSettings.save(app, mode)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            try {
                ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showSettingsDialog")
                var dialog = ReflectionHelpers.getField<Dialog>(c.get(), "settingsPanel")
                val settings = texts(dialog.window!!.decorView)
                assertEquals(1, settings.count { it == "Данные расписаний" })
                assertFalse(settings.any { it.contains("TEST") || it.contains("Проверка Рамадана") })
                ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showScheduleData")
                waitForData()
                dialog = ReflectionHelpers.getField(c.get(), "settingsPanel")
                val facts = texts(dialog.window!!.decorView).joinToString("\n")
                for (expected in listOf("2026 · версия данных 1", "Москва: 2026-08-01 — 2026-12-31", "Сафаджай: 2026-08-01 — 2026-12-31", "namaz-vakytylary/namaz-schedules", "Последняя успешная проверка обновлений")) {
                    assertTrue(facts, facts.contains(expected))
                }
                assertArrayEquals(before, cache.readBytes())
            } finally { c.pause().stop().destroy() }
        }
    }
}
