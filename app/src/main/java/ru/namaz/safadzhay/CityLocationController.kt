package ru.namaz.safadzhay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor

/** Automatic selection uses foreground-only attempts bounded to 20s. No service or GPS
 * tracking in the background; passive foreground movement updates never power GPS.
 * Independent of Qibla's screen-scoped listener.
 */
internal class CityLocationController(
    private val activity: Activity,
    private val settings: CitySelectionSettings,
    private val candidates: () -> List<ScheduleCity>,
    private val onCity: (ScheduleCity) -> Unit,
    private val onState: (CityLocationState) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val cancellations = mutableListOf<CancellationSignal>()
    private var listener: LocationListener? = null
    private var passiveListener: LocationListener? = null
    private var passiveGeneration = 0
    private var generation = 0
    private var searching = false
    private var foreground = false
    private var permissionsAvailable = true
    private var unsupportedFix = false
    private var state = CityLocationState.IDLE
    private var forceNextResume = false
    private val timeout = Runnable {
        if (searching) fail(if (unsupportedFix) CityLocationState.UNSUPPORTED else CityLocationState.UNAVAILABLE)
    }
    private val nextRefresh = Runnable { if (foreground) refresh() }

    private fun emit(value: CityLocationState) { state = value; onState(value) }
    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun age(fix: Location) = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000_000.0
    private fun valid(fix: Location) = fix.hasAccuracy() && CitySelectionPolicy.validFix(fix.latitude, fix.longitude, fix.accuracy.toDouble(), age(fix))

    fun resume(allowPermissionRequest: Boolean = true) {
        foreground = true
        permissionsAvailable = allowPermissionRequest
        val force = forceNextResume || !settings.session.startupChecked
        forceNextResume = false
        refresh(force)
    }

    // Avoid competing with the app's notification permission dialog.
    fun permissionRequestsAvailable() {
        permissionsAvailable = true
        if (foreground) refresh()
    }

    fun pause() {
        foreground = false
        handler.removeCallbacks(nextRefresh)
        stopAttempt()
        stopPassive()
    }

    fun schedulesChanged() {
        if (settings.isAutomatic() && foreground && !searching && (state == CityLocationState.UNSUPPORTED ||
                (settings.hasResolvedCity() && candidates().none { it.id == settings.savedCity()?.id }))) {
            refresh(force = true)
        }
    }

    // A city click locks this entire session; no mode switch or hidden reset.
    fun selectionChanged() {
        handler.removeCallbacks(nextRefresh)
        stopAttempt()
        stopPassive()
        forceNextResume = false
        emit(CityLocationState.IDLE)
    }

    private fun stopPassive() {
        passiveGeneration++
        passiveListener?.let { runCatching { manager.removeUpdates(it) } }
        passiveListener = null
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun observeMovement() {
        if (passiveListener != null || !foreground || !settings.isAutomatic() || !hasPermission()) return
        val token = passiveGeneration
        val receiver = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (token != passiveGeneration || !foreground || !settings.isAutomatic()) return
                applyFix(location)
            }
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                CitySelectionPolicy.PASSIVE_INTERVAL_MS, CitySelectionPolicy.MOVEMENT_METERS,
                receiver, Looper.getMainLooper())
            passiveListener = receiver
        } catch (_: SecurityException) { stopPassive() }
        catch (_: IllegalArgumentException) { stopPassive() }
        catch (_: IllegalStateException) { stopPassive() }
    }

    private fun stopAttempt() {
        searching = false; generation++
        handler.removeCallbacks(timeout)
        cancellations.forEach { runCatching { it.cancel() } }; cancellations.clear()
        listener?.let { runCatching { manager.removeUpdates(it) } }; listener = null
    }

    private fun permissionState() = if (settings.permissionAsked() &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
        CityLocationState.PERMISSION_BLOCKED
    } else CityLocationState.PERMISSION_REQUIRED

    fun permissionResult() {
        if (!settings.isAutomatic()) { selectionChanged(); return }
        if (hasPermission()) {
            forceNextResume = true
            if (foreground) { forceNextResume = false; refresh(force = true) }
        } else {
            stopAttempt()
            stopPassive()
            settings.recordFailure(clock())
            emit(permissionState())
        }
    }

    private fun later(timestamp: Long, interval: Long) {
        handler.removeCallbacks(nextRefresh)
        if (foreground && settings.isAutomatic()) handler.postDelayed(nextRefresh, (timestamp + interval - clock()).coerceAtLeast(1000L))
    }

    // Every provider operation is preceded by a grant check and handles revocation.
    @android.annotation.SuppressLint("MissingPermission")
    private fun refresh(force: Boolean = false) {
        if (!settings.isAutomatic()) {
            handler.removeCallbacks(nextRefresh); stopAttempt(); stopPassive(); emit(CityLocationState.IDLE)
            return
        }
        if (!foreground || searching) return
        handler.removeCallbacks(nextRefresh)
        if (!hasPermission()) {
            stopPassive()
            val required = permissionState()
            emit(required)
            val mayAsk = !settings.permissionAsked()
            if (permissionsAvailable && mayAsk) {
                // Persist before asking so recreation/resume cannot repeat the prompt.
                settings.markPermissionAsked()
                runCatching {
                    activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_CODE)
                }.onFailure { emit(CityLocationState.PERMISSION_REQUIRED) }
            }
            return
        }
        val recovered = state == CityLocationState.PERMISSION_REQUIRED || state == CityLocationState.PERMISSION_BLOCKED ||
            state == CityLocationState.LOCATION_OFF
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val enabledProviders = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            (it != LocationManager.GPS_PROVIDER || fine || Build.VERSION.SDK_INT >= 31) &&
                runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        // Prefer network location. GPS is only a bounded fallback when network
        // location is unavailable; coarse permission alone is supported on S+.
        val providers = if (LocationManager.NETWORK_PROVIDER in enabledProviders)
            listOf(LocationManager.NETWORK_PROVIDER) else enabledProviders
        if (providers.isEmpty()) { stopPassive(); fail(CityLocationState.LOCATION_OFF); return }
        val eligible = candidates()
        if (eligible.isEmpty()) { stopPassive(); fail(CityLocationState.UNSUPPORTED); return }
        observeMovement()
        val now = clock()
        val needsStartupCheck = !settings.session.startupChecked
        if (!force && !needsStartupCheck && !recovered && settings.hasResolvedCity() && eligible.any { it.id == settings.savedCity()?.id } &&
            CitySelectionPolicy.fresh(settings.lastSuccess(), now, CitySelectionPolicy.REFRESH_MS)) {
            emit(CityLocationState.READY); later(settings.lastSuccess(), CitySelectionPolicy.REFRESH_MS); return
        }
        if (!force && !needsStartupCheck && !recovered && CitySelectionPolicy.fresh(settings.lastAttempt(), now, CitySelectionPolicy.RETRY_MS)) {
            emit(CityLocationState.UNAVAILABLE); later(settings.lastAttempt(), CitySelectionPolicy.RETRY_MS); return
        }
        settings.session.startupChecked = true
        stopAttempt(); searching = true; unsupportedFix = false
        val token = generation
        emit(CityLocationState.SEARCHING)
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter(::valid).sortedBy { it.accuracy }.forEach { if (accept(it, token)) return }
        if (!searching || token != generation || !foreground) return
        var started = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val executor = Executor { command -> handler.post(command) }
            providers.forEach { provider ->
                val cancel = CancellationSignal(); cancellations.add(cancel)
                try {
                    manager.getCurrentLocation(provider, cancel, executor) { fix -> if (fix != null) accept(fix, token) }
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
        if (started == 0) fail(if (hasPermission()) CityLocationState.UNAVAILABLE else permissionState())
        else handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun accept(fix: Location, token: Int): Boolean {
        if (token != generation || !searching || !foreground || !settings.isAutomatic()) return false
        return applyFix(fix)
    }

    private fun applyFix(fix: Location): Boolean {
        if (!foreground || !settings.isAutomatic()) return false
        if (!hasPermission()) { stopPassive(); fail(permissionState()); return false }
        if (!valid(fix) || fix.elapsedRealtimeNanos <= settings.session.latestFixNanos) return false
        val current = settings.savedCity()
        val city = CitySelectionPolicy.nearest(fix.latitude, fix.longitude, fix.accuracy.toDouble(), candidates(), current)
        if (city == null) { unsupportedFix = true; return false }
        // Even a formerly valid cached fix cannot change cities after it ages.
        if (city != current && age(fix) > CitySelectionPolicy.MAX_SWITCH_FIX_AGE_SECONDS) return false
        val now = clock()
        val accepted = settings.acceptAutomatic(city, now - (age(fix) * 1000).toLong(), now)
        if (!accepted) return false
        settings.session.latestFixNanos = fix.elapsedRealtimeNanos
        settings.session.startupChecked = true
        stopAttempt()
        onCity(city); emit(CityLocationState.READY)
        later(settings.lastSuccess(), CitySelectionPolicy.REFRESH_MS)
        return true
    }

    private fun fail(value: CityLocationState) {
        stopAttempt()
        if (foreground && settings.isAutomatic()) {
            settings.recordFailure(clock()); emit(value)
            if (value != CityLocationState.PERMISSION_REQUIRED && value != CityLocationState.PERMISSION_BLOCKED) {
                later(settings.lastAttempt(), CitySelectionPolicy.RETRY_MS)
            }
        }
    }

    companion object { const val REQUEST_CODE = 8105; const val TIMEOUT_MS = 20_000L }
}
