package ru.namaz.safadzhay

import android.content.Context
import android.content.SharedPreferences

/** Additive keys in the existing settings file; never clears or migrates old preferences.
 * Coordinates are deliberately not persisted, logged, or transmitted.
 */
internal class CitySelectionSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
    fun mode() = CitySelectionMode.fromKey(prefs.getString(MODE_KEY, null))
    fun setMode(mode: CitySelectionMode) {
        val edit = prefs.edit().putString(MODE_KEY, mode.key)
        if (mode == CitySelectionMode.AUTOMATIC && mode() != mode) {
            edit.remove(SUCCESS_KEY).remove(ATTEMPT_KEY)
        }
        edit.apply()
    }
    fun lastSuccess() = prefs.getLong(SUCCESS_KEY, 0L)
    fun lastAttempt() = prefs.getLong(ATTEMPT_KEY, 0L)
    fun permissionAsked() = prefs.getBoolean(PERMISSION_KEY, false)
    fun markPermissionAsked() { prefs.edit().putBoolean(PERMISSION_KEY, true).apply() }
    fun recordFailure(now: Long) { prefs.edit().putLong(ATTEMPT_KEY, now).apply() }
    fun acceptAutomatic(city: ScheduleCity, fixTime: Long, now: Long): Boolean {
        if (mode() != CitySelectionMode.AUTOMATIC || city !in CityCatalog.all) return false
        prefs.edit().putString("city", city.name).putLong(SUCCESS_KEY, fixTime)
            .putLong(ATTEMPT_KEY, now).apply()
        return true
    }
    fun selectManual(city: ScheduleCity) {
        require(city in CityCatalog.all)
        prefs.edit().putString(MODE_KEY, CitySelectionMode.MANUAL.key).putString("city", city.name)
            .remove(SUCCESS_KEY).remove(ATTEMPT_KEY).apply()
    }
    companion object {
        const val MODE_KEY = "city_selection_mode"
        private const val SUCCESS_KEY = "city_auto_last_success"
        private const val ATTEMPT_KEY = "city_auto_last_attempt"
        private const val PERMISSION_KEY = "city_location_requested"
    }
}
