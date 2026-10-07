package ru.namaz.safadzhay

import kotlin.math.*

internal enum class CitySelectionMode(val key: String) {
    AUTOMATIC("AUTO"), MANUAL("MANUAL");

    companion object {
        fun fromKey(key: String?) = if (key == MANUAL.key || key == "manual") MANUAL else AUTOMATIC
    }
}

internal enum class CityLocationState {
    IDLE, SEARCHING, READY, PERMISSION_REQUIRED, PERMISSION_BLOCKED, LOCATION_OFF, UNAVAILABLE, UNSUPPORTED
}

internal object CitySelectionStatus {
    fun description(resolved: Boolean, state: CityLocationState): String {
        val retained = if (resolved) "Сохранён последний определённый город." else "Местоположение ещё не определено."
        return when (state) {
            CityLocationState.READY -> if (resolved) "Определено автоматически" else retained
            CityLocationState.SEARCHING -> "$retained Определяем местоположение…"
            CityLocationState.PERMISSION_REQUIRED -> "$retained Нет разрешения на местоположение."
            CityLocationState.PERMISSION_BLOCKED -> "$retained Разрешите местоположение в настройках Android."
            CityLocationState.LOCATION_OFF -> "$retained Местоположение устройства выключено."
            CityLocationState.UNAVAILABLE -> "$retained Координаты временно недоступны. Повторим попытку автоматически."
            CityLocationState.UNSUPPORTED -> "$retained Рядом нет подходящего доступного расписания."
            CityLocationState.IDLE -> retained
        }
    }
}

internal data class ScheduleCity(val id: String, val name: String, val latitude: Double, val longitude: Double)

/** Metadata for automatic matching against the existing schedule IDs.
 * It does not add cities to ScheduleRepository's validation allowlist.
 */
internal object CityCatalog {
    val all = listOf(
        // Krasnaya Gorka / Safadzhay, Pilninsky district: 55°23′02″N 46°06′57″E.
        // https://ru.wikipedia.org/wiki/Красная_Горка_(Красногорский_сельсовет)
        ScheduleCity("safadzhay", "Сафаджай", 55.383889, 46.115833),
        ScheduleCity("moscow", "Москва", 55.7558, 37.6173)
    )
}

internal object CitySelectionPolicy {
    const val REFRESH_MS = 30 * 60 * 1000L
    const val RETRY_MS = 5 * 60 * 1000L
    const val MAX_FIX_AGE_SECONDS = 10 * 60.0
    const val MAX_ACCURACY_METERS = 25_000.0
    // A bounded matching policy, not a claim that a timetable covers distant places.
    const val MAX_DISTANCE_METERS = 50_000.0

    fun validFix(latitude: Double, longitude: Double, accuracy: Double, ageSeconds: Double) =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracy.isFinite() && accuracy in 0.0..MAX_ACCURACY_METERS &&
            ageSeconds.isFinite() && ageSeconds in 0.0..MAX_FIX_AGE_SECONDS

    fun distance(latitude: Double, longitude: Double, city: ScheduleCity): Double {
        val p = Math.toRadians(latitude); val q = Math.toRadians(city.latitude)
        val d = Math.toRadians(city.longitude - longitude)
        val a = (sin((q-p)/2).pow(2) + cos(p)*cos(q)*sin(d/2).pow(2)).coerceIn(0.0, 1.0)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    fun nearest(latitude: Double, longitude: Double, accuracy: Double, candidates: List<ScheduleCity>): ScheduleCity? {
        if (!validFix(latitude, longitude, accuracy, 0.0)) return null
        val ranked = candidates.map { it to distance(latitude, longitude, it) }.sortedBy { it.second }
        val first = ranked.firstOrNull() ?: return null
        if (first.second + accuracy > MAX_DISTANCE_METERS) return null
        // Do not switch cities if the uncertainty overlaps their nearest-city boundary.
        if (ranked.size > 1 && ranked[1].second - first.second <= 2 * accuracy) return null
        return first.first
    }

    fun fresh(timestamp: Long, now: Long, interval: Long) = timestamp > 0 && now >= timestamp && now - timestamp < interval
}
