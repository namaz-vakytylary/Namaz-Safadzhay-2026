package ru.namaz.safadzhay

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** JVM tests for real matching and persistence code; no phone model or GPS required. */
class CitySelectionTest {
    private val moscow = CityCatalog.all.single { it.id == "moscow" }
    private val village = CityCatalog.all.single { it.id == "safadzhay" }
    private fun nearest(lat: Double, lon: Double, accuracy: Double = 10.0, cities: List<ScheduleCity> = CityCatalog.all) =
        CitySelectionPolicy.nearest(lat, lon, accuracy, cities)

    @Test fun preciseFixMatchesMoscow() { assertEquals(moscow, nearest(55.75, 37.62)) }
    @Test fun approximateFixMatchesMoscow() { assertEquals(moscow, nearest(55.75, 37.62, 5000.0)) }
    @Test fun approximateFixMatchesVillage() { assertEquals(village, nearest(55.384, 46.116, 3000.0)) }
    @Test fun noTimetableCandidateCannotBeSelected() { assertNull(nearest(55.75, 37.62, cities = listOf(village))) }
    @Test fun noCandidatesPreservesLastCity() { assertNull(nearest(55.75, 37.62, cities = emptyList())) }
    @Test fun distantLocationDoesNotBorrowAnotherCityTimetable() { assertNull(nearest(52.37, 4.90)) }
    @Test fun ambiguousApproximateFixDoesNotSwitchCity() {
        val cities = listOf(ScheduleCity("a", "A", 0.0, -0.1), ScheduleCity("b", "B", 0.0, 0.1))
        assertNull(nearest(0.0, 0.0, 2000.0, cities))
    }
    @Test fun uncertaintyMustStayInsideMatchingRadius() { assertNull(nearest(55.75, 37.62, 60_000.0)) }
    @Test fun staleFutureOrMalformedFixIsRejected() {
        for (age in listOf(-1.0, 601.0, Double.NaN)) assertFalse(CitySelectionPolicy.validFix(55.75, 37.62, 100.0, age))
        assertFalse(CitySelectionPolicy.validFix(Double.NaN, 37.62, 100.0, 0.0))
        assertFalse(CitySelectionPolicy.validFix(91.0, 37.62, 100.0, 0.0))
        assertFalse(CitySelectionPolicy.validFix(55.75, 181.0, 100.0, 0.0))
        assertFalse(CitySelectionPolicy.validFix(55.75, 37.62, -1.0, 0.0))
        assertTrue(CitySelectionPolicy.validFix(55.75, 37.62, 5000.0, 600.0))
    }
    @Test fun distanceIsStableAtPolesAndAntimeridian() {
        val point = ScheduleCity("x", "X", 0.0, -179.9)
        assertTrue(CitySelectionPolicy.distance(0.0, 179.9, point) in 22_000.0..23_000.0)
        assertTrue(CitySelectionPolicy.distance(90.0, 180.0, point).isFinite())
        assertEquals(0.0, CitySelectionPolicy.distance(moscow.latitude, moscow.longitude, moscow), 0.001)
    }
    @Test fun refreshAndRetryAreBoundedAndClockChangesInvalidateFreshness() {
        assertTrue(CitySelectionPolicy.fresh(1000, 1001, CitySelectionPolicy.REFRESH_MS))
        assertFalse(CitySelectionPolicy.fresh(1000, 1000 + CitySelectionPolicy.REFRESH_MS, CitySelectionPolicy.REFRESH_MS))
        assertFalse(CitySelectionPolicy.fresh(1000, 999, CitySelectionPolicy.RETRY_MS))
        assertFalse(CitySelectionPolicy.fresh(0, 999, CitySelectionPolicy.REFRESH_MS))
    }
    @Test fun newInstallAllowsAutomaticSelection() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        assertTrue(settings.isAutomatic()); assertNull(settings.savedCity()); assertTrue(prefs.all.isEmpty())
    }

    @Test fun oldInstallPreservesManualCityButAllowsStartupLocation() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putBoolean("notifications_enabled", true)
            .putString("app_theme", "dark").commit()
        val settings = CitySelectionSettings(prefs)
        assertTrue(settings.isAutomatic()); assertEquals(moscow, settings.savedCity())
        assertEquals(moscow, settings.manualCity()); assertNull(settings.automaticCity())
        assertTrue(prefs.getBoolean("notifications_enabled", false)); assertEquals("dark", prefs.getString("app_theme", null))
    }

    @Test fun legacyModeMigratesOnceWithoutBlockingNewSession() {
        for (mode in listOf("manual", "MANUAL")) {
            val prefs = MemoryCityPreferences()
            prefs.edit().putString("city", moscow.name).putString("city_selection_mode", mode).commit()
            val settings = CitySelectionSettings(prefs)
            assertFalse(prefs.contains("city_selection_mode")); assertTrue(settings.isAutomatic())
            assertEquals(moscow, settings.manualCity()); assertEquals(moscow, settings.savedCity())
            assertTrue(settings.acceptAutomatic(village, 1000, 1200))
            assertEquals(moscow, CitySelectionSettings(prefs).manualCity())
        }
    }

    @Test fun oldAutomaticSuccessKeepsIndependentHistory() {
        for (mode in listOf("automatic", "AUTO")) {
            val prefs = MemoryCityPreferences()
            prefs.edit().putString("city", village.name).putString("city_selection_mode", mode)
                .putLong("city_auto_last_success", 1000).commit()
            val settings = CitySelectionSettings(prefs)
            assertEquals(village, settings.automaticCity()); assertNull(settings.manualCity())
            assertTrue(settings.hasResolvedCity()); assertFalse(prefs.contains("city_selection_mode"))
        }
    }

    @Test fun failedOldAutomaticAttemptDoesNotInventGpsHistory() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putString("city_selection_mode", "automatic").commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals(moscow, settings.savedCity()); assertNull(settings.automaticCity())
    }

    @Test fun staleLegacyCacheRestoresPreviouslySelectedManualCity() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", village.name).putString("city_selection_mode", "MANUAL")
            .putString("city_manual_id", moscow.id).putString("city_auto_resolved_id", village.id).commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals(moscow, settings.savedCity()); assertEquals(village, settings.automaticCity())
        assertTrue(settings.isAutomatic())
    }

    @Test fun manualAndAutomaticSelectionsNeverOverwriteEachOther() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        assertTrue(settings.acceptAutomatic(village, 1000, 1200)); settings.selectManual(moscow)
        assertEquals(moscow, settings.savedCity()); assertEquals(moscow, settings.manualCity())
        assertEquals(village, settings.automaticCity()); assertEquals(1000L, settings.lastSuccess())
        val next = CitySelectionSettings(prefs)
        assertTrue(next.acceptAutomatic(village, 3000, 3200)); assertEquals(moscow, next.manualCity())
        assertEquals(village, next.savedCity())
    }

    @Test fun manualOverrideRejectsEveryLateResultAndFailureWithoutAnyPreferenceWrite() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        val before = prefs.all.toMap()
        assertFalse(settings.acceptAutomatic(village, 3000, 3200)); settings.recordFailure(5000)
        assertEquals(before, prefs.all); assertFalse(settings.isAutomatic())
    }

    @Test fun restoredSessionKeepsManualOverrideAndFreshLaunchResetsIt() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.selectManual(moscow)
        val recreated = CitySelectionSettings(prefs, CitySelectionSession(settings.session.manualOverride, true))
        assertFalse(recreated.isAutomatic()); assertEquals(moscow, recreated.savedCity())
        assertFalse(recreated.acceptAutomatic(village, 1000, 1200))
        val fresh = CitySelectionSettings(prefs)
        assertTrue(fresh.isAutomatic()); assertEquals(moscow, fresh.savedCity())
        assertTrue(fresh.acceptAutomatic(village, 2000, 2200)); assertEquals(village, fresh.savedCity())
    }

    @Test fun selectingAlreadyActiveCityAlsoLocksTheSession() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.acceptAutomatic(moscow, 1000, 1200); settings.selectManual(moscow)
        assertFalse(settings.isAutomatic()); assertFalse(settings.acceptAutomatic(village, 2000, 2200))
    }

    @Test fun failureKeepsWorkingCityAndUnrelatedSettings() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putBoolean("notifications_enabled", true).putBoolean("show_tatar_names", false)
            .putString("app_theme", "light").commit()
        val settings = CitySelectionSettings(prefs); settings.acceptAutomatic(village, 1000, 1200)
        settings.recordFailure(5000)
        assertEquals(village, settings.savedCity()); assertEquals(1000L, settings.lastSuccess())
        assertTrue(prefs.getBoolean("notifications_enabled", false)); assertFalse(prefs.getBoolean("show_tatar_names", true))
        assertEquals("light", prefs.getString("app_theme", null))
    }

    @Test fun permissionMarkerSurvivesFreshLaunchWithoutSavingSessionOrCoordinates() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.markPermissionAsked(); settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        assertTrue(CitySelectionSettings(prefs).permissionAsked())
        assertEquals(setOf("city", "city_auto_resolved_id", "city_manual_id", "city_location_requested",
            "city_auto_last_success", "city_auto_last_attempt"), prefs.all.keys)
    }

    @Test fun unregisteredCitiesCannotEnterSelections() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        val unknown = ScheduleCity("unknown", "Unknown", 0.0, 0.0)
        assertFalse(settings.acceptAutomatic(unknown, 1000, 1200))
        assertThrows(IllegalArgumentException::class.java) { settings.selectManual(unknown) }
        assertTrue(settings.isAutomatic())
    }

    @Test fun jitterNearBoundaryDoesNotFlapButConfidentMovementCanSwitch() {
        val left = ScheduleCity("left", "Left", 0.0, -0.1)
        val right = ScheduleCity("right", "Right", 0.0, 0.1)
        val cities = listOf(left, right)
        repeat(10) {
            assertNull(CitySelectionPolicy.nearest(0.0, 0.001, 20.0, cities, left))
            assertNull(CitySelectionPolicy.nearest(0.0, -0.001, 20.0, cities, right))
        }
        assertEquals(right, CitySelectionPolicy.nearest(0.0, 0.1, 3000.0, cities, left))
        assertEquals(moscow, CitySelectionPolicy.nearest(moscow.latitude, moscow.longitude, 5000.0, CityCatalog.all, village))
    }
}

/** In-memory Android SharedPreferences interface for exercising the real settings class on a JVM. */
private class MemoryCityPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>(); private var clearing = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearing = true }
        override fun commit(): Boolean {
            if (clearing) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; return true
        }
        override fun apply() { commit() }
    }
}
