package ru.namaz.safadzhay

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
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
import java.io.File

/** Native Android rendering fixtures: layout, contrast, live theme changes and settings routes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationScreenTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", 0)
    private val sizes = listOf(320 to 640, 360 to 800, 393 to 873, 412 to 915)
    private val titles = listOf("Когда напоминать", "Намазы для уведомлений", "Звук уведомления", "Точные уведомления")

    @Before fun setup() {
        RuntimeEnvironment.setFontScale(1f)
        TestNetwork.offline(app); TestNetwork.published(app); TestNetwork.published(app)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().clear().putBoolean("notifications_enabled", false)
            .putInt("notify_before_min", 15).putBoolean("notify_asr", false)
            .putString("notification_sound_uri", "content://example/sound")
            .putString("city", "Москва").commit()
    }

    private fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
    private fun invoke(a: MainActivity, name: String) = ReflectionHelpers.callInstanceMethod<Unit>(a, name)
    private fun panel(a: MainActivity): View = ReflectionHelpers.getField<Dialog>(a, "settingsPanel").findViewById(android.R.id.content)
    private fun open(a: MainActivity): View {
        invoke(a, "showSettingsDialog")
        clickText(panel(a), "Уведомления")
        return panel(a)
    }
    private fun clickText(root: View, text: String) {
        var target: View = walk(root).filterIsInstance<TextView>().first { it.text.toString() == text }
        while (!target.isClickable && target.parent is View) target = target.parent as View
        assertTrue(target.performClick())
    }
    private fun layout(root: View, width: Int, height: Int) {
        repeat(8) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
    private fun capture(root: View, width: Int, height: Int, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/theme-ui/notifications").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun assertAppearance(root: View, dark: Boolean) {
        val colors = AppColors(dark)
        val cards = walk(root).filterIsInstance<ViewGroup>().filter { it.tag == "notification_card" }
        assertEquals(4, cards.size)
        assertEquals(titles, cards.map { walk(it).filterIsInstance<TextView>().first().text.toString() })
        val icons = walk(root).filterIsInstance<ImageView>().filter { it.tag == "notification_icon" }
        assertEquals(5, icons.size)
        for (icon in icons) {
            assertEquals(colors.primary, icon.imageTintList!!.defaultColor)
            assertTrue("Icon contrast", ColorUtils.calculateContrast(icon.imageTintList!!.defaultColor, colors.surface) >= 3.0)
            assertEquals(26, icon.width - icon.paddingLeft - icon.paddingRight)
            assertEquals(26, icon.height - icon.paddingTop - icon.paddingBottom)
            assertFalse(icon.drawable.bounds.isEmpty)
            assertTrue(icon.drawable.bounds.width() <= 26 && icon.drawable.bounds.height() <= 26)
        }
        for (card in cards) {
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, card.layoutParams.height)
            for (i in 0 until card.childCount) {
                val child = card.getChildAt(i)
                assertTrue("Child outside card", child.left >= card.paddingLeft && child.right <= card.width - card.paddingRight)
                assertTrue("Child outside card", child.top >= card.paddingTop && child.bottom <= card.height - card.paddingBottom)
                if (i > 0) assertTrue("Overlapping card elements", card.getChildAt(i - 1).right <= child.left)
            }
            for (text in walk(card).filterIsInstance<TextView>()) {
                assertTrue("Text/arrow contrast", ColorUtils.calculateContrast(text.currentTextColor, colors.surface) >= 4.5)
            }
        }
        for (text in walk(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }) {
            val textLayout = text.layout ?: error("Text was not laid out")
            val width = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
            val height = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
            assertTrue("Clipped height: ${text.text}", textLayout.height <= height + 2)
            assertEquals("Truncated text: ${text.text}", text.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            for (line in 0 until textLayout.lineCount) {
                assertEquals("Ellipsized text: ${text.text}", 0, textLayout.getEllipsisCount(line))
                assertTrue("Clipped width: ${text.text}", textLayout.getLineMax(line) <= width + 2)
            }
        }
    }

    @Test fun lightLayoutsAtAllSizesAndFontScales() = layouts(ThemeMode.LIGHT, false)
    @Test fun darkLayoutsAtAllSizesAndFontScales() = layouts(ThemeMode.DARK, true)
    private fun layouts(mode: ThemeMode, dark: Boolean) {
        for ((width, height) in sizes) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-" + (if (dark) "night" else "notnight") + "-mdpi")
            RuntimeEnvironment.setFontScale(scale)
            ThemeSettings.save(app, mode)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val root = open(c.get())
                layout(root, width, height)
                assertAppearance(root, dark)
                if (scale == 1f) {
                    val cards = walk(root).filter { it.tag == "notification_card" }
                    assertTrue("Oversized cards at normal font", cards.all { it.height <= 96 })
                    val master = walk(root).single { it.tag == "notification_master" }
                    assertEquals("Oversized master card", 76, master.height)
                    val scroll = walk(root).filterIsInstance<ScrollView>().single()
                    assertTrue("Excessive scrolling at normal font", scroll.getChildAt(0).height <= height + 240)
                }
                capture(root, width, height, "${mode.storedValue}-${width}x${height}-font$scale")
                // A long user-selected ringtone title must also wrap completely.
                val card = walk(root).filterIsInstance<ViewGroup>().single {
                    it.tag == "notification_card" && walk(it).filterIsInstance<TextView>().first().text.toString() == "Звук уведомления"
                }
                walk(card).filterIsInstance<TextView>()[1].text = "Очень длинное название выбранного звука уведомления пользователя"
                layout(root, width, height)
                assertAppearance(root, dark)
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun notificationsMatchThemeAndCityCardProportions() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            ThemeSettings.save(app, mode)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val a = c.get()
                invoke(a, "showSettingsDialog"); clickText(panel(a), "Тема приложения")
                layout(panel(a), 360, 800)
                val theme = walk(panel(a)).filterIsInstance<RadioButton>().first()
                invoke(a, "showSettingsDialog"); clickText(panel(a), "Город")
                layout(panel(a), 360, 800)
                val city = walk(panel(a)).filterIsInstance<RadioButton>().first()
                val root = open(a); layout(root, 360, 800)
                val cards = walk(root).filter { it.tag == "notification_card" }
                for (card in cards + walk(root).single { it.tag == "notification_master" }) {
                    assertEquals(theme.height, card.height)
                    for (reference in listOf(theme, city)) {
                        assertEquals(reference.paddingLeft, card.paddingLeft)
                        assertEquals(reference.paddingTop, card.paddingTop)
                        assertEquals(reference.paddingRight, card.paddingRight)
                        assertEquals(reference.paddingBottom, card.paddingBottom)
                        assertEquals((reference.background as GradientDrawable).cornerRadius,
                            (card.background as GradientDrawable).cornerRadius, 0f)
                    }
                }
                val themeGap = (theme.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
                val cityGap = (city.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
                for ((previous, next) in cards.zipWithNext()) {
                    assertEquals(themeGap, next.top - previous.bottom)
                    assertEquals(cityGap, next.top - previous.bottom)
                }
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun systemModeTracksBothDeviceThemesAtAllSizes() {
        for ((width, height) in sizes) for (dark in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-" + (if (dark) "night" else "notnight") + "-mdpi")
            ThemeSettings.save(app, ThemeMode.SYSTEM)
            val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                val root = open(c.get()); layout(root, width, height); assertAppearance(root, dark)
            } finally { c.pause().stop().destroy() }
        }
    }

    @Test fun deviceThemeChangeRecolorsOpenNotificationsWithoutAppRelaunch() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-notnight-mdpi")
        ThemeSettings.save(app, ThemeMode.SYSTEM)
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            open(c.get())
            for (dark in listOf(true, false)) {
                val config = Configuration(c.get().resources.configuration)
                config.uiMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                c.configurationChange(config)
                assertSame(app, c.get().application)
                assertEquals("notifications", ReflectionHelpers.getField<String>(c.get(), "panelRoute"))
                val root = panel(c.get()); layout(root, 360, 800); assertAppearance(root, dark)
            }
        } finally { c.pause().stop().destroy() }
    }

    @Test fun themeSelectorUpdatesNotificationColorsAndKeepsChosenSettings() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val keys = listOf("notifications_enabled", "notify_before_min", "notify_asr", "notification_sound_uri", "city")
            val before = keys.associateWith { prefs.all[it] }
            for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
                invoke(c.get(), "showSettingsDialog"); clickText(panel(c.get()), "Тема приложения")
                walk(panel(c.get())).filterIsInstance<RadioButton>().single { it.text.startsWith(mode.title) }.performClick()
                c.recreate()
                assertSame(app, c.get().application)
                val root = open(c.get()); layout(root, 360, 800)
                assertAppearance(root, ThemeSettings.isDark(c.get()))
                assertEquals(mode, ThemeSettings.read(app))
                assertEquals(before, keys.associateWith { prefs.all[it] })
            }
        } finally { c.pause().stop().destroy() }
    }

    @Test fun originalPreferenceActionsAndNavigationRemainAvailable() {
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a = c.get()
            clickText(open(a), "Когда напоминать")
            walk(panel(a)).filterIsInstance<RadioButton>().single { it.text.toString() == "За 30 минут" }.performClick()
            assertEquals(30, prefs.getInt("notify_before_min", -1))
            assertEquals("notifications", ReflectionHelpers.getField<String>(a, "panelRoute"))
            clickText(panel(a), "Намазы для уведомлений")
            assertEquals("prayers", ReflectionHelpers.getField<String>(a, "panelRoute"))
            clickText(open(a), "Звук уведомления")
            assertEquals("sound", ReflectionHelpers.getField<String>(a, "panelRoute"))
            val root = open(a)
            val toggle = walk(root).filterIsInstance<Switch>().single()
            assertFalse(toggle.isChecked)
            toggle.isChecked = true
            assertTrue(prefs.getBoolean("notifications_enabled", false))
            toggle.isChecked = false
            assertFalse(prefs.getBoolean("notifications_enabled", true))
            assertEquals("content://example/sound", prefs.getString("notification_sound_uri", null))
            assertFalse(prefs.getBoolean("notify_asr", true))
            assertTrue(walk(root).filterIsInstance<TextView>().any { it.text.toString() == "Точные уведомления" })
        } finally { c.pause().stop().destroy() }
    }
}
