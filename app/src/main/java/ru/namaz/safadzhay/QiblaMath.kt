package ru.namaz.safadzhay

import kotlin.math.*

/** Great-circle initial direction relative to true north. No city or Android dependency. */
internal object QiblaMath {
    const val KAABA_LATITUDE = 21.422487
    const val KAABA_LONGITUDE = 39.826206
    const val MAX_FIX_AGE_SECONDS = 120.0
    fun bearing(latitude: Double, longitude: Double): Float {
        val p = Math.toRadians(latitude)
        val k = Math.toRadians(KAABA_LATITUDE)
        val d = Math.toRadians(KAABA_LONGITUDE - longitude)
        return ((Math.toDegrees(atan2(sin(d) * cos(k), cos(p) * sin(k) - sin(p) * cos(k) * cos(d))) + 360) % 360).toFloat()
    }
    fun distance(latitude: Double, longitude: Double): Double {
        val p = Math.toRadians(latitude); val k = Math.toRadians(KAABA_LATITUDE)
        val d = Math.toRadians(KAABA_LONGITUDE - longitude)
        val a = (sin((k-p)/2).pow(2) + cos(p)*cos(k)*sin(d/2).pow(2)).coerceIn(0.0, 1.0)
        return 6371000 * 2 * atan2(sqrt(a), sqrt(1-a))
    }
    fun usableFix(latitude: Double, longitude: Double, accuracy: Double, ageSeconds: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracy.isFinite() && accuracy in 0.0..5000.0 && ageSeconds.isFinite() && ageSeconds in 0.0..MAX_FIX_AGE_SECONDS

    // Near the destination or its antipode, small coordinate errors can change the direction greatly.
    fun directionResolvable(latitude: Double, longitude: Double, accuracy: Double): Boolean {
        val d = distance(latitude, longitude)
        val margin = maxOf(20.0, accuracy * 10)
        return d > margin && PI * 6371000 - d > margin
    }
    fun shortestAngle(angle: Float) = ((angle + 540f) % 360f) - 180f
}
