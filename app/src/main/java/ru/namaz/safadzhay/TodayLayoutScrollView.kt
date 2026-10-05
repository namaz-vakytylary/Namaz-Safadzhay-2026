package ru.namaz.safadzhay

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import kotlin.math.roundToInt

/** Fits the measured Today content, then enables scrolling only for real overflow.
 * Geometry is resolved synchronously in one bounded measure pass; themes, artwork,
 * text sizes (except the existing timer autosize), and progress are left untouched.
 */
internal class TodayLayoutScrollView(context: Context) : ScrollView(context) {
    private data class Content(
        val root: LinearLayout, val mode: LinearLayout, val banner: LinearLayout,
        val card: FrameLayout, val artwork: ImageView, val copy: LinearLayout,
        val prayers: LinearLayout, val city: TextView, val date: TextView,
        val clock: TextView, val isToday: () -> Boolean
    )
    // Ordered reductions: empty space, padding, large blocks, prayer rows, timer.
    private data class Geometry(
        val top: Int, val bottom: Int, val bannerGap: Int, val tabTop: Int,
        val tabBottom: Int, val cardGap: Int, val prayerGap: Int, val timerGap: Int,
        val datePadding: Int, val cityHeight: Int, val clockHeight: Int,
        val rowPadding: Int, val copyPadding: Int, val bannerHeight: Int,
        val cardHeight: Int, val rowHeight: Int, val iconHeight: Int, val timerHeight: Int
    )
    private val geometry = listOf(
        Geometry(6,8,7,8,7,7,6,6, 3,29,29,8,32,95,200,66,42,60),
        Geometry(2,4,1,1,1,2,1,1, 3,29,29,8,32,95,200,66,42,60),
        Geometry(2,4,1,1,1,2,1,1, 0,23,22,4,12,95,200,66,42,60),
        Geometry(2,4,1,1,1,2,1,1, 0,23,22,4,12,64,0,66,42,60),
        Geometry(2,4,1,1,1,2,1,1, 0,23,22,1,12,64,0,40,34,60),
        Geometry(1,4,1,1,1,1,1,0, 0,23,22,1,12,64,0,40,34,44)
    )
    private var content: Content? = null
    private var fitting = false
    private var fittedHeight: Int? = null
    private var possibleClick = false
    private var touchX = 0f
    private var touchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var contentInsets: Insets = Insets.NONE
        set(value) { if (field != value) { field = value; requestLayout() } }
    var compactLevel: Int = 0
        private set
    var geometryPosition: Float = 0f
        private set
    var scrollingEnabled: Boolean = true
        private set
    var measuredContentHeight: Int = 0
        private set

    fun bind(root: LinearLayout, mode: LinearLayout, banner: LinearLayout,
             card: FrameLayout, artwork: ImageView, copy: LinearLayout,
             prayers: LinearLayout, city: TextView, date: TextView,
             clock: TextView, isToday: () -> Boolean) {
        content = Content(root, mode, banner, card, artwork, copy, prayers, city, date, clock, isToday)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun height(view: View, value: Int) {
        // Avoid scheduling a second pass while applying a candidate inside measure.
        view.layoutParams.height = value
    }
    private fun margins(view: View, top: Int, bottom: Int) {
        (view.layoutParams as LinearLayout.LayoutParams).apply { topMargin = top; bottomMargin = bottom }
    }
    private fun padding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom)
            view.setPadding(left, top, right, bottom)
    }
    private fun forceTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (index in 0 until view.childCount) forceTree(view.getChildAt(index))
    }
    override fun requestLayout() { if (!fitting) super.requestLayout() }

    private fun configure(c: Content, position: Float, width: Int) {
        val index = position.toInt().coerceIn(0, geometry.lastIndex)
        val from = geometry[index]
        val to = geometry[minOf(index + 1, geometry.lastIndex)]
        val fraction = position - index
        fun px(value: (Geometry) -> Int) = ((value(from) + (value(to) - value(from)) * fraction) * resources.displayMetrics.density).roundToInt()
        padding(c.root, contentInsets.left + dp(16), contentInsets.top + px { it.top },
            contentInsets.right + dp(16), contentInsets.bottom + px { it.bottom })
        c.city.minHeight = px { it.cityHeight }; c.clock.minHeight = px { it.clockHeight }
        padding(c.date, 0, px { it.datePadding }, 0, px { it.datePadding })
        margins(c.banner, px { it.bannerGap }, 0)
        margins(c.mode, px { it.tabTop }, px { it.tabBottom })
        margins(c.card, 0, px { it.cardGap })
        for (i in 0 until c.prayers.childCount) {
            val row = c.prayers.getChildAt(i) as ViewGroup
            height(row.getChildAt(0), px { it.iconHeight })
            padding(row, dp(12), px { it.rowPadding }, dp(12), px { it.rowPadding })
            row.minimumHeight = px { it.rowHeight }
            margins(row, 0, if (i == c.prayers.childCount - 1) 0 else px { it.prayerGap })
        }
        val timer = c.copy.getChildAt(2)
        @Suppress("DEPRECATION")
        val readableTimer = if (resources.configuration.fontScale > 1f) (58 * resources.displayMetrics.scaledDensity).roundToInt() else 0
        height(timer, maxOf(px { it.timerHeight }, readableTimer))
        margins(timer, px { it.timerGap }, px { it.timerGap })
        val innerWidth = (width - c.root.paddingLeft - c.root.paddingRight).coerceAtLeast(1)
        val copyWidth = (innerWidth - dp(40)).coerceAtLeast(1)
        forceTree(c.copy)
        c.copy.measure(MeasureSpec.makeMeasureSpec(copyWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val image = c.artwork.visibility == View.VISIBLE
        // Never force copy into a height smaller than its actual text layout.
        // Match the artwork mask's 3.5dp stroke clearance + one physical
        // antialias pixel on EACH side. Rounding a total 8dp can leave one side
        // a pixel short at fractional density (e.g. 420dpi).
        val contourClearance = maxOf(dp(4), kotlin.math.ceil(3.5f * resources.displayMetrics.density + 1f).toInt())
        val minimum = c.copy.measuredHeight + if (image) 2 * contourClearance else px { it.copyPadding }
        val imagePreferred = if (index < 2) innerWidth / 3 else {
            val amount = ((position - 2f) / 1f).coerceIn(0f, 1f)
            (innerWidth / 3 * (1f - amount)).roundToInt()
        }
        height(c.card, maxOf(minimum, if (image) imagePreferred else px { it.cardHeight }))
        // Preserve banner text, including padding, at large accessibility scales.
        val text = (c.banner.getChildAt(0) as FrameLayout).getChildAt(1)
        forceTree(text)
        text.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        height(c.banner, maxOf(px { it.bannerHeight }, text.measuredHeight))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = content
        val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val available = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        fittedHeight = null
        if (c != null && width > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            fitting = true
            try {
                val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
                fun candidate(position: Float): Int {
                    configure(c, position, width)
                    forceTree(c.root)
                    c.root.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    return c.root.measuredHeight
                }
                geometryPosition = 0f
                var naturalHeight = candidate(0f)
                if (c.isToday() && naturalHeight > available) {
                    for (level in 1..geometry.lastIndex) {
                        naturalHeight = candidate(level.toFloat())
                        geometryPosition = level.toFloat()
                        if (naturalHeight <= available) {
                            // Smallest sufficient reduction within this stage, down to pixels.
                            var low = level - 1f; var high = level.toFloat()
                            repeat(8) {
                                val middle = (low + high) / 2f
                                if (candidate(middle) <= available) high = middle else low = middle
                            }
                            geometryPosition = high
                            naturalHeight = candidate(high)
                            break
                        }
                    }
                }
                compactLevel = kotlin.math.ceil(geometryPosition.toDouble()).toInt()
                measuredContentHeight = naturalHeight
                fittedHeight = naturalHeight
                // The final ScrollView measure must use the SAME intrinsic height as
                // fitting. Its unspecified-height hints/caches cannot add a few pixels.
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            } finally { fitting = false; fittedHeight = null }
            updateScrolling(!c.isToday() || c.root.measuredHeight > measuredHeight - paddingTop - paddingBottom)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            updateScrolling(true)
        }
    }

    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                         parentHeightMeasureSpec: Int, heightUsed: Int) {
        val resolved = fittedHeight
        if (child === content?.root && resolved != null) {
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            child.measure(getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width),
                MeasureSpec.makeMeasureSpec(resolved, MeasureSpec.EXACTLY))
        } else super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
    }

    private fun updateScrolling(enabled: Boolean) {
        if (scrollingEnabled != enabled) {
            scrollingEnabled = enabled
            isVerticalScrollBarEnabled = enabled
            isNestedScrollingEnabled = enabled
            if (!enabled) {
                possibleClick = false
                // ScrollView has no public abort API. The first ordinary zero
                // scroll replaces any fling; the immediate second one takes
                // its documented rapid-call path and aborts the animation.
                // Never use fling(0): its spline can have a zero duration.
                super.smoothScrollBy(0, 0)
                super.smoothScrollBy(0, 0)
                super.scrollTo(0, 0)
            }
        }
    }
    override fun onInterceptTouchEvent(ev: MotionEvent) = scrollingEnabled && super.onInterceptTouchEvent(ev)
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!scrollingEnabled) return false
        // Delegate dragging to ScrollView. If a click listener is ever supplied,
        // distinguish a tap from a drag before invoking the accessibility click.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = ev.x; touchY = ev.y; possibleClick = isClickable }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(ev.x - touchX) > touchSlop || kotlin.math.abs(ev.y - touchY) > touchSlop) possibleClick = false
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> possibleClick = false
            MotionEvent.ACTION_UP -> { if (possibleClick) performClick(); possibleClick = false }
        }
        return super.onTouchEvent(ev)
    }
    override fun performClick() = super.performClick()
    override fun onGenericMotionEvent(event: MotionEvent) = scrollingEnabled && super.onGenericMotionEvent(event)
    override fun executeKeyEvent(event: KeyEvent) = scrollingEnabled && super.executeKeyEvent(event)
    override fun fling(velocityY: Int) { if (scrollingEnabled) super.fling(velocityY) }
    override fun scrollTo(x: Int, y: Int) { if (scrollingEnabled) super.scrollTo(x,y) else super.scrollTo(0,0) }
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (scrollingEnabled) super.onOverScrolled(scrollX,scrollY,clampedX,clampedY)
        else super.onOverScrolled(0,0,true,true)
    }
    override fun canScrollVertically(direction: Int) = scrollingEnabled && super.canScrollVertically(direction)
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!scrollingEnabled) {
            info.isScrollable = false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!scrollingEnabled) event.isScrollable = false
    }
}
