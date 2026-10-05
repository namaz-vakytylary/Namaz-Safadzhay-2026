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
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executor

internal enum class CityLocationState { IDLE, SEARCHING, READY, PERMISSION_REQUIRED, LOCATION_OFF, UNAVAILABLE, UNSUPPORTED }

/** One foreground attempt, bounded to 20s and cancelled on pause/manual selection.
 * Independent of Qibla's screen-scoped compass listener. No service or background job.
 */
internal class CityLocationController(
    private val activity: Activity,
    private val settings: CitySelectionSettings,
    private val candidates: () -> List<ScheduleCity>,
    private val onCity: (ScheduleCity) -> Unit,
    private val onState: (CityLocationState) -> Unit
) {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val cancellations = mutableListOf<CancellationSignal>()
    private var listener: LocationListener? = null
    private var generation = 0
    private var searching = false
    private var foreground = false
    private var unsupportedFix = false
    private var forceNextResume = false
    private val timeout = Runnable { if (searching) fail(if (unsupportedFix) CityLocationState.UNSUPPORTED else CityLocationState.UNAVAILABLE) }

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun age(fix: Location) = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000_000.0
    private fun valid(fix: Location) = fix.hasAccuracy() && CitySelectionPolicy.validFix(fix.latitude, fix.longitude, fix.accuracy.toDouble(), age(fix))
    private fun automatic() = foreground && settings.mode() == CitySelectionMode.AUTOMATIC

    fun resume() {
        foreground = true
        val force = forceNextResume; forceNextResume = false
        refresh(force = force)
    }
    fun retryOnResume() { forceNextResume = true }
    fun pause() { foreground = false; stop() }
    fun stop() {
        searching = false; generation++
        handler.removeCallbacks(timeout)
        cancellations.forEach { runCatching { it.cancel() } }; cancellations.clear()
        listener?.let { runCatching { manager.removeUpdates(it) } }; listener = null
    }

    fun requestOrOpenPermissionSettings() {
        if (!automatic()) return
        if (hasPermission()) { refresh(force = true); return }
        val canAsk = !settings.permissionAsked() ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (canAsk) {
            settings.markPermissionAsked()
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_CODE)
        } else {
            retryOnResume()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${activity.packageName}") }
            runCatching { activity.startActivity(intent) }.onFailure { onState(CityLocationState.PERMISSION_REQUIRED) }
        }
    }

    fun permissionResult() {
        if (!automatic()) return
        if (hasPermission()) refresh(force = true) else fail(CityLocationState.PERMISSION_REQUIRED)
    }

    // Every provider operation is preceded by a grant check and handles revocation.
    @android.annotation.SuppressLint("MissingPermission")
    fun refresh(force: Boolean = false, askPermission: Boolean = false) {
        if (!automatic() || searching) return
        if (!hasPermission()) {
            onState(CityLocationState.PERMISSION_REQUIRED)
            if (askPermission) requestOrOpenPermissionSettings()
            return
        }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            // Android 12+ can provide a coarse GPS fix; older GPS providers need FINE.
            (it != LocationManager.GPS_PROVIDER || fine || Build.VERSION.SDK_INT >= 31) &&
                runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) { fail(CityLocationState.LOCATION_OFF); return }
        if (candidates().isEmpty()) { fail(CityLocationState.UNSUPPORTED); return }
        val now = System.currentTimeMillis()
        if (!force && CitySelectionPolicy.fresh(settings.lastSuccess(), now, CitySelectionPolicy.REFRESH_MS)) {
            onState(CityLocationState.READY); return
        }
        if (!force && CitySelectionPolicy.fresh(settings.lastAttempt(), now, CitySelectionPolicy.RETRY_MS)) {
            onState(CityLocationState.UNAVAILABLE); return
        }
        stop(); searching = true; unsupportedFix = false
        val token = generation
        onState(CityLocationState.SEARCHING)
        // Use only fresh, accurate cached fixes, never a wall-clock-only stale value.
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter(::valid).sortedBy { it.accuracy }.forEach { if (accept(it, token)) return }
        if (!searching || token != generation || !automatic()) return
        var started = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val executor = Executor { command -> handler.post(command) }
            providers.forEach { provider ->
                val cancel = CancellationSignal(); cancellations.add(cancel)
                try {
                    manager.getCurrentLocation(provider, cancel, executor) { fix ->
                        if (fix != null) accept(fix, token)
                    }
                    started++
                } catch (_: SecurityException) { cancel.cancel() }
                catch (_: IllegalArgumentException) { cancel.cancel() }
                catch (_: IllegalStateException) { cancel.cancel() }
            }
        } else {
            val receiver = object : LocationListener {
                override fun onLocationChanged(location: Location) { accept(location, token) }
                @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            listener = receiver
            providers.forEach { provider ->
                try { manager.requestLocationUpdates(provider, 5000L, 0f, receiver, Looper.getMainLooper()); started++ }
                catch (_: SecurityException) {} catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
            }
        }
        if (started == 0) fail(if (hasPermission()) CityLocationState.UNAVAILABLE else CityLocationState.PERMISSION_REQUIRED)
        else handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun accept(fix: Location, token: Int): Boolean {
        if (token != generation || !searching || !automatic()) return false
        if (!hasPermission()) { fail(CityLocationState.PERMISSION_REQUIRED); return false }
        if (!valid(fix)) return false
        val city = CitySelectionPolicy.nearest(fix.latitude, fix.longitude, fix.accuracy.toDouble(), candidates())
        if (city == null) { unsupportedFix = true; return false }
        val now = System.currentTimeMillis()
        val accepted = settings.acceptAutomatic(city, now - (age(fix) * 1000).toLong(), now)
        stop()
        if (accepted) { onCity(city); onState(CityLocationState.READY) }
        return accepted
    }

    private fun fail(state: CityLocationState) {
        stop()
        if (settings.mode() == CitySelectionMode.AUTOMATIC) {
            settings.recordFailure(System.currentTimeMillis()); onState(state)
        }
    }
    companion object { const val REQUEST_CODE = 8105; const val TIMEOUT_MS = 20_000L }
}
