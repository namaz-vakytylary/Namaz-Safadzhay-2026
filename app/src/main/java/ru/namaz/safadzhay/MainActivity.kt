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
private const val OPEN_PRAYER_ACTION = "ru.namaz.safadzhay.OPEN_PRAYER"
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
    return saved?.let { runCatching { Uri.parse(it) }.getOrNull() }?.takeIf(::isLocalNotificationSound)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
}

internal fun isLocalNotificationSound(uri: Uri): Boolean =
    uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo == null

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
private val prayerAlarmWorker = PrayerAlarmScheduler.worker
private val holidayUpdateWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
private val scheduleDataWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

class MainActivity : Activity() {
    private val zone = ZoneId.of("Europe/Moscow")
    private val handler = Handler(Looper.getMainLooper())
    private var nextNetworkCheck = 0L
    private val ticker = object : Runnable {
        override fun run() {
            update()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (elapsed >= nextNetworkCheck && !notificationPermissionPending) {
                nextNetworkCheck = elapsed + 300_000L
                scheduleUpdateChecker.allowRetryIfDue(); startScheduleUpdateCheck()
            }
            handler.postDelayed(this, 1000L)
        }
    }
    private val alarmExecutor = prayerAlarmWorker
    private lateinit var scheduleRepository: ScheduleRepository
    private lateinit var scheduleUpdateChecker: ScheduleUpdateChecker
    private lateinit var holidayRepository: HolidayRepository
    private var holidayCheckAt = Long.MIN_VALUE
    private var panelRoute = ""
    private var activeCompass: QiblaCompassView? = null
    private var activeQiblaLocation: QiblaLocationController? = null
    private var lastPrayerRender = ""
    private var lastCalendarRender = ""
    private var lastAlarmSignature = ""
    private var lastDay: LocalDate? = null
    private lateinit var countdownStart: TextView
    private lateinit var headerBox: LinearLayout
    private lateinit var ramadanCard: LinearLayout
    private lateinit var heroCopy: LinearLayout
    private lateinit var palette: AppColors
    private val mint get() = palette.primary
    private val muted get() = palette.secondaryText
    private val ink get() = palette.onBackground
    private var settingsPanel: android.app.Dialog? = null
    private var aboutDialog: android.app.Dialog? = null
    private var scheduleUpdateDialog: android.app.Dialog? = null
    private var scheduleUpdateBar: android.widget.ProgressBar? = null
    private var scheduleUpdatePercent: TextView? = null
    private var scheduleUpdateStatus: TextView? = null
    private var scheduleUpdateShownAt = 0L
    private var scheduleUpdateCompletionPending = false
    private var settingsPanelBack: () -> Unit = {}

    private lateinit var scheduleRetry: TextView
    private var scheduleProgressRequested = false
    private lateinit var countdown: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var nextName: TextView
    private lateinit var dateText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var prayerList: LinearLayout
    private lateinit var progress: CardProgressIndicator
    private lateinit var eventBanner: TextView
    private lateinit var countdownCard: android.widget.FrameLayout
    private lateinit var ramadanCountdownBackground: ImageView
    private lateinit var modeRow: LinearLayout
    private lateinit var todayButtonView: TextView
    private lateinit var scheduleButtonView: TextView
    private var scheduleTabSelected: Boolean = false
    private lateinit var mainScroll: ScrollView
    private lateinit var calendarPanel: LinearLayout
    private lateinit var calendarTitle: TextView
    private lateinit var calendarGrid: android.widget.GridLayout
    private lateinit var holidayCard: LinearLayout
    private var calendarMonth: java.time.YearMonth = java.time.YearMonth.now(
    ZoneId.of("Europe/Moscow")
)
    private val prayerKeys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")
    private val prayerRussian = listOf("Фаджр", "Зухр", "Аср", "Магриб", "Иша")
    private val prayerTatar = listOf("Иртәнге намаз", "Өйлә намазы", "Икенде намазы", "Ахшам намазы", "Ястү намазы")

    private var selectedDate: LocalDate? = null
    private var selectedCity: String = "Сафаджай"
    private lateinit var citySelection: CitySelectionSettings
    private lateinit var cityLocation: CityLocationController
    private var citySettingsSummary: TextView? = null
    private val cityButtons = mutableListOf<RadioButton>()
    private var notificationPermissionPending = false
    private lateinit var placeText: TextView





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
        palette = ThemeSettings.colors(this)
        setTheme(if (palette.isDark) R.style.AppTheme else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        scheduleRepository = ScheduleRepository.get(applicationContext)
        scheduleUpdateChecker = (lastNonConfigurationInstance as? ScheduleUpdateChecker)
            ?: ScheduleUpdateChecker(applicationContext)
            holidayRepository = HolidayRepository(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val openedFromReminder = intent?.action == OPEN_PRAYER_ACTION
        selectedDate = savedInstanceState?.getString("selected_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(zone)
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        val sameProcess = savedInstanceState?.getString("city_session_process") == CitySelectionSession.processToken
        val citySession = CitySelectionSession(
            manualOverride = sameProcess && savedInstanceState?.getBoolean("city_session_manual_override") == true,
            startupChecked = sameProcess && savedInstanceState?.getBoolean("city_session_startup_checked") == true,
            latestFixNanos = if (sameProcess) savedInstanceState?.getLong("city_session_latest_fix_nanos") ?: 0L else 0L
        )
        citySelection = CitySelectionSettings(this, citySession)
        selectedCity = citySelection.savedCity()?.name ?: "Сафаджай"
        cityLocation = CityLocationController(this, citySelection,
            { availableCitySchedules() }, { onScheduleCitySelected(it) }, { /* No location status in UI. */ })
        scheduleTabSelected = savedInstanceState?.getBoolean("schedule_tab") ?: false
        if (openedFromReminder) { selectedDate = LocalDate.now(zone); scheduleTabSelected = false }
        calendarMonth = savedInstanceState?.getString("calendar_month")?.let {
            runCatching { java.time.YearMonth.parse(it) }.getOrNull()
        } ?: java.time.YearMonth.from(selectedDate)
        createNotificationChannel()
        buildUi()
        update()
        schedulePrayerNotifications()
        val firstNotificationSetup = !prefs.contains(NOTIFICATIONS_ENABLED_KEY)
val needsNotificationPermission = firstNotificationSetup &&
    Build.VERSION.SDK_INT >= 33 &&
    ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED

notificationPermissionPending = savedInstanceState?.getBoolean("notification_permission_pending") == true
if (needsNotificationPermission) {
    if (!notificationPermissionPending) {
        notificationPermissionPending = true
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7001)
    }
} else {
    notificationPermissionPending = false
    maybeRequestExactAlarmPermission()
    startScheduleUpdateCheck()
}
        when (if (openedFromReminder) null else savedInstanceState?.getString("panel_route")) {
            "notifications" -> showNotificationsScreen { showSettingsDialog() }
            "settings" -> showSettingsDialog()
            "qibla" -> showQiblaCompass()
            "theme" -> showThemeSelector()
            "schedule_data" -> showScheduleData()
            "city" -> showCityChoice()
            "lead" -> showNotifyBeforeDialog { showNotificationsScreen { showSettingsDialog() } }
            "prayers" -> showPrayerSelectionDialog { showNotificationsScreen { showSettingsDialog() } }
            "sound" -> showSoundScreen()
            "calibration" -> showCalibration()
        }
        if (savedInstanceState?.getBoolean("show_about") == true) showAboutDialog()
        
                
    
        if (openedFromReminder) intent.action = Intent.ACTION_MAIN

    }
private fun startScheduleUpdateCheck() {
    val elapsed = android.os.SystemClock.elapsedRealtime()
    if (holidayCheckAt == Long.MIN_VALUE || elapsed - holidayCheckAt >= 300_000) {
    holidayCheckAt = elapsed
    holidayUpdateWorker.execute {
    val year = LocalDate.now(zone).year
    for (targetYear in year..minOf(year + 1, 2100)) {
        holidayRepository.loadSaved(targetYear)?.let { HolidayCalendar.setRemote(targetYear, it) }
    }
    holidayRepository.downloadYears((year..minOf(year + 1, 2100)).toSet()).forEach { (loadedYear, holidays) ->
        HolidayCalendar.setRemote(loadedYear, holidays)
    }
    handler.post { if (!isDestroyed) { lastCalendarRender = ""; update() } }
    }
    }
    scheduleUpdateChecker.checkOnce(
        
        onProgress = { year, value ->
            showScheduleUpdateDialog(year, value)
        },
        onFinished = {
            handler.post {
                scheduleProgressRequested = false
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
        setColor(if (selected) palette.activePrayer else palette.surface)
        setStroke(dp(1), if (selected) mint else palette.outline)
    }
    private fun showScheduleUpdateDialog(year: Int, value: Int) {
    handler.post {
        if (isFinishing || isDestroyed) return@post
        // This decision reads Activity/session state only on the UI thread.
        if (!scheduleProgressRequested && scheduleUpdateDialog == null &&
            scheduleRepository.day(selectedCityId(), LocalDate.now(zone)) != null) return@post

        val safeValue = value.coerceIn(0, 100)

        if (scheduleUpdateDialog == null) {
            val gold = palette.gold
            val brightGreen = palette.brightProgress
            val progressTrack = palette.progressTrack

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
                        palette.updateTop,
                        palette.updateBottom
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
                    setColor(palette.noticeSurface)
                    setStroke(
                        dp(1),
                        palette.noticeOutline
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

            dialog.setCancelable(true)
            dialog.setOnCancelListener { scheduleUpdateChecker.cancel() }
            dialog.setCanceledOnTouchOutside(false)
            dialog.setContentView(root)

            dialog.show()
            dialog.window?.let { prepareThemedWindow(it) }

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

        }
        scheduleUpdateBar?.progress = safeValue
        scheduleUpdatePercent?.text = "$safeValue%"
        scheduleUpdateStatus?.text = when {
            safeValue >= 100 -> "Проверка завершена"
            safeValue >= 68 -> "Проверка данных"
            safeValue >= 12 -> "Загрузка расписания"
            else -> "Проверка обновлений"
        }
        if (safeValue >= 100) {
            scheduleUpdateCompletionPending = true
            handler.postDelayed({ dismissScheduleUpdateDialog() }, 600L)
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

    private fun iftarPrayerBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(palette.iftarSurface)
        setStroke(dp(2), palette.ramadanAccent)
    }
    private fun label(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView = text(value, size, color, bold).apply {
        includeFontPadding = false
    }
    private fun button(value: String, action: () -> Unit): TextView = label(value, 16f, palette.onAction, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(mint) }
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun tabBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(if (selected) palette.tabSelected else Color.TRANSPARENT)
    }

    private fun updateTabStyles() {
        if (!::todayButtonView.isInitialized || !::scheduleButtonView.isInitialized) return
        todayButtonView.background = tabBackground(!scheduleTabSelected)
        scheduleButtonView.background = tabBackground(scheduleTabSelected)
        todayButtonView.setTextColor(if (!scheduleTabSelected) palette.onPrimary else palette.tabInactiveText)
        scheduleButtonView.setTextColor(if (scheduleTabSelected) palette.onPrimary else palette.tabInactiveText)
    }

    private fun buildUi() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        prepareThemedWindow(window)
        val scroll = TodayLayoutScrollView(this).apply {
            setBackgroundColor(palette.background); isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            scroll.contentInsets = androidx.core.graphics.Insets.of(
                bars.left, bars.top, bars.right, maxOf(bars.bottom, gestures.bottom)
            )
            insets
        }
        headerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerBox, LinearLayout.LayoutParams(-1, -2))
        val top = android.widget.FrameLayout(this)
        top.addView(label("Намаз", 28f, ink, true).apply { gravity = Gravity.CENTER; minHeight = dp(44) }, android.widget.FrameLayout.LayoutParams(-1, -2))
        fun shortcut(res: Int, desc: String, side: Int, action: () -> Unit) {
            top.addView(ImageView(this).apply {
                setImageResource(res); if (!palette.isDark) setColorFilter(mint); setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = desc; isFocusable = true; setOnClickListener { action() }; TouchTargets.expand(this)
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
        ramadanCard = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    visibility = View.GONE

    val backgroundImage = ImageView(this@MainActivity).apply {
    setImageResource(R.drawable.ramadan_header)
    scaleType = ImageView.ScaleType.CENTER_CROP

    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(
                0,
                0,
                view.width,
                view.height,
                dp(16).toFloat()
            )
        }
    }
}

    val textLayer = LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        translationX = dp(20).toFloat()
        setPadding(dp(16), dp(10), dp(16), dp(10))

        addView(
            label("Рамадан", 18f, palette.ramadanImageAccent, true).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        addView(
            label("", 14f, palette.ramadanHeaderText, true).apply {
                gravity = Gravity.CENTER
                tag = "ramadan_day"
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(3)
            }
        )
    }

    addView(
        android.widget.FrameLayout(this@MainActivity).apply {
            addView(
                backgroundImage,
                android.widget.FrameLayout.LayoutParams(-1, -1)
            )
            addView(
                textLayer,
                android.widget.FrameLayout.LayoutParams(-1, -1)
            )
        },
        LinearLayout.LayoutParams(-1, -1)
    )
}

headerBox.addView(
    ramadanCard,
    LinearLayout.LayoutParams(-1, dp(64)).apply {
        topMargin = dp(7)
    }
)
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
        months.addView(prev, LinearLayout.LayoutParams(dp(44), dp(40))); TouchTargets.expand(prev)
        calendarTitle = label("", 16f, ink, true).apply { gravity = Gravity.CENTER }
        months.addView(calendarTitle, LinearLayout.LayoutParams(0, dp(40), 1f))
        months.addView(next, LinearLayout.LayoutParams(dp(44), dp(40))); TouchTargets.expand(next)
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
        setColor(palette.surface)
        setStroke(dp(1), palette.gold)
    }

    val holidayHeader = LinearLayout(this@MainActivity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL

    addView(
        ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_holiday_crescent)
            if (!palette.isDark) setColorFilter(palette.gold)
            scaleType = ImageView.ScaleType.FIT_CENTER
        },
        LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginEnd = dp(7)
        }
    )

    addView(
        text("Мусульманский праздник", 13f, palette.gold, true)
    )
}
addView(holidayHeader)

    addView(
        text("", 18f, palette.onSurface, true).apply {
            tag = "holiday_title"
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        }
    )

    addView(
        text("", 13f, palette.holidaySecondaryText).apply {
            tag = "holiday_hijri"
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(2)
        }
    )

    

        

}
headerBox.addView(
    holidayCard,
    LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(7)
    }
)
        countdownCard = android.widget.FrameLayout(this).apply {
            background = surface()
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
        }
        ramadanCountdownBackground = RamadanCountdownImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        countdownCard.addView(
            ramadanCountdownBackground,
            android.widget.FrameLayout.LayoutParams(-1, -1)
        )
        progress = CardProgressIndicator(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        countdownCard.addView(progress, android.widget.FrameLayout.LayoutParams(-1, -1))
        heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        countdownLabel = label("", 13f, muted).apply { gravity = Gravity.CENTER; visibility = View.GONE; maxLines = Int.MAX_VALUE }
        nextName = label("", 18f, ink, true).apply { gravity = Gravity.CENTER; maxLines = Int.MAX_VALUE }
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
        scheduleRetry = button("Повторить") { retryScheduleDownload() }.apply {
            tag = "schedule_retry"; visibility = View.GONE
            contentDescription = "Повторить загрузку расписания"
        }
        headerBox.addView(scheduleRetry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        prayerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(prayerList, LinearLayout.LayoutParams(-1, -2))
        scroll.bind(root, modeRow, ramadanCard, countdownCard, ramadanCountdownBackground,
            heroCopy, prayerList, placeText, dateText, currentTimeText) { !scheduleTabSelected }
        mainScroll = scroll
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun prepareThemedWindow(target: android.view.Window) {
        if (Build.VERSION.SDK_INT >= 29) target.decorView.isForceDarkAllowed = false
        WindowCompat.getInsetsController(target, target.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
        // Android 6/7 cannot draw dark navigation icons on a light background.
        if (!palette.isDark && Build.VERSION.SDK_INT < 26) target.navigationBarColor = palette.onBackground
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
    private fun screenRow(iconRes: Int, title: String, value: String = "", onClick: () -> Unit): LinearLayout = makeScreenRow(ImageView(this).apply { setImageResource(iconRes); if (!palette.isDark) setColorFilter(mint); setPadding(dp(7), dp(7), dp(7), dp(7)) }, title, value, onClick)
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

    // Notification cards have their own sizing so other settings screens stay unchanged.
    private fun notificationIcon(iconRes: Int): ImageView = ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(mint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        tag = "notification_icon"
    }

    private fun notificationRow(iconRes: Int, title: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            tag = "notification_card"
            gravity = Gravity.CENTER_VERTICAL
            background = surface()
            // Match the theme selector proportions; wrap content for larger fonts.
            minimumHeight = dp(76)
            setPadding(dp(15), dp(12), dp(15), dp(12))
            isFocusable = true
            setOnClickListener { onClick() }
            addView(notificationIcon(iconRes), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 17f, ink).apply { maxLines = Int.MAX_VALUE }, LinearLayout.LayoutParams(-1, -2))
                addView(label(value, 14f, muted).apply { maxLines = Int.MAX_VALUE }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 25f, muted).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 25f)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(40)).apply { marginStart = dp(6) })
        }

    private fun fullScreenPanel(titleText: String, onBack: () -> Unit): Pair<android.app.Dialog, LinearLayout> {
        citySettingsSummary = null; cityButtons.clear()
        // Keep one window throughout settings navigation so the home screen is never exposed.
        val dialog = settingsPanel ?: object : android.app.Dialog(this, if (palette.isDark) R.style.AppPanelTheme else R.style.AppPanelThemeLight) {
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
        val scroll = ScrollView(this).apply { setBackgroundColor(palette.background); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(24)) }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(18), bars.top + dp(10), dp(18), bars.bottom + dp(24)); insets
        }
        val bar = android.widget.FrameLayout(this)
        val back = text("‹", 38f, palette.onSurface, false).apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 38f); contentDescription = "Назад"; isFocusable = true; gravity = Gravity.CENTER; setOnClickListener { onBack() } }
        bar.addView(label(titleText, 20f, ink, true).apply { gravity = Gravity.CENTER; minHeight = dp(58); maxLines = Int.MAX_VALUE; setPadding(dp(46), 0, dp(46), 0) }, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        bar.addView(back, android.widget.FrameLayout.LayoutParams(dp(48), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(scroll)
        dialog.window?.let { prepareThemedWindow(it) }
        ViewCompat.requestApplyInsets(scroll)
        return dialog to root
    }

    private fun showNotifyBeforeDialog(onBack: () -> Unit = {}) {
        panelRoute = "lead"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Когда напоминать", onBack)
        dialog = pair.first
        val root = pair.second
        val values = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(palette.surface) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val current = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5)
        values.forEach { minutes ->
            group.addView(RadioButton(this).apply {
                text = notifyBeforeLabel(minutes); textSize = 16f; setTextColor(palette.onSurface); isChecked = minutes == current
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
        panelRoute = "prayers"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Намазы для уведомлений", onBack)
        dialog = pair.first
        val root = pair.second
        val checked = BooleanArray(prayerKeys.size) { prefs.getBoolean("notify_${prayerKeys[it]}", true) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(palette.surface) }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        prayerKeys.indices.forEach { i ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(prayerDisplayName(this, prayerRussian[i], prayerTatar[i]), 16f, palette.onSurface, false).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
            val sw = Switch(this).apply { isChecked = checked[i]; setOnCheckedChangeListener { _, v -> checked[i] = v } }
            row.setOnClickListener { sw.isChecked = !sw.isChecked }
            row.addView(sw, LinearLayout.LayoutParams(dp(58), dp(58)))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(58)))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val save = text("Сохранить", 16f, palette.onSaveAction, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(palette.primary) }
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
    if (prefs.contains(NOTIFICATIONS_ENABLED_KEY) && !prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, true)) return
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

    private fun scheduleCities(): List<ScheduleCity> =
        CityCatalog.all.filter { it.id in ScheduleRepository.supportedCityIds() }

    private fun availableCitySchedules(): List<ScheduleCity> {
        // Only cities with actual verified/built-in coverage today are eligible.
        val today = LocalDate.now(zone).toString()
        return scheduleCities().filter { city -> dataForCity(city.name).any { it.date == today } }
    }

    private fun onScheduleCitySelected(city: ScheduleCity) {
        if (selectedCity != city.name) {
            selectedCity = city.name
            lastPrayerRender = ""; lastCalendarRender = ""; lastAlarmSignature = ""
            calendarMonth = calendarMonth.coerceIn(calendarMinMonth(), calendarMaxMonth())
            update(); schedulePrayerNotifications()
        }
        refreshCitySelectionUi()
    }

    private fun refreshCitySelectionUi() {
        citySettingsSummary?.text = selectedCity
        cityButtons.forEach { button ->
            val checked = button.text.toString() == selectedCity
            button.isChecked = checked
            button.background = surface(checked)
        }
    }

    private fun cityInformationCard(): LinearLayout {
        val row = screenRow(R.drawable.ic_location, "Город", selectedCity) { showCityChoice() }
        row.tag = "city_settings_entry"
        citySettingsSummary = ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).apply {
            tag = "city_selection_summary"
            maxLines = Int.MAX_VALUE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        return row
    }

    private fun showCityChoice() {
        panelRoute = "city"
        val pair = fullScreenPanel("Город") { cityButtons.clear(); showSettingsDialog() }
        val cities = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        cityButtons.clear()
        scheduleCities().forEach { city ->
            val choice = RadioButton(this).apply {
                id = View.generateViewId(); tag = "city_choice_${city.id}"
                text = city.name; textSize = 17f; setTextColor(ink)
                isChecked = selectedCity == city.name
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(mint, muted)
                )
                background = surface(isChecked)
                setPadding(dp(15), dp(12), dp(15), dp(12)); minHeight = dp(64)
                setOnClickListener {
                    citySelection.selectManual(city)
                    cityLocation.selectionChanged()
                    onScheduleCitySelected(city)
                }
            }
            cityButtons.add(choice)
            cities.addView(choice, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        pair.second.addView(cities, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        pair.first.show()
    }

    private fun showNotificationsScreen(onBack: () -> Unit = {}) {
        panelRoute = "notifications"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        val pair = fullScreenPanel("Уведомления", onBack)
        dialog = pair.first; val root = pair.second
        val master = LinearLayout(this).apply {
            tag = "notification_master"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12))
            background = surface()
        }
        master.addView(notificationIcon(R.drawable.ic_notification), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        master.addView(text("Уведомления", 17f, palette.onSurface, true).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = Int.MAX_VALUE }, LinearLayout.LayoutParams(0, -2, 1f))
        master.addView(Switch(this).apply {
            contentDescription = "Уведомления"
            isChecked = prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(NOTIFICATIONS_ENABLED_KEY, checked).apply(); schedulePrayerNotifications(); if (checked) {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { notificationPermissionPending = true; requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7001) }
                else maybeRequestExactAlarmPermission(true)
            } }
        }, LinearLayout.LayoutParams(dp(60), dp(52)))
        master.minimumHeight = dp(76)
        root.addView(master, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(text("Приложение будет напоминать о выбранных намазах в указанное вами время.", 14f, palette.notificationText, false).apply { maxLines = Int.MAX_VALUE; setPadding(dp(8), dp(12), dp(8), dp(12)) })
        root.addView(notificationRow(R.drawable.ic_schedule, "Когда напоминать", notifyBeforeLabel(prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5))) { showNotifyBeforeDialog { showNotificationsScreen(onBack) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(notificationRow(R.drawable.ic_checklist, "Намазы для уведомлений", selectedPrayerSummary(prefs)) { showPrayerSelectionDialog { showNotificationsScreen(onBack) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val sound = runCatching { RingtoneManager.getRingtone(this, selectedNotificationSound(this))?.getTitle(this) }.getOrNull() ?: "Системный звук"
        root.addView(notificationRow(R.drawable.ic_music_note, "Звук уведомления", sound) { showSoundScreen() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            root.addView(notificationRow(R.drawable.ic_alarm_on, "Точные уведомления", if (am.canScheduleExactAlarms()) "Разрешены" else "Нужно разрешить") { maybeRequestExactAlarmPermission(true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        root.addView(label("Со звуком и вибрацией", 14f, muted).apply { gravity = Gravity.CENTER; background = surface(); maxLines = Int.MAX_VALUE; minimumHeight = dp(56); setPadding(dp(15), dp(12), dp(15), dp(12)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        dialog.show()
    }

    private fun showSettingsDialog() {
        panelRoute = "settings"
        val prefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        lateinit var dialog: android.app.Dialog
        fun create(): Pair<android.app.Dialog, LinearLayout> = fullScreenPanel("Настройки") { dialog.dismiss() }
        val pair = create(); dialog = pair.first; val root = pair.second

        root.addView(screenRow(R.drawable.ic_notification, "Уведомления", if (prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, true)) "Включены" else "Выключены") { showNotificationsScreen { showSettingsDialog() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(cityInformationCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(screenRow(R.drawable.ic_schedule, "Данные расписаний", "Годы, города и сохранённая копия") {
            showScheduleData()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(screenRow("◐", "Тема приложения", ThemeSettings.read(this).title) {
            showThemeSelector()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })


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

    private fun showScheduleData() {
        panelRoute = "schedule_data"
        val pair = fullScreenPanel("Данные расписаний") { showSettingsDialog() }
        val dialog = pair.first
        val root = pair.second
        root.addView(label("Читаем сохранённые сведения…", 14f, muted))
        dialog.show()
        val repository = scheduleRepository
        scheduleDataWorker.execute {
            val result = runCatching {
                repository.loadSavedIfNeeded()
                repository.cacheInfo()
            }
            handler.post {
                if (isDestroyed || isFinishing || settingsPanel !== dialog || !dialog.isShowing) return@post
                root.removeViews(1, root.childCount - 1)
                fun fact(title: String, value: String) {
                    val row = screenRow(R.drawable.ic_schedule, title, value) {}
                    row.isClickable = false
                    row.isFocusable = true
                    row.contentDescription = "$title. $value"
                    row.getChildAt(row.childCount - 1).visibility = View.GONE
                    val copy = row.getChildAt(1) as LinearLayout
                    for (index in 0 until copy.childCount) {
                        (copy.getChildAt(index) as TextView).apply {
                            maxLines = Int.MAX_VALUE
                            ellipsize = null
                        }
                    }
                    root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
                result.fold(onSuccess = { info ->
                    fact("Состояние сохранённых данных", when (info.state) {
                        ScheduleRepository.CacheState.NOT_LOADED -> "Копия ещё не прочитана"
                        ScheduleRepository.CacheState.ABSENT -> "Проверенных сохранённых расписаний нет"
                        ScheduleRepository.CacheState.INVALID -> "Сохранённый файл не прошёл проверку; файл не удалён"
                        ScheduleRepository.CacheState.VERIFIED -> "Проверенная сохранённая копия загружена"
                    } + ". Результат последнего чтения или успешного сохранения.")
                    val stamp = if (info.lastSuccessMillis > 0) java.time.Instant.ofEpochMilli(info.lastSuccessMillis)
                        .atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + " · МСК"
                        else "Нет записанного времени"
                    fact("Последняя успешная проверка обновлений", stamp)
                    val today = LocalDate.now(zone)
                    val city = selectedCityId()
                    val offline = info.years.flatMap { it.cities }.any {
                        it.id == city && today >= LocalDate.parse(it.from) && today <= LocalDate.parse(it.to)
                    }
                    fact("Офлайн на сегодня · $selectedCity", if (offline)
                        "Доступно на $today. Интернет для чтения этой копии не нужен."
                        else "Нет проверенных данных на $today для выбранного города.")
                    info.years.forEach { year ->
                        fact("${year.year} · версия данных ${year.version}", year.cities.joinToString("\n") { cached ->
                            val name = CityCatalog.all.firstOrNull { it.id == cached.id }?.name ?: cached.id
                            "$name: ${cached.from} — ${cached.to}, дней: ${cached.days}"
                        })
                    }
                    fact("Источник расписаний", "GitHub · namaz-vakytylary/namaz-schedules\n${ScheduleRepository.BASE_URL}")
                }, onFailure = {
                    fact("Не удалось прочитать сведения", "Вернитесь назад и повторите. Сохранённый файл не удалён.")
                })
            }
        }
    }

    private fun showThemeSelector() {
        panelRoute = "theme"
        val pair = fullScreenPanel("Тема приложения") { showSettingsDialog() }
        val current = ThemeSettings.read(this)
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        ThemeMode.entries.forEach { mode ->
            val copy = android.text.SpannableString("${mode.title}\n${mode.subtitle}").apply {
                val start = mode.title.length + 1
                setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(muted), start, length, 0)
            }
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = copy; textSize = 17f; setTextColor(ink)
                contentDescription = "${mode.title}. ${mode.subtitle}"
                isChecked = mode == current
                val iconRes = when (mode) {
                    ThemeMode.SYSTEM -> R.drawable.ic_theme_system
                    ThemeMode.LIGHT -> R.drawable.ic_theme_light
                    ThemeMode.DARK -> R.drawable.ic_theme_dark
                }
                val icon = getDrawable(iconRes)!!.mutate().apply {
                    setTint(if (isChecked) mint else muted)
                    setBounds(0, 0, dp(22), dp(22))
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(10)
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(mint, muted)
                )
                background = surface(isChecked)
                setPadding(dp(15), dp(12), dp(15), dp(12))
                minHeight = dp(76)
                setOnClickListener {
                    if (mode != current) {
                        ThemeSettings.save(this@MainActivity, mode)
                        recreate()
                    }
                }
            }, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        pair.second.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        pair.first.show()
    }

    override fun onRetainNonConfigurationInstance(): Any = scheduleUpdateChecker

    private fun onScheduleUpdated() {
        // A completed download must update alarms even if the activity has closed.
        val app = applicationContext
        alarmExecutor.execute {
            runCatching { PrayerAlarmScheduler.rebuild(app) }
                .onFailure { android.util.Log.w("ScheduleUpdate", "Alarm refresh failed: ${it.javaClass.simpleName}") }
        }
        handler.post {
            if (!isDestroyed && !isFinishing) {
                lastAlarmSignature = ""; lastCalendarRender = ""; lastPrayerRender = ""
                update()
                if (::cityLocation.isInitialized) cityLocation.schedulesChanged()
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
        dialog.window?.let { prepareThemedWindow(it) }
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
        panelRoute = "calibration"
        val pair = fullScreenPanel("Калибровка компаса") { showQiblaCompass() }
        pair.second.addView(label("∞", 110f, mint).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(170)))
        pair.second.addView(label("1. Отойдите от металла и магнитов.\n\n2. Плавно опишите телефоном восьмёрку несколько раз.\n\n3. Вернитесь к компасу и проверьте направление.", 17f, ink).apply { maxLines = Int.MAX_VALUE; setPadding(dp(16), dp(16), dp(16), dp(16)); background = surface() })
        pair.second.addView(button("Вернуться к кибле") { showQiblaCompass() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        pair.first.show()
    }

    private fun showThemedMessage(title: String, message: String, positive: String, negative: String? = null, action: () -> Unit) {
        val builder = android.app.AlertDialog.Builder(this, if (palette.isDark) R.style.AppDialogTheme else R.style.AppDialogThemeLight).setTitle(title).setMessage(message).setPositiveButton(positive) { _, _ -> action() }
        if (negative != null) builder.setNegativeButton(negative, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.let { prepareThemedWindow(it) }
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
        panelRoute = "sound"
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
        if (picked != null && isLocalNotificationSound(picked)) {
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
        val signature = listOf(selectedCity, LocalDate.now(zone), scheduleRepository.revision(), prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, true), prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5), prefs.getString(SOUND_URI_KEY, ""), prayerKeys.map { prefs.getBoolean("notify_$it", true) }, if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true).joinToString("|")
        if (signature == lastAlarmSignature) return
        lastAlarmSignature = signature
        val app = applicationContext
        val cachePending = !scheduleRepository.isLoaded
        alarmExecutor.execute {
            if (cachePending) {
                scheduleRepository.loadSavedIfNeeded()
                handler.post { if (!isDestroyed) { update(); cityLocation.schedulesChanged() } }
            }
            runCatching { PrayerAlarmScheduler.rebuild(app) }.onFailure {
            handler.post { lastAlarmSignature = ""; if (!isDestroyed) Toast.makeText(this, "Не удалось настроить уведомления. Проверьте разрешения.", Toast.LENGTH_LONG).show() }
        } }
    }

    private fun currentData(): List<PrayerDay> = dataForCity(selectedCity)

    private fun selectedCityId() = if (selectedCity == "Москва") "moscow" else "safadzhay"
    private fun dataForCity(city: String): List<PrayerDay> =
        scheduleRepository.days(if (city == "Москва") "moscow" else "safadzhay")
   private fun calendarMinMonth(): java.time.YearMonth {
    val data = currentData()
    return if (data.isNotEmpty()) {
        minOf(java.time.YearMonth.from(LocalDate.parse(data.first().date)), java.time.YearMonth.from(LocalDate.now(zone)))
    } else {
        val currentYear = LocalDate.now(zone).year
        java.time.YearMonth.of(currentYear, 1)
    }
}

private fun calendarMaxMonth(): java.time.YearMonth {
    val data = currentData()
    return if (data.isNotEmpty()) {
        maxOf(java.time.YearMonth.from(LocalDate.parse(data.last().date)), java.time.YearMonth.from(LocalDate.now(zone)))
    } else {
        val currentYear = LocalDate.now(zone).year
        java.time.YearMonth.of(currentYear, 12)
    }
}
    private fun formatRussianDate(date: LocalDate): String {
        val months = listOf(
            "Января", "Февраля", "Марта", "Апреля", "Мая", "Июня",
            "Июля", "Августа", "Сентября", "Октября", "Ноября", "Декабря"
        )
        return "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
    }

    private fun dayFor(date: LocalDate): PrayerDay? = scheduleRepository.day(selectedCityId(), date)

    private fun renderInlineCalendar() {
        calendarMonth = calendarMonth.coerceIn(calendarMinMonth(), calendarMaxMonth())
        if (!::calendarGrid.isInitialized) return
        val renderKey = "$calendarMonth|$selectedDate|${LocalDateTime.now(zone).toLocalDate()}"
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
            val dayName = text(name, 11f, palette.calendarWeekday, true).apply {
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
                if (!inMonth) palette.calendarOutsideMonth else if (isSelected && !hasHoliday) palette.onPrimary else palette.onSurface,
                isSelected
            ).apply {
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                if (isSelected || (hasHoliday && inMonth)) {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL

       if (isSelected && !hasHoliday) {
    setColor(palette.tabSelected)
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
            setStroke(dp(1), palette.holidayOutline)
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
            if (!palette.isDark) setColorFilter(palette.gold)
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
        if (isToday) palette.fridayToday
        else palette.friday
    )
    setIncludeFontPadding(false)
}

cell.addView(
    fridayDot,
    LinearLayout.LayoutParams(dp(34), dp(8))
)
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = maxOf(dp(48), numberHeight + dp(17))
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

   holidayCard.visibility = View.VISIBLE
    }

    private fun openDatePicker() {
        val minDate = calendarMinMonth().atDay(1)
        val maxDate = calendarMaxMonth().atEndOfMonth()
        val initial = selectedDate?.coerceIn(minDate, maxDate) ?: LocalDate.now(zone).coerceIn(minDate, maxDate)
        val dialog = DatePickerDialog(this, if (palette.isDark) R.style.AppDialogTheme else R.style.AppDialogThemeLight, { _, year, month, dayOfMonth ->
            selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
            scheduleTabSelected = true
            updateTabStyles()
            update()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.datePicker.minDate = minDate.atStartOfDay(zone).toInstant().toEpochMilli()
        dialog.datePicker.maxDate = maxDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        dialog.show()
        dialog.window?.let { prepareThemedWindow(it) }
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

    private fun retryScheduleDownload() {
        scheduleProgressRequested = true
        scheduleUpdateChecker.allowRetry()
        startScheduleUpdateCheck()
    }

    private fun update() {
        val now = LocalDateTime.now(zone).withNano(0)
        val todayDate = now.toLocalDate()
        countdown.visibility = View.VISIBLE
        countdownStart.visibility = View.VISIBLE

        val ramadanDate = if (scheduleTabSelected) selectedDate ?: todayDate else todayDate
        val ramadanDay = HolidayCalendar.ramadanDay(ramadanDate)
        val ramadanDayText = ramadanCard.findViewWithTag<TextView>("ramadan_day")
        if (ramadanDay != null) {
            ramadanCard.visibility = View.VISIBLE
            ramadanDayText.text = "Сегодня $ramadanDay-й день поста"
        } else {
            ramadanCard.visibility = View.GONE
            ramadanDayText.text = ""
        }
        
        
        prayerList.visibility = View.VISIBLE
        if (!scheduleTabSelected) selectedDate = todayDate
        if (selectedDate == null) selectedDate = todayDate
        val realToday = LocalDate.now(zone)
        if (lastDay != realToday) {
            val previousDay = lastDay
            lastDay = realToday; schedulePrayerNotifications()
            if (previousDay != null) {
                if (!scheduleTabSelected && previousDay.year != realToday.year) {
                    calendarMonth = java.time.YearMonth.from(todayDate); lastCalendarRender = ""
                }
                scheduleUpdateChecker.allowRetry(); startScheduleUpdateCheck()
            }
        }
        val selected = selectedDate!!
        updateHolidayCard(selected)
        if (selected != todayDate && !scheduleTabSelected) scheduleTabSelected = true
        updateTabStyles()
        placeText.text = selectedCity
        dateText.text = "${formatRussianDate(selected)}\n${hijriText(selected)}"
        dateText.contentDescription = "${dateText.text}. Открыть календарь"
        currentTimeText.text = "Сейчас " +
            now.format(DateTimeFormatter.ofPattern("HH:mm"))
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
        scheduleRetry.visibility = if (selectedDay == null && scheduleRepository.isLoaded) View.VISIBLE else View.GONE
        if (selectedDay == null) {
            ramadanCountdownBackground.visibility = View.GONE
            nextName.text = "Расписание пока недоступно"
            countdownLabel.visibility = View.VISIBLE
            countdown.text = "—"
            countdownLabel.text = if (!scheduleRepository.isLoaded) "Читаем сохранённое расписание…"
                else if (!scheduleRepository.hasDownloads())
                "Подключитесь к интернету, чтобы загрузить расписание. Нажмите здесь, чтобы повторить."
                else "Для этой даты расписание ещё не опубликовано или не загружено. Нажмите здесь, чтобы проверить."
            countdownLabel.isClickable = true
            countdownLabel.contentDescription = countdownLabel.text
            countdownLabel.setOnClickListener { retryScheduleDownload() }
            progress.progress = 0f
            countdownStart.text = ""

            val unavailablePrayers = listOf(
                Prayer("Фаджр", "Иртәнге намаз", "— —"),
                Prayer("Зухр", "Өйлә намазы", "— —"),
                Prayer("Аср", "Икенде намазы", "— —"),
                Prayer("Магриб", "Ахшам намазы", "— —"),
                Prayer("Иша", "Ястү намазы", "— —")
            )

            renderPrayers(
                unavailablePrayers,
                -1,
                now,
                selected,
                false
            )
            return
        }

        countdownLabel.isClickable = false
        countdownLabel.setOnClickListener(null)
        val prayers = getPrayers(selectedDay)
        if (selected != todayDate) {
            nextName.text = "Расписание"
            countdownLabel.visibility = View.VISIBLE
            countdown.text = "—"
            countdownLabel.text = "Намазы на ${formatRussianDate(selected)}"
            progress.progress = 0f
            renderPrayers(prayers, -1, now, selected, false)
            return
        }

        data class PrayerEvent(val prayer: Prayer, val time: LocalDateTime)

        val eventsToday = mutableListOf<PrayerEvent>()
        val basePrayers = getPrayers(selectedDay)
        basePrayers.forEach { p -> eventsToday += PrayerEvent(p, dateTime(todayDate, p.time)) }

        eventsToday.sortBy { it.time }
        val nextEvent = eventsToday.firstOrNull { it.time.isAfter(now) }
        val previousEvent = eventsToday.lastOrNull { !it.time.isAfter(now) }
        val iftarJustStarted =
            ramadanDay != null &&
            previousEvent?.prayer?.name == "Магриб" &&
            Duration.between(previousEvent.time, now).toMinutes() < 10
        ramadanCountdownBackground.scaleType = if (iftarJustStarted)
            ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

        if (nextEvent != null) {
            val previousTime = previousEvent?.time
                ?: dayFor(todayDate.minusDays(1))?.let {
                    dateTime(todayDate.minusDays(1), it.isha)
                }
                ?: todayDate.atStartOfDay()

            val total = Duration.between(previousTime, nextEvent.time).seconds.coerceAtLeast(1)
            val left = Duration.between(now, nextEvent.time).seconds.coerceAtLeast(0)

            progress.progress =
                (1.0 - left.toDouble() / total.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()

            countdown.text = String.format(
                "%02d:%02d:%02d",
                left / 3600,
                (left % 3600) / 60,
                left % 60
            )
            progress.goldMode = false
            if (iftarJustStarted) {
                progress.goldMode = true
               
                    
                ramadanCountdownBackground.setImageResource(R.drawable.ramadan_iftar_started)
                ramadanCountdownBackground.visibility = View.VISIBLE
                nextName.setTextColor(palette.onRamadanImage)
                nextName.setShadowLayer(4f, 0f, 2f, Color.BLACK)
                nextName.text = "Время ифтара наступило"
                countdownLabel.visibility = View.GONE
                countdown.text = ""
                countdownStart.text = ""
                progress.progress = 1f
            } else if (ramadanDay != null && nextEvent.prayer.name == "Фаджр") {
                ramadanCountdownBackground.setImageResource(R.drawable.ramadan_suhoor)
                ramadanCountdownBackground.visibility = View.VISIBLE
                countdown.setTextColor(palette.onRamadanImage)
                countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 43f)

                nextName.setTextColor(palette.onRamadanImage)
                nextName.setShadowLayer(4f, 0f, 2f, Color.BLACK)
                nextName.text = "До окончания сухура"
                countdownLabel.visibility = View.GONE

                countdownStart.setTextColor(palette.onRamadanImage)
                countdownStart.setShadowLayer(3f, 0f, 1f, Color.BLACK)
                countdownStart.text =
                    "Сухур заканчивается с началом Фаджра · ${nextEvent.prayer.time}"
             
            } else if (ramadanDay != null && nextEvent.prayer.name == "Магриб") {
                ramadanCountdownBackground.setImageResource(R.drawable.ramadan_iftar)
                ramadanCountdownBackground.visibility = View.VISIBLE
                countdown.setTextColor(palette.onRamadanImage)
                countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 43f)
                
                nextName.setTextColor(palette.onRamadanImage)
                nextName.setShadowLayer(4f, 0f, 2f, Color.BLACK)
                nextName.text = "До ифтара"
                countdownLabel.visibility = View.GONE
                
                countdownStart.setTextColor(palette.onRamadanImage)
                countdownStart.setShadowLayer(3f, 0f, 1f, Color.BLACK)
                countdownStart.text =
                    "Ифтар с наступлением Магриба · ${nextEvent.prayer.time}"
            } else {
                ramadanCountdownBackground.visibility = View.GONE

                countdown.setTextColor(mint)
                countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 48f)

                nextName.setTextColor(ink)
                nextName.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

                countdownStart.setTextColor(ink)
                countdownStart.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                nextName.text =
                    prayerDisplayName(
                        this,
                        nextEvent.prayer.name,
                        nextEvent.prayer.tatar
                    )
                countdownLabel.visibility = View.GONE
                countdownStart.text =
                    "До начала намаза · ${nextEvent.prayer.time}"
            }
        } else {

            ramadanCountdownBackground.visibility = View.GONE

            countdown.setTextColor(mint)
            countdown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 48f)

            nextName.setTextColor(ink)
            nextName.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            countdownStart.setTextColor(ink)
            countdownStart.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            val tomorrow = todayDate.plusDays(1)
            val tomorrowDay = dayFor(tomorrow)

            if (tomorrowDay != null) {
                val nextFajr = dateTime(tomorrow, tomorrowDay.fajr)
                val previousTime = eventsToday.lastOrNull()?.time ?: now
                val total =
                    Duration.between(previousTime, nextFajr).seconds.coerceAtLeast(1)
                val left =
                    Duration.between(now, nextFajr).seconds.coerceAtLeast(0)

                progress.progress =
                    (1.0 - left.toDouble() / total.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()

                countdown.text = String.format(
                    "%02d:%02d:%02d",
                    left / 3600,
                    (left % 3600) / 60,
                    left % 60
                )

                nextName.text =
                    prayerDisplayName(this, "Фаджр", "Иртәнге намаз")
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
        val nextIndex = if (iftarJustStarted) {
            prayers.indexOfFirst { it.name == "Магриб" }
        } else {
            prayers.indexOfFirst {
                it.name == nextEvent?.prayer?.name &&
                    it.time == nextEvent?.prayer?.time
            }
        }
        renderPrayers(prayers, nextIndex, now, todayDate, iftarJustStarted)
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

    private fun renderPrayers(prayers: List<Prayer>, nextIndex: Int, now: LocalDateTime, displayDate: LocalDate, iftarJustStarted: Boolean) {
        val showTatar = showTatarNames(this)        
        val renderKey = "$selectedCity|$displayDate|$nextIndex|$iftarJustStarted|${now.toLocalDate()}|${now.hour}:${now.minute}|$showTatar|${prayers.joinToString()}"
        if (lastPrayerRender == renderKey) return
        lastPrayerRender = renderKey
        prayerList.removeAllViews()
        for ((index, p) in prayers.withIndex()) {
            val hasTime = p.time.matches(Regex("\\d{1,2}:\\d{2}"))
            val eventDateTime = if (hasTime) dateTime(displayDate, p.time) else null
            val isNext = hasTime && index == nextIndex
            val isIftar = isNext && iftarJustStarted && p.name == "Магриб"
            val passed = hasTime && eventDateTime != null && eventDateTime.isBefore(now) && !isNext
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(66)
                background = if (isIftar) iftarPrayerBackground() else cardBackground(isNext, passed)
                alpha = if (palette.isDark && passed && displayDate == now.toLocalDate()) 0.70f else 1f
            }

            row.addView(PrayerIconView(this, p.name, if (isIftar) palette.ramadanAccent else if (isNext) mint else muted), LinearLayout.LayoutParams(dp(34), dp(42)).apply { rightMargin = dp(8) })
            
            val nameBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val ru = text(p.name, 18f, ink, true).apply {
                gravity = Gravity.START
                setIncludeFontPadding(false)
                maxLines = 2
            }
            val tt = text("(${p.tatar})", 13f, palette.tatarText, false).apply {
                visibility = if (showTatar) View.VISIBLE else View.GONE
                gravity = Gravity.START
                setIncludeFontPadding(false)
                maxLines = Int.MAX_VALUE
            }
            nameBox.addView(ru, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            nameBox.addView(tt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            val time = text(p.time, 25f, if (isIftar) palette.ramadanAccent else if (isNext) palette.activeTime else palette.onSurface, true).apply {
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
    handler.removeCallbacks(ticker); handler.post(ticker)
    scheduleUpdateChecker.allowRetryIfDue()
    if (!notificationPermissionPending) startScheduleUpdateCheck()

    if (::placeText.isInitialized) {
        schedulePrayerNotifications()
        update()

        if (panelRoute == "notifications") {
            showNotificationsScreen { showSettingsDialog() }
        }
    }

    if (::cityLocation.isInitialized) cityLocation.resume(allowPermissionRequest = !notificationPermissionPending)

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
        notificationPermissionPending = false
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
        if (::cityLocation.isInitialized) cityLocation.permissionRequestsAvailable()
    }

    if (requestCode == CityLocationController.REQUEST_CODE && ::cityLocation.isInitialized) {
        cityLocation.permissionResult()
    }

    if (requestCode == QiblaLocationController.REQUEST_CODE) {
        activeQiblaLocation?.start()
    }
}

        


    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("city_session_process", CitySelectionSession.processToken)
        outState.putBoolean("city_session_manual_override", citySelection.session.manualOverride)
        outState.putBoolean("city_session_startup_checked", citySelection.session.startupChecked)
        outState.putLong("city_session_latest_fix_nanos", citySelection.session.latestFixNanos)
        outState.putString("selected_date", selectedDate?.toString())
        outState.putBoolean("schedule_tab", scheduleTabSelected)
        outState.putString("calendar_month", calendarMonth.toString())
        outState.putBoolean("show_about", aboutDialog?.isShowing == true)
        outState.putString("panel_route", panelRoute)
        outState.putBoolean("notification_permission_pending", notificationPermissionPending)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { handler.removeCallbacks(ticker); if (::cityLocation.isInitialized) cityLocation.pause(); activeQiblaLocation?.stop(); activeCompass?.stop(); super.onPause() }
    override fun onDestroy() {
        scheduleUpdateChecker.detach()
        if (::cityLocation.isInitialized) cityLocation.pause()
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
        val pending = goAsync()
        PrayerAlarmScheduler.worker.execute {
            try { deliver(context.applicationContext, intent) }
            catch (error: Exception) { android.util.Log.w("PrayerReminder", "Delivery rejected: ${error.javaClass.simpleName}") }
            finally { runCatching { PrayerAlarmScheduler.rebuild(context.applicationContext) }; pending.finish() }
        }
    }
    private fun deliver(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val index = PrayerAlarmScheduler.keys.indexOf(key)
        if (index < 0) return
        val date = intent.getStringExtra("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(NOTIFICATIONS_ENABLED_KEY, true) || !prefs.getBoolean("notify_$key", true)) return
        val city = prefs.getString("city", "Сафаджай") ?: "Сафаджай"
        if (intent.hasExtra("city") && intent.getStringExtra("city") != city) return
        val repo = ScheduleRepository.get(context)
        repo.loadSavedIfNeeded()
        if (intent.hasExtra("revision") && intent.getStringExtra("revision") != repo.revision()) return
        val day = repo.day(if (city == "Москва") "moscow" else "safadzhay", date) ?: return
        val time = PrayerAlarmScheduler.time(day, key) ?: return
        if (time != intent.getStringExtra("time")) return
        val lead = prefs.getInt(NOTIFY_BEFORE_MIN_KEY, 5).coerceIn(0, 180)
        val remaining = PrayerAlarmScheduler.remainingMinutes(date, time, lead, PrayerAlarmScheduler.clock()) ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val deliveredKey = "delivered_$key"
        val deliveredDatesKey = "delivered_dates_$key"
        val deliveredDates = prefs.getStringSet(deliveredDatesKey, emptySet()).orEmpty()
        if (prefs.getString(deliveredKey, null) == date.toString() || date.toString() in deliveredDates) return
        val prayer = PrayerAlarmScheduler.russian[index]
        val tatar = PrayerAlarmScheduler.tatar[index]
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = selectedNotificationSound(context)
        val channelId = ensurePrayerNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val minutes = remaining
        val reminderText = if (minutes == 0L) "Время намаза: $time" else "До намаза $minutes мин. Время намаза: $time"
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
            if (!prefs.edit().putString(deliveredKey, date.toString())
                .putStringSet(deliveredDatesKey, (deliveredDates + date.toString()).sortedDescending().take(64).toSet()).commit()) return
            NotificationManagerCompat.from(context)
                .notify(((date.toEpochDay() % 100000L) * 10L +
                    listOf("fajr", "zuhr", "asr", "maghrib", "isha").indexOf(key).coerceAtLeast(0)).toInt(), builder.build())
        }

    }
}

class PrayerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        PrayerAlarmScheduler.worker.execute {
            try { PrayerAlarmScheduler.rebuild(context.applicationContext) }
            catch (error: Exception) { android.util.Log.w("PrayerRestore", "Restore failed: ${error.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }
}
