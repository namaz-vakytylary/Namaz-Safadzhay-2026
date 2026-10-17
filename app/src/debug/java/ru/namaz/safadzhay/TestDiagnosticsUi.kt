package ru.namaz.safadzhay

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** TEST source set only. No telemetry, alarm writes or GPS requests. */
internal object TestDiagnosticsUi {
    const val available = true
    internal val reader = Executors.newSingleThreadExecutor()
    private val zone = PrayerAlarmScheduler.zone
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.forLanguageTag("ru"))
    private fun timestamp(millis: Long) = if (millis > 0) formatter.format(Instant.ofEpochMilli(millis).atZone(zone)) + " · МСК" else "Нет записанного времени"
    private fun name(id: String) = CityCatalog.all.firstOrNull { it.id == id }?.name ?: id
    private fun dp(host: TestDiagnosticsHost, n: Int) = (host.activity.resources.displayMetrics.density * n).toInt()

    fun addSettings(host: TestDiagnosticsHost, root: LinearLayout) {
        if (host.activity.packageName != "ru.namaz.safadzhay.test") return
        add(host, root, R.drawable.ic_schedule, "Данные расписаний", "Годы, города и сохранённая копия") { host.open("test_data") }
        add(host, root, R.drawable.ic_checklist, "Диагностика · TEST", "Технические проверки на устройстве") { host.open("test_diagnostics") }
    }

    private fun add(host: TestDiagnosticsHost, root: LinearLayout, icon: Int, title: String, value: String,
        action: (() -> Unit)? = null) {
        val row = host.row(icon, title, value, action ?: {})
        (row.getChildAt(0) as? android.widget.ImageView)?.imageTintList =
            android.content.res.ColorStateList.valueOf(ThemeSettings.colors(host.activity).primary)
        // Diagnostic facts can be long; retain the existing card style, allow wrapping.
        val copy = row.getChildAt(1) as LinearLayout
        for (i in 0 until copy.childCount) (copy.getChildAt(i) as android.widget.TextView).apply {
            maxLines = Int.MAX_VALUE; ellipsize = null
        }
        if (action == null) {
            row.isClickable = false; row.isFocusable = true
            row.contentDescription = "$title. $value"
            row.getChildAt(row.childCount - 1).visibility = View.GONE
            for (i in 0 until row.childCount) row.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(host, 8) })
    }

    fun show(host: TestDiagnosticsHost, route: String) {
        if (host.activity.packageName != "ru.namaz.safadzhay.test") return
        val title = when (route) {
            "test_data" -> "Данные расписаний"
            "test_alarms" -> "Уведомления · TEST"
            "test_geo" -> "Геолокация · TEST"
            else -> "Диагностика · TEST"
        }
        val pair = host.panel(title, route) {
            if (route == "test_diagnostics" || route == "test_data") host.settings() else host.open("test_diagnostics")
        }
        val root = pair.second.apply { tag = route }
        add(host, root, R.drawable.ic_schedule, "Чтение сведений", "Сохранённые данные устройства…")
        pair.first.show()
        // Controller and session state belongs to the UI thread.
        val geo = if (route == "test_geo") geoFacts(host) else emptyList()
        reader.execute {
            val result = runCatching {
                if (route == "test_data" || route == "test_diagnostics") host.repository.loadSavedIfNeeded()
                Triple(host.repository.cacheInfo(), permissionFacts(host.activity), TestAlarmTrace.snapshot())
            }
            host.activity.runOnUiThread {
                if (host.activity.isDestroyed || host.activity.isFinishing || !host.isCurrent(root)) return@runOnUiThread
                root.removeViews(1, root.childCount - 1)
                result.fold(onSuccess = { (info, permissions, trace) ->
                    when (route) {
                        "test_data" -> cache(host, root, info)
                        "test_alarms" -> alarms(host, root, trace, permissions)
                        "test_geo" -> geo.forEach { (label, value) -> add(host, root, R.drawable.ic_location, label, value) }
                        else -> overview(host, root, info, permissions)
                    }
                    add(host, root, R.drawable.ic_schedule, "Обновить сведения", "Только чтение; будильники и геопоиск не запускаются") { host.open(route) }
                }, onFailure = {
                    add(host, root, R.drawable.ic_checklist, "Не удалось прочитать сведения", "Вернитесь назад и повторите. Данные не изменялись.")
                })
            }
        }
    }

    internal fun cacheStatus(info: ScheduleRepository.CacheInfo): String = when (info.state) {
        ScheduleRepository.CacheState.NOT_LOADED -> "Копия ещё не прочитана"
        ScheduleRepository.CacheState.ABSENT -> "Проверенных сохранённых расписаний нет"
        ScheduleRepository.CacheState.INVALID -> "Сохранённый файл не прошёл проверку; файл не удалён"
        ScheduleRepository.CacheState.VERIFIED -> "Проверенная сохранённая копия загружена"
    }
    private fun cache(host: TestDiagnosticsHost, root: LinearLayout, info: ScheduleRepository.CacheInfo) {
        add(host, root, R.drawable.ic_checklist, "Состояние сохранённых данных", cacheStatus(info) + ". Результат последнего чтения или успешного сохранения.")
        add(host, root, R.drawable.ic_schedule, "Последняя успешная проверка обновлений", timestamp(info.lastSuccessMillis))
        val today = LocalDate.now(zone)
        val city = host.selection.savedCity()?.id ?: "safadzhay"
        val offline = info.years.flatMap { it.cities }.any { it.id == city && today >= LocalDate.parse(it.from) && today <= LocalDate.parse(it.to) }
        add(host, root, R.drawable.ic_location, "Офлайн на сегодня · ${name(city)}",
            if (offline) "Доступно на ${today}. Интернет для чтения этой копии не нужен."
            else "Нет проверенных данных на ${today} для выбранного города.")
        info.years.forEach { year ->
            add(host, root, R.drawable.ic_schedule, "${year.year} · версия данных ${year.version}",
                year.cities.joinToString("\n") { "${name(it.id)}: ${it.from} — ${it.to}, дней: ${it.days}" })
        }
        add(host, root, R.drawable.ic_schedule, "Источник расписаний", "GitHub · namaz-vakytylary/namaz-schedules\n${ScheduleRepository.BASE_URL}")
    }

    internal fun permissionFacts(context: Context): List<Pair<String, String>> {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val notifications = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrNull()
        val exact = if (Build.VERSION.SDK_INT >= 31) runCatching { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() }.getOrNull() else null
        val channel = if (Build.VERSION.SDK_INT >= 26) runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(notificationChannelId(context))
        }.getOrNull() else null
        return listOf(
            "Разрешение уведомлений" to if (Build.VERSION.SDK_INT < 33) "POST_NOTIFICATIONS не требуется на этой версии Android"
                else if (granted(Manifest.permission.POST_NOTIFICATIONS)) "Разрешено" else "Не разрешено",
            "Уведомления приложения" to when (notifications) { true -> "Разрешены системой"; false -> "Выключены системой"; null -> "Не удалось прочитать" },
            "Канал выбранного звука" to if (Build.VERSION.SDK_INT < 26) "Каналы не используются на этой версии Android" else when {
                channel == null -> "Канал отсутствует или недоступен для чтения"
                channel.importance == NotificationManager.IMPORTANCE_NONE -> "Выключен пользователем"
                else -> "Создан, не заблокирован"
            },
            "Доступ к точным будильникам" to if (Build.VERSION.SDK_INT < 31) "Специальный доступ не требуется; способ указан у каждой записи"
                else when (exact) { true -> "Разрешён"; false -> "Не разрешён"; null -> "Не удалось прочитать" },
            "Геолокация" to "Приблизительная: ${if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "разрешена" else "не разрешена"}; точная: ${if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "разрешена" else "не разрешена"}"
        )
    }

    private fun alarms(host: TestDiagnosticsHost, root: LinearLayout, snapshot: TestAlarmTrace.Snapshot,
        permissions: List<Pair<String, String>>) {
        val prefs = host.activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        add(host, root, R.drawable.ic_notification, "Напоминания в настройках", if (prefs.getBoolean("notifications_enabled", false)) "Включены" else "Выключены")
        permissions.take(4).forEach { (label, value) -> add(host, root, R.drawable.ic_notification, label, value) }
        val status = when (snapshot.status) {
            "complete" -> "Планирование завершено в этом процессе"
            "rebuilding" -> "Планирование ещё выполняется; список неполный"
            "incomplete" -> "Планирование завершилось ошибкой; список неполный"
            else -> "В этом процессе ещё нет сведений о планировании"
        }
        add(host, root, R.drawable.ic_checklist, "Состояние списка", "$status\n${timestamp(snapshot.updatedMillis)}")
        add(host, root, R.drawable.ic_schedule, "Граница проверки", "Ниже — запросы, принятые AlarmManager после успешного вызова. Android не предоставляет приложению полный список своих будильников. Сохранённый PendingIntent не доказывает наличие будильника; доставка зависит от системы и настроек устройства.")
        if (snapshot.entries.isEmpty()) add(host, root, R.drawable.ic_schedule, "Записи планирования", "Подтверждённых вызовов в текущем списке нет; это не проверка скрытого состояния Android.")
        snapshot.entries.sortedBy { it.triggerMillis }.forEach { entry ->
            val date = LocalDate.parse(entry.date)
            val pending = PendingIntent.getBroadcast(host.activity, PrayerAlarmScheduler.requestCode(date, entry.key),
                Intent(host.activity, PrayerNotificationReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            val index = PrayerAlarmScheduler.keys.indexOf(entry.key)
            val prayer = PrayerAlarmScheduler.russian.getOrNull(index) ?: entry.key
            val elapsed = entry.triggerMillis <= System.currentTimeMillis()
            add(host, root, R.drawable.ic_alarm_on, "$prayer · ${entry.city}",
                "Намаз: ${entry.date} · ${entry.prayerTime} · МСК\nНапоминание: ${timestamp(entry.triggerMillis)}\n" +
                "Способ при планировании: ${if (entry.exact) "точный" else "неточный"}\n" +
                "PendingIntent: ${if (pending != null) "присутствует" else "не найден"}\n" +
                (if (elapsed) "Ожидаемое время прошло; доставка этим экраном не подтверждается." else "Запрос принят AlarmManager; доставка не гарантируется."))
        }
    }

    private fun geoFacts(host: TestDiagnosticsHost): List<Pair<String, String>> {
        val fix = TestGeoTrace.snapshot(host.selection.session)
        val automatic = fix?.cityId?.let(::name) ?: host.selection.automaticCity()?.name
        val source = when (fix?.provider) { "network" -> "Сеть"; "gps" -> "GPS"; else -> "Источник не записан или неизвестен; не определяется по догадке" }
        val state = when (host.location.currentState()) {
            CityLocationState.IDLE -> "Поиск не выполняется"
            CityLocationState.SEARCHING -> "Выполняется ограниченный поиск"
            CityLocationState.READY -> "Результат принят"
            CityLocationState.PERMISSION_REQUIRED -> "Нет разрешения"
            CityLocationState.PERMISSION_BLOCKED -> "Разрешение заблокировано"
            CityLocationState.LOCATION_OFF -> "Геолокация выключена"
            CityLocationState.UNAVAILABLE -> "Нет подходящего результата"
            CityLocationState.UNSUPPORTED -> "Нет подходящего города с расписанием"
        }
        val location = host.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = runCatching { location.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || location.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        return listOf(
            "Выбранный город" to (host.selection.savedCity()?.name ?: "Используется исходный город Сафаджай; автоопределение ещё не подтверждено"),
            "Режим текущего запуска" to if (host.selection.isAutomatic()) "Автоматический" else "Ручной",
            "Последний автоматический город" to (automatic ?: "Город ещё не определён"),
            "Источник автоматического результата" to source,
            "Время последнего определения" to timestamp(fix?.timeMillis ?: host.selection.lastSuccess()),
            "Защита ручного выбора" to if (host.selection.session.manualOverride) "Включена до конца запуска; поздние результаты не меняют город" else "Ручной выбор в этом запуске ещё не сделан",
            "Состояние поиска" to state,
            "Геолокация устройства" to when (enabled) { true -> "Включена"; false -> "Выключена"; null -> "Не удалось прочитать" },
            permissionFacts(host.activity).last(),
            "Конфиденциальность" to "Только последний принятый результат текущего запуска. Координаты и история перемещений здесь не сохраняются."
        )
    }

    private fun overview(host: TestDiagnosticsHost, root: LinearLayout, info: ScheduleRepository.CacheInfo,
        permissions: List<Pair<String, String>>) {
        val version = runCatching {
            val p = host.activity.packageManager.getPackageInfo(host.activity.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            "${p.versionName} · $code\n${host.activity.packageName}"
        }.getOrElse { "Не удалось прочитать версию" }
        add(host, root, R.drawable.ic_checklist, "Версия приложения", version)
        add(host, root, R.drawable.ic_schedule, "Кеш", cacheStatus(info)) { host.open("test_data") }
        add(host, root, R.drawable.ic_schedule, "Источник расписаний", "GitHub · namaz-vakytylary/namaz-schedules")
        permissions.forEach { (label, value) -> add(host, root, R.drawable.ic_checklist, label, value) }
        add(host, root, R.drawable.ic_notification, "Проверка уведомлений · TEST", "Факты планирования и разрешения") { host.open("test_alarms") }
        add(host, root, R.drawable.ic_location, "Геолокация · TEST", "Результат и защита ручного выбора") { host.open("test_geo") }
        add(host, root, R.drawable.ic_holiday_crescent, "Проверка Рамадана", "Существующий тестовый режим") { host.ramadan() }
    }
}
