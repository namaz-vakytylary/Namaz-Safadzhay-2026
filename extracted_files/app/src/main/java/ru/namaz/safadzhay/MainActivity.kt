package ru.namaz.safadzhay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class PrayerDay(
    val date: String,
    val fajr: String,
    val zuhr: String,
    val asr: String,
    val maghrib: String,
    val isha: String
)

private data class Prayer(val name: String, val tatar: String, val time: String)

private class CircularProgressIndicator(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.rgb(25, 67, 52)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(57, 210, 145)
    }
    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = (minOf(width, height) / 2f) - 7f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, trackPaint)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * progress, false, progressPaint)
    }
}

class MainActivity : Activity() {
    private val zone = ZoneId.of("Europe/Moscow")
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var countdown: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var nextName: TextView
    private lateinit var dateText: TextView
    private lateinit var prayerList: LinearLayout
    private lateinit var progress: CircularProgressIndicator
    private lateinit var bellButton: TextView

    private val data = listOf(
        PrayerDay("2026-08-01", "02:14", "12:20", "16:55", "20:06", "21:56"),
        PrayerDay("2026-08-02", "02:16", "12:20", "16:54", "20:04", "21:54"),
        PrayerDay("2026-08-03", "02:18", "12:20", "16:53", "20:02", "21:52"),
        PrayerDay("2026-08-04", "02:20", "12:20", "16:51", "20:00", "21:50"),
        PrayerDay("2026-08-05", "02:22", "12:20", "16:50", "19:58", "21:48"),
        PrayerDay("2026-08-06", "02:24", "12:20", "16:49", "19:56", "21:46"),
        PrayerDay("2026-08-07", "02:26", "12:20", "16:47", "19:54", "21:44"),
        PrayerDay("2026-08-08", "02:28", "12:20", "16:46", "19:52", "21:42"),
        PrayerDay("2026-08-09", "02:29", "12:20", "16:44", "19:50", "21:40"),
        PrayerDay("2026-08-10", "02:32", "12:20", "16:42", "19:47", "21:36"),
        PrayerDay("2026-08-11", "02:35", "12:20", "16:41", "19:45", "21:33"),
        PrayerDay("2026-08-12", "02:38", "12:20", "16:40", "19:43", "21:30"),
        PrayerDay("2026-08-13", "02:42", "12:20", "16:38", "19:41", "21:26"),
        PrayerDay("2026-08-14", "02:44", "12:20", "16:37", "19:39", "21:24"),
        PrayerDay("2026-08-15", "02:48", "12:20", "16:35", "19:36", "21:19"),
        PrayerDay("2026-08-16", "02:50", "12:20", "16:33", "19:34", "21:17"),
        PrayerDay("2026-08-17", "02:53", "12:20", "16:32", "19:32", "21:14"),
        PrayerDay("2026-08-18", "02:57", "12:20", "16:30", "19:29", "21:09"),
        PrayerDay("2026-08-19", "02:59", "12:20", "16:29", "19:27", "21:07"),
        PrayerDay("2026-08-20", "03:02", "12:20", "16:27", "19:25", "21:04"),
        PrayerDay("2026-08-21", "03:04", "12:20", "16:25", "19:22", "21:00"),
        PrayerDay("2026-08-22", "03:07", "12:20", "16:24", "19:20", "20:57"),
        PrayerDay("2026-08-23", "03:10", "12:20", "16:21", "19:17", "20:53"),
        PrayerDay("2026-08-24", "03:12", "12:20", "16:20", "19:15", "20:51"),
        PrayerDay("2026-08-25", "03:15", "12:20", "16:19", "19:13", "20:48"),
        PrayerDay("2026-08-26", "03:19", "12:20", "16:17", "19:10", "20:43"),
        PrayerDay("2026-08-27", "03:21", "12:20", "16:15", "19:08", "20:41"),
        PrayerDay("2026-08-28", "03:24", "12:20", "16:13", "19:05", "20:37"),
        PrayerDay("2026-08-29", "03:26", "12:20", "16:12", "19:03", "20:35"),
        PrayerDay("2026-08-30", "03:29", "12:20", "16:10", "19:00", "20:31"),
        PrayerDay("2026-08-31", "03:32", "12:20", "16:08", "18:58", "20:28"),
        PrayerDay("2026-09-01", "03:34", "12:20", "16:06", "18:55", "20:25"),
        PrayerDay("2026-09-02", "03:36", "12:20", "16:04", "18:52", "20:22"),
        PrayerDay("2026-09-03", "03:37", "12:20", "16:02", "18:50", "20:20"),
        PrayerDay("2026-09-04", "03:39", "12:20", "16:00", "18:47", "20:17"),
        PrayerDay("2026-09-05", "03:42", "12:20", "15:59", "18:45", "20:14"),
        PrayerDay("2026-09-06", "03:44", "12:20", "15:57", "18:42", "20:11"),
        PrayerDay("2026-09-07", "03:48", "12:20", "15:55", "18:40", "20:07"),
        PrayerDay("2026-09-08", "03:50", "12:20", "15:53", "18:37", "20:04"),
        PrayerDay("2026-09-09", "03:53", "12:20", "15:51", "18:34", "20:00"),
        PrayerDay("2026-09-10", "03:55", "12:20", "15:50", "18:32", "19:58"),
        PrayerDay("2026-09-11", "03:57", "12:20", "15:48", "18:29", "19:55"),
        PrayerDay("2026-09-12", "04:00", "12:20", "15:46", "18:27", "19:52"),
        PrayerDay("2026-09-13", "04:01", "12:20", "15:44", "18:24", "19:49"),
        PrayerDay("2026-09-14", "04:03", "12:20", "15:42", "18:21", "19:46"),
        PrayerDay("2026-09-15", "04:05", "12:20", "15:40", "18:19", "19:44"),
        PrayerDay("2026-09-16", "04:07", "12:20", "15:38", "18:16", "19:41"),
        PrayerDay("2026-09-17", "04:09", "12:20", "15:36", "18:13", "19:38"),
        PrayerDay("2026-09-18", "04:11", "12:20", "15:35", "18:11", "19:36"),
        PrayerDay("2026-09-19", "04:13", "12:20", "15:33", "18:08", "19:33"),
        PrayerDay("2026-09-20", "04:15", "12:20", "15:30", "18:05", "19:30"),
        PrayerDay("2026-09-21", "04:17", "12:20", "15:29", "18:03", "19:28"),
        PrayerDay("2026-09-22", "04:18", "12:20", "15:27", "18:00", "19:25"),
        PrayerDay("2026-09-23", "04:20", "12:20", "15:25", "17:58", "19:23"),
        PrayerDay("2026-09-24", "04:22", "12:20", "15:23", "17:55", "19:20"),
        PrayerDay("2026-09-25", "04:24", "12:20", "15:21", "17:52", "19:17"),
        PrayerDay("2026-09-26", "04:26", "12:20", "15:20", "17:50", "19:15"),
        PrayerDay("2026-09-27", "04:28", "12:20", "15:18", "17:47", "19:12"),
        PrayerDay("2026-09-28", "04:30", "12:20", "15:15", "17:44", "19:09"),
        PrayerDay("2026-09-29", "04:32", "12:20", "15:14", "17:42", "19:07"),
        PrayerDay("2026-09-30", "04:34", "12:20", "15:12", "17:39", "19:04"),
        PrayerDay("2026-10-01", "04:36", "12:20", "15:11", "17:37", "19:02"),
        PrayerDay("2026-10-02", "04:38", "12:20", "15:08", "17:34", "18:59"),
        PrayerDay("2026-10-03", "04:40", "12:20", "15:06", "17:31", "18:56"),
        PrayerDay("2026-10-04", "04:42", "12:20", "15:05", "17:29", "18:54"),
        PrayerDay("2026-10-05", "04:44", "12:20", "15:03", "17:26", "18:51"),
        PrayerDay("2026-10-06", "04:45", "12:20", "15:01", "17:24", "18:49"),
        PrayerDay("2026-10-07", "04:47", "12:20", "14:59", "17:21", "18:46"),
        PrayerDay("2026-10-08", "04:49", "12:20", "14:58", "17:19", "18:44"),
        PrayerDay("2026-10-09", "04:51", "12:20", "14:56", "17:16", "18:41"),
        PrayerDay("2026-10-10", "04:53", "12:20", "14:53", "17:13", "18:38"),
        PrayerDay("2026-10-11", "04:55", "12:20", "14:52", "17:11", "18:36"),
        PrayerDay("2026-10-12", "04:57", "12:20", "14:50", "17:08", "18:33"),
        PrayerDay("2026-10-13", "04:59", "12:20", "14:49", "17:06", "18:31"),
        PrayerDay("2026-10-14", "05:01", "12:20", "14:46", "17:03", "18:28"),
        PrayerDay("2026-10-15", "05:03", "12:20", "14:45", "17:01", "18:26"),
        PrayerDay("2026-10-16", "05:05", "12:20", "14:43", "16:58", "18:23"),
        PrayerDay("2026-10-17", "05:07", "12:20", "14:42", "16:56", "18:21"),
        PrayerDay("2026-10-18", "05:09", "12:20", "14:40", "16:54", "18:19"),
        PrayerDay("2026-10-19", "05:11", "12:20", "14:38", "16:51", "18:16"),
        PrayerDay("2026-10-20", "05:14", "12:20", "14:37", "16:49", "18:14"),
        PrayerDay("2026-10-21", "05:16", "12:20", "14:35", "16:46", "18:11"),
        PrayerDay("2026-10-22", "05:18", "12:20", "14:33", "16:44", "18:09"),
        PrayerDay("2026-10-23", "05:20", "12:20", "14:32", "16:42", "18:07"),
        PrayerDay("2026-10-24", "05:22", "12:20", "14:30", "16:39", "18:04"),
        PrayerDay("2026-10-25", "05:24", "12:20", "14:29", "16:37", "18:02"),
        PrayerDay("2026-10-26", "05:26", "12:20", "14:27", "16:35", "18:00"),
        PrayerDay("2026-10-27", "05:28", "12:20", "14:25", "16:32", "17:57"),
        PrayerDay("2026-10-28", "05:30", "12:20", "14:24", "16:30", "17:55"),
        PrayerDay("2026-10-29", "05:32", "12:20", "14:22", "16:28", "17:53"),
        PrayerDay("2026-10-30", "05:34", "12:20", "14:21", "16:26", "17:51"),
        PrayerDay("2026-10-31", "05:36", "12:20", "14:20", "16:24", "17:49"),
        PrayerDay("2026-11-01", "05:38", "12:20", "14:18", "16:22", "17:47"),
        PrayerDay("2026-11-02", "05:41", "12:20", "14:16", "16:19", "17:44"),
        PrayerDay("2026-11-03", "05:42", "12:20", "14:15", "16:17", "17:43"),
        PrayerDay("2026-11-04", "05:44", "12:20", "14:14", "16:15", "17:41"),
        PrayerDay("2026-11-05", "05:46", "12:20", "14:12", "16:13", "17:39"),
        PrayerDay("2026-11-06", "05:48", "12:20", "14:11", "16:11", "17:37"),
        PrayerDay("2026-11-07", "05:50", "12:20", "14:10", "16:09", "17:35"),
        PrayerDay("2026-11-08", "05:52", "12:20", "14:08", "16:07", "17:33"),
        PrayerDay("2026-11-09", "05:53", "12:20", "14:07", "16:05", "17:32"),
        PrayerDay("2026-11-10", "05:55", "12:20", "14:06", "16:03", "17:30"),
        PrayerDay("2026-11-11", "05:57", "12:20", "14:05", "16:02", "17:29"),
        PrayerDay("2026-11-12", "05:59", "12:20", "14:04", "16:00", "17:27"),
        PrayerDay("2026-11-13", "06:02", "12:20", "14:03", "15:58", "17:25"),
        PrayerDay("2026-11-14", "06:03", "12:20", "14:01", "15:56", "17:24"),
        PrayerDay("2026-11-15", "06:03", "12:20", "14:01", "15:55", "17:25"),
        PrayerDay("2026-11-16", "06:05", "12:20", "14:00", "15:53", "17:23"),
        PrayerDay("2026-11-17", "06:07", "12:20", "13:58", "15:51", "17:21"),
        PrayerDay("2026-11-18", "06:09", "12:20", "13:58", "15:50", "17:20"),
        PrayerDay("2026-11-19", "06:11", "12:20", "13:56", "15:48", "17:18"),
        PrayerDay("2026-11-20", "06:13", "12:20", "13:56", "15:47", "17:17"),
        PrayerDay("2026-11-21", "06:15", "12:20", "13:55", "15:45", "17:15"),
        PrayerDay("2026-11-22", "06:17", "12:20", "13:54", "15:44", "17:14"),
        PrayerDay("2026-11-23", "06:18", "12:20", "13:53", "15:42", "17:12"),
        PrayerDay("2026-11-24", "06:20", "12:20", "13:52", "15:41", "17:11"),
        PrayerDay("2026-11-25", "06:22", "12:20", "13:52", "15:40", "17:10"),
        PrayerDay("2026-11-26", "06:24", "12:20", "13:51", "15:39", "17:09"),
        PrayerDay("2026-11-27", "06:26", "12:20", "13:51", "15:38", "17:08"),
        PrayerDay("2026-11-28", "06:28", "12:20", "13:49", "15:36", "17:06"),
        PrayerDay("2026-11-29", "06:29", "12:20", "13:49", "15:35", "17:05"),
        PrayerDay("2026-11-30", "06:31", "12:20", "13:48", "15:34", "17:04"),
        PrayerDay("2026-12-01", "06:31", "12:20", "13:48", "15:33", "17:05"),
        PrayerDay("2026-12-02", "06:30", "12:20", "13:48", "15:33", "17:07"),
        PrayerDay("2026-12-03", "06:32", "12:20", "13:47", "15:32", "17:06"),
        PrayerDay("2026-12-04", "06:33", "12:20", "13:47", "15:31", "17:05"),
        PrayerDay("2026-12-05", "06:35", "12:20", "13:46", "15:30", "17:04"),
        PrayerDay("2026-12-06", "06:36", "12:20", "13:46", "15:30", "17:04"),
        PrayerDay("2026-12-07", "06:36", "12:20", "13:46", "15:29", "17:05"),
        PrayerDay("2026-12-08", "06:37", "12:20", "13:46", "15:29", "17:05"),
        PrayerDay("2026-12-09", "06:37", "12:20", "13:45", "15:28", "17:05"),
        PrayerDay("2026-12-10", "06:39", "12:20", "13:46", "15:28", "17:05"),
        PrayerDay("2026-12-11", "06:40", "12:20", "13:46", "15:28", "17:05"),
        PrayerDay("2026-12-12", "06:40", "12:20", "13:45", "15:27", "17:05"),
        PrayerDay("2026-12-13", "06:41", "12:20", "13:45", "15:27", "17:05"),
        PrayerDay("2026-12-14", "06:42", "12:20", "13:45", "15:27", "17:05"),
        PrayerDay("2026-12-15", "06:43", "12:20", "13:46", "15:27", "17:05"),
        PrayerDay("2026-12-16", "06:44", "12:20", "13:46", "15:27", "17:05"),
        PrayerDay("2026-12-17", "06:45", "12:20", "13:46", "15:27", "17:05"),
        PrayerDay("2026-12-18", "06:45", "12:20", "13:46", "15:27", "17:05"),
        PrayerDay("2026-12-19", "06:46", "12:20", "13:47", "15:28", "17:06"),
        PrayerDay("2026-12-20", "06:47", "12:20", "13:47", "15:28", "17:06"),
        PrayerDay("2026-12-21", "06:47", "12:20", "13:47", "15:28", "17:06"),
        PrayerDay("2026-12-22", "06:48", "12:20", "13:48", "15:29", "17:07"),
        PrayerDay("2026-12-23", "06:48", "12:20", "13:48", "15:29", "17:07"),
        PrayerDay("2026-12-24", "06:49", "12:20", "13:49", "15:30", "17:08"),
        PrayerDay("2026-12-25", "06:49", "12:20", "13:49", "15:30", "17:08"),
        PrayerDay("2026-12-26", "06:49", "12:20", "13:50", "15:31", "17:09"),
        PrayerDay("2026-12-27", "06:49", "12:20", "13:51", "15:32", "17:10"),
        PrayerDay("2026-12-28", "06:50", "12:20", "13:52", "15:33", "17:11"),
        PrayerDay("2026-12-29", "06:50", "12:20", "13:53", "15:34", "17:12"),
        PrayerDay("2026-12-30", "06:50", "12:20", "13:53", "15:35", "17:13"),
        PrayerDay("2026-12-31", "06:49", "12:20", "13:54", "15:36", "17:14")
    )

    private val names = listOf(
        Triple("Фаджр", "Иртәнге намаз", 0),
        Triple("Зухр", "Өйлә намазы", 1),
        Triple("Аср", "Икенде намазы", 2),
        Triple("Магриб", "Ахшам намазы", 3),
        Triple("Иша", "Ястү намазы", 4)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        update()
        handler.post(object : Runnable {
            override fun run() {
                update()
                handler.postDelayed(this, 1000L)
            }
        })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans", Typeface.BOLD)
        includeFontPadding = true
    }

    private fun cardBackground(active: Boolean, next: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 24f
        setColor(if (active || next) Color.rgb(18, 83, 61) else Color.argb(80, 30, 67, 53))
        setStroke(if (active || next) 2 else 1, if (active || next) Color.rgb(57, 210, 145) else Color.argb(80, 95, 150, 126))
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(5, 22, 16)
        window.navigationBarColor = Color.rgb(5, 22, 16)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 22, 16))
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 8, 0, 12)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 18)
        }
        scroll.addView(root, ScrollView.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(header, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        val title = text("НАМАЗ", 30f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        header.addView(title, LinearLayout.LayoutParams(-1, 48))

        val place = text("САФАДЖАЙ", 13f, Color.rgb(83, 207, 153), true).apply { gravity = Gravity.CENTER }
        header.addView(place, LinearLayout.LayoutParams(-1, 28))

        dateText = text("", 16f, Color.rgb(180, 211, 198)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        val dateLp = LinearLayout.LayoutParams(-1, 50)
        dateLp.topMargin = 8
        header.addView(dateText, dateLp)

        val countdownCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 16, 18, 14)
            background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.rgb(9, 45, 33))
                setStroke(2, Color.rgb(27, 119, 82))
            }
        }
        val countdownLp = LinearLayout.LayoutParams(-1, 255)
        countdownLp.topMargin = 8
        countdownLp.bottomMargin = 18
        header.addView(countdownCard, countdownLp)

        nextName = text("", 19f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        countdownCard.addView(nextName, LinearLayout.LayoutParams(-1, 32))

        countdown = text("00:00:00", 54f, Color.rgb(91, 224, 164), true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setIncludeFontPadding(false)
        }
        val timerLp = LinearLayout.LayoutParams(-1, 80)
        timerLp.topMargin = 8
        countdownCard.addView(countdown, timerLp)

        countdownLabel = text("До намаза осталось", 15f, Color.rgb(178, 211, 198)).apply { gravity = Gravity.CENTER }
        countdownCard.addView(countdownLabel, LinearLayout.LayoutParams(-1, 30))

        progress = CircularProgressIndicator(this)
        val progLp = LinearLayout.LayoutParams(48, 48)
        progLp.topMargin = 8
        countdownCard.addView(progress, progLp)

        val bellRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bellButton = text("🔔", 25f, Color.rgb(91,224,164), false).apply {
            gravity = Gravity.CENTER
            setPadding(12, 4, 12, 4)
            contentDescription = "Уведомления"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val enabled = !prefs.getBoolean("notifications", true)
                prefs.edit().putBoolean("notifications", enabled).apply()
                refreshBell()
            }
        }
        bellRow.addView(bellButton, LinearLayout.LayoutParams(58, 50))
        countdownCard.addView(bellRow, LinearLayout.LayoutParams(-1, 50))

        prayerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(prayerList, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(scroll)
        refreshBell()
    }

    private fun refreshBell() {
        val on = prefs.getBoolean("notifications", true)
        bellButton.text = if (on) "🔔" else "🔕"
        bellButton.alpha = if (on) 1f else 0.55f
    }

    private fun currentDay(): PrayerDay? = data.firstOrNull { it.date == LocalDate.now(zone).toString() }

    private fun getPrayers(day: PrayerDay): List<Prayer> = listOf(
        Prayer(names[0].first, names[0].second, day.fajr),
        Prayer(names[1].first, names[1].second, day.zuhr),
        Prayer(names[2].first, names[2].second, day.asr),
        Prayer(names[3].first, names[3].second, day.maghrib),
        Prayer(names[4].first, names[4].second, day.isha)
    )

    private fun dateTime(date: LocalDate, time: String): LocalDateTime {
        val p = time.split(":")
        return date.atTime(p[0].toInt(), p[1].toInt())
    }

    private fun update() {
        val now = LocalDateTime.now(zone).withNano(0)
        dateText.text = now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val today = currentDay()

        if (today == null) {
            nextName.text = "Расписание"
            countdown.text = "—"
            countdownLabel.text = "Доступно: август–декабрь 2026"
            progress.progress = 0f
            return
        }

        val prayers = getPrayers(today)
        var next: Prayer? = null
        var nextDt: LocalDateTime? = null
        var nextIndex = 0
        var previousDt = now.minusHours(1)

        for ((index, p) in prayers.withIndex()) {
            val dt = dateTime(now.toLocalDate(), p.time)
            if (dt.isAfter(now)) {
                next = p
                nextDt = dt
                nextIndex = index
                previousDt = if (index == 0) {
                    dateTime(now.toLocalDate().minusDays(1), data.firstOrNull { it.date == now.toLocalDate().minusDays(1).toString() }?.isha ?: p.time)
                } else dateTime(now.toLocalDate(), prayers[index - 1].time)
                break
            }
        }

        if (next == null) {
            val tomorrow = data.firstOrNull { it.date == now.toLocalDate().plusDays(1).toString() }
            if (tomorrow != null) {
                next = Prayer(names[0].first, names[0].second, tomorrow.fajr)
                nextDt = dateTime(now.toLocalDate().plusDays(1), tomorrow.fajr)
                nextIndex = 0
                previousDt = dateTime(now.toLocalDate(), prayers.last().time)
            }
        }

        if (next != null && nextDt != null) {
            val total = Duration.between(previousDt, nextDt).seconds.coerceAtLeast(1)
            val left = Duration.between(now, nextDt).seconds.coerceAtLeast(0)
            val fraction = (left.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            progress.progress = fraction.toFloat()
            countdown.text = String.format("%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
            nextName.text = "${next.name} (${next.tatar})"
            countdownLabel.text = "До ${next.name} осталось"
        }

        renderPrayers(prayers, nextIndex, now)
    }

    private fun renderPrayers(prayers: List<Prayer>, nextIndex: Int, now: LocalDateTime) {
        prayerList.removeAllViews()
        val today = currentDay() ?: return
        val currentMinutes = now.hour * 60 + now.minute

        for ((index, p) in prayers.withIndex()) {
            val parts = p.time.split(":")
            val mins = parts[0].toInt() * 60 + parts[1].toInt()
            val isNext = index == nextIndex
            val passed = mins <= currentMinutes && !isNext

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 8, 18, 8)
                background = cardBackground(passed.not() && isNext, isNext)
                alpha = if (passed) 0.58f else 1f
            }

            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val ru = text(p.name, 17f, Color.WHITE, true).apply { gravity = Gravity.START }
            val tt = text("(${p.tatar})", 13f, Color.rgb(171, 202, 190), false).apply { gravity = Gravity.START }
            nameBox.addView(ru, LinearLayout.LayoutParams(-1, 28))
            nameBox.addView(tt, LinearLayout.LayoutParams(-1, 25))

            val time = text(p.time, 22f, if (isNext) Color.rgb(91, 224, 164) else Color.WHITE, true).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = Typeface.create("monospace", Typeface.BOLD)
            }

            row.addView(nameBox, LinearLayout.LayoutParams(0, 62, 1f))
            val timeLp = LinearLayout.LayoutParams(92, 62)
            timeLp.leftMargin = 10
            row.addView(time, timeLp)

            val lp = LinearLayout.LayoutParams(-1, 78)
            lp.bottomMargin = 10
            prayerList.addView(row, lp)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
