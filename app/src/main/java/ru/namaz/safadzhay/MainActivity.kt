package ru.namaz.safadzhay

import android.app.Activity
import android.app.DatePickerDialog
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Duration
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

private data class Prayer(val name: String, val tatar: String, val time: String)
private data class HijriDate(val day: Int, val month: Int, val year: Int)

private const val SOUND_PICKER_REQUEST = 8102
private const val OPEN_PRAYER_ACTION = "ru.namaz.safadzhay.test.OPEN_PRAYER"
private const val SETTINGS_PREFS = "settings"
private const val SOUND_URI_KEY = "notification_sound_uri"
private const val NOTIFICATIONS_ENABLED_KEY = "notifications_enabled"
private const val NOTIFY_BEFORE_MIN_KEY = "notify_before_min"
private const val EXACT_ALARM_PROMPTED_KEY = "exact_alarm_prompted"
private const val SHOW_TATAR_NAMES_KEY = "show_tatar_names"

private fun showTatarNames(context: Context): Boolean =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getBoolean(SHOW_TATAR_NAMES_KEY, true)

private fun prayerDisplayName(context: Context, russian: String, tatar: String): String =
    if (showTatarNames(context) && tatar.isNotBlank()) "$russian ($tatar)" else russian

private fun selectedNotificationSound(context: Context): Uri {
    val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    val saved = prefs.getString(SOUND_URI_KEY, null)
    return saved?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

private fun notificationChannelId(context: Context): String {
    val sound = selectedNotificationSound(context).toString()
    return "prayer_times_v3_${sound.hashCode().toUInt().toString(16)}"
}

private fun ensurePrayerNotificationChannel(context: Context): String {
    val channelId = notificationChannelId(context)
    if (Build.VERSION.SDK_INT >= 26) {
        val sound = selectedNotificationSound(context)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            channelId,
            "Время намаза",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Напоминания о времени намаза"
            setSound(sound, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
            setShowBadge(true)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
    return channelId
}


// Process-wide queues let a completed download rebuild alarms even if its screen was closed.
private val prayerAlarmWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

class MainActivity : Activity() {
    private val zone = ZoneId.of("Europe/Moscow")
    private val handler = Handler(Looper.getMainLooper())
    private val alarmExecutor = prayerAlarmWorker
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var scheduleUpdateChecker: ScheduleUpdateChecker
    private var panelRoute = ""
    private var activeCompass: QiblaCompassView? = null
    private var activeQiblaLocation: QiblaLocationController? = null
    private var lastPrayerRender = ""
    private var lastCalendarRender = ""
    private var lastAlarmSignature = ""
    private var lastDay: LocalDate? = null
    private lateinit var countdownStart: TextView
    private lateinit var headerBox: LinearLayout
    private lateinit var heroCopy: LinearLayout
    private val mint = Color.rgb(70, 218, 145)
    private val muted = Color.rgb(173, 203, 189)
    private val ink = Color.rgb(235, 247, 240)
    private var settingsPanel: android.app.Dialog? = null
    private var aboutDialog: android.app.Dialog? = null
    private var scheduleUpdateDialog: android.app.Dialog? = null
    private var scheduleUpdateBar: android.widget.ProgressBar? = null
    private var scheduleUpdatePercent: TextView? = null
    private var scheduleUpdateStatus: TextView? = null
    private var scheduleUpdateShownAt = 0L
    private var scheduleUpdateCompletionPending = false
    private var settingsPanelBack: () -> Unit = {}

    private lateinit var countdown: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var nextName: TextView
    private lateinit var dateText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var prayerList: LinearLayout
    private lateinit var progress: CardProgressIndicator
    private lateinit var eventBanner: TextView
    private lateinit var countdownCard: android.widget.FrameLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var todayButtonView: TextView
    private lateinit var scheduleButtonView: TextView
    private var scheduleTabSelected: Boolean = false
    private lateinit var mainScroll: ScrollView
    private lateinit var calendarPanel: LinearLayout
    private lateinit var calendarTitle: TextView
    private lateinit var calendarGrid: android.widget.GridLayout
    private lateinit var holidayCard: LinearLayout
    private var calendarMonth: java.time.YearMonth = java.time.YearMonth.of(2026, 9)
    private val prayerKeys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")
    private val prayerRussian = listOf("Фаджр", "Зухр", "Аср", "Магриб", "Иша")
    private val prayerTatar = listOf("Иртәнге намаз", "Өйлә намазы", "Икенде намазы", "Ахшам намазы", "Ястү намазы")

    private var selectedDate: LocalDate? = null
    private var selectedCity: String = "Сафаджай"
    private lateinit var placeText: TextView

    private val safadzhayData = listOf(
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

    // Москва: август–декабрь 2026. Времена взяты из предоставленного пользователем расписания Sajda.
    private val moscowData = listOf(
        PrayerDay("2026-08-01", "02:11", "12:41", "16:52", "20:42", "22:36"),
        PrayerDay("2026-08-02", "02:12", "12:41", "16:51", "20:40", "22:35"),
        PrayerDay("2026-08-03", "02:12", "12:41", "16:51", "20:38", "22:34"),
        PrayerDay("2026-08-04", "02:13", "12:41", "16:50", "20:36", "22:33"),
        PrayerDay("2026-08-05", "02:14", "12:41", "16:49", "20:34", "22:32"),
        PrayerDay("2026-08-06", "02:15", "12:40", "16:48", "20:32", "22:30"),
        PrayerDay("2026-08-07", "02:15", "12:40", "16:47", "20:30", "22:29"),
        PrayerDay("2026-08-08", "02:16", "12:40", "16:46", "20:28", "22:28"),
        PrayerDay("2026-08-09", "02:17", "12:40", "16:45", "20:26", "22:27"),
        PrayerDay("2026-08-10", "02:17", "12:40", "16:44", "20:23", "22:26"),
        PrayerDay("2026-08-11", "02:18", "12:40", "16:42", "20:21", "22:25"),
        PrayerDay("2026-08-12", "02:19", "12:40", "16:41", "20:19", "22:23"),
        PrayerDay("2026-08-13", "02:19", "12:39", "16:40", "20:17", "22:22"),
        PrayerDay("2026-08-14", "02:20", "12:39", "16:39", "20:14", "22:21"),
        PrayerDay("2026-08-15", "02:21", "12:39", "16:38", "20:12", "22:20"),
        PrayerDay("2026-08-16", "02:21", "12:39", "16:37", "20:10", "22:17"),
        PrayerDay("2026-08-17", "02:22", "12:39", "16:35", "20:07", "22:13"),
        PrayerDay("2026-08-18", "02:23", "12:38", "16:34", "20:05", "22:10"),
        PrayerDay("2026-08-19", "02:23", "12:38", "16:33", "20:02", "22:06"),
        PrayerDay("2026-08-20", "02:24", "12:38", "16:31", "20:00", "22:02"),
        PrayerDay("2026-08-21", "02:27", "12:38", "16:30", "19:58", "21:58"),
        PrayerDay("2026-08-22", "02:32", "12:37", "16:29", "19:55", "21:54"),
        PrayerDay("2026-08-23", "02:36", "12:37", "16:27", "19:53", "21:51"),
        PrayerDay("2026-08-24", "02:40", "12:37", "16:26", "19:50", "21:47"),
        PrayerDay("2026-08-25", "02:44", "12:37", "16:24", "19:48", "21:43"),
        PrayerDay("2026-08-26", "02:47", "12:36", "16:23", "19:45", "21:40"),
        PrayerDay("2026-08-27", "02:51", "12:36", "16:21", "19:43", "21:36"),
        PrayerDay("2026-08-28", "02:55", "12:36", "16:20", "19:40", "21:33"),
        PrayerDay("2026-08-29", "02:58", "12:35", "16:18", "19:38", "21:29"),
        PrayerDay("2026-08-30", "03:02", "12:35", "16:17", "19:35", "21:26"),
        PrayerDay("2026-08-31", "03:05", "12:35", "16:15", "19:33", "21:22"),
        PrayerDay("2026-09-01", "03:08", "12:35", "16:14", "19:30", "21:19"),
        PrayerDay("2026-09-02", "03:12", "12:34", "16:12", "19:27", "21:16"),
        PrayerDay("2026-09-03", "03:15", "12:34", "16:10", "19:25", "21:12"),
        PrayerDay("2026-09-04", "03:18", "12:34", "16:09", "19:22", "21:09"),
        PrayerDay("2026-09-05", "03:21", "12:33", "16:07", "19:20", "21:06"),
        PrayerDay("2026-09-06", "03:24", "12:33", "16:05", "19:17", "21:02"),
        PrayerDay("2026-09-07", "03:27", "12:33", "16:04", "19:14", "20:59"),
        PrayerDay("2026-09-08", "03:30", "12:32", "16:02", "19:12", "20:56"),
        PrayerDay("2026-09-09", "03:33", "12:32", "16:00", "19:09", "20:53"),
        PrayerDay("2026-09-10", "03:36", "12:32", "15:59", "19:07", "20:49"),
        PrayerDay("2026-09-11", "03:38", "12:31", "15:57", "19:04", "20:46"),
        PrayerDay("2026-09-12", "03:41", "12:31", "15:55", "19:01", "20:43"),
        PrayerDay("2026-09-13", "03:44", "12:30", "15:53", "18:59", "20:40"),
        PrayerDay("2026-09-14", "03:46", "12:30", "15:52", "18:56", "20:37"),
        PrayerDay("2026-09-15", "03:49", "12:30", "15:50", "18:53", "20:34"),
        PrayerDay("2026-09-16", "03:52", "12:29", "15:48", "18:51", "20:31"),
        PrayerDay("2026-09-17", "03:54", "12:29", "15:46", "18:48", "20:28"),
        PrayerDay("2026-09-18", "03:57", "12:29", "15:44", "18:46", "20:25"),
        PrayerDay("2026-09-19", "03:59", "12:28", "15:42", "18:43", "20:22"),
        PrayerDay("2026-09-20", "04:02", "12:28", "15:41", "18:40", "20:19"),
        PrayerDay("2026-09-21", "04:04", "12:28", "15:39", "18:38", "20:16"),
        PrayerDay("2026-09-22", "04:07", "12:27", "15:37", "18:35", "20:13"),
        PrayerDay("2026-09-23", "04:09", "12:27", "15:35", "18:32", "20:10"),
        PrayerDay("2026-09-24", "04:11", "12:27", "15:33", "18:30", "20:07"),
        PrayerDay("2026-09-25", "04:14", "12:26", "15:31", "18:27", "20:04"),
        PrayerDay("2026-09-26", "04:16", "12:26", "15:29", "18:24", "20:01"),
        PrayerDay("2026-09-27", "04:18", "12:26", "15:27", "18:22", "19:58"),
        PrayerDay("2026-09-28", "04:21", "12:25", "15:25", "18:19", "19:55"),
        PrayerDay("2026-09-29", "04:23", "12:25", "15:23", "18:17", "19:53"),
        PrayerDay("2026-09-30", "04:25", "12:25", "15:21", "18:14", "19:50"),
        PrayerDay("2026-10-01", "04:27", "12:24", "15:20", "18:11", "19:47"),
        PrayerDay("2026-10-02", "04:30", "12:24", "15:18", "18:09", "19:44"),
        PrayerDay("2026-10-03", "04:32", "12:24", "15:16", "18:06", "19:42"),
        PrayerDay("2026-10-04", "04:34", "12:23", "15:14", "18:04", "19:39"),
        PrayerDay("2026-10-05", "04:36", "12:23", "15:12", "18:01", "19:36"),
        PrayerDay("2026-10-06", "04:38", "12:23", "15:10", "17:58", "19:34"),
        PrayerDay("2026-10-07", "04:40", "12:22", "15:08", "17:56", "19:31"),
        PrayerDay("2026-10-08", "04:42", "12:22", "15:06", "17:53", "19:28"),
        PrayerDay("2026-10-09", "04:45", "12:22", "15:04", "17:51", "19:26"),
        PrayerDay("2026-10-10", "04:47", "12:22", "15:02", "17:48", "19:23"),
        PrayerDay("2026-10-11", "04:49", "12:21", "15:00", "17:46", "19:21"),
        PrayerDay("2026-10-12", "04:51", "12:21", "14:58", "17:43", "19:18"),
        PrayerDay("2026-10-13", "04:53", "12:21", "14:56", "17:41", "19:16"),
        PrayerDay("2026-10-14", "04:55", "12:21", "14:54", "17:38", "19:13"),
        PrayerDay("2026-10-15", "04:57", "12:20", "14:52", "17:36", "19:11"),
        PrayerDay("2026-10-16", "04:59", "12:20", "14:51", "17:33", "19:09"),
        PrayerDay("2026-10-17", "05:01", "12:20", "14:49", "17:31", "19:06"),
        PrayerDay("2026-10-18", "05:03", "12:20", "14:47", "17:28", "19:04"),
        PrayerDay("2026-10-19", "05:05", "12:20", "14:45", "17:26", "19:02"),
        PrayerDay("2026-10-20", "05:07", "12:19", "14:43", "17:23", "18:59"),
        PrayerDay("2026-10-21", "05:09", "12:19", "14:41", "17:21", "18:57"),
        PrayerDay("2026-10-22", "05:11", "12:19", "14:39", "17:19", "18:55"),
        PrayerDay("2026-10-23", "05:12", "12:19", "14:38", "17:16", "18:53"),
        PrayerDay("2026-10-24", "05:14", "12:19", "14:36", "17:14", "18:51"),
        PrayerDay("2026-10-25", "05:16", "12:19", "14:34", "17:12", "18:49"),
        PrayerDay("2026-10-26", "05:18", "12:18", "14:32", "17:09", "18:46"),
        PrayerDay("2026-10-27", "05:20", "12:18", "14:30", "17:07", "18:44"),
        PrayerDay("2026-10-28", "05:22", "12:18", "14:29", "17:05", "18:42"),
        PrayerDay("2026-10-29", "05:24", "12:18", "14:27", "17:03", "18:40"),
        PrayerDay("2026-10-30", "05:26", "12:18", "14:25", "17:00", "18:38"),
        PrayerDay("2026-10-31", "05:27", "12:18", "14:24", "16:58", "18:36"),
        PrayerDay("2026-11-01", "05:29", "12:18", "14:22", "16:56", "18:35"),
        PrayerDay("2026-11-02", "05:31", "12:18", "14:20", "16:54", "18:33"),
        PrayerDay("2026-11-03", "05:33", "12:18", "14:19", "16:52", "18:31"),
        PrayerDay("2026-11-04", "05:35", "12:18", "14:17", "16:50", "18:29"),
        PrayerDay("2026-11-05", "05:37", "12:18", "14:15", "16:48", "18:27"),
        PrayerDay("2026-11-06", "05:38", "12:18", "14:14", "16:46", "18:26"),
        PrayerDay("2026-11-07", "05:40", "12:18", "14:12", "16:44", "18:24"),
        PrayerDay("2026-11-08", "05:42", "12:18", "14:11", "16:42", "18:22"),
        PrayerDay("2026-11-09", "05:44", "12:18", "14:09", "16:40", "18:21"),
        PrayerDay("2026-11-10", "05:45", "12:18", "14:08", "16:38", "18:19"),
        PrayerDay("2026-11-11", "05:47", "12:19", "14:07", "16:36", "18:18"),
        PrayerDay("2026-11-12", "05:49", "12:19", "14:05", "16:34", "18:16"),
        PrayerDay("2026-11-13", "05:50", "12:19", "14:04", "16:32", "18:15"),
        PrayerDay("2026-11-14", "05:52", "12:19", "14:03", "16:31", "18:13"),
        PrayerDay("2026-11-15", "05:54", "12:19", "14:01", "16:29", "18:12"),
        PrayerDay("2026-11-16", "05:55", "12:19", "14:00", "16:27", "18:11"),
        PrayerDay("2026-11-17", "05:57", "12:19", "13:59", "16:26", "18:10"),
        PrayerDay("2026-11-18", "05:59", "12:20", "13:58", "16:24", "18:08"),
        PrayerDay("2026-11-19", "06:00", "12:20", "13:57", "16:22", "18:07"),
        PrayerDay("2026-11-20", "06:02", "12:20", "13:56", "16:21", "18:06"),
        PrayerDay("2026-11-21", "06:03", "12:20", "13:55", "16:20", "18:05"),
        PrayerDay("2026-11-22", "06:05", "12:21", "13:54", "16:18", "18:04"),
        PrayerDay("2026-11-23", "06:06", "12:21", "13:53", "16:17", "18:03"),
        PrayerDay("2026-11-24", "06:08", "12:21", "13:52", "16:15", "18:02"),
        PrayerDay("2026-11-25", "06:09", "12:21", "13:51", "16:14", "18:01"),
        PrayerDay("2026-11-26", "06:11", "12:22", "13:50", "16:13", "18:00"),
        PrayerDay("2026-11-27", "06:12", "12:22", "13:49", "16:12", "18:00"),
        PrayerDay("2026-11-28", "06:13", "12:22", "13:49", "16:11", "17:59"),
        PrayerDay("2026-11-29", "06:15", "12:23", "13:48", "16:10", "17:58"),
        PrayerDay("2026-11-30", "06:16", "12:23", "13:47", "16:09", "17:58"),
        PrayerDay("2026-12-01", "06:17", "12:24", "13:47", "16:08", "17:57"),
        PrayerDay("2026-12-02", "06:19", "12:24", "13:46", "16:07", "17:57"),
        PrayerDay("2026-12-03", "06:20", "12:24", "13:46", "16:06", "17:56"),
        PrayerDay("2026-12-04", "06:21", "12:25", "13:45", "16:06", "17:56"),
        PrayerDay("2026-12-05", "06:22", "12:25", "13:45", "16:05", "17:55"),
        PrayerDay("2026-12-06", "06:23", "12:25", "13:45", "16:04", "17:55"),
        PrayerDay("2026-12-07", "06:25", "12:26", "13:44", "16:04", "17:55"),
        PrayerDay("2026-12-08", "06:26", "12:26", "13:44", "16:03", "17:55"),
        PrayerDay("2026-12-09", "06:27", "12:27", "13:44", "16:03", "17:54"),
        PrayerDay("2026-12-10", "06:28", "12:27", "13:44", "16:03", "17:54"),
        PrayerDay("2026-12-11", "06:29", "12:28", "13:44", "16:02", "17:54"),
        PrayerDay("2026-12-12", "06:30", "12:28", "13:44", "16:02", "17:54"),
        PrayerDay("2026-12-13", "06:30", "12:29", "13:44", "16:02", "17:54"),
        PrayerDay("2026-12-14", "06:31", "12:29", "13:44", "16:02", "17:54"),
        PrayerDay("2026-12-15", "06:32", "12:30", "13:44", "16:02", "17:55"),
        PrayerDay("2026-12-16", "06:33", "12:30", "13:44", "16:02", "17:55"),
        PrayerDay("2026-12-17", "06:34", "12:31", "13:45", "16:02", "17:55"),
        PrayerDay("2026-12-18", "06:34", "12:31", "13:45", "16:03", "17:55"),
        PrayerDay("2026-12-19", "06:35", "12:32", "13:45", "16:03", "17:56"),
        PrayerDay("2026-12-20", "06:36", "12:32", "13:46", "16:03", "17:56"),
        PrayerDay("2026-12-21", "06:36", "12:33", "13:46", "16:04", "17:57"),
        PrayerDay("2026-12-22", "06:37", "12:33", "13:47", "16:04", "17:57"),
        PrayerDay("2026-12-23", "06:37", "12:34", "13:47", "16:05", "17:58"),
        PrayerDay("2026-12-24", "06:38", "12:34", "13:48", "16:05", "17:58"),
        PrayerDay("2026-12-25", "06:38", "12:35", "13:48", "16:06", "17:59"),
        PrayerDay("2026-12-26", "06:38", "12:35", "13:49", "16:07", "18:00"),
        PrayerDay("2026-12-27", "06:38", "12:36", "13:50", "16:08", "18:00"),
        PrayerDay("2026-12-28", "06:39", "12:36", "13:51", "16:09", "18:01"),
        PrayerDay("2026-12-29", "06:39", "12:36", "13:51", "16:10", "18:02"),
        PrayerDay("2026-12-30", "06:39", "12:37", "13:52", "16:11", "18:03"),
        PrayerDay("2026-12-31", "06:39", "12:37", "13:53", "16:12", "18:04")
    )



    private val names = listOf(
        Triple("Фаджр", "Иртәнге намаз", 0),
        Triple("Зухр", "Өйлә намазы", 1),
        Triple("Аср", "Икенде намазы", 2),
        Triple("Магриб", "Ахшам намазы", 3),
        Triple("Иша", "Ястү намазы", 4)
    )

    private val hijriMonthNames = listOf(
        "Мухаррам", "Сафар", "Раби аль-авваль", "Раби ас-сани",
        "Джумада аль-уля", "Джумада ас-сания", "Раджаб", "Шаабан",
        "Рамадан", "Шавваль", "Зуль-каада", "Зуль-хиджа"
    )


    // Даты для августа–декабря 2026 взяты из календаря IslamicFinder.
    // Начало месяцев: 14.08 — 1 Раби аль-авваль; 12.09 — 1 Раби ас-сани;
    // 12.10 — 1 Джумада аль-уля; 11.11 — 1 Джумада ас-сания; 11.12 — 1 Раджаб.
    private val hijriAnchors = listOf(
        Triple(LocalDate.of(2026, 8, 1), 2, 18),
        Triple(LocalDate.of(2026, 8, 14), 3, 1),
        Triple(LocalDate.of(2026, 9, 12), 4, 1),
        Triple(LocalDate.of(2026, 10, 12), 5, 1),
        Triple(LocalDate.of(2026, 11, 11), 6, 1),
        Triple(LocalDate.of(2026, 12, 11), 7, 1)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleRepository = ScheduleRepository.get(applicationContext)
        scheduleUpdateChecker = (lastNonConfigurationInstance as? ScheduleUpdateChecker)
            ?: ScheduleUpdateChecker(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val openedFromReminder = intent?.action == OPEN_PRAYER_ACTION
        selectedDate = savedInstanceState?.getString("selected_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(zone)
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        selectedCity = prefs.getString("city", "Сафаджай") ?: "Сафаджай"
        scheduleTabSelected = savedInstanceState?.getBoolean("schedule_tab") ?: false
        if (openedFromReminder) { selectedDate = LocalDate.now(zone); scheduleTabSelected = false }
        calendarMonth = java.time.YearMonth.from(selectedDate)
        createNotificationChannel()
        buildUi()
        update()
        schedulePrayerNotifications()
        val firstNotificationSetup = !prefs.contains(NOTIFICATIONS_ENABLED_KEY)
val needsNotificationPermission = firstNotificationSetup &&
    Build.VERSION.SDK_INT >= 33 &&
    ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED

if (needsNotificationPermission) {
    requestPermissions(
        arrayOf("android.permission.POST_NOTIFICATIONS"),
        7001
    )
} else {
    maybeRequestExactAlarmPermission()
    startScheduleUpdateCheck()
}
        when (if (openedFromReminder) null else savedInstanceState?.getString("panel_route")) {
            "notifications" -> showNotificationsScreen { showSettingsDialog() }
            "settings" -> showSettingsDialog()
            "qibla" -> showQiblaCompass()
        }
        
                
    
        if (openedFromReminder) intent.action = Intent.ACTION_MAIN
        handler.post(object : Runnable {
            override fun run() {
                update()
                handler.postDelayed(this, 1000L)
            }
        })
    }
private fun startScheduleUpdateCheck() {
    scheduleUpdateChecker.checkOnce(
        onProgress = { year, value ->
            showScheduleUpdateDialog(year, value)
        },
        onFinished = {
            handler.post {
                if (!scheduleUpdateCompletionPending) {
                    dismissScheduleUpdateDialog()
                }
            }
        },
        onChanged = {
            onScheduleUpdated()
        }
    )
}
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = true
        maxLines = 3
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun surface(selected: Boolean = false, radius: Int = 18): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(if (selected) Color.rgb(10, 78, 54) else Color.rgb(8, 52, 39))
        setStroke(dp(1), if (selected) mint else Color.rgb(21, 83, 60))
    }
    private fun showScheduleUpdateDialog(year: Int, value: Int) {
    handler.post {
        if (isFinishing || isDestroyed) return@post

        val safeValue = value.coerceIn(0, 100)

        if (scheduleUpdateDialog == null) {
            val gold = Color.rgb(214, 178, 77)
            val brightGreen = Color.rgb(67, 230, 145)
            val progressTrack = Color.rgb(20, 75, 56)

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(
                    dp(24),
                    dp(22),
                    dp(24),
                    dp(22)
                )

                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.rgb(7, 73, 50),
                        Color.rgb(3, 48, 34)
                    )
                ).apply {
                    cornerRadius = dp(26).toFloat()
                    setStroke(dp(2), gold)
                }
            }

            root.addView(
                label(
                    "☪",
                    34f,
                    gold,
                    true
                ).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            root.addView(
                label(
                    "Обновление расписания",
                    22f,
                    ink,
                    true
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            root.addView(
                label(
                    "Загружаем актуальные времена намаза...",
                    15f,
                    muted
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(18))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val progressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val bar = android.widget.ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0

                progressTintList =
                    android.content.res.ColorStateList.valueOf(brightGreen)

                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(progressTrack)
            }

            progressRow.addView(
                bar,
                LinearLayout.LayoutParams(
                    0,
                    dp(18),
                    1f
                )
            )

            val percent = label(
                "0%",
                20f,
                ink,
                true
            ).apply {
                gravity = Gravity.CENTER
            }

            progressRow.addView(
                percent,
                LinearLayout.LayoutParams(
                    dp(64),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(12)
                }
            )

            root.addView(
                progressRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val status = label(
                "Загружаем данные на $year год...",
                14f,
                muted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(18))
            }

            root.addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val notice = label(
                "ⓘ   Пожалуйста, не закрывайте приложение",
                14f,
                ink
            ).apply {
                gravity = Gravity.CENTER

                setPadding(
                    dp(14),
                    dp(12),
                    dp(14),
                    dp(12)
                )

                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.rgb(5, 61, 44))
                    setStroke(
                        dp(1),
                        Color.rgb(32, 126, 88)
                    )
                }
            }

            root.addView(
                notice,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val dialog = android.app.Dialog(this)

            dialog.requestWindowFeature(
                android.view.Window.FEATURE_NO_TITLE
            )

            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setContentView(root)

            dialog.show()

            val dialogWidth = minOf(
                resources.displayMetrics.widthPixels - dp(32),
                dp(520)
            ).coerceAtLeast(dp(280))

            dialog.window?.apply {
                setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(
                        Color.TRANSPARENT
                    )
                )

                addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
                )

                setDimAmount(0.55f)

                setLayout(
                    dialogWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            scheduleUpdateDialog = dialog
            scheduleUpdateBar = bar
            scheduleUpdatePercent = percent
            scheduleUpdateStatus = status
            scheduleUpdateShownAt = android.os.SystemClock.uptimeMillis()
scheduleUpdateCompletionPending = false

handler.postDelayed({
    if (
        scheduleUpdateDialog != null &&
        (scheduleUpdateBar?.progress ?: 0) < 40
    ) {
        scheduleUpdateBar?.progress = 40
        scheduleUpdatePercent?.text = "40%"
        scheduleUpdateStatus?.text =
            "Загружаем данные на $year год..."
    }
}, 400L)
        
        }
        
        if (safeValue >= 100) {
            scheduleUpdateCompletionPending = true
        }

        val minimumOffset = when {
            safeValue >= 100 -> 2300L
            safeValue >= 88 -> 1600L
            safeValue >= 68 -> 900L
            else -> 0L
        }

        val delay = (
            scheduleUpdateShownAt +
                minimumOffset -
                android.os.SystemClock.uptimeMillis()
            ).coerceAtLeast(0L)

        val applyProgress = Runnable {
            if (scheduleUpdateDialog != null) {
                scheduleUpdateBar?.progress = safeValue
                scheduleUpdatePercent?.text = "$safeValue%"

                if (safeValue >= 100) {
                    scheduleUpdateStatus?.text = "Расписание обновлено"

                    handler.postDelayed({
                        dismissScheduleUpdateDialog()
                    }, 2000L)
                } else {
                    scheduleUpdateStatus?.text =
                        "Загружаем данные на $year год..."
                }
            }
        }

        if (delay > 0L) {
            handler.postDelayed(applyProgress, delay)
        } else {
            applyProgress.run()
        }
    }
}

private fun dismissScheduleUpdateDialog() {
    handler.post {
        scheduleUpdateDialog?.dismiss()

        scheduleUpdateDialog = null
        scheduleUpdateBar = null
        scheduleUpdatePercent = null
        scheduleUpdateStatus = null
    }
}
    private fun cardBackground(next: Boolean, passed: Boolean): GradientDrawable = surface(next)
    private fun label(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView = text(value, size, color, bold).apply {
        includeFontPadding = false
    }
    private fun button(value: String, action: () -> Unit): TextView = label(value, 16f, Color.rgb(1, 38, 25), true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(mint) }
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun tabBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(if (selected) Color.rgb(32, 194, 127) else Color.TRANSPARENT)
    }

    private fun updateTabStyles() {
        if (!::todayButtonView.isInitialized || !::scheduleButtonView.isInitialized) return
        todayButtonView.background = tabBackground(!scheduleTabSelected)
        scheduleButtonView.background = tabBackground(scheduleTabSelected)
        todayButtonView.setTextColor(if (!scheduleTabSelected) Color.WHITE else Color.rgb(188, 204, 197))
        scheduleButtonView.setTextColor(if (scheduleTabSelected) Color.WHITE else Color.rgb(188, 204, 197))
    }

    private fun buildUi() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(2, 30, 22)); isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(16), bars.top + dp(6), dp(16), bars.bottom + dp(8))
            insets
        }
        headerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerBox, LinearLayout.LayoutParams(-1, -2))
        val top = android.widget.FrameLayout(this)
        top.addView(label("Намаз", 28f, ink, true).apply { gravity = Gravity.CENTER; minHeight = dp(44) }, android.widget.FrameLayout.LayoutParams(-1, -2))
        fun shortcut(res: Int, desc: String, side: Int, action: () -> Unit) {
            top.addView(ImageView(this).apply {
                setImageResource(res); setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = desc; isFocusable = true; setOnClickListener { action() }
            }, android.widget.FrameLayout.LayoutParams(dp(48), dp(44), side))
        }
        shortcut(R.drawable.ic_qibla, "Кибла", Gravity.START) { showQiblaCompass() }
        shortcut(R.drawable.ic_settings, "Настройки", Gravity.END) { showSettingsDialog() }
        headerBox.addView(top)
        placeText = label(selectedCity, 19f, mint, true).apply { gravity = Gravity.CENTER }
        headerBox.addView(placeText.apply { minHeight = dp(29) }, LinearLayout.LayoutParams(-1, -2))
        dateText = label("", 15f, muted).apply {
            gravity = Gravity.CENTER; maxLines = Int.MAX_VALUE; setPadding(0, dp(3), 0, dp(3))
            contentDescription = "Дата и календарь"; isFocusable = true
            setOnClickListener { scheduleTabSelected = true; update() }
        }
        headerBox.addView(dateText, LinearLayout.LayoutParams(-1, -2))
        currentTimeText = label("", 17f, muted, true).apply { gravity = Gravity.CENTER }
        headerBox.addView(currentTimeText.apply { minHeight = dp(29) }, LinearLayout.LayoutParams(-1, -2))
        eventBanner = label("", 13f, mint, true).apply {
            gravity = Gravity.CENTER; maxLines = Int.MAX_VALUE
            setPadding(dp(12), dp(7), dp(12), dp(7)); background = surface(); visibility = View.GONE
        }
        headerBox.addView(eventBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = surface(false, 24); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        todayButtonView = label("Сегодня", 13f, ink, true).apply {
            gravity = Gravity.CENTER; isFocusable = true
            setOnClickListener { scheduleTabSelected = false; selectedDate = LocalDate.now(zone); update() }
        }
        scheduleButtonView = label("Расписание", 13f, muted, true).apply {
            gravity = Gravity.CENTER; isFocusable = true
            setOnClickListener { scheduleTabSelected = true; calendarMonth = java.time.YearMonth.from(selectedDate); update() }
        }
        modeRow.addView(todayButtonView, LinearLayout.LayoutParams(0, dp(38), 1f))
        modeRow.addView(scheduleButtonView, LinearLayout.LayoutParams(0, dp(38), 1f))
        headerBox.addView(modeRow, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8); bottomMargin = dp(7) })
        calendarPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = surface(); setPadding(dp(8), dp(5), dp(8), dp(7)); visibility = View.GONE
        }
        val months = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prev = label("‹", 27f).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 27f); gravity = Gravity.CENTER; contentDescription = "Предыдущий месяц"; setOnClickListener { if (calendarMonth > calendarMinMonth()) { calendarMonth = calendarMonth.minusMonths(1); renderInlineCalendar() } } }
        val next = label("›", 27f).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 27f); gravity = Gravity.CENTER; contentDescription = "Следующий месяц"; setOnClickListener { if (calendarMonth < calendarMaxMonth()) { calendarMonth = calendarMonth.plusMonths(1); renderInlineCalendar() } } }
        months.addView(prev, LinearLayout.LayoutParams(dp(44), dp(40)))
        calendarTitle = label("", 16f, ink, true).apply { gravity = Gravity.CENTER }
        months.addView(calendarTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        months.addView(next, LinearLayout.LayoutParams(dp(44), dp(40)))
        calendarPanel.addView(months)
        calendarGrid = android.widget.GridLayout(this).apply { columnCount = 7; alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS }
        calendarPanel.addView(calendarGrid, LinearLayout.LayoutParams(-1, -2))
        headerBox.addView(calendarPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        holidayCard = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    visibility = View.GONE
    setPadding(dp(16), dp(12), dp(16), dp(12))

    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.rgb(8, 52, 39))
        setStroke(dp(1), Color.rgb(214, 178, 77))
    }

    val holidayHeader = LinearLayout(this@MainActivity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL

    addView(
        ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_holiday_crescent)
            scaleType = ImageView.ScaleType.FIT_CENTER
        },
        LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginEnd = dp(7)
        }
    )

    addView(
        text("Мусульманский праздник", 13f, Color.rgb(214, 178, 77), true)
    )
}
addView(holidayHeader)

    addView(
        text("", 18f, Color.WHITE, true).apply {
            tag = "holiday_title"
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        }
    )

    addView(
        text("", 13f, Color.rgb(190, 205, 198)).apply {
            tag = "holiday_hijri"
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
        }
    )

    addView(
    LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            text(
                "Нажмите, чтобы узнать подробнее",
                12f,
                Color.rgb(214, 178, 77)
            ),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        addView(
            text(
                "›",
                24f,
                Color.rgb(246, 196, 83),
                true
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(dp(28), dp(28))
        )
    },
    LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(5)
    }
)
}
headerBox.addView(
    holidayCard,
    LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(7)
    }
)
        countdownCard = android.widget.FrameLayout(this).apply { background = surface() }
        progress = CardProgressIndicator(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        countdownCard.addView(progress, android.widget.FrameLayout.LayoutParams(-1, -1))
        heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        countdownLabel = label("", 13f, muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        nextName = label("", 18f, ink, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        countdown = label("00:00:00", 48f, mint).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); maxLines = 1
            fontFeatureSettings = "tnum"; includeFontPadding = false
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 24, 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        countdownStart = label("", 14f, muted).apply { gravity = Gravity.CENTER; maxLines = Int.MAX_VALUE }
        heroCopy.addView(countdownLabel, LinearLayout.LayoutParams(-1, -2))
        heroCopy.addView(nextName, LinearLayout.LayoutParams(-1, -2))
        val timerHeight = maxOf(dp(60), (58 * resources.displayMetrics.scaledDensity).toInt())
        heroCopy.addView(countdown, LinearLayout.LayoutParams(-1, timerHeight).apply { topMargin = dp(6); bottomMargin = dp(6) })
        heroCopy.addView(countdownStart, LinearLayout.LayoutParams(-1, -2))
        countdownCard.addView(heroCopy, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = dp(20); rightMargin = dp(20)
        })
        headerBox.addView(countdownCard, LinearLayout.LayoutParams(-1, dp(174)).apply { bottomMargin = dp(7) })
        prayerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(prayerList, LinearLayout.LayoutParams(-1, -2))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (countdownCard.visibility == View.VISIBLE && scroll.height > 0) {
                // Fit five readable rows; allow scrolling if larger text or events need more room.
                val occupied = root.paddingTop + root.paddingBottom + headerBox.height - countdownCard.height + prayerList.height
                // Measure the full text, not the height already clipped by the current card.
                heroCopy.measure(
                    View.MeasureSpec.makeMeasureSpec((countdownCard.width - dp(40)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val minimum = heroCopy.measuredHeight + dp(32)
                val target = maxOf(minimum, minOf(dp(200), scroll.height - occupied))
                if (countdownCard.layoutParams.height != target) {
                    countdownCard.layoutParams = (countdownCard.layoutParams as LinearLayout.LayoutParams).apply { height = target }
                }
            }
        }
        mainScroll = scroll
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun notifyBeforeLabel(minutes: Int): String = when (minutes) {
        0 -> "В момент начала"
        60 -> "За 1 час"
        1 -> "За 1 минуту"
        else -> "За $minutes минут"
    }

    private fun selectedPrayerSummary(prefs: android.content.SharedPreferences): String {
        val count = prayerKeys.count { prefs.getBoolean("notify_$it", true) }
        return when (count) {
            5 -> "Все 5"
            0 -> "Не выбраны"
            else -> "Выбрано: $count"
        }
    }

    private fun screenRow(icon: String, title: String, value: String = "", onClick: () -> Unit): LinearLayout = makeScreenRow(label(icon, 22f, muted).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22f); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, title, value, onClick)
    private fun screenRow(iconRes: Int, title: String, value: String = "", onClick: () -> Unit): LinearLayout = makeScreenRow(ImageView(this).apply { setImageResource(iconRes); setPadding(dp(7), dp(7), dp(7), dp(7)) }, title, value, onClick)
    private fun makeScreenRow(icon: View, title: String, value: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = surface(); minimumHeight = dp(64); setPadding(dp(10), dp(5), dp(9), dp(5))
            isFocusable = true; setOnClickListener { onClick() }
            addView(icon, LinearLayout.LayoutParams(dp(34), dp(40)))
            val copy = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            copy.addView(label(title, 15f, ink).apply { maxLines = 2 })
            if (value.isNotBlank()) copy.addView(label(value, 12f, muted).apply { maxLines = 2 })
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 25f, muted).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 25f); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(22), -1))
        }
    }

    private fun fullScreenPanel(titleText: String, onBack: () -> Unit): Pair<android.app.Dialog, LinearLayout> {
        // Keep one window throughout settings navigation so the home screen is never exposed.
        val dialog = settingsPanel ?: object : android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun cancel() {
                // System Back navigates inside the panel instead of dismissing its window.
                settingsPanelBack()
            }
        }.also { panel ->
            settingsPanel = panel
            panel.setOnDismissListener {
                if (settingsPanel === panel) {
                    settingsPanel = null
                    settingsPanelBack = {}
                    panelRoute = ""
                    activeQiblaLocation?.stop(); activeQiblaLocation = null
                    activeCompass?.stop(); activeCompass = null
                }
            }
        }
        activeQiblaLocation?.stop(); activeQiblaLocation = null
        activeCompass?.stop(); activeCompass = null
        settingsPanelBack = onBack
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(2, 30, 22)); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(24)) }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(18), bars.top + dp(10), dp(18), bars.bottom + dp(24)); insets
        }
        val bar = android.widget.FrameLayout(this)
        val back = text("‹", 38f, Color.WHITE, false).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 38f); contentDescription = "Назад"; isFocusable = true; gravity = Gravity.CENTER; setOnClickListener { onBack() } }
        bar.addView(label(titleText, 20f, ink, true).apply { gravity = Gravity.CENTER; minHeight = dp(58); maxLines = Int.MAX_VALUE; setPadding(dp(46), 0, dp(46), 0) }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        bar.addView(back, android.widget.FrameLayout.LayoutParams(dp(48), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        return dialog to root
    }

    private fun showNotifyBeforeDialog(onBack: () -> Unit = {}) {
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Когда напоминать", onBack)
        dialog = pair.first
        val root = pair.second
        val values = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(8, 52, 39)) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val current = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5)
        values.forEach { minutes ->
            group.addView(RadioButton(this).apply {
                text = notifyBeforeLabel(minutes); textSize = 16f; setTextColor(Color.WHITE); isChecked = minutes == current
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    prefs.edit().putInt(NOTIFY_BEFORE_MIN_KEY, minutes).apply()
                    schedulePrayerNotifications()
                    onBack()
                }
            }, RadioGroup.LayoutParams(-1, dp(56)))
        }
        root.addView(group, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        dialog.show()
    }

    private fun showPrayerSelectionDialog(onBack: () -> Unit = {}) {
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Намазы для уведомлений", onBack)
        dialog = pair.first
        val root = pair.second
        val checked = BooleanArray(prayerKeys.size) { prefs.getBoolean("notify_${prayerKeys[it]}", true) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(8, 52, 39)) }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        prayerKeys.indices.forEach { i ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(prayerDisplayName(this, prayerRussian[i], prayerTatar[i]), 16f, Color.WHITE, false).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
            val sw = Switch(this).apply { isChecked = checked[i]; setOnCheckedChangeListener { _, v -> checked[i] = v } }
            row.setOnClickListener { sw.isChecked = !sw.isChecked }
            row.addView(sw, LinearLayout.LayoutParams(dp(58), dp(58)))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(58)))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val save = text("Сохранить", 16f, Color.rgb(1, 45, 29), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.rgb(70, 218, 145)) }
            setOnClickListener {
                val editor = prefs.edit(); prayerKeys.indices.forEach { editor.putBoolean("notify_${prayerKeys[it]}", checked[it]) }; editor.apply()
                schedulePrayerNotifications()
                onBack()
            }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(24) })
        dialog.show()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:$packageName") }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
        }
    }

    private fun maybeRequestExactAlarmPermission(force: Boolean = false) {
    if (Build.VERSION.SDK_INT < 31) return
    val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    if (prefs.contains(NOTIFICATIONS_ENABLED_KEY) && !prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false)) return
    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (am.canScheduleExactAlarms()) return
    if (!force && prefs.getBoolean(EXACT_ALARM_PROMPTED_KEY, false)) return
    prefs.edit().putBoolean(EXACT_ALARM_PROMPTED_KEY, true).apply()
    showThemedMessage(
        "Точные уведомления",
        "Чтобы напоминания о намазе приходили точно в выбранное время, разрешите точные будильники в настройках телефона.",
        "Открыть настройки",
        "Позже"
    ) { openExactAlarmSettings() }
}

    private fun showCityChoice(refreshSettings: () -> Unit) {
        val pair = fullScreenPanel("Город") { refreshSettings() }
        pair.second.addView(label("Выберите город для расписания", 14f, muted).apply { setPadding(0, dp(16), 0, dp(16)) })
        listOf("Сафаджай", "Москва").forEach { city ->
            val choice = RadioButton(this).apply {
                text = city; textSize = 18f; setTextColor(ink); isChecked = selectedCity == city
                background = surface(isChecked); setPadding(dp(15), dp(8), dp(15), dp(8))
                setOnClickListener {
                    selectedCity = city
                    getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putString("city", city).apply()
                    update(); schedulePrayerNotifications(); refreshSettings()
                }
            }
            choice.minimumHeight = dp(64)
            pair.second.addView(choice, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        pair.second.addView(label("Время намазов зависит от выбранного города. Направление киблы определяется по местоположению телефона.", 14f, muted).apply { setPadding(0, dp(12), 0, 0) })
        pair.first.show()
    }

    private fun showNotificationsScreen(onBack: () -> Unit = {}) {
        panelRoute = "notifications"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Уведомления", onBack)
        dialog = pair.first; val root = pair.second
        val master = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(8, 52, 39)) }
        }
        master.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_notification)
            setPadding(dp(7), dp(16), dp(7), dp(16))
            contentDescription = "Уведомления"
        }, LinearLayout.LayoutParams(dp(36), dp(58)))
        master.addView(text("Уведомления", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL; minHeight = dp(58) }, LinearLayout.LayoutParams(0, -2, 1f))
        master.addView(Switch(this).apply {
            contentDescription = "Уведомления"
            isChecked = prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(NOTIFICATIONS_ENABLED_KEY, checked).apply(); schedulePrayerNotifications(); if (checked) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7001)
                else maybeRequestExactAlarmPermission(true)
            } }
        }, LinearLayout.LayoutParams(dp(60), dp(58)))
        master.minimumHeight = dp(66)
        root.addView(master, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(text("Приложение будет напоминать о выбранных намазах в указанное вами время.", 13f, Color.rgb(170, 198, 186), false).apply { maxLines = Int.MAX_VALUE; setPadding(dp(8), dp(12), dp(8), dp(12)) })
        root.addView(screenRow("◷", "Когда напоминать", notifyBeforeLabel(prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5))) { showNotifyBeforeDialog { showNotificationsScreen(onBack) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(screenRow("✓", "Намазы для уведомлений", selectedPrayerSummary(prefs)) { showPrayerSelectionDialog { showNotificationsScreen(onBack) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val sound = runCatching { RingtoneManager.getRingtone(this, selectedNotificationSound(this))?.getTitle(this) }.getOrNull() ?: "Системный звук"
        root.addView(screenRow("♪", "Звук уведомления", sound) { showSoundScreen() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            root.addView(screenRow("◉", "Точные уведомления", if (am.canScheduleExactAlarms()) "Разрешены" else "Нужно разрешить") { maybeRequestExactAlarmPermission(true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(label("Со звуком и вибрацией", 13f, muted).apply { gravity = Gravity.CENTER; background = surface(); setPadding(dp(10), dp(16), dp(10), dp(16)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        dialog.show()
    }

    private fun showSettingsDialog() {
        panelRoute = "settings"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        fun create(): Pair<android.app.Dialog, LinearLayout> = fullScreenPanel("Настройки") { dialog.dismiss() }
        val pair = create(); dialog = pair.first; val root = pair.second

        root.addView(screenRow(R.drawable.ic_notification, "Уведомления", if (prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false)) "Включены" else "Выключены") { showNotificationsScreen { showSettingsDialog() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(screenRow(R.drawable.ic_location, "Город", selectedCity) { showCityChoice { showSettingsDialog() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12)); background = surface()
        }
        val languageLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        languageLabels.addView(label("Названия намазов на татарском", 16f, ink))
        languageLabels.addView(label("Показывать рядом с русскими", 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
        languageRow.addView(languageLabels, LinearLayout.LayoutParams(0, -2, 1f))
        val languageSwitch = Switch(this).apply {
            contentDescription = "Названия намазов на татарском"
            isChecked = prefs.getBoolean(SHOW_TATAR_NAMES_KEY, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(SHOW_TATAR_NAMES_KEY, checked).apply()
                update()
            }
        }
        languageRow.addView(languageSwitch, LinearLayout.LayoutParams(dp(56), dp(52)))
        languageRow.setOnClickListener { languageSwitch.isChecked = !languageSwitch.isChecked }
        root.addView(languageRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(screenRow("ⓘ", "О приложении", "Версия ${packageManager.getPackageInfo(packageName, 0).versionName}") {
            showAboutDialog()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.show()
    }

    override fun onRetainNonConfigurationInstance(): Any = scheduleUpdateChecker

    private fun onScheduleUpdated() {
        // A completed download must update alarms even if the activity has closed.
        alarmExecutor.execute {
            val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            val city = prefs.getString("city", "Сафаджай") ?: "Сафаджай"
            runCatching { rebuildPrayerNotifications(dataForCity(city), city,
                prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false),
                prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180),
                prayerKeys.filter { prefs.getBoolean("notify_$it", true) }.toSet()) }
                .onFailure { android.util.Log.w("ScheduleUpdate", "Alarm refresh failed: ${it.javaClass.simpleName}") }
        }
        handler.post {
            if (!isDestroyed && !isFinishing) {
                lastAlarmSignature = ""; lastCalendarRender = ""; lastPrayerRender = ""
                update()
            }
        }
    }

    private fun showAboutDialog() {
        aboutDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20)); background = surface(false, 24)
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_source)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        root.addView(label(getString(R.string.app_name), 24f, ink, true).apply {
            gravity = Gravity.CENTER; maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        root.addView(label("Версия $version", 15f, muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(button("ОК") { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val scroll = ScrollView(this).apply { addView(root) }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { if (aboutDialog === dialog) aboutDialog = null }
        aboutDialog = dialog
        dialog.show()
        dialog.window?.setLayout(minOf(dp(360), resources.displayMetrics.widthPixels-dp(40)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showQiblaCompass() {
        panelRoute = "qibla"
        val pair = fullScreenPanel("Кибла") { settingsPanel?.dismiss() }
        val root = pair.second
        val locationTitle = label("Ищем местоположение", 18f, mint, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); maxLines = 2
        }
        root.addView(locationTitle)
        root.addView(label("Держите телефон плашмя", 14f, muted).apply { gravity = Gravity.CENTER })
        val compass = QiblaCompassView(this)
        activeCompass = compass
        // Begin sensor startup during screen construction, before showing the window.
        compass.start()
        val compassSize = minOf(resources.displayMetrics.widthPixels / resources.displayMetrics.density - 40,
            resources.displayMetrics.heightPixels / resources.displayMetrics.density - 360).coerceAtLeast(210f).toInt()
        root.addView(compass, LinearLayout.LayoutParams(-1, dp(compassSize)).apply { topMargin = dp(18); bottomMargin = dp(14) })
        val hint = label("Получаем координаты телефона", 15f, ink).apply {
            gravity = Gravity.CENTER; background = surface(); maxLines = Int.MAX_VALUE
            // Reserve room for the calibration hint as well as the shorter ready
            // message. Long errors and enlarged accessibility text may still expand.
            minLines = 4
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        val locationAction = button("Обновить местоположение") { activeQiblaLocation?.performAction() }
        root.addView(locationAction, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        var hasLocation = false
        var aligned = false
        var lowAccuracy = true
        var locationDescription = "Получаем координаты телефона"
        fun updateHint() {
            val message = when {
                !compass.hasCompass() -> "На телефоне нет поддерживаемого датчика компаса. Направление недоступно."
                !hasLocation -> locationDescription
                !compass.hasOrientation() -> "Определяем направление…\nДержите телефон плашмя"
                lowAccuracy -> "Держите телефон плашмя, вдали от металла и магнитов.\nПри необходимости выполните калибровку."
                aligned -> "Вы направлены к кибле"
                else -> "Поверните телефон\nСовместите Каабу с меткой сверху"
            }
            if (hint.text.toString() != message) hint.text = message
            hint.setTextColor(if (hasLocation && aligned && !lowAccuracy) mint else ink)
        }
        compass.onDirection = { ready, inaccurate -> aligned = ready; lowAccuracy = inaccurate; updateHint() }
        activeQiblaLocation = QiblaLocationController(this, { fix ->
            hasLocation = fix != null; compass.setLocation(fix); updateHint()
        }, { title, description, actionText ->
            locationTitle.text = title; locationDescription = description; locationAction.text = actionText; updateHint()
        })
        root.addView(button("Калибровка компаса") { showCalibration() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        pair.first.show()
        if (compass.hasCompass()) {
            activeQiblaLocation?.start(askOnFirstUse = true)
        } else { locationTitle.text = "Компас недоступен"; locationAction.visibility = View.GONE; updateHint() }
    }

    private fun showCalibration() {
        val pair = fullScreenPanel("Калибровка компаса") { showQiblaCompass() }
        pair.second.addView(label("∞", 110f, mint).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(170)))
        pair.second.addView(label("1. Отойдите от металла и магнитов.\n\n2. Плавно опишите телефоном восьмёрку несколько раз.\n\n3. Вернитесь к компасу и проверьте направление.", 17f, ink).apply { maxLines = Int.MAX_VALUE; setPadding(dp(16), dp(16), dp(16), dp(16)); background = surface() })
        pair.second.addView(button("Вернуться к кибле") { showQiblaCompass() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        pair.first.show()
    }

    private fun showThemedMessage(title: String, message: String, positive: String, negative: String? = null, action: () -> Unit) {
        val builder = android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(positive) { _, _ -> action() }
        if (negative != null) builder.setNegativeButton(negative, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(surface(false, 22))
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(ink)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(mint)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(muted)
        }
        dialog.show()
    }

    private fun createNotificationChannel() {
        ensurePrayerNotificationChannel(this)
    }

    private fun showSoundScreen() {
        val pair = fullScreenPanel("Звук уведомления") { showNotificationsScreen { showSettingsDialog() } }
        val selected = runCatching { RingtoneManager.getRingtone(this, selectedNotificationSound(this))?.getTitle(this) }.getOrNull() ?: "Системный звук"
        pair.second.addView(label("Выбранный звук\n$selected", 18f, ink).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(24), dp(16), dp(24)); background = surface() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        pair.second.addView(button("Выбрать звук") { openNotificationSoundPicker() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        pair.second.addView(label("Откроется список звуков вашего телефона.", 14f, muted).apply { setPadding(0, dp(18), 0, 0) })
        pair.first.show()
    }

    private fun openNotificationSoundPicker() {
        val current = selectedNotificationSound(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Выберите звук уведомления")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        }
        runCatching { startActivityForResult(intent, SOUND_PICKER_REQUEST) }.onFailure {
            Toast.makeText(this, "На телефоне недоступен выбор звука", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API, retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SOUND_PICKER_REQUEST || resultCode != Activity.RESULT_OK) return
        val picked = if (Build.VERSION.SDK_INT >= 33) {
            data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (picked != null) {
            getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SOUND_URI_KEY, picked.toString()).apply()
            ensurePrayerNotificationChannel(this)
            schedulePrayerNotifications()
            if (panelRoute == "notifications") showNotificationsScreen { showSettingsDialog() }
            Toast.makeText(this, "Звук уведомления сохранён", Toast.LENGTH_SHORT).show()
        }
    }

    private fun schedulePrayerNotifications() {
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val signature = listOf(selectedCity, LocalDate.now(zone), scheduleRepository.revision(), prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false), prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5), prefs.getString(SOUND_URI_KEY, ""), prayerKeys.map { prefs.getBoolean("notify_$it", true) }, if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true).joinToString("|")
        if (signature == lastAlarmSignature) return
        lastAlarmSignature = signature
        val days = currentData().toList()
        val city = selectedCity
        val enabled = prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false)
        val before = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180)
        val selectedKeys = prayerKeys.filter { prefs.getBoolean("notify_$it", true) }.toSet()
        alarmExecutor.execute { runCatching { rebuildPrayerNotifications(days, city, enabled, before, selectedKeys) }.onFailure {
            handler.post { lastAlarmSignature = ""; if (!isDestroyed) Toast.makeText(this, "Не удалось настроить уведомления. Проверьте разрешения.", Toast.LENGTH_LONG).show() }
        } }
    }

    private fun rebuildPrayerNotifications(days: List<PrayerDay>, city: String, notificationsEnabled: Boolean, notifyBeforeMinutes: Int, selectedKeys: Set<String>) {
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val today = LocalDate.now(zone)
        val entries = mutableListOf<String>()
        val previousEntries = prefs.getString("scheduled_prayers", "").orEmpty()
        // Keep all downloaded future dates, including the next year, for the receiver chain.
        for (day in days) {
            val date = LocalDate.parse(day.date)
            if (date.isBefore(today)) continue
            val times = listOf(day.fajr, day.zuhr, day.asr, day.maghrib, day.isha)
            for (i in times.indices) {
                val key = prayerKeys[i]
                if (!notificationsEnabled || key !in selectedKeys) continue
                entries += listOf(
                    date.toString(), key, prayerRussian[i], prayerTatar[i], times[i], city
                ).joinToString("|")
            }
        }
        prefs.edit().putString("scheduled_prayers", entries.joinToString("\n")).apply()

        // Cancel the prior date/key identities, regardless of their year or city.
        previousEntries.lineSequence().filter { it.isNotBlank() }.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size >= 2) {
                val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull()
                if (date != null && parts[1] in prayerKeys) {
                    val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    PendingIntent.getBroadcast(this, alarmRequestCode(date, parts[1]),
                        Intent(this, PrayerNotificationReceiver::class.java), flags)?.let { existing ->
                        am.cancel(existing); existing.cancel()
                    }
                }
            }
        }

        // Rebuild the near-term alarms from the stored schedule.
        entries.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size < 6) return@forEach
            val date = LocalDate.parse(parts[0])
            val key = parts[1]
            val idx = prayerKeys.indexOf(key)
            if (idx < 0) return@forEach
            val time = parts[4]
            val dt = dateTime(date, time).minusMinutes(notifyBeforeMinutes.toLong())
            if (dt.isBefore(LocalDateTime.now(zone))) return@forEach
            val requestCode = alarmRequestCode(date, key)
            if (date.isAfter(today.plusDays(14))) return@forEach
            val pi = pendingIntentFor(
                this, requestCode, parts[2], parts[3], time, key, date.toString()
            )
            am.cancel(pi)
            setPrayerAlarm(am, dt.atZone(zone).toInstant().toEpochMilli(), pi)
        }
    }

    private fun alarmRequestCode(date: LocalDate, key: String): Int {
        val index = prayerKeys.indexOf(key).coerceAtLeast(0)
        return ((date.toEpochDay() % 100000L) * 10L + index).toInt()
    }

    private fun pendingIntentFor(
        context: Context, requestCode: Int, prayer: String, tatar: String,
        time: String, key: String, date: String
    ): PendingIntent {
        val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
            putExtra("prayer", prayer)
            putExtra("tatar", tatar)
            putExtra("time", time)
            putExtra("key", key)
            putExtra("date", date)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun setPrayerAlarm(am: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun currentData(): List<PrayerDay> = dataForCity(selectedCity)

    private fun dataForCity(city: String): List<PrayerDay> =
        if (city == "Москва") scheduleRepository.merged("moscow", moscowData)
        else scheduleRepository.merged("safadzhay", safadzhayData)

    private fun calendarMinMonth(): java.time.YearMonth = java.time.YearMonth.from(
        minOf(LocalDate.now(zone), LocalDate.parse(currentData().first().date)))

    private fun calendarMaxMonth(): java.time.YearMonth = java.time.YearMonth.from(
        maxOf(LocalDate.now(zone), LocalDate.parse(currentData().last().date)))

    private fun formatRussianDate(date: LocalDate): String {
        val months = listOf(
            "Января", "Февраля", "Марта", "Апреля", "Мая", "Июня",
            "Июля", "Августа", "Сентября", "Октября", "Ноября", "Декабря"
        )
        return "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
    }

    private fun dayFor(date: LocalDate): PrayerDay? = currentData().firstOrNull { it.date == date.toString() }

    private fun renderInlineCalendar() {
        calendarMonth = calendarMonth.coerceIn(calendarMinMonth(), calendarMaxMonth())
        if (!::calendarGrid.isInitialized) return
        val renderKey = "$calendarMonth|$selectedDate|${LocalDate.now(zone)}"
        if (lastCalendarRender == renderKey) return
        lastCalendarRender = renderKey

        val monthNames = listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
        )
        calendarTitle.text = "${monthNames[calendarMonth.monthValue - 1]} ${calendarMonth.year}"
        calendarGrid.removeAllViews()

        val weekdays = listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС")
        weekdays.forEach { name ->
            val dayName = text(name, 11f, Color.rgb(150, 175, 164), true).apply {
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
            }
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = dp(24)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            calendarGrid.addView(dayName, lp)
        }

        val first = calendarMonth.atDay(1)
        val leading = first.dayOfWeek.value - 1
        val startDate = first.minusDays(leading.toLong())
        val today = LocalDate.now(zone)
        val selected = selectedDate ?: today

        val cells = ((leading + calendarMonth.lengthOfMonth() + 6) / 7) * 7
        for (i in 0 until cells) {
            val d = startDate.plusDays(i.toLong())
            val inMonth = d.monthValue == calendarMonth.monthValue && d.year == calendarMonth.year
            val isSelected = d == selected
            val isToday = d == today
            val hasEvent = eventsFor(d).isNotEmpty()
            val isFriday = d.dayOfWeek == java.time.DayOfWeek.FRIDAY
            val hasHoliday = HolidayCalendar.holidayFor(d) != null

            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = inMonth
                isFocusable = isClickable
                contentDescription = formatRussianDate(d) + eventsFor(d).joinToString("; ", prefix = if (hasEvent) "; " else "")
                this.isSelected = isSelected
                if (isClickable) {
                    setOnClickListener {
                        selectedDate = d
                        scheduleTabSelected = true
                        calendarMonth = java.time.YearMonth.from(d)
                        updateTabStyles()
                        update()
                    }
                }
            }

            val dayNumber = text(
                d.dayOfMonth.toString(),
                15f,
                if (!inMonth) Color.rgb(88, 105, 98) else Color.WHITE,
                isSelected
            ).apply {
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                if (isSelected || (hasHoliday && inMonth)) {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL

       if (isSelected && !hasHoliday) {
    setColor(Color.rgb(32, 194, 127))
} else {
    setColor(Color.TRANSPARENT)
}

        
    }
}
            }
            dayNumber.maxLines = 1
            val numberHeight = maxOf(dp(27), kotlin.math.ceil(dayNumber.paint.fontSpacing).toInt())
            val numberWidth = maxOf(dp(29), kotlin.math.ceil(dayNumber.paint.measureText("31")).toInt() + dp(4))
            val dayContent = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER

    if (hasHoliday && inMonth) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.rgb(246, 196, 83))
        }
    }
}

dayContent.addView(
    dayNumber,
    LinearLayout.LayoutParams(numberWidth, numberHeight)
)



            val marker = ImageView(this).apply {
    if (hasHoliday && inMonth) {
        setImageResource(R.drawable.ic_holiday_crescent)
        visibility = View.VISIBLE
    } else {
        visibility = View.INVISIBLE
    }

    scaleType = ImageView.ScaleType.FIT_CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

if (hasHoliday && inMonth) {
    dayContent.addView(
        marker,
        LinearLayout.LayoutParams(dp(15), dp(15)).apply {
            topMargin = -dp(7)
        }
    )
}
cell.addView(
    dayContent,
    if (hasHoliday && inMonth) {
        LinearLayout.LayoutParams(dp(38), dp(38))
    } else {
        LinearLayout.LayoutParams(numberWidth, numberHeight)
    }
)

val fridayDot = TextView(this).apply {
    gravity = Gravity.CENTER
    text = if (isFriday && inMonth) "•" else ""
    textSize = 12f
    setTextColor(
        if (isToday) Color.rgb(48, 228, 161)
        else Color.rgb(100, 190, 150)
    )
    setIncludeFontPadding(false)
}

cell.addView(
    fridayDot,
    LinearLayout.LayoutParams(dp(34), dp(8))
)
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = maxOf(dp(44), numberHeight + dp(17))
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            calendarGrid.addView(cell, lp)
        }
    }

    private fun updateHolidayCard(date: LocalDate) {
    val holiday = HolidayCalendar.holidayFor(date)

    if (holiday == null) {
        holidayCard.visibility = View.GONE
        return
    }

    val titleView = holidayCard.findViewWithTag<TextView>("holiday_title")
    val hijriView = holidayCard.findViewWithTag<TextView>("holiday_hijri")

    titleView.text = holiday.title
    hijriView.text = if (holiday.hijriDate.isNotBlank()) {
        holiday.hijriDate
    } else {
        hijriText(date)
    }
holidayCard.setOnClickListener {
    showHolidayDetails(holiday)
}
    holidayCard.visibility = View.VISIBLE
}
    private fun showHolidayDetails(holiday: Holiday) {
    val dialog = android.app.Dialog(this)

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))

        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.rgb(8, 52, 39))
            setStroke(dp(1), Color.rgb(214, 178, 77))
        }
    }

    val holidayDetailsIcon = ImageView(this).apply {
    setImageResource(R.drawable.ic_holiday_crescent)
    scaleType = ImageView.ScaleType.FIT_CENTER
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

content.addView(
    holidayDetailsIcon,
    LinearLayout.LayoutParams(dp(72), dp(72)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(10)
    }
)

    content.addView(
        text(holiday.title, 21f, Color.WHITE, true),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        }
    )

    val hijri = if (holiday.hijriDate.isNotBlank()) {
        holiday.hijriDate
    } else {
        hijriText(holiday.date)
    }

    content.addView(
        text(hijri, 14f, Color.rgb(190, 205, 198)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        }
    )

    content.addView(
        text(holiday.description, 15f, Color.WHITE),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        }
    )

    val close = text("Закрыть", 15f, Color.rgb(214, 178, 77), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener {
            dialog.dismiss()
        }
    }

    content.addView(
        close,
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        }
    )

    dialog.setContentView(content)

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setGravity(Gravity.BOTTOM)
    }

    dialog.show()

    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels * 0.92f).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    }
    private fun openDatePicker() {
        val minDate = calendarMinMonth().atDay(1)
        val maxDate = calendarMaxMonth().atEndOfMonth()
        val initial = selectedDate?.coerceIn(minDate, maxDate) ?: LocalDate.now(zone).coerceIn(minDate, maxDate)
        val dialog = DatePickerDialog(this, { _, year, month, dayOfMonth ->
            selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
            scheduleTabSelected = true
            updateTabStyles()
            update()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.datePicker.minDate = minDate.atStartOfDay(zone).toInstant().toEpochMilli()
        dialog.datePicker.maxDate = maxDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        dialog.show()
    }

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
        val todayDate = now.toLocalDate()
        prayerList.visibility = View.VISIBLE
        if (!scheduleTabSelected) selectedDate = todayDate
        if (selectedDate == null) selectedDate = todayDate
        if (lastDay != todayDate) { lastDay = todayDate; schedulePrayerNotifications() }
        val selected = selectedDate!!
        updateHolidayCard(selected)
        if (selected != todayDate && !scheduleTabSelected) scheduleTabSelected = true
        updateTabStyles()
        placeText.text = selectedCity
        dateText.text = "${formatRussianDate(selected)}\n${hijriText(selected)}"
        dateText.contentDescription = "${dateText.text}. Открыть календарь"
        currentTimeText.text = "Сейчас " + now.format(DateTimeFormatter.ofPattern("HH:mm"))
        updateTodayEventBanner(if (scheduleTabSelected) selected else todayDate)
        if (scheduleTabSelected) {
            countdownCard.visibility = View.GONE
            calendarPanel.visibility = View.VISIBLE
            renderInlineCalendar()
        } else {
            calendarPanel.visibility = View.GONE
            countdownCard.visibility = View.VISIBLE
        }

        val selectedDay = dayFor(selected)
        if (selectedDay == null) {
            nextName.text = "Расписание"
            countdownLabel.visibility = View.VISIBLE
            countdown.text = "—"
            countdownLabel.text = "Нет расписания на эту дату"
            progress.progress = 0f
            countdownStart.text = ""
            val missingKey = "missing|$selected|$selectedCity"
            if (lastPrayerRender != missingKey) {
                lastPrayerRender = missingKey
                prayerList.removeAllViews()
                prayerList.addView(label("На эту дату расписание отсутствует.", 14f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(16)) })
            }
            return
        }

        val prayers = getPrayers(selectedDay)
        if (selected != todayDate) {
            nextName.text = "Расписание"
            countdownLabel.visibility = View.VISIBLE
            countdown.text = "—"
            countdownLabel.text = "Намазы на ${formatRussianDate(selected)}"
            progress.progress = 0f
            renderPrayers(prayers, -1, now, selected)
            return
        }

        data class PrayerEvent(val prayer: Prayer, val time: LocalDateTime)

        val eventsToday = mutableListOf<PrayerEvent>()
        val basePrayers = getPrayers(selectedDay)
        basePrayers.forEach { p -> eventsToday += PrayerEvent(p, dateTime(todayDate, p.time)) }

        eventsToday.sortBy { it.time }
        val nextEvent = eventsToday.firstOrNull { it.time.isAfter(now) }
        val previousEvent = eventsToday.lastOrNull { !it.time.isAfter(now) }

        if (nextEvent != null) {
            val previousTime = previousEvent?.time
                ?: dayFor(todayDate.minusDays(1))?.let { dateTime(todayDate.minusDays(1), it.isha) }
                ?: todayDate.atStartOfDay()
            val total = Duration.between(previousTime, nextEvent.time).seconds.coerceAtLeast(1)
            val left = Duration.between(now, nextEvent.time).seconds.coerceAtLeast(0)
            progress.progress = (1.0 - left.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
            countdown.text = String.format("%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
            nextName.text = prayerDisplayName(this, nextEvent.prayer.name, nextEvent.prayer.tatar)
            countdownLabel.visibility = View.GONE
            countdownStart.text = "До начала намаза · ${nextEvent.prayer.time}"
        } else {
            val tomorrow = todayDate.plusDays(1)
            val tomorrowDay = dayFor(tomorrow)
            if (tomorrowDay != null) {
                val nextFajr = dateTime(tomorrow, tomorrowDay.fajr)
                val previousTime = eventsToday.lastOrNull()?.time ?: now
                val total = Duration.between(previousTime, nextFajr).seconds.coerceAtLeast(1)
                val left = Duration.between(now, nextFajr).seconds.coerceAtLeast(0)
                progress.progress = (1.0 - left.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
                countdown.text = String.format("%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
                nextName.text = prayerDisplayName(this, "Фаджр", "Иртәнге намаз")
                countdownLabel.visibility = View.GONE
                countdownStart.text = "Завтра · ${tomorrowDay.fajr}"
            } else {
                nextName.text = "Расписание"
                countdownLabel.visibility = View.VISIBLE
                countdown.text = "—"
                countdownLabel.text = "Период расписания завершён"
                countdownStart.text = ""
                progress.progress = 0f
            }
        }

        val nextIndex = prayers.indexOfFirst { it.name == nextEvent?.prayer?.name && it.time == nextEvent?.prayer?.time }
        renderPrayers(prayers, nextIndex, now, todayDate)
    }

    private fun hijriFor(date: LocalDate): HijriDate? {
        if (date < LocalDate.of(2026, 8, 1) || date > LocalDate.of(2026, 12, 31)) {
            return runCatching { java.time.chrono.HijrahDate.from(date).let { HijriDate(it.get(java.time.temporal.ChronoField.DAY_OF_MONTH), it.get(java.time.temporal.ChronoField.MONTH_OF_YEAR), it.get(java.time.temporal.ChronoField.YEAR)) } }.getOrNull()
        }
        val anchor = hijriAnchors.lastOrNull { !date.isBefore(it.first) } ?: return null
        var month = anchor.second
        var day = anchor.third + java.time.temporal.ChronoUnit.DAYS.between(anchor.first, date).toInt()
        var year = 1448
        while (day > 30) {
            day -= if (month % 2 == 0) 29 else 30
            month++
            if (month > 12) { month = 1; year++ }
        }
        return HijriDate(day, month, year)
    }

    private fun eventsFor(date: LocalDate): List<String> = HolidayCalendar.labels(date)

    private fun hijriText(date: LocalDate): String {
        val h = hijriFor(date) ?: return "Дата по Хиджре: —"
        return "${h.day} ${hijriMonthNames[h.month - 1]} ${h.year} г. х."
    }

    private fun updateTodayEventBanner(today: LocalDate) {
        val now = LocalDateTime.now(zone)
        val asr = dayFor(today)?.asr?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        val items = HolidayCalendar.bannerLabels(today, now, asr)
        if (items.isEmpty()) { eventBanner.text = ""; eventBanner.visibility = View.GONE; return }
        eventBanner.visibility = View.VISIBLE
        val prefix = if (today == now.toLocalDate()) "Сегодня: " else ""
        eventBanner.text = items.joinToString("\n") { prefix + it }
    }

    private fun renderPrayers(prayers: List<Prayer>, nextIndex: Int, now: LocalDateTime, displayDate: LocalDate) {
        val showTatar = showTatarNames(this)
        val renderKey = "$selectedCity|$displayDate|$nextIndex|${now.toLocalDate()}|${now.hour}:${now.minute}|$showTatar|${prayers.joinToString()}"
        if (lastPrayerRender == renderKey) return
        lastPrayerRender = renderKey
        prayerList.removeAllViews()
        for ((index, p) in prayers.withIndex()) {
            val hasTime = p.time.matches(Regex("\\d{1,2}:\\d{2}"))
            val eventDateTime = if (hasTime) dateTime(displayDate, p.time) else null
            val isNext = hasTime && index == nextIndex
            val passed = hasTime && eventDateTime != null && eventDateTime.isBefore(now) && !isNext
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(66)
                background = cardBackground(isNext, passed)
                alpha = if (passed && displayDate == now.toLocalDate()) 0.70f else 1f
            }
            row.addView(PrayerIconView(this, p.name, if (isNext) mint else muted), LinearLayout.LayoutParams(dp(34), dp(42)).apply { rightMargin = dp(8) })
            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val ru = text(p.name, 18f, ink, true).apply {
                gravity = Gravity.START
                setIncludeFontPadding(false)
                maxLines = 2
            }
            val tt = text("(${p.tatar})", 13f, Color.rgb(171, 202, 190), false).apply {
                visibility = if (showTatar) View.VISIBLE else View.GONE
                gravity = Gravity.START
                setIncludeFontPadding(false)
                maxLines = Int.MAX_VALUE
            }
            nameBox.addView(ru, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            nameBox.addView(tt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            val time = text(p.time, 25f, if (isNext) Color.rgb(91, 224, 164) else Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setIncludeFontPadding(false)
                maxLines = 1
                minWidth = dp(84)
            }
            row.addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val timeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            timeLp.marginStart = dp(8)
            row.addView(time, timeLp)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(6)
            prayerList.addView(row, lp)
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        if (newIntent.action == OPEN_PRAYER_ACTION) {
            aboutDialog?.dismiss()
            settingsPanel?.dismiss()
            panelRoute = ""
            scheduleTabSelected = false
            selectedDate = LocalDate.now(zone)
            calendarMonth = java.time.YearMonth.from(selectedDate)
            update()
            mainScroll.scrollTo(0, 0)
            newIntent.action = Intent.ACTION_MAIN
        }
    }

    override fun onResume() {
    super.onResume()

    if (::placeText.isInitialized) {
        schedulePrayerNotifications()
        update()

        if (panelRoute == "notifications") {
            showNotificationsScreen { showSettingsDialog() }
        }
    }

    activeCompass?.start()

    if (activeCompass?.hasCompass() == true) {
        activeQiblaLocation?.start()
    }
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (requestCode == 7001) {
        val granted =
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED

        getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(NOTIFICATIONS_ENABLED_KEY, granted)
            .apply()

        schedulePrayerNotifications()

        if (granted) {
            maybeRequestExactAlarmPermission()
        }

        if (panelRoute == "notifications") {
    showNotificationsScreen { showSettingsDialog() }
}

startScheduleUpdateCheck()
    }

    if (requestCode == QiblaLocationController.REQUEST_CODE) {
        activeQiblaLocation?.start()
    }
}

        


    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_date", selectedDate?.toString())
        outState.putBoolean("schedule_tab", scheduleTabSelected)
        outState.putString("panel_route", panelRoute)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { activeQiblaLocation?.stop(); activeCompass?.stop(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeQiblaLocation?.stop()
        activeCompass?.stop()
        aboutDialog?.dismiss()
        settingsPanel?.dismiss()
        scheduleUpdateDialog?.dismiss()
        scheduleUpdateDialog = null
        scheduleUpdateBar = null
        scheduleUpdatePercent = null
        scheduleUpdateStatus = null
        super.onDestroy()
    }
}

class PrayerNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayer = intent.getStringExtra("prayer") ?: return
        val tatar = intent.getStringExtra("tatar") ?: ""
        val time = intent.getStringExtra("time") ?: ""
        val key = intent.getStringExtra("key") ?: return
        val date = intent.getStringExtra("date") ?: return

        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false) || !prefs.getBoolean("notify_$key", true)) {
            scheduleNextStoredPrayer(context, date, key)
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = selectedNotificationSound(context)
        val channelId = ensurePrayerNotificationChannel(context)
        val minutes = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180)
        val reminderText = if (minutes == 0) "Время намаза: $time" else "До намаза $minutes мин. Время намаза: $time"
        val openApp = Intent(context, MainActivity::class.java).apply {
            action = OPEN_PRAYER_ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(context, 8106, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Напоминание: ${prayerDisplayName(context, prayer, tatar)}")
            .setContentText(reminderText)
            .setContentIntent(openAppPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSound(sound)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setVibrate(longArrayOf(0, 500, 250, 500))

        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context)
                .notify(((LocalDate.parse(date).toEpochDay() % 100000L) * 10L +
                    listOf("fajr", "zuhr", "asr", "maghrib", "isha").indexOf(key).coerceAtLeast(0)).toInt(), builder.build())
        }

        // Schedule the next occurrence of this same prayer without requiring the app UI.
        scheduleNextStoredPrayer(context, date, key)
    }
}

private fun scheduleNextStoredPrayer(context: Context, firedDate: String, key: String) {
    val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false) || !prefs.getBoolean("notify_$key", true)) return
    val notifyBeforeMinutes = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180)
    val lines = prefs.getString("scheduled_prayers", "")
        .orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    val fired = runCatching { LocalDate.parse(firedDate) }.getOrNull() ?: return
    val next = lines.mapNotNull { line ->
        val p = line.split("|")
        if (p.size < 6 || p[1] != key) return@mapNotNull null
        val d = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@mapNotNull null
        if (!d.isAfter(fired)) return@mapNotNull null
        Triple(d, p[4], p)
    }.minByOrNull { it.first }
        ?: return

    val dt = runCatching { next.first.atTime(next.second.substringBefore(":").toInt(), next.second.substringAfter(":").toInt()).minusMinutes(notifyBeforeMinutes.toLong()) }.getOrNull()
        ?: return
    if (!dt.isAfter(LocalDateTime.now(ZoneId.of("Europe/Moscow")))) return

    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val p = next.third
    val index = listOf("fajr", "zuhr", "asr", "maghrib", "isha").indexOf(key).coerceAtLeast(0)
    val requestCode = ((next.first.toEpochDay() % 100000L) * 10L + index).toInt()
    val piIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
        putExtra("prayer", p[2]); putExtra("tatar", p[3]); putExtra("time", p[4]);
        putExtra("key", key); putExtra("date", p[0])
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    val pi = PendingIntent.getBroadcast(context, requestCode, piIntent, flags)
    if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
    } else if (Build.VERSION.SDK_INT >= 23) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
    } else {
        am.set(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
    }
}

class PrayerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val pending = goAsync()
        try {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, false)) return
            val notifyBeforeMinutes = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180)
            val lines = prefs.getString("scheduled_prayers", "")
                .orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            val now = LocalDateTime.now(ZoneId.of("Europe/Moscow"))
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            lines.forEach { line ->
                val p = line.split("|")
                if (p.size < 6) return@forEach
                if (!prefs.getBoolean("notify_${p[1]}", true)) return@forEach
                val d = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@forEach
                val dt = runCatching { d.atTime(p[4].substringBefore(":").toInt(), p[4].substringAfter(":").toInt()).minusMinutes(notifyBeforeMinutes.toLong()) }.getOrNull() ?: return@forEach
                if (!dt.isAfter(now) || dt.isAfter(now.plusDays(14))) return@forEach
                val keys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")
                val idx = keys.indexOf(p[1]).coerceAtLeast(0)
                val rc = ((d.toEpochDay() % 100000L) * 10L + idx).toInt()
                val piIntent = Intent(context, PrayerNotificationReceiver::class.java).apply {
                    putExtra("prayer", p[2]); putExtra("tatar", p[3]); putExtra("time", p[4]); putExtra("key", p[1]); putExtra("date", p[0])
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, rc, piIntent, flags)
                if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
                } else if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, dt.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(), pi)
                }
            }
        } finally {
            pending.finish()
        }
    }
}
