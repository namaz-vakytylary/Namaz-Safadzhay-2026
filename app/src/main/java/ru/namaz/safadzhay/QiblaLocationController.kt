package ru.namaz.safadzhay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** Location is held in memory and listened to only while the Qibla screen is visible. */
internal class QiblaLocationController(
    private val activity: Activity,
    private val onFix: (Location?) -> Unit,
    private val onStatus: (String, String, String) -> Unit
) : LocationListener {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var latest: Location? = null
    private var action: () -> Unit = { start() }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            val fix = latest
            if (fix == null || age(fix) > QiblaMath.MAX_FIX_AGE_SECONDS) {
                latest = null; onFix(null)
                status("Ищем местоположение", "Нет свежих координат. У окна или под открытым небом GPS обычно работает лучше.")
            }
            handler.postDelayed(this, 15000)
        }
    }
    private fun fine() = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun coarse() = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun age(location: Location) = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000.0
    private fun status(title: String, description: String, button: String = "Обновить местоположение", perform: () -> Unit = { start() }) {
        action = perform; onStatus(title, description, button)
    }
    fun performAction() = action()

    // Permissions are checked below and every provider call handles revocation.
    @android.annotation.SuppressLint("MissingPermission")
    fun start(askOnFirstUse: Boolean = false) {
        stop()
        if (!fine() && !coarse()) {
            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val asked = prefs.getBoolean("qibla_location_requested", false)
            val canAsk = !asked || activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            status("Нужно местоположение", "Разрешите геолокацию, чтобы определить киблу там, где вы сейчас.",
                if (canAsk) "Разрешить геолокацию" else "Разрешения приложения") {
                if (canAsk) requestPermission() else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            }
            if (askOnFirstUse && !asked) requestPermission()
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            (it != LocationManager.GPS_PROVIDER || fine()) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            if (!fine() && runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                status("Нужна точная геолокация", "Для GPS без сети разрешите точное местоположение в настройках приложения.", "Разрешения приложения") {
                    openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                }
            } else status("Геолокация недоступна", "Включите местоположение в настройках телефона.", "Включить геолокацию") {
                openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            return
        }
        listening = true
        status("Ищем местоположение", "Получаем координаты телефона. Направление появится после определения местоположения.")
        try {
            providers.forEach { manager.requestLocationUpdates(it, 2000L, 0f, this, Looper.getMainLooper()) }
            providers.mapNotNull { manager.getLastKnownLocation(it) }
                .filter { valid(it) }.sortedBy { it.accuracy }.firstOrNull()?.let { accept(it) }
            handler.postDelayed(watchdog, 15000)
        } catch (_: SecurityException) {
            stop()
            status("Нет доступа к геолокации", "Проверьте разрешение на местоположение.", "Разрешения приложения") {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            }
        } catch (_: IllegalArgumentException) {
            stop(); status("Местоположение недоступно", "Телефон не предоставил координаты. Попробуйте ещё раз.")
        }
    }
    private fun requestPermission() {
        activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("qibla_location_requested", true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_CODE)
    }
    private fun openSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply { if (withPackage) data = Uri.parse("package:${activity.packageName}") }
        runCatching { activity.startActivity(intent) }.onFailure { status("Настройки недоступны", "Откройте разрешения приложения через настройки телефона.") }
    }
    private fun valid(fix: Location) = fix.hasAccuracy() && QiblaMath.usableFix(fix.latitude, fix.longitude, fix.accuracy.toDouble(), age(fix))
    private fun accept(fix: Location) {
        if (!listening) return
        if (!valid(fix)) {
            if (latest == null) status("Уточняем местоположение", "Координаты устарели или их точности недостаточно. Ожидаем GPS.")
            return
        }
        // Do not replace a recent accurate GPS fix with a much less accurate network fix.
        val old = latest
        if (old != null && age(old) < 15 && fix.accuracy > old.accuracy * 2) return
        latest = Location(fix)
        if (!QiblaMath.directionResolvable(fix.latitude, fix.longitude, fix.accuracy.toDouble())) {
            onFix(null)
            status("Нужны более точные координаты", "В этой точке погрешность местоположения слишком велика для определения направления.")
            return
        }
        onFix(fix)
        status(if (fine() && fix.accuracy <= 100) "Ваше местоположение" else "Приблизительное местоположение",
            "Направление рассчитано по координатам телефона.")
    }
    override fun onLocationChanged(location: Location) = accept(location)
    override fun onProviderDisabled(provider: String) { if (listening) start() }
    override fun onProviderEnabled(provider: String) { if (listening) start() }
    @Deprecated("Legacy Android callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    // Removing a listener is also safe after one-time permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        runCatching { manager.removeUpdates(this) }
        latest = null; onFix(null)
    }
    companion object { const val REQUEST_CODE = 8104 }
}
