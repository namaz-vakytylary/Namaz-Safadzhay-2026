package ru.namaz.safadzhay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Real Activity/window measurement. Representative rendered content is supplied
 * only by JVM tests; no preview date or clock is needed in production. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayLayoutTest {
    private fun <T> field(a: MainActivity, name: String): T = ReflectionHelpers.getField(a, name)
    private fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()

    @Test fun allContentFitsMeasuredWindowsWithoutScrolling() {
        val report = File("build/reports/today-layout").apply { mkdirs() }
        val rows = mutableListOf("theme,systemDark,tatar,state,width,height,navInset,compact,content,ishaBottom,safeBottom")
        val windows = listOf(Triple(320,640,24), Triple(360,640,48), Triple(320,720,24), Triple(360,720,48), Triple(360,800,48), Triple(390,844,24), Triple(412,915,48))
        for ((mode, dark) in listOf(ThemeMode.LIGHT to true, ThemeMode.DARK to false, ThemeMode.SYSTEM to false, ThemeMode.SYSTEM to true)) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-${if (dark) "night" else "notnight"}-mdpi")
            val app = RuntimeEnvironment.getApplication()
            TestNetwork.offline(app)
            for (tatar in listOf(false, true)) {
                app.getSharedPreferences("settings",0).edit().clear().putBoolean("notifications_enabled",false)
                    .putBoolean("show_tatar_names",tatar).commit()
                ThemeSettings.save(app,mode)
                val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                try {
                    val a = controller.get()
                    for (state in listOf("Фаджр", "Зухр", "Аср", "Магриб", "Иша", "day", "suhoor", "iftar", "after")) {
                        renderContent(a,state,tatar)
                        for ((width,height,nav) in windows) {
                            val scroll: TodayLayoutScrollView = field(a,"mainScroll")
                            ViewCompat.dispatchApplyWindowInsets(scroll, WindowInsetsCompat.Builder()
                                .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,24,0,0))
                                .setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.of(0,32,0,0))
                                .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,0,nav)).build())
                            measure(a,width,height)
                            val root = scroll.getChildAt(0)
                            val prayers: LinearLayout = field(a,"prayerList")
                            val isha = prayers.getChildAt(4)
                            val position = IntArray(2); isha.getLocationInWindow(position)
                            val name = "$mode/$dark/$tatar/$state/${width}x$height"
                            assertEquals(name,5,prayers.childCount)
                            assertEquals(name,0,scroll.scrollY)
                            assertFalse("$name scrolls down content=${root.height}, header=${field<View>(a,"headerBox").height}, banner=${field<View>(a,"ramadanCard").height}, prayers=${prayers.height}, card=${field<View>(a,"countdownCard").height}, pad=${root.paddingTop}/${root.paddingBottom}",scroll.canScrollVertically(1))
                            assertFalse("$name scrolls up",scroll.canScrollVertically(-1))
                            assertTrue("$name content ${root.height} exceeds ${scroll.height}",root.height<=scroll.height)
                            assertTrue("$name Isha under navigation bar",position[1]+isha.height<=height-nav-4)
                            assertTrue("$name top under cutout",root.paddingTop>=32)
                            assertTextFits(a,name)
                            val card: View = field(a,"countdownCard")
                            val copy: View = field(a,"heroCopy")
                            assertTrue("$name countdown text outside border",copy.top>=4 && copy.bottom<=card.height-4)
                            assertFalse("$name visible test label",walk(root).filterIsInstance<TextView>().any { it.text.contains("Тест UI") })
                            rows += "$mode,$dark,$tatar,$state,$width,$height,$nav,${scroll.compactLevel},${root.height},${position[1]+isha.height},${height-nav}"
                            if (width==360 && height in listOf(640,720) && tatar) snapshot(a,"$state-${mode.storedValue}-$dark-$height",width,height)
                        }
                    }
                } finally { controller.pause().stop().destroy() }
            }
        }
        report.resolve("matrix.csv").writeText(rows.joinToString("\n"))
    }

    private fun renderContent(a: MainActivity,state: String,tatar: Boolean) {
        // Keep the actual Today header, tabs and prayer rows. Vary the dynamic
        // banner/countdown copy and existing artwork exactly as the UI does.
        field<View>(a,"eventBanner").visibility=View.GONE
        field<View>(a,"holidayCard").visibility=View.GONE
        val banner: View = field(a,"ramadanCard")
        banner.visibility=if(state in listOf("day","suhoor","iftar","after")) View.VISIBLE else View.GONE
        banner.findViewWithTag<TextView>("ramadan_day").text="Сегодня 29-й день поста"
        field<TextView>(a,"countdownLabel").visibility=View.GONE
        field<TextView>(a,"currentTimeText").text="Сейчас 12:00"
        val title: TextView=field(a,"nextName")
        val timer: TextView=field(a,"countdown")
        val footer: TextView=field(a,"countdownStart")
        val image: ImageView=field(a,"ramadanCountdownBackground")
        image.visibility=if(state in listOf("suhoor","iftar","after")) View.VISIBLE else View.GONE
        title.text=when(state) {
            "suhoor"->"До окончания сухура"; "iftar"->"До ифтара"; "after"->"Время ифтара наступило"
            else-> {
                val name=if(state=="day") "Зухр" else state
                val translation=mapOf("Фаджр" to "Иртәнге намаз", "Зухр" to "Өйлә намазы", "Аср" to "Икенде намазы", "Магриб" to "Ахшам намазы", "Иша" to "Ястү намазы")
                if(tatar) "$name (${translation[name]})" else name
            }
        }
        timer.text=if(state=="after") "" else "23:59:59"
        footer.text=when(state) {
            "suhoor"->"Сухур заканчивается с началом Фаджра · 02:32"
            "iftar"->"Ифтар с наступлением Магриба · 19:47"
            "after"->""; else->"До начала намаза · 12:20"
        }
        if(image.visibility==View.VISIBLE) {
            image.setImageResource(when(state) { "suhoor"->R.drawable.ramadan_suhoor; "iftar"->R.drawable.ramadan_iftar; else->R.drawable.ramadan_iftar_started })
            image.scaleType=if(state=="after") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        field<CardProgressIndicator>(a,"progress").goldMode=state=="after"
    }

    @Test fun accessibilityKeepsTextReadableInsteadOfCroppingToForceFit() {
        for (scale in listOf(1.3f,1.5f,2f)) {
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-mdpi")
            val app=RuntimeEnvironment.getApplication(); TestNetwork.offline(app)
            val config=android.content.res.Configuration(app.resources.configuration).apply { fontScale=scale }
            @Suppress("DEPRECATION") app.resources.updateConfiguration(config,app.resources.displayMetrics)
            app.getSharedPreferences("settings",0).edit().clear().putBoolean("notifications_enabled",false).commit()
            val c=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
            try {
                for(state in listOf("day","suhoor","iftar","after")) {
                    renderContent(c.get(),state,true); measure(c.get(),360,720)
                    assertTextFits(c.get(),"font=$scale/$state")
                    val card: View=field(c.get(),"countdownCard"); val copy: View=field(c.get(),"heroCopy")
                    assertTrue(copy.top>=4 && copy.bottom<=card.height-4)
                }
            } finally { c.pause().stop().destroy() }
        }
    }

    internal fun assertTextFits(a: MainActivity, name: String) {
        for(text in walk(a.findViewById(android.R.id.content)).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }) {
            val layout=text.layout ?: continue
            assertTrue("$name clipped height ${layout.height}>${text.height-text.paddingTop-text.paddingBottom} width=${text.width}, card=${field<View>(a,"countdownCard").height}, copy=${field<View>(a,"heroCopy").height}: ${text.text}",layout.height<=text.height-text.paddingTop-text.paddingBottom)
            assertEquals("$name clipped lines: ${text.text}",text.text.length,layout.getLineEnd(layout.lineCount-1))
            for(line in 0 until layout.lineCount) assertEquals("$name ellipsis: ${text.text}",0,layout.getEllipsisCount(line))
        }
    }
    internal fun measure(a: MainActivity,width: Int,height: Int) {
        val root=a.findViewById<View>(android.R.id.content)
        repeat(6) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
        }
    }
    internal fun snapshot(a: MainActivity,name: String,width: Int,height: Int) {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        a.findViewById<View>(android.R.id.content).draw(Canvas(bitmap))
        File("build/reports/today-layout/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
