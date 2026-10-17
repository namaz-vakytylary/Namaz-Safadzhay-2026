package ru.namaz.safadzhay

import android.os.Bundle
import java.util.WeakHashMap

/** Only the last accepted result of an active session. No coordinates or movement history. */
internal object TestGeoTrace {
    data class Fix(val cityId: String, val provider: String?, val timeMillis: Long)
    private val values = WeakHashMap<CitySelectionSession, Fix>()
    @Synchronized fun accepted(session: CitySelectionSession, cityId: String, provider: String?, time: Long) {
        values[session] = Fix(cityId, provider?.takeIf { it == "network" || it == "gps" }, time)
    }
    @Synchronized fun snapshot(session: CitySelectionSession): Fix? = values[session]
    @Synchronized fun save(session: CitySelectionSession, state: Bundle) {
        values[session]?.let {
            state.putString("test_geo_city", it.cityId); state.putString("test_geo_provider", it.provider)
            state.putLong("test_geo_time", it.timeMillis)
        }
    }
    fun restore(session: CitySelectionSession, state: Bundle?) {
        val city = state?.getString("test_geo_city")?.takeIf { id -> CityCatalog.all.any { it.id == id } } ?: return
        val time = state.getLong("test_geo_time", 0).takeIf { it > 0 } ?: return
        accepted(session, city, state.getString("test_geo_provider"), time)
    }
}
