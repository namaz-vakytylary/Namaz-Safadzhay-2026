package ru.namaz.safadzhay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.widget.ImageView
import kotlin.math.min

/** Clips only the artwork, leaving the countdown's progress/text layers untouched. */
internal class RamadanCountdownImageView(context: Context) : ImageView(context) {
    private val artworkClip = Path()
    private val artworkBounds = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        // Match the existing CardProgressIndicator, without changing its geometry:
        // 2.5dp stroke, centre inset = half stroke + 1dp, outer corner radius = 18dp.
        val halfStroke = density * 2.5f / 2f
        val centreInset = halfStroke + density
        val centreRadius = min(
            density * 18f - centreInset,
            min(w - 2f * centreInset, h - 2f * centreInset) / 2f
        ).coerceAtLeast(0f)
        // One physical pixel keeps antialiased corner coverage inside the stroke.
        // This affects only the artwork mask, never the view bounds or border.
        val antialiasInset = 1f
        val innerInset = centreInset + halfStroke + antialiasInset
        val innerRadius = (centreRadius - halfStroke - antialiasInset).coerceAtLeast(0f)
        artworkClip.reset()
        artworkBounds.set(innerInset, innerInset, w - innerInset, h - innerInset)
        if (!artworkBounds.isEmpty) {
            artworkClip.addRoundRect(artworkBounds, innerRadius, innerRadius, Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(artworkClip)
        super.onDraw(canvas)
        canvas.restoreToCount(saved)
    }
}
