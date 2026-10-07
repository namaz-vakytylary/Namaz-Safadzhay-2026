package ru.namaz.safadzhay

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RadioButton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Exercises actual foreground provider lifetime, preferences and the existing Today UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
class AutomaticCityTest {
    class RationaleActivity : Activity() {
        override fun shouldShowRequestPermissionRationale(permission: String) = true
    }
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    private lateinit var settings: CitySelectionSettings
    private val states = mutableListOf<CityLocationState>()
    private val cities = mutableListOf<ScheduleCity>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", 0).edit().clear().putString("city", "Сафаджай").putString("city_selection_mode", "AUTO").putBoolean("notifications_enabled", false).commit()
        settings = CitySelectionSettings(app)
        manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
        TestNetwork.offline(app)
    }

    private fun fix(accuracyMeters: Float = 20f) = Location(LocationManager.NETWORK_PROVIDER).apply {
        latitude = 55.7558; longitude = 37.6173; accuracy = accuracyMeters
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(); time = System.currentTimeMillis()
    }
    private fun controller(activity: Activity, clock: () -> Long = System::currentTimeMillis,
        candidates: () -> List<ScheduleCity> = { CityCatalog.all }) =
        CityLocationController(activity, settings, candidates, { cities.add(it) }, { states.add(it) }, clock)
    private fun savedCity() = app.getSharedPreferences("settings", 0).getString("city", null)

    @Test fun fineFixChangesCityAndStopsProviderImmediately() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isNotEmpty())
            shadowOf(manager).simulateLocation(fix()); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
            assertEquals(listOf("moscow"), cities.map { it.id })
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun approximatePermissionAndFreshCachedFixAreEnough() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(5000f))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun approximatePermissionAcceptsLiveCurrentLocationWithoutFineGrant() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals(CityLocationState.SEARCHING, states.last())
            shadowOf(manager).simulateLocation(fix(5000f))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
            assertEquals(listOf("moscow"), cities.map { it.id })
            location.pause()
            val village = CityCatalog.all.first()
            shadowOf(manager).simulateLocation(fix().apply { latitude = village.latitude; longitude = village.longitude })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity()); assertEquals(1, cities.size)
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun deniedPermissionKeepsLastCityAndDoesNotStartProviders() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); location.permissionResult()
            assertEquals(CityLocationState.PERMISSION_BLOCKED, states.last())
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun permissionRevocationDuringRequestCannotOverwriteLastResolvedCity() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.markPermissionAsked()
        val old = System.currentTimeMillis() - CitySelectionPolicy.REFRESH_MS - 1000
        settings.acceptAutomatic(CityCatalog.all.first(), old, old)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            val receiver = shadowOf(manager).getLocationUpdateListeners().single()
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            receiver.onLocationChanged(fix())
            assertEquals(CityLocationState.PERMISSION_BLOCKED, states.last())
            assertEquals("Сафаджай", savedCity()); assertTrue(settings.hasResolvedCity())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun startupAutomaticallyRequestsBothPermissionsAndAcceptsCoarseResult() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertTrue(settings.permissionAsked())
            val request = shadowOf(activity.get()).lastRequestedPermission
            assertEquals(CityLocationController.REQUEST_CODE, request.requestCode)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), request.requestedPermissions.toSet())
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(4000f))
            location.permissionResult()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun oldManualInstallKeepsItsCityUntilUserEnablesAutomatic() {
        app.getSharedPreferences("settings", 0).edit().putString("city_selection_mode", "manual").commit()
        settings = CitySelectionSettings(app)
        assertEquals("Сафаджай", savedCity()); assertFalse(settings.hasResolvedCity())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals("Сафаджай", savedCity()); assertEquals(CityLocationState.IDLE, states.last())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            settings.selectMode(CitySelectionMode.AUTOMATIC); location.selectionChanged()
            assertEquals("Москва", savedCity()); assertTrue(settings.hasResolvedCity())
            assertEquals(CityLocationState.READY, states.last())
            assertEquals(CityCatalog.all.first(), settings.manualCity())
            assertEquals(CityCatalog.all.last(), settings.automaticCity())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun disabledLocationKeepsLastCity() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals(CityLocationState.LOCATION_OFF, states.last()); assertEquals("Сафаджай", savedCity())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun unavailableFixTimesOutWithoutChangingCityOrLeavingListeners() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val old = System.currentTimeMillis() - CitySelectionPolicy.REFRESH_MS - 1000
        settings.acceptAutomatic(CityCatalog.all.last(), old, old)
        settings.selectManual(CityCatalog.all.first()); settings.selectMode(CitySelectionMode.AUTOMATIC)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
            assertEquals(CityLocationState.UNAVAILABLE, states.last()); assertEquals("Москва", savedCity()); assertTrue(settings.hasResolvedCity())
            assertEquals(CityCatalog.all.first(), settings.manualCity()); assertEquals(CityCatalog.all.last(), settings.automaticCity())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun unsupportedLocationCannotReplaceSavedTimetable() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            shadowOf(manager).simulateLocation(fix().apply { latitude = 52.37; longitude = 4.90 })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
            assertEquals(CityLocationState.UNSUPPORTED, states.last()); assertEquals("Сафаджай", savedCity())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun pauseResumeRejectsOldRequestsAndLateResults() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            val previous = shadowOf(manager).getLocationUpdateListeners().single()
            location.pause()
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            previous.onLocationChanged(fix())
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
            location.resume()
            val current = shadowOf(manager).getLocationUpdateListeners().single()
            previous.onLocationChanged(fix())
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
            val village = CityCatalog.all.first()
            current.onLocationChanged(fix().apply { latitude = village.latitude; longitude = village.longitude })
            previous.onLocationChanged(fix())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Сафаджай", savedCity()); assertTrue(settings.hasResolvedCity())
            assertEquals(listOf("safadzhay"), cities.map { it.id })
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun repeatedResumeWithinFreshnessWindowDoesNotRequestLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val now = System.currentTimeMillis()
        settings.acceptAutomatic(CityCatalog.all.last(), now, now)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            repeat(3) { location.resume(); location.pause() }
            assertEquals(CityLocationState.READY, states.last())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty()); assertTrue(cities.isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun permissionRequestWaitsForOtherAppPermissionDialog() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(allowPermissionRequest = false)
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            location.permissionRequestsAvailable()
            assertEquals(CityLocationController.REQUEST_CODE, shadowOf(activity.get()).lastRequestedPermission.requestCode)
            assertTrue(settings.permissionAsked())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun permanentDenialDoesNotRepeatedlyPromptOrOpenAndroidSettings() {
        settings.markPermissionAsked()
        repeat(2) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val location = controller(activity.get())
            try {
                repeat(2) { location.resume(); location.pause() }
                assertEquals(CityLocationState.PERMISSION_BLOCKED, states.last())
                assertNull(shadowOf(activity.get()).lastRequestedPermission)
                assertNull(shadowOf(activity.get()).nextStartedActivity)
                assertEquals("Сафаджай", savedCity())
            } finally { location.pause(); activity.pause().stop().destroy() }
        }
    }

    @Test fun returningToAutoCanRequestPermissionAfterAnOrdinaryDenial() {
        settings.markPermissionAsked(); settings.selectManual(CityCatalog.all.last())
        val activity = Robolectric.buildActivity(RationaleActivity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); assertNull(shadowOf(activity.get()).lastRequestedPermission)
            settings.selectMode(CitySelectionMode.AUTOMATIC); location.selectionChanged()
            assertEquals(CityLocationController.REQUEST_CODE, shadowOf(activity.get()).lastRequestedPermission.requestCode)
            assertEquals(CityLocationState.PERMISSION_REQUIRED, states.last())
            location.permissionResult()
            assertEquals(CityCatalog.all.last(), settings.manualCity()); assertEquals("Москва", savedCity())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun permissionGrantedWhilePausedIsUsedOnNextResume() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); location.pause()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(3000f))
            location.permissionResult()
            assertEquals("Сафаджай", savedCity())
            location.resume()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun automaticCityChangesAfterSavedLocationExpires() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        var now = System.currentTimeMillis()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get(), clock = { now })
        try {
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
            location.resume(); location.pause()
            assertEquals("Москва", savedCity())
            now += CitySelectionPolicy.REFRESH_MS + 1000
            val village = CityCatalog.all.first()
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
                fix().apply { latitude = village.latitude; longitude = village.longitude })
            location.resume()
            assertEquals("Сафаджай", savedCity()); assertEquals(listOf("moscow", "safadzhay"), cities.map { it.id })
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun savedCityIsRestoredWithoutGpsOnNewController() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val now = System.currentTimeMillis()
        settings.acceptAutomatic(CityCatalog.all.last(), now, now)
        repeat(2) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup()
            val location = controller(activity.get())
            try {
                location.resume()
                assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
                assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty()); assertTrue(cities.isEmpty())
            } finally { location.pause(); activity.pause().stop().destroy() }
        }
    }

    @Test fun timedRefreshOnlyRunsWhileForeground() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val epoch = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get(), clock = { epoch + SystemClock.elapsedRealtime() - elapsed })
        try {
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
            location.resume(); assertEquals("Москва", savedCity())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertEquals(CityLocationState.SEARCHING, states.last())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isNotEmpty())
            val village = CityCatalog.all.first()
            shadowOf(manager).simulateLocation(fix().apply { latitude = village.latitude; longitude = village.longitude })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Сафаджай", savedCity())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            location.pause()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertEquals(listOf("moscow", "safadzhay"), cities.map { it.id })
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun failedAttemptRetriesAutomaticallyAfterCooldown() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val epoch = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get(), clock = { epoch + SystemClock.elapsedRealtime() - elapsed })
        try {
            location.resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
            assertEquals(CityLocationState.UNAVAILABLE, states.last())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.RETRY_MS))
            assertEquals(CityLocationState.SEARCHING, states.last())
            shadowOf(manager).simulateLocation(fix())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun noEligibleSchedulesNeverInventsCityOrTimetable() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get(), candidates = { emptyList() })
        try {
            location.resume()
            assertEquals(CityLocationState.UNSUPPORTED, states.last()); assertEquals("Сафаджай", savedCity())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun refreshedSchedulesCanEnableAutomaticSelectionWithoutManualAction() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        var eligible = emptyList<ScheduleCity>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get(), candidates = { eligible })
        try {
            location.resume()
            assertEquals(CityLocationState.UNSUPPORTED, states.last())
            eligible = CityCatalog.all
            location.schedulesChanged()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun fullModeCycleRejectsLateAutoRequestEvenAfterReturningToAuto() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val village = CityCatalog.all.first(); val moscow = CityCatalog.all.last()
        val old = System.currentTimeMillis() - CitySelectionPolicy.REFRESH_MS - 1000
        settings.acceptAutomatic(village, old, old)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            val previous = shadowOf(manager).getLocationUpdateListeners().single()
            settings.selectManual(moscow); location.selectionChanged()
            previous.onLocationChanged(fix())
            assertEquals("Москва", savedCity()); assertEquals(village, settings.automaticCity())
            assertEquals(moscow, settings.manualCity()); assertTrue(cities.isEmpty())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())

            assertEquals(village, settings.selectMode(CitySelectionMode.AUTOMATIC))
            location.selectionChanged()
            val current = shadowOf(manager).getLocationUpdateListeners().single()
            previous.onLocationChanged(fix())
            assertEquals("Сафаджай", savedCity()); assertEquals(village, settings.automaticCity()); assertTrue(cities.isEmpty())
            current.onLocationChanged(fix().apply { latitude = village.latitude; longitude = village.longitude })
            assertEquals(CityLocationState.READY, states.last()); assertEquals(listOf(village), cities)
            assertEquals(moscow, settings.manualCity())
            settings.selectMode(CitySelectionMode.MANUAL); location.selectionChanged()
            assertEquals("Москва", savedCity()); assertEquals(village, settings.automaticCity())
            location.pause(); location.resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertEquals("Москва", savedCity()); assertEquals(listOf(village), cities)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun manualModeNeverRequestsLocationEvenWithFreshProviderCoordinates() {
        val village = CityCatalog.all.first(); val moscow = CityCatalog.all.last()
        val now = System.currentTimeMillis()
        settings.acceptAutomatic(village, now, now); settings.selectManual(moscow)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            repeat(2) { location.resume(); location.schedulesChanged(); location.pause() }
            location.permissionRequestsAvailable(); location.permissionResult()
            assertEquals("Москва", savedCity()); assertEquals(village, settings.automaticCity())
            assertEquals(moscow, settings.manualCity()); assertEquals(now, settings.lastSuccess())
            assertTrue(cities.isEmpty()); assertFalse(settings.permissionAsked())
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun permissionResultAfterManualSelectionCannotActivateAutoCity() {
        val moscow = CityCatalog.all.last(); val village = CityCatalog.all.first()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals(CityLocationController.REQUEST_CODE, shadowOf(activity.get()).lastRequestedPermission.requestCode)
            settings.selectManual(moscow); location.selectionChanged()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
                fix().apply { latitude = village.latitude; longitude = village.longitude })
            location.permissionResult()
            assertEquals("Москва", savedCity()); assertEquals(moscow, settings.manualCity())
            assertNull(settings.automaticCity()); assertTrue(cities.isEmpty())
            settings.selectMode(CitySelectionMode.AUTOMATIC); location.selectionChanged()
            assertEquals("Сафаджай", savedCity()); assertEquals(village, settings.automaticCity())
            assertEquals(moscow, settings.manualCity()); assertEquals(listOf(village), cities)
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun switchingBackToFreshAutomaticCityUsesSavedCityWithoutAnotherGpsRequest() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val village = CityCatalog.all.first(); val moscow = CityCatalog.all.last()
        val now = System.currentTimeMillis()
        settings.acceptAutomatic(village, now, now); settings.selectManual(moscow)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); assertEquals("Москва", savedCity())
            assertEquals(village, settings.selectMode(CitySelectionMode.AUTOMATIC))
            location.selectionChanged()
            assertEquals("Сафаджай", savedCity()); assertEquals(CityLocationState.READY, states.last())
            assertEquals(moscow, settings.manualCity()); assertEquals(now, settings.lastSuccess())
            assertTrue(cities.isEmpty()); assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun locationDisabledAfterModeSwitchRetainsBothIndependentCities() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val village = CityCatalog.all.first(); val moscow = CityCatalog.all.last()
        val old = System.currentTimeMillis() - CitySelectionPolicy.REFRESH_MS - 1000
        settings.acceptAutomatic(village, old, old); settings.selectManual(moscow)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            settings.selectMode(CitySelectionMode.AUTOMATIC); location.selectionChanged()
            assertEquals(CityLocationState.LOCATION_OFF, states.last()); assertEquals("Сафаджай", savedCity())
            assertEquals(village, settings.automaticCity()); assertEquals(moscow, settings.manualCity())
            settings.selectMode(CitySelectionMode.MANUAL); location.selectionChanged()
            assertEquals("Москва", savedCity()); assertEquals(village, settings.automaticCity())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    private fun installVerifiedTodaySchedules() {
        val today = LocalDate.now(ZoneId.of("Europe/Moscow")); val date = today.toString()
        val cities = JSONArray()
        listOf("safadzhay", "moscow").forEach { id ->
            val day = JSONObject().put("date", date).put("fajr", if (id == "moscow") "04:01" else "04:00")
                .put("zuhr", "12:00").put("asr", "16:00").put("maghrib", "19:00").put("isha", "21:00")
            cities.put(JSONObject().put("id", id).put("timeZone", "Europe/Moscow").put("days", JSONArray().put(day)))
        }
        val bytes = JSONObject().put("schemaVersion", 1).put("year", today.year).put("version", 100)
            .put("coverage", JSONObject().put("from", date).put("to", date).put("completeYear", false))
            .put("cities", cities).toString().toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = JSONObject().put("year", today.year).put("version", 100).put("path", "${today.year}.json")
            .put("cityIds", JSONArray().put("safadzhay").put("moscow")).put("dateFrom", date).put("dateTo", date)
            .put("completeYear", false).put("daysPerCity", 1).put("bytes", bytes.size).put("sha256", sha)
        val manifest = JSONObject().put("schemaVersion", 1).put("schedules", JSONArray().put(entry)).toString().toByteArray()
        app.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-schedules") }?.forEach { it.delete() }
        val repo = ScheduleRepository(app, { url, _ -> if (url.endsWith("manifest.json")) manifest else bytes })
        assertTrue(repo.sync(true).changed)
        ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
    }

    private fun tagged(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) tagged(root.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun verifyUi(mode: ThemeMode, systemDark: Boolean) {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-${if (systemDark) "night" else "notnight"}-mdpi")
        installVerifiedTodaySchedules(); ThemeSettings.save(app, mode)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(3000f))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        fun root() = ReflectionHelpers.getField<Dialog>(activity.get(), "settingsPanel").window!!.decorView
        fun choice(tag: String) = tagged(root(), tag) as RadioButton
        fun assertSchedule(city: String, fajr: String) {
            val main = activity.get()
            assertEquals(city, ReflectionHelpers.getField<String>(main, "selectedCity"))
            assertEquals(city, savedCity())
            assertEquals(fajr, ReflectionHelpers.callInstanceMethod<List<PrayerDay>>(main, "currentData").single().fajr)
            assertTrue(ReflectionHelpers.getField<TextView>(main, "placeText").text.contains(city))
            assertEquals(mode.usesDark(systemDark), ReflectionHelpers.getField<AppColors>(main, "palette").isDark)
        }
        fun texts(view: View): List<String> = buildList {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
        }
        fun assertNoRefresh() {
            assertNull(tagged(root(), "city_location_retry"))
            assertFalse(texts(root()).any { it.contains("Обновить местоположение") })
        }
        try {
            assertSchedule("Москва", "04:01")
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "showSettingsDialog")
            assertTrue((tagged(root(), "city_selection_summary") as TextView).text.contains("Определено автоматически"))
            val card = tagged(root(), "city_information")!!
            assertTrue(card.isClickable); assertTrue(card.isFocusable); assertTrue(card.performClick())
            assertTrue(texts(root()).contains("Как выбирать город для расписания"))
            assertEquals("Автоматически", choice("city_mode_AUTO").text.toString())
            assertEquals("Вручную", choice("city_mode_MANUAL").text.toString())
            assertTrue(choice("city_mode_AUTO").isChecked)
            assertNull(tagged(root(), "city_manual_moscow")); assertNull(tagged(root(), "city_manual_safadzhay"))
            assertNoRefresh()

            // AUTO Moscow -> MANUAL Safadzhay: independent retained cities and actual timetable.
            assertTrue(choice("city_mode_MANUAL").performClick())
            assertSchedule("Сафаджай", "04:00")
            assertTrue(choice("city_mode_MANUAL").isChecked); assertTrue(choice("city_manual_safadzhay").isChecked)
            assertTrue(choice("city_manual_moscow").performClick()); assertSchedule("Москва", "04:01")
            assertTrue(choice("city_manual_safadzhay").performClick()); assertSchedule("Сафаджай", "04:00")
            val saved = CitySelectionSettings(app)
            assertEquals(CityCatalog.all.last(), saved.automaticCity()); assertEquals(CityCatalog.all.first(), saved.manualCity())
            assertNoRefresh()

            assertTrue(choice("city_mode_AUTO").performClick()); assertSchedule("Москва", "04:01")
            assertTrue(choice("city_mode_AUTO").isChecked); assertNull(tagged(root(), "city_manual_moscow"))
            assertEquals(CityCatalog.all.first(), CitySelectionSettings(app).manualCity())
            assertTrue(choice("city_mode_MANUAL").performClick()); assertSchedule("Сафаджай", "04:00")
            assertNoRefresh()

            // Restart/recreation restores MANUAL and both remembered selections without GPS.
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
            activity.recreate()
            assertSchedule("Сафаджай", "04:00")
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertTrue(choice("city_mode_MANUAL").isChecked); assertTrue(choice("city_manual_safadzhay").isChecked)
            assertTrue(choice("city_mode_AUTO").performClick()); assertSchedule("Москва", "04:01")
            assertTrue((tagged(root(), "city_location_status") as TextView).text.contains("Определено автоматически"))
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertNoRefresh()
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun lightSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.LIGHT, true)
    @Test @Config(sdk = [33]) fun darkSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.DARK, false)
    @Test @Config(sdk = [33]) fun systemLightSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.SYSTEM, false)
    @Test @Config(sdk = [33]) fun systemDarkSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.SYSTEM, true)
}
