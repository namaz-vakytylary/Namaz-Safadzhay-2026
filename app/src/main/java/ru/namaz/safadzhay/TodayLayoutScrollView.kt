package ru.namaz.safadzhay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets

/** Fits Today to its measured window, including bars/cutouts, without hiding text.
 * Calendar and enlarged accessibility text retain the existing scroll container.
 * Artwork and the progress layer are never modified here.
 */
internal class TodayLayoutScrollView(context: Context) : ScrollView(context) {
    private data class Content(
        val root: LinearLayout, val mode: LinearLayout, val banner: LinearLayout,
        val card: FrameLayout, val artwork: ImageView, val copy: LinearLayout,
        val prayers: LinearLayout, val city: TextView, val date: TextView,
        val clock: TextView, val isToday: () -> Boolean
    )
    private var content: Content? = null
    private var layoutKey: List<Any?>? = null
    var contentInsets: Insets = Insets.NONE
        set(value) { if (field != value) { field = value; requestLayout() } }
    var compactLevel: Int = 0
        private set

    fun bind(root: LinearLayout, mode: LinearLayout, banner: LinearLayout,
             card: FrameLayout, artwork: ImageView, copy: LinearLayout,
             prayers: LinearLayout, city: TextView, date: TextView,
             clock: TextView, isToday: () -> Boolean) {
        content = Content(root, mode, banner, card, artwork, copy, prayers, city, date, clock, isToday)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun height(view: View, value: Int) {
        if (view.layoutParams.height != value) {
            view.layoutParams = view.layoutParams.apply { height = value }
        }
    }
    private fun margins(view: View, top: Int, bottom: Int) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        if (params.topMargin != top || params.bottomMargin != bottom) {
            params.topMargin = top; params.bottomMargin = bottom; view.layoutParams = params
        }
    }
    private fun padding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom)
            view.setPadding(left, top, right, bottom)
    }

    private fun configure(c: Content, level: Int) {
        val compact = level > 0
        val tight = level >= 2
        padding(c.root, contentInsets.left + dp(16), contentInsets.top + dp(if (level == 3) 3 else if (compact) 4 else 6),
            contentInsets.right + dp(16), contentInsets.bottom + dp(if (level == 3) 4 else if (compact) 6 else 8))
        c.city.minHeight = dp(if (level == 3) 24 else if (tight) 26 else 29)
        c.clock.minHeight = c.city.minHeight
        padding(c.date, 0, dp(if (tight) 1 else 3), 0, dp(if (tight) 1 else 3))
        margins(c.banner, dp(if (compact) 4 else 7), 0)
        margins(c.mode, dp(if (compact) 4 else 8), dp(if (compact) 4 else 7))
        margins(c.card, 0, dp(if (level == 3) 2 else if (compact) 4 else 7))
        height(c.banner, dp(95))
        val timer = c.copy.getChildAt(2)
        @Suppress("DEPRECATION")
        val timerHeight = maxOf(dp(if (tight) 56 else 60), (58 * resources.displayMetrics.scaledDensity).toInt())
        height(timer, timerHeight)
        margins(timer, dp(if (level == 3) 2 else if (compact) 4 else 6), dp(if (level == 3) 2 else if (compact) 4 else 6))
        val rowPadding = dp(if (level == 3) 4 else if (tight) 6 else 8)
        val rowMinimum = dp(if (level == 3) 50 else if (tight) 58 else 66)
        for (index in 0 until c.prayers.childCount) {
            val row = c.prayers.getChildAt(index)
            padding(row, dp(12), rowPadding, dp(12), rowPadding)
            row.minimumHeight = rowMinimum
            margins(row, 0, if (index == c.prayers.childCount - 1) 0 else dp(if (level == 3) 2 else if (tight) 3 else if (compact) 4 else 6))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = content
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val available = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        if (c != null && width > 0 && available > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Timer digits do not change geometry. Refit only for a changed window,
            // content/state, font scale, or newly rendered prayer rows.
            val key = listOf(width, available, resources.configuration.fontScale, contentInsets,
                c.isToday(), c.banner.visibility, c.card.visibility, c.artwork.visibility,
                c.city.text.toString(), c.date.text.toString(), c.prayers.getChildAt(0)) +
                (c.card.parent as ViewGroup).let { header ->
                    (0 until header.childCount).map { index ->
                        val child = header.getChildAt(index)
                        child.visibility to (child as? TextView)?.text?.toString()
                    }
                } + (0 until c.copy.childCount).map { index ->
                    val child = c.copy.getChildAt(index) as TextView
                    child.visibility to if (index == 2) child.text.length.toString() else child.text.toString()
                }
            if (key == layoutKey) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            layoutKey = key
            val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            fun measureContent() = c.root.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val image = c.artwork.visibility == View.VISIBLE
            var minimumCard = 0
            compactLevel = 0
            for (level in 0..if (c.isToday()) 3 else 0) {
                configure(c, level)
                val copyWidth = (width - c.root.paddingLeft - c.root.paddingRight - dp(40)).coerceAtLeast(1)
                (0 until c.copy.childCount).forEach { c.copy.getChildAt(it).forceLayout() }
                c.copy.forceLayout()
                c.copy.measure(MeasureSpec.makeMeasureSpec(copyWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                minimumCard = c.copy.measuredHeight + dp(if (image) 8 else if (level == 3) 12 else if (level > 0) 24 else 32)
                val preferred = if (image) (width - c.root.paddingLeft - c.root.paddingRight) / 3 else dp(174)
                height(c.card, maxOf(preferred, minimumCard))
                measureContent()
                if (!c.isToday() || c.root.measuredHeight <= available) { compactLevel = level; break }
                // Exhaust spare space inside the ordinary countdown before reducing rows.
                if (level > 0 && !image) {
                    val room = available - c.root.measuredHeight + c.card.layoutParams.height
                    height(c.card, maxOf(minimumCard, room.coerceAtMost(dp(174))))
                    measureContent()
                    if (c.root.measuredHeight <= available) { compactLevel = level; break }
                }
                compactLevel = level
            }
            if (c.isToday() && c.root.measuredHeight > available && c.banner.visibility == View.VISIBLE) {
                // Last resort: reduce only excess banner height. Its two text lines still fit.
                val overflow = c.root.measuredHeight - available
                val text = (c.banner.getChildAt(0) as FrameLayout).getChildAt(1)
                text.measure(MeasureSpec.makeMeasureSpec((width - c.root.paddingLeft - c.root.paddingRight).coerceAtLeast(1), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                height(c.banner, maxOf(dp(64), text.measuredHeight, dp(95) - overflow))
                measureContent()
            }
            if (c.isToday() && !image && c.card.visibility == View.VISIBLE && c.root.measuredHeight < available) {
                // Preserve the spacious existing countdown on taller windows.
                val room = c.card.layoutParams.height + available - c.root.measuredHeight
                height(c.card, maxOf(minimumCard, minOf(dp(200), room)))
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
