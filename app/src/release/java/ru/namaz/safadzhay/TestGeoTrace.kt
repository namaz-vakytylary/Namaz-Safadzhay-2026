package ru.namaz.safadzhay

import android.os.Bundle

/** Production records no TEST location metadata. */
internal object TestGeoTrace {
    fun accepted(session: CitySelectionSession, cityId: String, provider: String?, time: Long) = Unit
    fun save(session: CitySelectionSession, state: Bundle) = Unit
    fun restore(session: CitySelectionSession, state: Bundle?) = Unit
}
