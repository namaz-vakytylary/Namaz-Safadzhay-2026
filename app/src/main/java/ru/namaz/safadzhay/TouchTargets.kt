package ru.namaz.safadzhay

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/** Enlarges free space only; drawing/layout and other clickable siblings stay intact. */
internal object TouchTargets {
    fun expand(view: View) {
        view.post {
            val parent = view.parent as? ViewGroup ?: return@post
            val bounds = Rect(); view.getHitRect(bounds)
            val wanted = (48 * view.resources.displayMetrics.density).toInt()
            val dx = ((wanted - bounds.width()) / 2).coerceAtLeast(0)
            val dy = ((wanted - bounds.height()) / 2).coerceAtLeast(0)
            val expanded = Rect(bounds).apply { inset(-dx, -dy) }
            if (!expanded.intersect(0, 0, parent.width, parent.height)) return@post
            for (i in 0 until parent.childCount) {
                val other = parent.getChildAt(i)
                if (other === view || !other.isClickable || other.visibility != View.VISIBLE) continue
                val rect = Rect(); other.getHitRect(rect)
                // Split available gaps at their midpoint, so expanded targets cannot overlap.
                if (rect.right <= bounds.left) expanded.left = maxOf(expanded.left, (rect.right + bounds.left) / 2)
                if (rect.left >= bounds.right) expanded.right = minOf(expanded.right, (rect.left + bounds.right) / 2)
                if (rect.bottom <= bounds.top) expanded.top = maxOf(expanded.top, (rect.bottom + bounds.top) / 2)
                if (rect.top >= bounds.bottom) expanded.bottom = minOf(expanded.bottom, (rect.top + bounds.bottom) / 2)
            }
            @Suppress("UNCHECKED_CAST")
            val delegates = (parent.getTag(R.id.expanded_touch_targets) as? MutableMap<View, TouchDelegate>)
                ?: mutableMapOf<View, TouchDelegate>().also { parent.setTag(R.id.expanded_touch_targets, it) }
            delegates[view] = TouchDelegate(expanded, view)
            parent.touchDelegate = object : TouchDelegate(Rect(), parent) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    for (delegate in delegates.values) {
                        val copy = MotionEvent.obtain(event)
                        val handled = try { delegate.onTouchEvent(copy) } finally { copy.recycle() }
                        if (handled) return true
                    }
                    return false
                }
            }
        }
    }
}
