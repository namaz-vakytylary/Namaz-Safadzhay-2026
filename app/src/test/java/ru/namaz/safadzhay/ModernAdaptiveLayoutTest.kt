package ru.namaz.safadzhay

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Actual View measurement in Android models; device rotation policy still needs a device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], qualifiers = "sw600dp-w800dp-h1280dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModernAdaptiveLayoutTest {
    private val helper = TodayLayoutTest()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun <T> field(a: MainActivity, name: String): T = ReflectionHelpers.getField(a, name)
    @Before fun setup() {
        TestNetwork.offline(app); TestNetwork.published(app)
        app.getSharedPreferences("settings", 0).edit().clear()
            .putBoolean("notifications_enabled", false).putBoolean("show_tatar", true).commit()
    }
    private fun settleCache() {
        PrayerAlarmScheduler.worker.submit {}.get(20, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun insets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(24, 0, 12, 0))
        .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 56))
        .build()

    @Test fun largePortraitLandscapeAndNarrowWindowsKeepAllPrayersAndReadableRamadanText() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)) {
            ThemeSettings.save(app, mode)
            for (font in listOf(1f, 1.5f)) {
                RuntimeEnvironment.setFontScale(font)
                val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                try {
                    settleCache()
                    val a = c.get()
                    val scroll: TodayLayoutScrollView = field(a, "mainScroll")
                    ViewCompat.dispatchApplyWindowInsets(scroll, insets())
                    for ((width, height) in listOf(800 to 1280, 1280 to 800, 600 to 400, 360 to 800)) {
                        for (state in listOf("day", "suhoor", "iftar", "after")) {
                            helper.renderContent(a, state, true)
                            helper.measure(a, width, height)
                            val description = "API=${Build.VERSION.SDK_INT}, $mode/$font/$state/${width}x$height"
                            val prayers: LinearLayout = field(a, "prayerContainer")
                            assertEquals(description, 5, prayers.childCount)
                            helper.assertTextFits(a, description)
                            val root = scroll.getChildAt(0)
                            assertTrue(description, root.paddingLeft >= 24 + 16)
                            assertTrue(description, root.paddingRight >= 12 + 16)
                            val isha = prayers.getChildAt(4)
                            val xy = IntArray(2)
                            scroll.scrollTo(0, Int.MAX_VALUE)
                            isha.getLocationInWindow(xy)
                            assertTrue("$description Isha unreachable", xy[1] + isha.height <= height - 56)
                            scroll.scrollTo(0, 0)
                            if (mode != ThemeMode.SYSTEM && font == 1f && state == "after" && width >= 800) {
                                helper.snapshot(a, "modern-${Build.VERSION.SDK_INT}-${mode.storedValue}-${width}x$height", width, height)
                            }
                        }
                    }
                } finally { c.pause().stop().destroy() }
            }
        }
    }

    @Test fun settingsPanelHonoursBothSideCutoutsAndGestureInset() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ThemeSettings.save(app, mode)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                settleCache()
                ReflectionHelpers.callInstanceMethod<Unit>(c.get(), "showSettingsDialog")
                val dialog: Dialog = field(c.get(), "settingsPanel")
                val content = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)
                val scroll = content.getChildAt(0) as ScrollView
                ViewCompat.dispatchApplyWindowInsets(scroll, insets())
                val root = scroll.getChildAt(0)
                assertEquals(24 + 18, root.paddingLeft)
                assertEquals(12 + 18, root.paddingRight)
                assertEquals(24 + 10, root.paddingTop)
                assertEquals(56 + 24, root.paddingBottom)
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun portraitToLandscapeRecreationKeepsCalendarPanelAndManualCityGuard() {
        val saved = Bundle()
        val date = LocalDate.of(2026, 10, 16)
        val old = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            settleCache()
            val selection: CitySelectionSettings = field(old.get(), "citySelection")
            selection.selectManual(CityCatalog.all.single { it.id == "moscow" })
            field<CityLocationController>(old.get(), "cityLocation").selectionChanged()
            ReflectionHelpers.setField(old.get(), "selectedDate", date)
            ReflectionHelpers.setField(old.get(), "scheduleTabSelected", true)
            ReflectionHelpers.callInstanceMethod<Unit>(old.get(), "showSettingsDialog")
            old.saveInstanceState(saved)
        } finally { old.pause().stop().destroy() }
        RuntimeEnvironment.setQualifiers("sw600dp-w1280dp-h800dp-land-mdpi")
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            assertEquals("Москва", field<String>(restored.get(), "selectedCity"))
            assertTrue(field<CitySelectionSettings>(restored.get(), "citySelection").session.manualOverride)
            assertEquals(date, field<LocalDate>(restored.get(), "selectedDate"))
            assertTrue(field<Boolean>(restored.get(), "scheduleTabSelected"))
            assertEquals("settings", field<String>(restored.get(), "panelRoute"))
            assertTrue(field<Dialog>(restored.get(), "settingsPanel").isShowing)
        } finally { restored.pause().stop().destroy() }
    }
}
