package ru.namaz.safadzhay

import android.content.Context
import android.graphics.*
import android.hardware.*
import android.view.View
import kotlin.math.*

/** Clockwise elapsed progress along the card edge, starting at top centre. */
internal class CardProgressIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    private val outline = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = paint.strokeWidth / 2f + resources.displayMetrics.density
        val l = inset; val t = inset; val r = w - inset; val b = h - inset
        val radius = min(resources.displayMetrics.density * 18f - inset, min(r-l, b-t) / 2f).coerceAtLeast(0f)
        outline.reset()
        outline.moveTo(w / 2f, t)
        outline.lineTo(r-radius, t)
        outline.arcTo(RectF(r-2*radius, t, r, t+2*radius), -90f, 90f)
        outline.lineTo(r, b-radius)
        outline.arcTo(RectF(r-2*radius, b-2*radius, r, b), 0f, 90f)
        outline.lineTo(l+radius, b)
        outline.arcTo(RectF(l, b-2*radius, l+2*radius, b), 90f, 90f)
        outline.lineTo(l, t+radius)
        outline.arcTo(RectF(l, t, l+2*radius, t+2*radius), 180f, 90f)
        outline.close()
        measure.setPath(outline, true)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.rgb(18, 72, 51)
        canvas.drawPath(outline, paint)
        paint.color = Color.rgb(91, 224, 164)
        if (progress >= 1f) canvas.drawPath(outline, paint)
        else if (progress > 0f) {
            segment.reset()
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, paint)
        }
    }
}

internal class PrayerIconView(context: Context, private val name: String, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) * .23f; val cx = width / 2f; val cy = height / 2f
        paint.color = tint; paint.strokeWidth = resources.displayMetrics.density * 1.5f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        if (name == "Иша") {
    val drawable = androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, R.drawable.ic_isha_crescent)

    drawable?.setBounds(
        (cx - r * 1.7f).toInt(),
        (cy - r * 1.7f).toInt(),
        (cx + r * 1.7f).toInt(),
        (cy + r * 1.7f).toInt()
    )
    drawable?.setTint(tint)
    drawable?.draw(canvas)
        } else if (name == "Фаджр" || name == "Магриб") {
            val horizon = cy + r * .45f
            canvas.drawLine(cx-r*1.8f, horizon, cx+r*1.8f, horizon, paint)
            canvas.drawLine(cx-r*1.55f, horizon+r*.45f, cx+r*1.55f, horizon+r*.45f, paint)
            if (name == "Магриб") {
                canvas.drawArc(RectF(cx-r, horizon-r, cx+r, horizon+r), 180f, 180f, false, paint)
            }
            // Dawn is light above the horizon before the sun appears; sunset includes the disk.
            val inner = if (name == "Фаджр") .95f else 1.35f
            for (i in 0..4) {
                val a = Math.toRadians((-150+i*30).toDouble())
                canvas.drawLine(cx+cos(a).toFloat()*r*inner, horizon+sin(a).toFloat()*r*inner,
                    cx+cos(a).toFloat()*r*1.8f, horizon+sin(a).toFloat()*r*1.8f, paint)
            }
        } else {
            canvas.drawCircle(cx, cy, r, paint)
            for (i in 0..7) {
                val a = i * PI / 4
                canvas.drawLine(cx + cos(a).toFloat() * r * 1.4f, cy + sin(a).toFloat() * r * 1.4f, cx + cos(a).toFloat() * r * 1.9f, cy + sin(a).toFloat() * r * 1.9f, paint)
            }
        }
    }
}

internal class QiblaCompassView(context: Context) : View(context), SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val acceleration = FloatArray(3)
    private val magnetic = FloatArray(3)
    private var hasAcceleration = false
    private var hasMagnetic = false
    private var declination = 0f
    private var bearing: Float? = null
    private var heading = 0f
    private var initialized = false
    private var registered = false
    private var unreliable = true
    private var tilted = false
    var onDirection: ((Boolean, Boolean) -> Unit)? = null

    fun setLocation(location: android.location.Location?) {
        bearing = location?.let { QiblaMath.bearing(it.latitude, it.longitude) }
        if (location != null) declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(),
            (if (location.hasAltitude()) location.altitude else 0.0).toFloat(), System.currentTimeMillis()).declination
        notifyDirection(); invalidate()
    }
    fun hasCompass() = rotation != null || (accelerometer != null && magnetometer != null)
    fun hasOrientation() = initialized
    fun start() {
        if (registered || !hasCompass()) return
        initialized = false; unreliable = true; hasAcceleration = false; hasMagnetic = false
        // Request animation-rate updates while this screen is visible. The first
        // reading is applied immediately; later readings retain noise smoothing.
        registered = if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
        else {
            val a = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            val m = sensors.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            if (!a || !m) sensors.unregisterListener(this)
            a && m
        }
        notifyDirection()
    }
    fun stop() { sensors.unregisterListener(this); registered = false; initialized = false; notifyDirection(); invalidate() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                unreliable = event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, acceleration, 0, 3); hasAcceleration = true
                if (!hasMagnetic || !SensorManager.getRotationMatrix(matrix, null, acceleration, magnetic)) return
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetic, 0, 3); hasMagnetic = true
                unreliable = event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                if (!hasAcceleration || !SensorManager.getRotationMatrix(matrix, null, acceleration, magnetic)) return
            }
            else -> return
        }
        SensorManager.getOrientation(matrix, orientation)
        tilted = abs(orientation[1]) > Math.toRadians(25.0) || abs(orientation[2]) > Math.toRadians(25.0)
        val target = (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360) % 360
        if (!initialized) { heading = target; initialized = true }
        else heading = (heading + QiblaMath.shortestAngle(target - heading) * .18f + 360) % 360
        notifyDirection(); invalidate()
    }
    private fun notifyDirection() {
        val target = bearing
        onDirection?.invoke(target != null && initialized && abs(QiblaMath.shortestAngle(target-heading)) < 5,
            unreliable || tilted || !initialized)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            unreliable = accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM; notifyDirection()
        }
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val radius = min(width, height) * .46f
        val density = resources.displayMetrics.density
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.rgb(66, 130, 103)
        canvas.drawCircle(cx, cy, radius, paint)
        val north = if (initialized) heading else 0f
        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30 - north - 90).toDouble())
            canvas.drawLine(cx + cos(a).toFloat()*radius*.94f, cy + sin(a).toFloat()*radius*.94f,
                cx + cos(a).toFloat()*radius*.99f, cy + sin(a).toFloat()*radius*.99f, paint)
        }
        // Never show a north reading until orientation data has arrived.
        if (initialized) {
            paint.style = Paint.Style.FILL; paint.textSize = radius*.13f; paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT; paint.color = Color.rgb(228, 243, 234)
            listOf("С", "В", "Ю", "З").forEachIndexed { i, letter ->
                val a = Math.toRadians((i*90 - north - 90).toDouble())
                canvas.drawText(letter, cx+cos(a).toFloat()*radius*.82f,
                    cy+sin(a).toFloat()*radius*.82f+paint.textSize*.34f, paint)
            }
        }
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(91, 224, 164)
        canvas.drawRoundRect(cx-density*2, cy-radius-density*4, cx+density*2, cy-radius+density*10, density*2, density*2, paint)
        val target = bearing
        if (target != null && initialized) {
            val angle = target - heading
            canvas.save(); canvas.rotate(angle, cx, cy)
            paint.color = Color.rgb(31, 89, 65)
            val tail = Path().apply { moveTo(cx-radius*.045f, cy); lineTo(cx, cy+radius*.40f); lineTo(cx+radius*.045f, cy); close() }
            canvas.drawPath(tail, paint)
            paint.color = Color.rgb(91, 224, 164)
            val needle = Path().apply { moveTo(cx, cy-radius*.54f); lineTo(cx-radius*.065f, cy-radius*.05f); lineTo(cx, cy+radius*.035f); lineTo(cx+radius*.065f, cy-radius*.05f); close() }
            canvas.drawPath(needle, paint); canvas.restore()
            // The Kaaba follows the needle but its small icon stays upright and clear of the letters.
            val a = Math.toRadians((angle-90).toDouble())
            val kx = cx + cos(a).toFloat()*radius*.66f; val ky = cy + sin(a).toFloat()*radius*.66f
            val k = radius*.074f
            paint.color = Color.rgb(20, 28, 24)
            val cube = Path().apply { moveTo(kx-k, ky-k*.55f); lineTo(kx, ky-k); lineTo(kx+k, ky-k*.55f); lineTo(kx+k, ky+k*.8f); lineTo(kx, ky+k*1.15f); lineTo(kx-k, ky+k*.8f); close() }
            canvas.drawPath(cube, paint)
            paint.color = Color.rgb(40, 49, 44)
            val lid = Path().apply { moveTo(kx-k, ky-k*.55f); lineTo(kx, ky-k); lineTo(kx+k, ky-k*.55f); lineTo(kx, ky-k*.15f); close() }
            canvas.drawPath(lid, paint)
            paint.color = Color.rgb(178, 232, 204); paint.style = Paint.Style.STROKE; paint.strokeWidth = k*.20f
            val band = Path().apply { moveTo(kx-k, ky-k*.25f); lineTo(kx, ky+k*.10f); lineTo(kx+k, ky-k*.25f) }
            canvas.drawPath(band, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(kx-k*.48f, ky+k*.32f, kx-k*.15f, ky+k*.85f, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(228, 243, 234)
        canvas.drawCircle(cx, cy, radius*.032f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density*1.5f; paint.color = Color.rgb(19, 75, 52)
        canvas.drawCircle(cx, cy, radius*.042f, paint)
    }
}
