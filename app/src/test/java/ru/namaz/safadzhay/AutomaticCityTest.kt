package ru.namaz.safadzhay

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Bundle
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

/** Real provider lifetimes, stale callbacks, migration, session lifecycle and city-only UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
class AutomaticCityTest {
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    private lateinit var settings: CitySelectionSettings
    private val states = mutableListOf<CityLocationState>()
    private val cities = mutableListOf<ScheduleCity>()
    private val village = CityCatalog.all.first()
    private val moscow = CityCatalog.all.last()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("settings", 0).edit().clear().putString("city", "Сафаджай")
            .putBoolean("notifications_enabled", false).commit()
        settings = CitySelectionSettings(app)
        manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
        TestNetwork.offline(app)
    }

    private fun fix(city: ScheduleCity = moscow, accuracyMeters: Float = 20f, ageSeconds: Long = 0L) =
        Location(LocationManager.NETWORK_PROVIDER).apply {
            // Distinct monotonic fixes, including when two results arrive immediately.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            latitude = city.latitude; longitude = city.longitude; accuracy = accuracyMeters
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageSeconds * 1_000_000_000L
            time = System.currentTimeMillis() - ageSeconds * 1000L
        }
    private fun savedCity() = app.getSharedPreferences("settings", 0).getString("city", null)
    private fun active(location: CityLocationController) = ReflectionHelpers.getField<LocationListener>(location, "listener")
    private fun passive(location: CityLocationController) = ReflectionHelpers.getField<LocationListener>(location, "passiveListener")
    private fun listeners() = shadowOf(manager).getLocationUpdateListeners()
    private fun coarse() { shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION) }
    private fun withLocation(clock: () -> Long = System::currentTimeMillis,
        candidates: () -> List<ScheduleCity> = { CityCatalog.all }, block: (CityLocationController) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = CityLocationController(activity.get(), settings, candidates, { cities.add(it) }, { states.add(it) }, clock)
        try { block(location) } finally { location.pause(); activity.pause().stop().destroy() }
    }

    @Test fun startupNearVillageSelectsVillage() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village, 3000f))
        withLocation { it.resume(); assertEquals("Сафаджай", savedCity()); assertEquals(listOf(village), cities) }
    }
    @Test fun startupNearMoscowSelectsMoscowWithCoarsePermission() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(moscow, 5000f))
        withLocation { it.resume(); assertEquals("Москва", savedCity()); assertNull(active(it)); assertNotNull(passive(it)) }
    }
    @Test @Config(sdk = [33]) fun approximateLiveCurrentLocationWorksWithoutFineGrant() {
        coarse()
        withLocation {
            it.resume(); shadowOf(manager).simulateLocation(fix(moscow, 5000f)); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity()); assertEquals(CityLocationState.READY, states.last())
        }
    }
    @Test fun successfulFixStopsActiveProviderAndKeepsOnlyPassiveMovementObserver() {
        coarse()
        withLocation {
            it.resume(); active(it).onLocationChanged(fix())
            assertEquals("Москва", savedCity()); assertNull(active(it))
            assertEquals(listOf(passive(it)), listeners().toList())
        }
    }
    @Test fun confidentPassiveMovementChangesVillageToMoscow() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
        withLocation {
            it.resume(); val movement = passive(it)
            movement.onLocationChanged(fix(moscow, 4000f))
            assertEquals("Москва", savedCity()); assertEquals(listOf(village, moscow), cities); assertNull(active(it))
        }
    }
    @Test fun jitterPoorAccuracyStaleAndDistantFixesKeepWorkingCity() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
        withLocation {
            it.resume(); val movement = passive(it)
            movement.onLocationChanged(fix(village).apply { latitude += 0.00002 })
            assertEquals("Сафаджай", savedCity())
            movement.onLocationChanged(fix(moscow, 30_000f))
            movement.onLocationChanged(fix(moscow, ageSeconds = 601))
            movement.onLocationChanged(fix(moscow, ageSeconds = 121))
            movement.onLocationChanged(fix().apply { latitude = 52.37; longitude = 4.90 })
            assertEquals("Сафаджай", savedCity()); assertEquals(village, settings.automaticCity())
        }
    }
    @Test fun olderCachedResultCannotReverseNewerLiveMovement() {
        coarse()
        withLocation {
            it.resume(); val previous = fix(village); active(it).onLocationChanged(fix(moscow))
            passive(it).onLocationChanged(previous)
            assertEquals("Москва", savedCity()); assertEquals(listOf(moscow), cities)
        }
    }
    @Test fun boundaryJitterCannotFlapCity() {
        coarse()
        val left = ScheduleCity(village.id, village.name, 0.0, -0.1)
        val right = ScheduleCity(moscow.id, moscow.name, 0.0, 0.1)
        withLocation(candidates = { listOf(left, right) }) {
            it.resume()
            repeat(10) { count ->
                active(it).onLocationChanged(fix().apply { latitude = 0.0; longitude = if (count % 2 == 0) 0.001 else -0.001 })
            }
            assertEquals("Сафаджай", savedCity()); assertTrue(cities.isEmpty())
        }
    }
    @Test fun manualClickRejectsLateActiveAndPassiveCallbacksWithoutPreferenceWrite() {
        coarse()
        withLocation {
            it.resume(); val oldActive = active(it); val oldPassive = passive(it)
            settings.selectManual(moscow); it.selectionChanged()
            val before = app.getSharedPreferences("settings", 0).all.toMap()
            oldActive.onLocationChanged(fix(village)); oldPassive.onLocationChanged(fix(village))
            it.permissionResult(); it.schedulesChanged(); it.permissionRequestsAvailable()
            assertEquals(before, app.getSharedPreferences("settings", 0).all)
            assertEquals("Москва", savedCity()); assertTrue(cities.isEmpty()); assertTrue(listeners().isEmpty())
        }
    }
    @Test @Config(sdk = [33]) fun lateCurrentLocationCallbackCannotUndoManualClick() {
        coarse()
        withLocation {
            it.resume(); settings.selectManual(moscow); it.selectionChanged()
            shadowOf(manager).simulateLocation(fix(village)); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Москва", savedCity()); assertTrue(cities.isEmpty()); assertTrue(listeners().isEmpty())
        }
    }
    @Test fun manualOverrideStopsAllFutureRefreshesAndSurvivesBackgroundForeground() {
        coarse()
        withLocation {
            it.resume(); settings.selectManual(moscow); it.selectionChanged(); it.pause(); it.resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertEquals("Москва", savedCity()); assertTrue(listeners().isEmpty()); assertTrue(cities.isEmpty())
        }
    }
    @Test fun newLaunchChecksLocationDespiteRecentAutoHistoryAndManualCity() {
        coarse(); val now = System.currentTimeMillis()
        settings.acceptAutomatic(village, now, now); settings.selectManual(moscow)
        settings = CitySelectionSettings(app)
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
        withLocation {
            it.resume(); assertEquals("Сафаджай", savedCity()); assertEquals(moscow, settings.manualCity())
        }
    }
    @Test fun legacyManualInstallRetainsCityUntilReliableStartupFixArrives() {
        app.getSharedPreferences("settings", 0).edit().putString("city", "Москва").putString("city_selection_mode", "MANUAL").commit()
        settings = CitySelectionSettings(app); coarse()
        withLocation {
            it.resume(); assertEquals("Москва", savedCity())
            active(it).onLocationChanged(fix(village)); assertEquals("Сафаджай", savedCity())
            assertEquals(moscow, settings.manualCity())
        }
    }
    @Test fun deniedPermissionKeepsCityAndDoesNotRepeatedlyPrompt() {
        withLocation {
            it.resume(); assertTrue(settings.permissionAsked()); it.permissionResult()
            assertEquals("Сафаджай", savedCity()); assertTrue(listeners().isEmpty())
            it.pause(); it.resume(); assertEquals(CityLocationState.PERMISSION_BLOCKED, states.last())
        }
    }
    @Test fun permissionDialogWaitsForNotificationDialog() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val location = CityLocationController(activity.get(), settings, { CityCatalog.all }, {}, {})
        try {
            location.resume(false); assertNull(shadowOf(activity.get()).lastRequestedPermission)
            location.permissionRequestsAvailable()
            val request = shadowOf(activity.get()).lastRequestedPermission
            assertEquals(CityLocationController.REQUEST_CODE, request.requestCode)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), request.requestedPermissions.toSet())
        } finally { location.pause(); activity.pause().stop().destroy() }
    }
    @Test fun permissionGrantedWhilePausedIsUsedOnResume() {
        withLocation {
            it.resume(); it.pause(); coarse()
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
            it.permissionResult(); assertEquals("Сафаджай", savedCity()); it.resume(); assertEquals("Москва", savedCity())
        }
    }
    @Test fun permissionRevocationRejectsCallbacksAndStopsObservers() {
        coarse()
        withLocation {
            it.resume(); val callback = active(it)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            callback.onLocationChanged(fix())
            assertEquals("Сафаджай", savedCity()); assertTrue(listeners().isEmpty())
        }
    }
    @Test fun locationOffAndMissingSchedulesKeepCityWithoutObservers() {
        coarse(); shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        withLocation { it.resume(); assertEquals(CityLocationState.LOCATION_OFF, states.last()); assertTrue(listeners().isEmpty()) }
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        withLocation(candidates = { emptyList() }) {
            it.resume(); assertEquals(CityLocationState.UNSUPPORTED, states.last()); assertTrue(listeners().isEmpty())
            assertEquals("Сафаджай", savedCity())
        }
    }
    @Test fun refreshedSchedulesCanEnableSelection() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        var eligible = emptyList<ScheduleCity>()
        withLocation(candidates = { eligible }) {
            it.resume(); eligible = CityCatalog.all; it.schedulesChanged(); assertEquals("Москва", savedCity())
        }
    }
    @Test fun timeoutStopsActiveGpsAndRetriesOnlyAfterCooldown() {
        coarse(); val epoch = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        withLocation(clock = { epoch + SystemClock.elapsedRealtime() - elapsed }) {
            it.resume(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
            assertEquals(CityLocationState.UNAVAILABLE, states.last()); assertNull(active(it))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.RETRY_MS))
            assertNotNull(active(it)); active(it).onLocationChanged(fix()); assertEquals("Москва", savedCity())
        }
    }
    @Test fun timedRefreshRunsOnlyWhileForeground() {
        coarse(); val epoch = System.currentTimeMillis(); val elapsed = SystemClock.elapsedRealtime()
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
        withLocation(clock = { epoch + SystemClock.elapsedRealtime() - elapsed }) {
            it.resume(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertNotNull(active(it)); active(it).onLocationChanged(fix()); assertEquals("Москва", savedCity())
            it.pause(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CitySelectionPolicy.REFRESH_MS + 1))
            assertTrue(listeners().isEmpty())
        }
    }
    @Test fun oldRequestsAreIgnoredAfterPauseAndForegroundReturnCanRefresh() {
        coarse()
        withLocation {
            it.resume(); val oldActive = active(it); val oldPassive = passive(it)
            it.pause(); assertTrue(listeners().isEmpty())
            oldActive.onLocationChanged(fix()); oldPassive.onLocationChanged(fix()); assertEquals("Сафаджай", savedCity())
            it.resume(); oldActive.onLocationChanged(fix()); assertEquals("Сафаджай", savedCity())
            active(it).onLocationChanged(fix()); assertEquals("Москва", savedCity())
        }
    }
    @Test fun ordinaryResumeAndRecreatedSessionDoNotCauseRepeatedActiveRequests() {
        coarse(); shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix())
        withLocation { it.resume(); it.pause(); it.resume(); assertNull(active(it)); assertEquals("Москва", savedCity()) }
        settings = CitySelectionSettings(app, CitySelectionSession(startupChecked = true))
        withLocation { it.resume(); assertNull(active(it)); assertNotNull(passive(it)) }
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
        installVerifiedTodaySchedules(); ThemeSettings.save(app, mode); coarse()
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(moscow, 3000f))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        fun root() = ReflectionHelpers.getField<Dialog>(activity.get(), "settingsPanel").window!!.decorView
        fun choice(city: ScheduleCity) = tagged(root(), "city_choice_${city.id}") as RadioButton
        fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        fun assertSchedule(city: String, fajr: String) {
            val main = activity.get()
            assertEquals(city, ReflectionHelpers.getField<String>(main, "selectedCity")); assertEquals(city, savedCity())
            assertEquals(fajr, ReflectionHelpers.callInstanceMethod<List<PrayerDay>>(main, "currentData").single().fajr)
            assertTrue(ReflectionHelpers.getField<TextView>(main, "placeText").text.contains(city))
            assertEquals(mode.usesDark(systemDark), ReflectionHelpers.getField<AppColors>(main, "palette").isDark)
        }
        fun assertCityOnly() {
            val texts = walk(root()).filterIsInstance<TextView>().filter { it.contentDescription != "Назад" }.map { it.text.toString() }.filter { it.isNotBlank() }
            assertEquals(listOf("Город", "Сафаджай", "Москва"), texts)
            assertEquals(2, walk(root()).filterIsInstance<RadioButton>().size)
            assertNull(tagged(root(), "city_location_status")); assertNull(tagged(root(), "city_mode_AUTO"))
        }
        try {
            assertSchedule("Москва", "04:01")
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "showSettingsDialog")
            assertEquals("Москва", (tagged(root(), "city_selection_summary") as TextView).text.toString())
            assertTrue(tagged(root(), "city_information")!!.performClick())
            assertCityOnly(); assertTrue(choice(moscow).isChecked); assertFalse(choice(village).isChecked)
            val location = ReflectionHelpers.getField<CityLocationController>(activity.get(), "cityLocation")
            passive(location).onLocationChanged(fix(village)); assertSchedule("Сафаджай", "04:00")
            assertTrue(choice(village).isChecked); assertFalse(choice(moscow).isChecked)
            val oldPassive = passive(location)
            assertTrue(choice(moscow).performClick()); assertSchedule("Москва", "04:01")
            oldPassive.onLocationChanged(fix(village)); assertSchedule("Москва", "04:01")
            assertTrue(choice(moscow).isChecked); assertTrue(listeners().isEmpty()); assertCityOnly()
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "showSettingsDialog")
            ReflectionHelpers.callInstanceMethod<Unit>(activity.get(), "showCityChoice")
            assertTrue(choice(moscow).isChecked); assertCityOnly()
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
            activity.recreate()
            assertSchedule("Москва", "04:01"); assertTrue(choice(moscow).isChecked); assertCityOnly()
            assertTrue(listeners().isEmpty())
            assertTrue(ReflectionHelpers.getField<CitySelectionSettings>(activity.get(), "citySelection").session.manualOverride)
        } finally { activity.pause().stop().destroy() }
        // A new task launch has no saved Activity state: auto is enabled again.
        val fresh = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            assertEquals("Сафаджай", ReflectionHelpers.getField<String>(fresh.get(), "selectedCity"))
            assertFalse(ReflectionHelpers.getField<CitySelectionSettings>(fresh.get(), "citySelection").session.manualOverride)
            assertEquals(moscow, CitySelectionSettings(app).manualCity())
        } finally { fresh.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun lightCityOnlyUiAndSessionLifecycle() = verifyUi(ThemeMode.LIGHT, true)
    @Test @Config(sdk = [33]) fun darkCityOnlyUiAndSessionLifecycle() = verifyUi(ThemeMode.DARK, false)
    @Test @Config(sdk = [33]) fun systemLightCityOnlyUiAndSessionLifecycle() = verifyUi(ThemeMode.SYSTEM, false)
    @Test @Config(sdk = [33]) fun systemDarkCityOnlyUiAndSessionLifecycle() = verifyUi(ThemeMode.SYSTEM, true)

    @Test @Config(sdk = [33]) fun systemSavedStateRecreationPreservesManualOverride() {
        installVerifiedTodaySchedules(); coarse()
        val saved = Bundle()
        val old = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val selections = ReflectionHelpers.getField<CitySelectionSettings>(old.get(), "citySelection")
            selections.selectManual(moscow)
            ReflectionHelpers.getField<CityLocationController>(old.get(), "cityLocation").selectionChanged()
            old.saveInstanceState(saved)
        } finally { old.pause().stop().destroy() }
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(village))
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
        try {
            assertEquals("Москва", ReflectionHelpers.getField<String>(restored.get(), "selectedCity"))
            assertTrue(ReflectionHelpers.getField<CitySelectionSettings>(restored.get(), "citySelection").session.manualOverride)
            assertTrue(listeners().isEmpty())
        } finally { restored.pause().stop().destroy() }
    }
}
