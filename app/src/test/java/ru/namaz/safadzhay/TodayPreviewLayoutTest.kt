package ru.namaz.safadzhay

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Existing debug fixture only; this file is deliberately not promoted to main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayPreviewLayoutTest {
    @Test fun ordinaryDayAtDifferentPrayerTimesFitsWithoutRamadanBanner() {
        if (!RamadanUiPreview.available) return
        val helper = TodayLayoutTest()
        val scene = RamadanUiPreview.scenes.first { it.key == "before" }
        val originalTime = scene.time
        val rows = mutableListOf("theme,systemDark,tatar,time,width,height,geometry")
        // Extend the existing ordinary-day fixture inside this JVM test only.
        // Neither the APK's scenes nor its prayer/calendar data are changed.
        try {
            for ((mode, dark) in listOf(ThemeMode.LIGHT to true, ThemeMode.DARK to false,
                ThemeMode.SYSTEM to false, ThemeMode.SYSTEM to true)) {
                for (tatar in listOf(false, true)) for (time in listOf("00:30", "12:00", "18:00", "20:15")) {
                    ReflectionHelpers.setField(scene, "time", LocalDate.of(2026, 8, 9).atTime(LocalTime.parse(time)))
                    RuntimeEnvironment.setQualifiers("w360dp-h800dp-${if (dark) "night" else "notnight"}-mdpi")
                    val app = RuntimeEnvironment.getApplication(); TestNetwork.offline(app)
                    app.getSharedPreferences("settings", 0).edit().clear().putBoolean("notifications_enabled", false)
                        .putBoolean("show_tatar_names", tatar).putString("debug_ramadan_ui_scene", "before").commit()
                    ThemeSettings.save(app, mode)
                    val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                    try {
                        val a = controller.get()
                        val scroll = ReflectionHelpers.getField<TodayLayoutScrollView>(a, "mainScroll")
                        for ((width, height, nav) in listOf(Triple(320,640,24), Triple(360,640,48), Triple(320,720,24),
                            Triple(360,720,48), Triple(360,800,48), Triple(390,844,24), Triple(412,915,48))) {
                            ViewCompat.dispatchApplyWindowInsets(scroll, WindowInsetsCompat.Builder()
                                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0,24,0,nav))
                                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0,32,0,0)).build())
                            helper.measure(a, width, height)
                            val description = "$mode/$dark/$tatar/$time/${width}x$height"
                            assertFalse(description, scroll.scrollingEnabled)
                            assertFalse(description, scroll.isVerticalScrollBarEnabled)
                            assertEquals(View.GONE, ReflectionHelpers.getField<View>(a, "ramadanCard").visibility)
                            assertEquals("Сейчас $time", ReflectionHelpers.getField<TextView>(a, "currentTimeText").text.toString())
                            helper.assertTextFits(a, description)
                            val prayers = ReflectionHelpers.getField<LinearLayout>(a, "prayerList")
                            assertEquals(5, prayers.childCount)
                            val isha = prayers.getChildAt(4); val position = IntArray(2); isha.getLocationInWindow(position)
                            assertTrue(description, position[1] + isha.height <= height - nav - 4)
                            rows += "$mode,$dark,$tatar,$time,$width,$height,${scroll.geometryPosition}"
                        }
                    } finally { controller.pause().stop().destroy() }
                }
            }
        } finally { ReflectionHelpers.setField(scene, "time", originalTime) }
        File("build/reports/today-layout").apply { mkdirs() }
            .resolve("ordinary-matrix.csv").writeText(rows.joinToString("\n"))
    }

    @Test fun actualPreviewStatesFitWithInsetsInEveryTheme() {
        if(!RamadanUiPreview.available) return
        val helper=TodayLayoutTest()
        val rows=mutableListOf("theme,systemDark,tatar,scene,width,height,compact,content,ishaBottom,safeBottom")
        for((mode,dark) in listOf(ThemeMode.LIGHT to true,ThemeMode.DARK to false,ThemeMode.SYSTEM to false,ThemeMode.SYSTEM to true)) {
            for(tatar in listOf(false,true)) for(scene in listOf("before","suhoor","fast","iftar","after")) {
                RuntimeEnvironment.setQualifiers("w360dp-h800dp-${if(dark) "night" else "notnight"}-mdpi")
                val app=RuntimeEnvironment.getApplication(); TestNetwork.offline(app)
                app.getSharedPreferences("settings",0).edit().clear().putBoolean("notifications_enabled",false)
                    .putBoolean("show_tatar_names",tatar).putString("debug_ramadan_ui_scene",scene).commit()
                ThemeSettings.save(app,mode)
                val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                try {
                    val a=c.get()
                    val scroll=ReflectionHelpers.getField<TodayLayoutScrollView>(a,"mainScroll")
                    for((width,height,nav) in listOf(Triple(320,640,24), Triple(360,640,48), Triple(320,720,24),Triple(360,720,48),Triple(360,800,48),Triple(390,844,24),Triple(412,915,48))) {
                        ViewCompat.dispatchApplyWindowInsets(scroll,WindowInsetsCompat.Builder()
                            .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,nav))
                            .setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.of(0,32,0,0)).build())
                        helper.measure(a,width,height)
                        val name="$mode/$dark/$tatar/$scene/${width}x$height"
                        val root=scroll.getChildAt(0)
                        assertFalse("$name scrolls",scroll.canScrollVertically(1))
                        assertFalse("$name accepts scrolling despite fitting",scroll.scrollingEnabled)
                        assertFalse("$name has unnecessary scrollbar",scroll.isVerticalScrollBarEnabled)
                        assertTrue("$name ${root.height}>${scroll.height}",root.height<=scroll.height)
                        val prayers=ReflectionHelpers.getField<LinearLayout>(a,"prayerList")
                        assertEquals(5,prayers.childCount)
                        val isha=prayers.getChildAt(4); val position=IntArray(2); isha.getLocationInWindow(position)
                        assertTrue("$name Isha obscured",position[1]+isha.height<=height-nav-4)
                        helper.assertTextFits(a,name)
                        val banner=ReflectionHelpers.getField<View>(a,"ramadanCard")
                        assertEquals(if(scene=="before") View.GONE else View.VISIBLE,banner.visibility)
                        val title=ReflectionHelpers.getField<TextView>(a,"nextName")
                        val expected=when(scene) {"suhoor"->"До окончания сухура";"iftar"->"До ифтара";"after"->"Время ифтара наступило";else->null}
                        if(expected!=null) assertEquals(expected,title.text.toString())
                        val art=ReflectionHelpers.getField<ImageView>(a,"ramadanCountdownBackground")
                        if(expected!=null) assertEquals(when(scene) {"suhoor"->R.drawable.ramadan_suhoor;"iftar"->R.drawable.ramadan_iftar;else->R.drawable.ramadan_iftar_started},Shadows.shadowOf(art.drawable).createdFromResId)
                        val clock=ReflectionHelpers.getField<TextView>(a,"currentTimeText")
                        assertFalse(clock.text.contains("Тест UI"))
                        if(scene=="after") assertEquals("Сейчас 19:50",clock.text.toString())
                        rows+="$mode,$dark,$tatar,$scene,$width,$height,${scroll.compactLevel},${root.height},${position[1]+isha.height},${height-nav}"
                        if(width==360 && height in listOf(640,720) && tatar) helper.snapshot(a,"preview-$scene-${mode.storedValue}-$dark-$height",width,height)
                    }
                } finally {c.pause().stop().destroy()}
            }
        }
        File("build/reports/today-layout").apply { mkdirs() }
            .resolve("preview-matrix.csv").writeText(rows.joinToString("\n"))
    }
}
