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
    @Test fun existingInstallDefaultsToManualWithoutWritingPreferences() {
        val prefs = MemoryCityPreferences(); prefs.edit().putString("city", "Москва").putBoolean("notifications_enabled", true).commit()
        val before = prefs.all.toMap(); val settings = CitySelectionSettings(prefs)
        assertEquals(CitySelectionMode.MANUAL, settings.mode()); assertEquals(before, prefs.all)
    }
    @Test fun unknownModeUsesManual() { assertEquals(CitySelectionMode.MANUAL, CitySelectionMode.fromKey("future-mode")) }
    @Test fun modeAndAutomaticCitySurviveRestart() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.setMode(CitySelectionMode.AUTOMATIC)
        assertTrue(settings.acceptAutomatic(moscow, 1000, 1200))
        val restarted = CitySelectionSettings(prefs)
        assertEquals(CitySelectionMode.AUTOMATIC, restarted.mode()); assertEquals(1000L, restarted.lastSuccess())
        assertEquals("Москва", prefs.getString("city", null))
    }
    @Test fun manualModeRejectsLateAutomaticResult() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.selectManual(village)
        assertFalse(settings.acceptAutomatic(moscow, 1000, 1200)); assertEquals(village.name, prefs.getString("city", null))
    }
    @Test fun failureDoesNotOverwriteCityOrOtherSettings() {
        val prefs = MemoryCityPreferences(); prefs.edit().putString("city", village.name).putBoolean("notifications_enabled", true)
            .putBoolean("show_tatar_names", false).putString("app_theme", "light").commit()
        val settings = CitySelectionSettings(prefs); settings.setMode(CitySelectionMode.AUTOMATIC); settings.recordFailure(1200)
        assertEquals(village.name, prefs.getString("city", null)); assertTrue(prefs.getBoolean("notifications_enabled", false))
        assertFalse(prefs.getBoolean("show_tatar_names", true)); assertEquals("light", prefs.getString("app_theme", null))
    }
    @Test fun reEnablingAutomaticAfterManualSelectionDoesNotReuseOldSuccess() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs)
        settings.setMode(CitySelectionMode.AUTOMATIC); settings.acceptAutomatic(moscow, 1000, 1200)
        settings.selectManual(village); settings.setMode(CitySelectionMode.AUTOMATIC)
        assertEquals(0L, settings.lastSuccess()); assertEquals(0L, settings.lastAttempt()); assertEquals(village.name, prefs.getString("city", null))
    }
    @Test fun unregisteredCityCannotEnterPreferences() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs); settings.setMode(CitySelectionMode.AUTOMATIC)
        assertFalse(settings.acceptAutomatic(ScheduleCity("unknown", "Unknown", 55.75, 37.62), 1000, 1200))
        assertNull(prefs.getString("city", null))
    }
    @Test fun successfulSelectionDoesNotPersistCoordinates() {
        val prefs = MemoryCityPreferences(); val settings = CitySelectionSettings(prefs); settings.setMode(CitySelectionMode.AUTOMATIC)
        settings.acceptAutomatic(moscow, 1000, 1200)
        assertEquals(setOf(CitySelectionSettings.MODE_KEY, "city", "city_auto_last_success", "city_auto_last_attempt"), prefs.all.keys)
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
