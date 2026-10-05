package ru.namaz.safadzhay

import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
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
import kotlin.math.roundToInt

/** Native Android measurement/touch regression tests, including fractional density
 * and narrow/fallback windows. No model names or mathematical-only fit assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveTodayWindowTest {
    private val helper = TodayLayoutTest()
    private fun scroll(a: MainActivity) = ReflectionHelpers.getField<TodayLayoutScrollView>(a,"mainScroll")
    private fun setInsets(view: TodayLayoutScrollView, nav: Int, gesture: Int, cutout: Int) {
        ViewCompat.dispatchApplyWindowInsets(view,WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,view.resources.displayMetrics.density.times(24).roundToInt(),0,nav))
            .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(),Insets.of(0,0,0,gesture))
            .setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.of(0,cutout,0,0)).build())
    }
    private fun assertStationary(view: TodayLayoutScrollView) {
        assertFalse(view.scrollingEnabled)
        assertFalse(view.isVerticalScrollBarEnabled)
        assertFalse(view.isNestedScrollingEnabled)
        val info=AccessibilityNodeInfo.obtain(); view.onInitializeAccessibilityNodeInfo(info)
        assertFalse(info.isScrollable); info.recycle()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(0,16,action,120f,if(action==MotionEvent.ACTION_DOWN) 400f else 100f,0)
            assertFalse(view.onInterceptTouchEvent(event))
            assertFalse(view.onTouchEvent(event)); event.recycle()
        }
        view.scrollTo(0,100); view.fling(1000); view.computeScroll()
        assertEquals(0,view.scrollY)
        assertFalse(view.canScrollVertically(1)); assertFalse(view.canScrollVertically(-1))
    }

    @Test fun actualBoundsAcrossDensityThemesNamingFontsAndWindowResizes() {
        val csv=mutableListOf("dpi,font,theme,dark,tatar,state,widthDp,heightDp,gesture,geometry,content,viewport,scroll,ishaBottom,safeBottom")
        val themeGeometry=mutableMapOf<List<Any>,List<Any>>()
        val shapes=listOf(Triple(320,640,false),Triple(360,680,false),Triple(390,844,true),Triple(412,915,true),Triple(360,420,false),Triple(600,360,true))
        for(dpi in listOf(160,420)) for(font in listOf(1f,1.5f))
            for((theme,dark) in listOf(ThemeMode.LIGHT to true,ThemeMode.DARK to false,ThemeMode.SYSTEM to false,ThemeMode.SYSTEM to true))
                for(tatar in listOf(false,true)) {
                    RuntimeEnvironment.setQualifiers("w360dp-h800dp-${if(dark) "night" else "notnight"}-${dpi}dpi")
                    val app=RuntimeEnvironment.getApplication();TestNetwork.offline(app)
                    RuntimeEnvironment.setFontScale(font)
                    app.getSharedPreferences("settings",0).edit().clear().putBoolean("notifications_enabled",false).putBoolean("show_tatar_names",tatar).commit()
                    ThemeSettings.save(app,theme)
                    val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
                    try {
                        val a=controller.get();val s=scroll(a)
                        assertEquals("Activity must use requested font scale",font,s.resources.configuration.fontScale,0.001f)
                        assertEquals("Activity must use requested density",dpi,s.resources.displayMetrics.densityDpi)
                        val density=s.resources.displayMetrics.density
                        fun px(dp:Int)=(dp*density).roundToInt()
                        for(state in listOf("Фаджр","day","suhoor","iftar","after")) {
                            helper.renderContent(a,state,tatar)
                            for((w,h,gesture) in shapes) {
                                val nav=if(gesture) 0 else px(48);val bottom=if(gesture) px(24) else nav
                                setInsets(s,nav,if(gesture) bottom else 0,px(32))
                                helper.measure(a,px(w),px(h))
                                val description="$dpi/$font/$theme/$dark/$tatar/$state/${w}x$h"
                                val root=s.getChildAt(0)
                                val prayers=ReflectionHelpers.getField<LinearLayout>(a,"prayerList")
                                assertEquals(description,5,prayers.childCount)
                                assertEquals(description,bottom,s.contentInsets.bottom)
                                assertTrue(description,root.paddingTop>=px(32))
                                helper.assertTextFits(a,description)
                                val card=ReflectionHelpers.getField<View>(a,"countdownCard")
                                val copy=ReflectionHelpers.getField<View>(a,"heroCopy")
                                assertTrue("$description copy inside outline",copy.top>=px(4) && copy.bottom<=card.height-px(4))
                                val isha=prayers.getChildAt(4);val xy=IntArray(2);isha.getLocationInWindow(xy)
                                val fitting=s.measuredContentHeight<=s.height
                                assertEquals(description,!fitting,s.scrollingEnabled)
                                if(font==1f && h>=640) assertTrue("$description standard phone must fit: ${s.measuredContentHeight}>${s.height}",fitting)
                                if(fitting) {
                                    assertStationary(s)
                                    assertTrue("$description Isha under system navigation",xy[1]+isha.height<=px(h)-bottom-px(4))
                                } else {
                                    assertTrue(description,s.canScrollVertically(1))
                                    assertTrue(description,s.isVerticalScrollBarEnabled)
                                    s.scrollTo(0,Int.MAX_VALUE)
                                    isha.getLocationInWindow(xy)
                                    assertTrue("$description fallback reaches complete Isha",xy[1]+isha.height<=px(h)-bottom-px(4))
                                    s.scrollTo(0,0)
                                }
                                val bounds=listOf(s.geometryPosition,root.height,card.height,isha.height)
                                val themeKey=listOf(dpi,font,tatar,state,w,h,gesture)
                                val previous=themeGeometry.putIfAbsent(themeKey,bounds+s.scrollingEnabled)
                                if(previous!=null) assertEquals("$description theme changed geometry",previous,bounds+s.scrollingEnabled)
                                repeat(2) {helper.measure(a,px(w),px(h));assertEquals("$description deterministic measure",bounds,listOf(s.geometryPosition,root.height,card.height,isha.height))}
                                csv+="$dpi,$font,$theme,$dark,$tatar,$state,$w,$h,$gesture,${s.geometryPosition},${s.measuredContentHeight},${s.height},${s.scrollingEnabled},${xy[1]+isha.height},${px(h)-bottom}"
                                if(dpi==160 && font==1f && theme in listOf(ThemeMode.LIGHT,ThemeMode.DARK) && tatar && h in listOf(640,844) && state!="Фаджр")
                                    helper.snapshot(a,"adaptive-$state-${theme.storedValue}-$h",px(w),px(h))
                            }
                        }
                    } finally {controller.pause().stop().destroy()}
                }
        File("build/reports/today-layout").apply {mkdirs()}.resolve("adaptive-matrix.csv").writeText(csv.joinToString("\n"))
    }

    @Test fun overflowToFitStopsExistingScrollAndFitToOverflowRestoresIt() {
        val app=RuntimeEnvironment.getApplication();TestNetwork.offline(app)
        app.getSharedPreferences("settings",0).edit().clear().putBoolean("notifications_enabled",false).commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val a=controller.get();val s=scroll(a);helper.renderContent(a,"suhoor",true)
            setInsets(s,48,0,32)
            helper.measure(a,360,360);assertTrue(s.scrollingEnabled)
            s.scrollTo(0,100);assertTrue(s.scrollY>0)
            helper.measure(a,360,915);assertStationary(s);assertEquals(0,s.compactLevel)
            helper.measure(a,360,360);assertTrue(s.scrollingEnabled);assertTrue(s.canScrollVertically(1))
            val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,120f,200f,0)
            assertTrue(s.onTouchEvent(down));down.recycle()
            val cancel=MotionEvent.obtain(0,16,MotionEvent.ACTION_CANCEL,120f,200f,0)
            s.onTouchEvent(cancel);cancel.recycle()
            var clicks=0; s.setOnClickListener { clicks++ }
            fun touch(action: Int, y: Float) {
                val event=MotionEvent.obtain(0,32,action,120f,y,0)
                s.onTouchEvent(event);event.recycle()
            }
            touch(MotionEvent.ACTION_DOWN,200f);touch(MotionEvent.ACTION_UP,200f)
            assertEquals(1,clicks)
            touch(MotionEvent.ACTION_DOWN,200f);touch(MotionEvent.ACTION_MOVE,100f);touch(MotionEvent.ACTION_UP,100f)
            assertEquals("Dragging must not report an accessibility click",1,clicks)
        } finally {controller.pause().stop().destroy()}
    }
}
