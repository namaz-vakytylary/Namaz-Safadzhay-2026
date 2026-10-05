package ru.namaz.safadzhay

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeAndRamadanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("settings", 0)

    @Before fun setup() {
        prefs.edit().clear().putBoolean("notifications_enabled", false).commit()
        TestNetwork.offline(app)
        HolidayCalendar.setRemote(2026, null)
    }
    private fun systemDark(dark: Boolean) {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-" + (if (dark) "night" else "notnight") + "-mdpi")
    }
    private fun start() = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    private fun invoke(a: MainActivity, name: String) = ReflectionHelpers.callInstanceMethod<Unit>(a, name)
    private fun panel(a: MainActivity): View = ReflectionHelpers.getField<Dialog>(a, "settingsPanel").findViewById(android.R.id.content)
    private fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
    private fun clickText(root: View, text: String) {
        var target = walk(root).filterIsInstance<TextView>().first { it.text.toString() == text } as View
        while (!target.isClickable && target.parent is View) target = target.parent as View
        assertTrue(target.performClick())
    }
    private fun assertTheme(a: MainActivity, dark: Boolean) {
        val actual = ReflectionHelpers.getField<AppColors>(a, "palette")
        assertEquals(dark, actual.isDark)
        assertEquals(AppColors(dark).background,
            (ReflectionHelpers.getField<View>(a, "mainScroll").background as android.graphics.drawable.ColorDrawable).color)
    }
    private fun screenshot(root: View, name: String) {
        repeat(8) {
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/theme-ui").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun systemLight() = checkMode(ThemeMode.SYSTEM, false, false)
    @Test fun systemDark() = checkMode(ThemeMode.SYSTEM, true, true)
    @Test fun forcedLightOnDarkDevice() = checkMode(ThemeMode.LIGHT, true, false)
    @Test fun forcedDarkOnLightDevice() = checkMode(ThemeMode.DARK, false, true)
    private fun checkMode(mode: ThemeMode, system: Boolean, expected: Boolean) {
        systemDark(system); ThemeSettings.save(app, mode)
        val c = start()
        try { assertTheme(c.get(), expected) } finally { c.pause().stop().destroy() }
    }
    @Test fun newInstallAndUnknownSettingUseSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.read(app))
        prefs.edit().putString(ThemeSettings.KEY, "unsupported").commit()
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.read(app))
    }
    @Test fun themePersistsAcrossLaunchWithoutChangingExistingPreferences() {
        prefs.edit().putString("city", "Москва").putBoolean("notifications_enabled", true)
            .putInt("notify_before_min", 15).putBoolean("show_tatar_names", false)
            .putString("notification_sound_uri", "content://example/sound").putBoolean("notify_asr", false).commit()
        val before = prefs.all.toMap()
        ThemeSettings.save(app, ThemeMode.LIGHT)
        var c = start(); assertTheme(c.get(), false); c.pause().stop().destroy()
        c = start()
        try {
            assertTheme(c.get(), false)
            assertEquals("Москва", ReflectionHelpers.getField<String>(c.get(), "selectedCity"))
            for ((key, value) in before) assertEquals("Preference $key", value, prefs.all[key])
        } finally { c.pause().stop().destroy() }
    }
    @Test fun systemConfigurationChangeRebuildsScreenAndKeepsNavigation() {
        systemDark(false)
        val c = start()
        try {
            invoke(c.get(), "showSettingsDialog")
            val config = Configuration(c.get().resources.configuration)
            config.uiMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_YES
            c.configurationChange(config)
            assertTheme(c.get(), true)
            assertTrue(walk(panel(c.get())).filterIsInstance<TextView>().any { it.text == "Настройки" })
        } finally { c.pause().stop().destroy() }
    }
    @Test fun selectorHasThreeDescriptionsAndSelectedRadioAndPersistsClick() {
        systemDark(false)
        val c = start()
        try {
            invoke(c.get(), "showSettingsDialog"); clickText(panel(c.get()), "Тема приложения")
            val radios = walk(panel(c.get())).filterIsInstance<RadioButton>()
            assertEquals(3, radios.size)
            assertEquals(listOf("Системная\nКак в настройках устройства", "Светлая\nВсегда светлая тема", "Тёмная\nВсегда тёмная тема"), radios.map { it.text.toString() })
            assertTrue(radios[0].isChecked); assertEquals(1, radios.count { it.isChecked })
            radios[2].performClick()
            assertEquals(ThemeMode.DARK, ThemeSettings.read(app))
            c.recreate()
            assertTheme(c.get(), true)
            assertTrue(walk(panel(c.get())).filterIsInstance<RadioButton>().single { it.isChecked }.text.startsWith("Тёмная"))
        } finally { c.pause().stop().destroy() }
    }

    @Test fun lightScreenCoverage() = screens(ThemeMode.LIGHT)
    @Test fun darkScreenCoverage() = screens(ThemeMode.DARK)
    private fun screens(mode: ThemeMode) {
        ThemeSettings.save(app, mode)
        val c = start(); val a = c.get(); val suffix = mode.storedValue
        try {
            screenshot(a.findViewById(android.R.id.content), "today-$suffix")
            ReflectionHelpers.setField(a, "scheduleTabSelected", true); invoke(a, "update")
            screenshot(a.findViewById(android.R.id.content), "schedule-$suffix")
            invoke(a, "showQiblaCompass"); screenshot(panel(a), "qibla-$suffix")
            // Verify cardinal letters, needle and Kaaba colours with a deterministic
            // heading/location fixture; this is not a physical sensor test.
            val compass = ReflectionHelpers.getField<QiblaCompassView>(a, "activeCompass")
            ReflectionHelpers.setField(compass, "initialized", true)
            compass.setLocation(android.location.Location("ui-fixture").apply {
                latitude = 55.75; longitude = 37.61
            })
            screenshot(panel(a), "compass-fixture-$suffix")
            invoke(a, "showSettingsDialog"); screenshot(panel(a), "settings-$suffix")
            invoke(a, "showThemeSelector"); screenshot(panel(a), "selector-$suffix")
            invoke(a, "showSettingsDialog"); clickText(panel(a), "Уведомления")
            screenshot(panel(a), "notifications-$suffix")
            clickText(panel(a), "Когда напоминать")
            assertTrue(walk(panel(a)).filterIsInstance<RadioButton>().all { it.currentTextColor == AppColors(mode == ThemeMode.DARK).onSurface })
            screenshot(panel(a), "lead-$suffix")
            invoke(a, "showSettingsDialog"); clickText(panel(a), "Город")
            screenshot(panel(a), "city-$suffix")
            invoke(a, "showAboutDialog")
            val about = ReflectionHelpers.getField<Dialog>(a, "aboutDialog")
            screenshot(about.findViewById(android.R.id.content), "about-$suffix")
            val palette = AppColors(mode == ThemeMode.DARK)
            assertEquals(palette.secondaryText, ReflectionHelpers.getField<TextView>(a, "dateText").currentTextColor)
            assertTheme(a, mode == ThemeMode.DARK)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun lightTextContrastAndLegacyDarkPalette() {
        val light = AppColors(false)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(light.onSurface, light.surface) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(light.secondaryText, light.surface) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(light.primary, light.activePrayer) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(light.onPrimary, light.tabSelected) >= 4.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(light.ramadanAccent, light.iftarSurface) >= 4.5)
        val dark = AppColors(true)
        assertEquals(Color.rgb(2,30,22), dark.background)
        assertEquals(Color.rgb(8,52,39), dark.surface)
        assertEquals(Color.rgb(10,78,54), dark.activePrayer)
        assertEquals(Color.rgb(70,218,145), dark.primary)
    }
    @Test fun previewBoundaryAndReleaseIsolation() {
        val before = LocalDate.of(2026,8,9); val start = before.plusDays(1)
        val realNow = LocalDateTime.of(2026,10,4,15,0)
        prefs.edit().putString("debug_ramadan_ui_scene", "iftar").commit()
        assertNull(RamadanUiPreview.ramadanDay(before))
        if (RamadanUiPreview.available) {
            assertEquals(1, RamadanUiPreview.ramadanDay(start))
            assertEquals(30, RamadanUiPreview.ramadanDay(start.plusDays(29)))
            assertNull(RamadanUiPreview.ramadanDay(start.plusDays(30)))
            assertEquals(start, RamadanUiPreview.now(app, realNow).toLocalDate())
        } else {
            assertNull(RamadanUiPreview.ramadanDay(start))
            assertEquals(realNow, RamadanUiPreview.now(app, realNow))
            assertTrue(RamadanUiPreview.scenes.isEmpty())
            assertNull(RamadanUiPreview.selected(app).time)
        }
        assertNull(HolidayCalendar.ramadanDay(start))
        assertNull(HolidayCalendar.holidayFor(start))
        assertEquals(1, HolidayCalendar.ramadanDay(LocalDate.of(2026,2,19)))
        assertNull(HolidayCalendar.ramadanDay(LocalDate.of(2026,3,20)))
    }
    @Test fun ramadanLightDarkAndSystemScenes() {
        if (!RamadanUiPreview.available) return
        for ((mode, dark) in listOf(ThemeMode.LIGHT to false, ThemeMode.DARK to true, ThemeMode.SYSTEM to false, ThemeMode.SYSTEM to true)) {
            systemDark(dark); ThemeSettings.save(app, mode)
            for (key in listOf("before", "suhoor", "iftar", "after")) {
                RamadanUiPreview.select(app, RamadanUiPreview.scenes.single { it.key == key })
                val c = start(); val a = c.get()
                try {
                    assertTheme(a, dark)
                    val card = ReflectionHelpers.getField<LinearLayout>(a, "ramadanCard")
                    assertEquals(if (key == "before") View.GONE else View.VISIBLE, card.visibility)
                    val next = ReflectionHelpers.getField<TextView>(a, "nextName")
                    if (key == "suhoor") assertEquals("До окончания сухура", next.text)
                    if (key == "iftar") assertEquals("До ифтара", next.text)
                    if (key == "after") assertEquals("Время ифтара наступило", next.text)
                    screenshot(a.findViewById(android.R.id.content), "ramadan-$key-${mode.storedValue}-$dark")
                    if (key != "before") {
                        assertRamadanArtwork(a, key, dark)
                        assertArtworkClipping(a, "ramadan-$key-${mode.storedValue}-$dark")
                    }
                    if (key == "iftar") {
                        ReflectionHelpers.setField(a, "scheduleTabSelected", true); invoke(a, "update")
                        screenshot(a.findViewById(android.R.id.content), "ramadan-schedule-${mode.storedValue}-$dark")
                        assertEquals("Сегодня 1-й день поста", card.findViewWithTag<TextView>("ramadan_day").text.toString())
                    }
                } finally { c.pause().stop().destroy() }
            }
        }
    }

    private fun assertArtworkClipping(a: MainActivity, name: String) {
        val image = ReflectionHelpers.getField<ImageView>(a, "ramadanCountdownBackground")
        val card = ReflectionHelpers.getField<View>(a, "countdownCard")
        assertClippedImage(image, name)
        val position = IntArray(2)
        card.getLocationInWindow(position)
        File("build/reports/theme-ui").resolve("$name-bounds.txt").writeText(
            "${position[0]},${position[1]},${card.width},${card.height}"
        )
        val composite = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(composite))
        File("build/reports/theme-ui").resolve("$name-card.png").outputStream().use {
            composite.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        composite.recycle()
    }

    @Test fun artworkClipRespectsInnerBorderAcrossDensitiesAndSizes() {
        for (qualifier in listOf("mdpi", "hdpi", "xhdpi", "xxxhdpi")) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-notnight-$qualifier")
            val density = app.resources.displayMetrics.density
            for (widthDp in listOf(328, 480)) {
                for (asset in listOf(R.drawable.ramadan_suhoor, R.drawable.ramadan_iftar, R.drawable.ramadan_iftar_started)) {
                    val image = RamadanCountdownImageView(app).apply {
                        scaleType = if (asset == R.drawable.ramadan_iftar_started)
                            ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                        setImageResource(asset)
                    }
                    val width = (widthDp * density).toInt()
                    image.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(width / 3, View.MeasureSpec.EXACTLY))
                    image.layout(0, 0, width, width / 3)
                    assertClippedImage(image, "clip-$qualifier-$widthDp-$asset")
                }
            }
        }
    }

    private fun assertClippedImage(image: ImageView, name: String) {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        image.draw(Canvas(bitmap))
        val density = image.resources.displayMetrics.density
        val inset = density * 3.5f
        val radius = minOf(density * 14.5f, (image.width - 2f * inset) / 2f,
            (image.height - 2f * inset) / 2f).coerceAtLeast(0f)
        // Independently check the inner stroke contour at every pixel centre,
        // including all four arcs. This catches rectangular/outer-only clipping.
        var visible = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val px = x + 0.5f; val py = y + 0.5f
            val inBounds = px >= inset && px < image.width - inset &&
                py >= inset && py < image.height - inset
            val cx = px.coerceIn(inset + radius, image.width - inset - radius)
            val cy = py.coerceIn(inset + radius, image.height - inset - radius)
            val inside = inBounds && (px - cx) * (px - cx) + (py - cy) * (py - cy) <= radius * radius
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (!inside) assertEquals("$name artwork outside inner border at ($x,$y)", 0, alpha)
            if (alpha > 0) visible++
        }
        assertTrue("Artwork must remain visible inside the border", visible > bitmap.width * bitmap.height / 2)
        File("build/reports/theme-ui").apply { mkdirs() }.resolve("$name-artwork.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun assertRamadanArtwork(a: MainActivity, scene: String, dark: Boolean) {
        val colors = AppColors(dark)
        val banner = ReflectionHelpers.getField<LinearLayout>(a, "ramadanCard")
        val header = walk(banner).filterIsInstance<ImageView>().single()
        assertTrue(header.isShown)
        assertEquals(R.drawable.ramadan_header, org.robolectric.Shadows.shadowOf(header.drawable).createdFromResId)
        assertNull(header.colorFilter)
        assertEquals(ImageView.ScaleType.CENTER_CROP, header.scaleType)
        assertEquals(colors.ramadanImageAccent,
            walk(banner).filterIsInstance<TextView>().single { it.text == "Рамадан" }.currentTextColor)
        val day = banner.findViewWithTag<TextView>("ramadan_day")
        assertEquals("Сегодня 1-й день поста", day.text.toString())
        assertFalse(walk(a.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .any { it.text.contains("Тест UI") })
        val clock = ReflectionHelpers.getField<TextView>(a, "currentTimeText")
        assertTrue(clock.text.matches(Regex("Сейчас \\d{2}:\\d{2}")))
        assertEquals(colors.ramadanHeaderText, day.currentTextColor)

        val image = ReflectionHelpers.getField<ImageView>(a, "ramadanCountdownBackground")
        val expected = when (scene) {
            "suhoor" -> R.drawable.ramadan_suhoor
            "iftar" -> R.drawable.ramadan_iftar
            else -> R.drawable.ramadan_iftar_started
        }
        assertTrue(image.isShown)
        assertEquals(expected, org.robolectric.Shadows.shadowOf(image.drawable).createdFromResId)
        assertNull(image.colorFilter)
        assertEquals(if (scene == "after") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER, image.scaleType)
        val card = ReflectionHelpers.getField<View>(a, "countdownCard")
        assertTrue("Artwork card retains its preferred height or expands for readable text", card.height >= card.width / 3)
        val copy = ReflectionHelpers.getField<View>(a, "heroCopy")
        assertTrue("Countdown copy remains inside the progress contour", copy.top >= 4 && copy.bottom <= card.height - 4)
        assertEquals(card.width, image.width)
        assertEquals(card.height, image.height)
        val title = ReflectionHelpers.getField<TextView>(a, "nextName")
        assertEquals(colors.onRamadanImage, title.currentTextColor)
        val timer = ReflectionHelpers.getField<TextView>(a, "countdown")
        val subtitle = ReflectionHelpers.getField<TextView>(a, "countdownStart")
        if (scene == "after") {
            assertNotEquals(org.robolectric.Shadows.shadowOf(header.drawable).createdFromResId,
                org.robolectric.Shadows.shadowOf(image.drawable).createdFromResId)
            val matrix = FloatArray(9)
            image.imageMatrix.getValues(matrix)
            val source = image.drawable
            val scale = maxOf(image.width.toFloat() / source.intrinsicWidth,
                image.height.toFloat() / source.intrinsicHeight)
            assertEquals(scale, matrix[android.graphics.Matrix.MSCALE_X], 0.001f)
            assertEquals(scale, matrix[android.graphics.Matrix.MSCALE_Y], 0.001f)
            assertEquals(((image.width - source.intrinsicWidth * scale) / 2f).roundToInt().toFloat(),
                matrix[android.graphics.Matrix.MTRANS_X], 1f)
            assertEquals(((image.height - source.intrinsicHeight * scale) / 2f).roundToInt().toFloat(),
                matrix[android.graphics.Matrix.MTRANS_Y], 1f)
            assertTrue(ReflectionHelpers.getField<CardProgressIndicator>(a, "progress").goldMode)
            assertEquals("", timer.text.toString())
            assertEquals("", subtitle.text.toString())
            val prayers = ReflectionHelpers.getField<LinearLayout>(a, "prayerList")
            val maghrib = (0 until prayers.childCount).map { prayers.getChildAt(it) }.single { row ->
                walk(row).filterIsInstance<TextView>().any { it.text == "Магриб" }
            }
            assertEquals(colors.iftarSurface, (maghrib.background as GradientDrawable).color!!.defaultColor)
            assertTrue(walk(maghrib).filterIsInstance<TextView>().any { it.currentTextColor == colors.ramadanAccent })
        } else {
            assertTrue(timer.isShown)
            assertTrue(timer.text.matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
            assertEquals(colors.onRamadanImage, timer.currentTextColor)
            assertEquals(colors.onRamadanImage, subtitle.currentTextColor)
            val prefix = if (scene == "suhoor") "Сухур заканчивается с началом Фаджра" else "Ифтар с наступлением Магриба"
            assertTrue(subtitle.text.startsWith("$prefix · "))
        }
    }

    @Test fun previewClockDoesNotChangeNotificationDatesOrExistingSettings() {
        if (!RamadanUiPreview.available) return
        val before = prefs.all.toMap()
        RamadanUiPreview.select(app, RamadanUiPreview.scenes.single { it.key == "suhoor" })
        val c = start()
        try {
            val a = c.get()
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(a, "alarmExecutor")
                .submit {}.get(20, java.util.concurrent.TimeUnit.SECONDS)
            val signature = ReflectionHelpers.getField<String>(a, "lastAlarmSignature")
            assertTrue(signature.contains(LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString()))
            assertFalse(signature.contains("2026-08-10"))
            for ((key, value) in before) assertEquals(value, prefs.all[key])
            RamadanUiPreview.select(app, RamadanUiPreview.scenes.single { it.key == "iftar" })
            invoke(a, "update")
            assertEquals(signature, ReflectionHelpers.getField<String>(a, "lastAlarmSignature"))
        } finally { c.pause().stop().destroy() }
    }
}
