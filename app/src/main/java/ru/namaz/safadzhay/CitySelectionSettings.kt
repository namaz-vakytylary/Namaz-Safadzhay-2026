package ru.namaz.safadzhay

import android.content.Context
import android.content.SharedPreferences

/** Remembered manual/automatic cities stay independent. "city" is the last working
 * selection used by the app and notifications, including while location is unavailable.
 * The old permanent mode is migrated once; only session state now gates geolocation.
 */
internal class CitySelectionSettings(
    private val prefs: SharedPreferences,
    val session: CitySelectionSession = CitySelectionSession()
) {
    constructor(context: Context, session: CitySelectionSession = CitySelectionSession()) :
        this(context.getSharedPreferences("settings", Context.MODE_PRIVATE), session)

    init {
        val legacyMode = prefs.getString(MODE_KEY, null)
        val legacyCity = effectiveCityCache()
        val edit = prefs.edit()
        var changed = false
        val wasManual = legacyMode == "manual" || legacyMode == "MANUAL"
        val wasAutomatic = legacyMode == "automatic" || legacyMode == "AUTO"
        if (manualCity() == null && legacyCity != null &&
            (wasManual || (legacyMode == null && automaticCity() == null))) {
            edit.putString(MANUAL_CITY_KEY, legacyCity.id)
            changed = true
        }
        if (automaticCity() == null && wasAutomatic && lastSuccess() > 0L && legacyCity != null) {
            edit.putString(AUTO_CITY_KEY, legacyCity.id)
            changed = true
        }
        // Repair an old mode's stale compatibility cache before discarding the mode.
        val active = if (wasManual) manualCity() ?: legacyCity
            else if (wasAutomatic) automaticCity() ?: legacyCity else legacyCity
        if (active != null && active != legacyCity) { edit.putString("city", active.name); changed = true }
        if (prefs.contains(MODE_KEY)) { edit.remove(MODE_KEY); changed = true }
        if (changed) edit.apply()
    }

    private fun cityById(key: String) = CityCatalog.all.firstOrNull { it.id == prefs.getString(key, null) }
    private fun effectiveCityCache() = CityCatalog.all.firstOrNull { it.name == prefs.getString("city", null) }
    fun isAutomatic() = !session.manualOverride
    fun automaticCity(): ScheduleCity? = cityById(AUTO_CITY_KEY)
    fun manualCity(): ScheduleCity? = cityById(MANUAL_CITY_KEY)
    fun savedCity(): ScheduleCity? = effectiveCityCache() ?: manualCity() ?: automaticCity()
    fun hasResolvedCity() = automaticCity() != null && lastSuccess() > 0L
    fun lastSuccess() = prefs.getLong(SUCCESS_KEY, 0L)
    fun lastAttempt() = prefs.getLong(ATTEMPT_KEY, 0L)
    fun permissionAsked() = prefs.getBoolean(PERMISSION_KEY, false)
    fun markPermissionAsked() { prefs.edit().putBoolean(PERMISSION_KEY, true).apply() }
    fun recordFailure(now: Long) {
        if (isAutomatic()) prefs.edit().putLong(ATTEMPT_KEY, now).apply()
    }

    fun selectManual(city: ScheduleCity) {
        require(city in CityCatalog.all)
        // Set the session guard before any UI, preference or provider operation.
        session.selectManual()
        prefs.edit().putString(MANUAL_CITY_KEY, city.id).putString("city", city.name).apply()
    }

    fun acceptAutomatic(city: ScheduleCity, fixTime: Long, now: Long): Boolean {
        if (!isAutomatic() || city !in CityCatalog.all) return false
        prefs.edit().putString(AUTO_CITY_KEY, city.id).putString("city", city.name)
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
