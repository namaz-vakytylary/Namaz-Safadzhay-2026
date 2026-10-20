package ru.namaz.safadzhay

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateAndAccessibilityTest {
    @Test fun tabTextContrastRemainsReadableInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = AppColors(dark)
            assertTrue(ColorUtils.calculateContrast(colors.onPrimary, colors.tabSelected) >= 4.5)
        }
    }
    @Test fun compassExposesUnavailableStateWithoutLiveAnnouncements() {
        val compass = QiblaCompassView(RuntimeEnvironment.getApplication())
        compass.setLocation(null)
        assertTrue(compass.isFocusable)
        assertTrue(compass.contentDescription.toString().contains("Кибл"))
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, compass.accessibilityLiveRegion)
    }
    @Test fun expandedTouchAreaDoesNotChangeBoundsOrStealNeighbour() {
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        val parent = FrameLayout(c.get())
        var first = 0; var second = 0
        val a = View(c.get()).apply { setOnClickListener { first++ } }
        val b = View(c.get()).apply { setOnClickListener { second++ } }
        parent.addView(a, FrameLayout.LayoutParams(32, 32).apply { leftMargin = 8; topMargin = 8 })
        parent.addView(b, FrameLayout.LayoutParams(32, 32).apply { leftMargin = 56; topMargin = 8 })
        c.get().setContentView(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 120, 60)
        TouchTargets.expand(a); TouchTargets.expand(b); shadowOf(Looper.getMainLooper()).idle()
        try {
            fun tap(x: Float) {
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val e = MotionEvent.obtain(0, 0, action, x, 6f, 0)
                    try { parent.dispatchTouchEvent(e) } finally { e.recycle() }
                }
            }
            tap(4f); tap(94f)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, first); assertEquals(1, second)
            assertEquals(32, a.width); assertEquals(32, b.width)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun fullyClippedControlDoesNotAcquireParentTouchDelegate() {
        val c = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(c.get())
            val button = View(c.get()).apply { setOnClickListener {} }
            parent.addView(button, FrameLayout.LayoutParams(32, 32).apply { leftMargin = 200 })
            c.get().setContentView(parent)
            parent.measure(View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 120, 60)
            TouchTargets.expand(button)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(parent.touchDelegate)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun firstLaunchOfflineHasNoInventedTimesAndOffersRetry() {
        val app = RuntimeEnvironment.getApplication()
        TestNetwork.offline(app)
        app.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-schedules") }?.forEach { it.delete() }
        val repo = ScheduleRepository(app, { _, _ -> throw java.io.IOException("offline") })
        org.robolectric.util.ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
        RuntimeEnvironment.setFontScale(2f)
        val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val label = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(c.get(), "countdownLabel")
            assertTrue(label.text.toString().contains("Подключитесь к интернету"))
            assertTrue(label.isClickable); assertEquals(Int.MAX_VALUE, label.maxLines)
            assertTrue(repo.days("moscow").isEmpty()); assertTrue(repo.days("safadzhay").isEmpty())
            val retry = c.get().findViewById<android.view.View>(android.R.id.content).findViewWithTag<android.widget.TextView>("schedule_retry")
            assertEquals(View.VISIBLE, retry.visibility)
            assertEquals("Повторить", retry.text.toString())
            assertTrue(retry.performClick())
            assertTrue(label.performClick())
        } finally { c.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
    }

}
