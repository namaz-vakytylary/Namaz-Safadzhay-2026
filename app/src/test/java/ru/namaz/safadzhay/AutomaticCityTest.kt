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
import android.widget.RadioButton
import android.widget.TextView
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
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    private lateinit var settings: CitySelectionSettings
    private val states = mutableListOf<CityLocationState>()
    private val cities = mutableListOf<ScheduleCity>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", 0).edit().clear().putString("city", "Сафаджай").commit()
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
    private fun controller(activity: Activity) = CityLocationController(activity, settings,
        { CityCatalog.all }, { cities.add(it) }, { states.add(it) })
    private fun savedCity() = app.getSharedPreferences("settings", 0).getString("city", null)

    @Test fun fineFixChangesCityAndStopsProviderImmediately() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.setMode(CitySelectionMode.AUTOMATIC)
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
        settings.setMode(CitySelectionMode.AUTOMATIC)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(5000f))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun deniedPermissionKeepsLastCityAndDoesNotStartProviders() {
        settings.setMode(CitySelectionMode.AUTOMATIC)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); location.permissionResult()
            assertEquals(CityLocationState.PERMISSION_REQUIRED, states.last())
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun enablingAutomaticRequestsBothPermissionsAndAcceptsCoarseResult() {
        settings.setMode(CitySelectionMode.AUTOMATIC)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume()
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            location.refresh(force = true, askPermission = true)
            val request = shadowOf(activity.get()).lastRequestedPermission
            assertEquals(CityLocationController.REQUEST_CODE, request.requestCode)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), request.requestedPermissions.toSet())
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(4000f))
            location.permissionResult()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun oldInstallRemainsManualWithoutPermissionPromptOrLocationRequest() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); location.refresh(force = true, askPermission = true)
            assertEquals(CitySelectionMode.MANUAL, settings.mode()); assertEquals("Сафаджай", savedCity())
            assertTrue(cities.isEmpty()); assertTrue(states.isEmpty())
            assertNull(shadowOf(activity.get()).lastRequestedPermission)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun disabledLocationKeepsLastCity() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.setMode(CitySelectionMode.AUTOMATIC)
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
        settings.setMode(CitySelectionMode.AUTOMATIC)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
            assertEquals(CityLocationState.UNAVAILABLE, states.last()); assertEquals("Сафаджай", savedCity())
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun unsupportedLocationCannotReplaceSavedTimetable() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.setMode(CitySelectionMode.AUTOMATIC)
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

    @Test fun pauseAndManualSelectionCancelPendingAutomaticChanges() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.setMode(CitySelectionMode.AUTOMATIC)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = controller(activity.get())
        try {
            location.resume(); location.pause()
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            shadowOf(manager).simulateLocation(fix()); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
            // simulateLocation updates the provider cache even while our listener is
            // stopped. Clear that fixture so resume starts a pending request instead
            // of legitimately resolving the cached Moscow fix before manual selection.
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
            location.resume()
            val queuedListener = shadowOf(manager).getLocationUpdateListeners().single()
            settings.selectManual(CityCatalog.all.first()); location.stop()
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            // A callback already queued by Android must also be ignored after stop.
            queuedListener.onLocationChanged(fix())
            shadowOf(manager).simulateLocation(fix()); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun repeatedResumeWithinFreshnessWindowDoesNotRequestLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        settings.setMode(CitySelectionMode.AUTOMATIC)
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
        settings.setMode(CitySelectionMode.AUTOMATIC)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(3000f))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val main = activity.get()
            assertEquals("Москва", ReflectionHelpers.getField<String>(main, "selectedCity"))
            assertEquals("Москва", savedCity())
            val data = ReflectionHelpers.callInstanceMethod<List<PrayerDay>>(main, "currentData")
            assertEquals("04:01", data.single().fajr)
            assertTrue(ReflectionHelpers.getField<TextView>(main, "placeText").text.contains("Москва"))
            assertEquals(mode.usesDark(systemDark), ReflectionHelpers.getField<AppColors>(main, "palette").isDark)
            ReflectionHelpers.callInstanceMethod<Unit>(main, "showSettingsDialog")
            val dialog = ReflectionHelpers.getField<Dialog>(main, "settingsPanel")
            val summary = tagged(dialog.window!!.decorView, "city_selection_summary") as TextView
            assertTrue(summary.text.contains("определено автоматически"))
            (summary.parent.parent as View).performClick()
            val root = dialog.window!!.decorView
            assertTrue((tagged(root, "city_mode_automatic") as RadioButton).isChecked)
            assertFalse(tagged(root, "city_manual_moscow")!!.isEnabled)
            tagged(root, "city_mode_manual")!!.performClick()
            assertEquals(CitySelectionMode.MANUAL, settings.mode())
            assertTrue(tagged(dialog.window!!.decorView, "city_manual_moscow")!!.isEnabled)
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun lightSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.LIGHT, true)
    @Test @Config(sdk = [33]) fun darkSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.DARK, false)
    @Test @Config(sdk = [33]) fun systemLightSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.SYSTEM, false)
    @Test @Config(sdk = [33]) fun systemDarkSettingsAndTodayUseResolvedCity() = verifyUi(ThemeMode.SYSTEM, true)
}
