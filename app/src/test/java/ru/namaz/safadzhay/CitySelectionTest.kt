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
    @Test fun newInstallUsesAutomaticWithoutInventingEitherCity() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        assertTrue(settings.isAutomatic()); assertNull(settings.savedCity())
        assertNull(settings.automaticCity()); assertNull(settings.manualCity())
        assertFalse(settings.hasResolvedCity()); assertTrue(prefs.all.isEmpty())
    }

    @Test fun oldInstallWithoutModePreservesItsExistingManualCity() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putBoolean("notifications_enabled", true)
            .putBoolean("show_tatar_names", false).putString("app_theme", "dark").commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals(CitySelectionMode.MANUAL, settings.mode())
        assertEquals(moscow, settings.manualCity()); assertEquals(moscow, settings.savedCity())
        assertNull(settings.automaticCity()); assertFalse(settings.hasResolvedCity())
        assertTrue(prefs.getBoolean("notifications_enabled", false))
        assertFalse(prefs.getBoolean("show_tatar_names", true)); assertEquals("dark", prefs.getString("app_theme", null))
    }

    @Test fun legacyManualModeMigratesToItsOwnCityKey() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putString("city_selection_mode", "manual").commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals("MANUAL", prefs.getString("city_selection_mode", null))
        assertEquals(moscow, settings.manualCity()); assertEquals(moscow, settings.savedCity())
        assertNull(settings.automaticCity())
    }

    @Test fun legacyAutomaticSuccessMigratesWithoutInventingAManualCity() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", village.name).putString("city_selection_mode", "automatic")
            .putLong("city_auto_last_success", 1000).commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals("AUTO", prefs.getString("city_selection_mode", null))
        assertEquals(village, settings.automaticCity()); assertEquals(village, settings.savedCity())
        assertNull(settings.manualCity()); assertTrue(settings.hasResolvedCity())
        assertEquals(1000L, settings.lastSuccess())
    }

    @Test fun failedLegacyAutomaticAttemptCannotBeClaimedAsAGpsCity() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putString("city_selection_mode", "automatic")
            .putLong("city_auto_last_attempt", 1000).commit()
        val settings = CitySelectionSettings(prefs)
        assertTrue(settings.isAutomatic()); assertEquals(moscow, settings.savedCity())
        assertNull(settings.automaticCity()); assertFalse(settings.hasResolvedCity())
    }

    @Test fun legacyManualCityCannotMasqueradeAsUnidentifiedOldGpsResult() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city", moscow.name).putString("city_selection_mode", "manual")
            .putLong("city_auto_last_success", 1000).commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals(moscow, settings.manualCity()); assertNull(settings.automaticCity())
        settings.selectMode(CitySelectionMode.AUTOMATIC)
        assertEquals(moscow, settings.savedCity()); assertFalse(settings.hasResolvedCity())
    }

    @Test fun unknownModeUsesAutomaticAndOnlyARegisteredGpsCity() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city_selection_mode", "future-mode").putString("city", village.name).commit()
        val settings = CitySelectionSettings(prefs)
        assertTrue(settings.isAutomatic())
        assertTrue(settings.acceptAutomatic(moscow, 1000, 1200)); assertEquals(moscow, settings.savedCity())
        assertEquals("AUTO", prefs.getString("city_selection_mode", null))
    }

    @Test fun autoToManualKeepsAutomaticCityAndFreshness() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        assertEquals(moscow, settings.savedCity()); assertEquals(moscow, settings.manualCity())
        assertEquals(village, settings.automaticCity()); assertTrue(settings.hasResolvedCity())
        assertEquals(1000L, settings.lastSuccess()); assertEquals(1200L, settings.lastAttempt())
    }

    @Test fun manualToAutoRestoresAutomaticCityAndKeepsManualCity() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        assertEquals(village, settings.selectMode(CitySelectionMode.AUTOMATIC))
        assertTrue(settings.isAutomatic()); assertEquals(village, settings.savedCity())
        assertEquals(moscow, settings.manualCity())
    }

    @Test fun completeModeRoundTripRestoresBothIndependentCities() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200)
        settings.selectMode(CitySelectionMode.MANUAL); settings.selectManual(moscow)
        assertEquals(moscow.name, prefs.getString("city", null))
        settings.selectMode(CitySelectionMode.AUTOMATIC)
        assertEquals(village.name, prefs.getString("city", null)); assertEquals(village, settings.savedCity())
        settings.selectMode(CitySelectionMode.MANUAL)
        assertEquals(moscow.name, prefs.getString("city", null)); assertEquals(moscow, settings.savedCity())
        assertEquals(village, settings.automaticCity()); assertEquals(moscow, settings.manualCity())
        assertEquals(1000L, settings.lastSuccess())
    }

    @Test fun restartPreservesModeAndBothCitiesInEitherMode() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        for (mode in CitySelectionMode.entries) {
            settings.selectMode(mode)
            val restored = CitySelectionSettings(prefs)
            assertEquals(mode, restored.mode())
            assertEquals(village, restored.automaticCity()); assertEquals(moscow, restored.manualCity())
            assertEquals(if (mode == CitySelectionMode.AUTOMATIC) village else moscow, restored.savedCity())
            assertEquals(1000L, restored.lastSuccess())
        }
    }

    @Test fun newAutomaticResultOnlyUpdatesAutomaticSelection() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.selectManual(moscow); settings.selectMode(CitySelectionMode.AUTOMATIC)
        settings.acceptAutomatic(village, 1000, 1200)
        assertEquals(village, settings.automaticCity()); assertEquals(moscow, settings.manualCity())
        settings.acceptAutomatic(moscow, 3000, 3200)
        assertEquals(moscow, settings.automaticCity()); assertEquals(moscow, settings.manualCity())
    }

    @Test fun manualSelectionChangesDoNotTouchAutomaticMetadata() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.acceptAutomatic(village, 1000, 1200)
        settings.selectManual(moscow); settings.selectManual(village)
        assertEquals(village, settings.automaticCity()); assertEquals(village, settings.manualCity())
        assertEquals(1000L, settings.lastSuccess()); assertEquals(1200L, settings.lastAttempt())
    }

    @Test fun firstManualChoiceDoesNotBorrowAutomaticCity() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.acceptAutomatic(moscow, 1000, 1200)
        assertEquals(village, settings.selectMode(CitySelectionMode.MANUAL))
        assertEquals(village, settings.manualCity()); assertEquals(moscow, settings.automaticCity())
    }

    @Test fun autoWithoutAnEarlierFixRetainsWorkingCityWithoutInventingGpsHistory() {
        val settings = CitySelectionSettings(MemoryCityPreferences())
        settings.selectManual(moscow); settings.selectMode(CitySelectionMode.AUTOMATIC)
        assertEquals(moscow, settings.savedCity()); assertNull(settings.automaticCity())
        assertFalse(settings.hasResolvedCity()); assertEquals(moscow, settings.manualCity())
        settings.recordFailure(2000)
        assertEquals(moscow, settings.manualCity()); assertEquals(moscow, settings.savedCity())
    }

    @Test fun locationFailurePreservesBothCitiesAndUnrelatedSettings() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putBoolean("notifications_enabled", true).putBoolean("show_tatar_names", false)
            .putString("app_theme", "light").commit()
        val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        settings.selectMode(CitySelectionMode.AUTOMATIC); settings.recordFailure(5000)
        assertEquals(village, settings.savedCity()); assertEquals(village, settings.automaticCity())
        assertEquals(moscow, settings.manualCity()); assertEquals(1000L, settings.lastSuccess())
        assertTrue(prefs.getBoolean("notifications_enabled", false)); assertFalse(prefs.getBoolean("show_tatar_names", true))
        assertEquals("light", prefs.getString("app_theme", null))
    }

    @Test fun automaticResultCannotOverwriteManualModeOrEitherRememberedCity() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        val before = prefs.all.toMap()
        assertFalse(settings.acceptAutomatic(moscow, 3000, 3200))
        assertEquals(before, prefs.all); assertEquals(moscow, settings.savedCity())
    }

    @Test fun lateAutomaticFailureInManualModeChangesNothing() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        val before = prefs.all.toMap(); settings.recordFailure(5000)
        assertEquals(before, prefs.all)
    }

    @Test fun savedIdsRestoreActiveCityWhenCompatibilityCacheIsStale() {
        val prefs = MemoryCityPreferences()
        prefs.edit().putString("city_selection_mode", "MANUAL").putString("city", village.name)
            .putString("city_auto_resolved_id", village.id).putString("city_manual_id", moscow.id)
            .putLong("city_auto_last_success", 1000).commit()
        val settings = CitySelectionSettings(prefs)
        assertEquals(moscow, settings.savedCity()); assertEquals(moscow.name, prefs.getString("city", null))
        assertEquals(village, settings.automaticCity()); assertEquals(1000L, settings.lastSuccess())
    }

    @Test fun selectingTheSameAutomaticModeIsNotAManualRefresh() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(moscow, 1000, 1200)
        val before = prefs.all.toMap(); settings.selectMode(CitySelectionMode.AUTOMATIC)
        assertEquals(before, prefs.all)
    }

    @Test fun unregisteredCityCannotEnterEitherSelection() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        val city = ScheduleCity("unknown", "Unknown", 55.75, 37.62)
        assertFalse(settings.acceptAutomatic(city, 1000, 1200))
        assertThrows(IllegalArgumentException::class.java) { settings.selectManual(city) }
        assertNull(settings.savedCity()); assertNull(settings.automaticCity()); assertNull(settings.manualCity())
    }

    @Test fun independentChoicesDoNotPersistCoordinates() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.acceptAutomatic(village, 1000, 1200); settings.selectManual(moscow)
        assertEquals(setOf("city", "city_selection_mode", "city_auto_resolved_id", "city_manual_id",
            "city_auto_last_success", "city_auto_last_attempt"), prefs.all.keys)
    }

    @Test fun permissionPromptMarkerSurvivesModeChangesAndRestart() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        assertFalse(settings.permissionAsked()); settings.markPermissionAsked()
        settings.selectManual(moscow); settings.selectMode(CitySelectionMode.AUTOMATIC)
        assertTrue(CitySelectionSettings(prefs).permissionAsked())
    }

    @Test fun automaticStatusNeverOffersAManualLocationRefresh() {
        for (state in CityLocationState.entries) for (resolved in listOf(true, false)) {
            val text = CitySelectionStatus.description(resolved, state)
            assertFalse(text, text.contains("Обновить местоположение"))
            if (!resolved) assertFalse(text, text == "Определено автоматически")
        }
        assertEquals("Определено автоматически", CitySelectionStatus.description(true, CityLocationState.READY))
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
