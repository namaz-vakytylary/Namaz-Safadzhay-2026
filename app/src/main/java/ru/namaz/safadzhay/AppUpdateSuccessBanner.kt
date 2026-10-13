package ru.namaz.safadzhay

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Small non-modal notice; only the coordinator can mark a real version increase as success. */
internal class AppUpdateSuccessBanner(private val activity: Activity, message: String, action: (() -> Unit)? = null) {
    private val handler = Handler(Looper.getMainLooper())
    private val host = activity.findViewById<FrameLayout>(android.R.id.content)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private val view = TextView(activity).apply {
        val colors = ThemeSettings.colors(activity)
        tag = "app_update_success"; text = message; textSize = 14f; setTextColor(colors.onSurface)
        setPadding(dp(16), dp(14), dp(16), dp(14)); elevation = dp(4).toFloat()
        background = GradientDrawable().apply { setColor(colors.surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), colors.outline) }
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { close(); action?.invoke() }
    }
    init {
        host.addView(view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(18); rightMargin = dp(18)
            bottomMargin = (ViewCompat.getRootWindowInsets(host)?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: dp(22)) + dp(18)
        })
        handler.postDelayed({ close() }, 6000)
    }
    fun close() { handler.removeCallbacksAndMessages(null); host.removeView(view) }
}
