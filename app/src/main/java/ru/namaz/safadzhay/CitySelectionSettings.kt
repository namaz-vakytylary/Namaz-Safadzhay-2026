package ru.namaz.safadzhay

import android.content.Context
import android.content.SharedPreferences

/** Independent selections in the existing settings file. "city" is only the
 * effective-city compatibility cache used by the app and prayer notifications.
 * Location errors and mode changes never erase either remembered selection.
 */
internal class CitySelectionSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    init {
        val legacyMode = prefs.getString(MODE_KEY, null)
        val legacyCity = effectiveCityCache()
        val edit = prefs.edit()
        var changed = false
        // Earlier installations stored only one active city. Preserve the
        // identifiable selection, without claiming a manual city was GPS-derived.
        if (manualCity() == null && legacyCity != null &&
            (legacyMode == "manual" || legacyMode == CitySelectionMode.MANUAL.key ||
                (legacyMode == null && automaticCity() == null))) {
            edit.putString(MANUAL_CITY_KEY, legacyCity.id).putString(MODE_KEY, CitySelectionMode.MANUAL.key)
            changed = true
        }
        if (automaticCity() == null && legacyMode == "automatic" && lastSuccess() > 0L && legacyCity != null) {
            edit.putString(AUTO_CITY_KEY, legacyCity.id)
            changed = true
        }
        if (legacyMode == "automatic" || legacyMode == "manual") {
            edit.putString(MODE_KEY, CitySelectionMode.fromKey(legacyMode).key)
            changed = true
        }
        if (changed) edit.apply()
        // Restore the selected mode if the compatibility cache was stale.
        savedCity()?.let { if (it != effectiveCityCache()) prefs.edit().putString("city", it.name).apply() }
    }

    private fun cityById(key: String) = CityCatalog.all.firstOrNull { it.id == prefs.getString(key, null) }
    private fun effectiveCityCache() = CityCatalog.all.firstOrNull { it.name == prefs.getString("city", null) }
    fun mode() = CitySelectionMode.fromKey(prefs.getString(MODE_KEY, null))
    fun isAutomatic() = mode() == CitySelectionMode.AUTOMATIC
    fun automaticCity(): ScheduleCity? = cityById(AUTO_CITY_KEY)
    fun manualCity(): ScheduleCity? = cityById(MANUAL_CITY_KEY)
    fun savedCity(): ScheduleCity? = (if (isAutomatic()) automaticCity() else manualCity()) ?: effectiveCityCache()
    fun hasResolvedCity() = automaticCity() != null && lastSuccess() > 0L
    fun lastSuccess() = prefs.getLong(SUCCESS_KEY, 0L)
    fun lastAttempt() = prefs.getLong(ATTEMPT_KEY, 0L)
    fun permissionAsked() = prefs.getBoolean(PERMISSION_KEY, false)
    fun markPermissionAsked() { prefs.edit().putBoolean(PERMISSION_KEY, true).apply() }
    fun recordFailure(now: Long) {
        if (isAutomatic()) prefs.edit().putLong(ATTEMPT_KEY, now).apply()
    }

    fun selectMode(mode: CitySelectionMode): ScheduleCity? {
        val city = if (mode == CitySelectionMode.AUTOMATIC) {
            automaticCity() ?: effectiveCityCache()
        } else manualCity() ?: CityCatalog.all.first()
        val edit = prefs.edit().putString(MODE_KEY, mode.key)
        if (mode == CitySelectionMode.MANUAL && manualCity() == null && city != null) {
            edit.putString(MANUAL_CITY_KEY, city.id)
        }
        if (city != null) edit.putString("city", city.name)
        edit.apply()
        return city
    }

    fun selectManual(city: ScheduleCity) {
        require(city in CityCatalog.all)
        prefs.edit().putString(MODE_KEY, CitySelectionMode.MANUAL.key)
            .putString(MANUAL_CITY_KEY, city.id).putString("city", city.name).apply()
    }

    fun acceptAutomatic(city: ScheduleCity, fixTime: Long, now: Long): Boolean {
        if (!isAutomatic() || city !in CityCatalog.all) return false
        prefs.edit().putString(MODE_KEY, CitySelectionMode.AUTOMATIC.key)
            .putString(AUTO_CITY_KEY, city.id).putString("city", city.name)
            .putLong(SUCCESS_KEY, fixTime).putLong(ATTEMPT_KEY, now).apply()
        return true
    }

    companion object {
        private const val MODE_KEY = "city_selection_mode"
        private const val AUTO_CITY_KEY = "city_auto_resolved_id"
        private const val MANUAL_CITY_KEY = "city_manual_id"
        private const val SUCCESS_KEY = "city_auto_last_success"
        private const val ATTEMPT_KEY = "city_auto_last_attempt"
        private const val PERMISSION_KEY = "city_location_requested"
    }
}
