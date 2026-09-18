package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test

class QiblaMathTest {
    @Test fun sameMeridianPointsNorthOrSouth() {
        assertEquals(0f, QiblaMath.bearing(0.0, QiblaMath.KAABA_LONGITUDE), .001f)
        assertEquals(180f, QiblaMath.bearing(50.0, QiblaMath.KAABA_LONGITUDE), .001f)
    }
    @Test fun bearingsAcrossHemispheres() {
        // Reference angles independently obtained by projecting a 3D destination unit vector
        // onto local east/north tangent vectors (spherical Earth).
        assertEquals(58.481706f, QiblaMath.bearing(40.7128, -74.006), .001f)
        assertEquals(292.998666f, QiblaMath.bearing(35.6762, 139.6503), .001f)
        assertEquals(277.499579f, QiblaMath.bearing(-33.8688, 151.2093), .001f)
        assertEquals(176.356246f, QiblaMath.bearing(55.7558, 37.6173), .001f)
    }
    @Test fun dateLineHasNoDirectionJump() {
        val west = QiblaMath.bearing(0.0, 179.9)
        val east = QiblaMath.bearing(0.0, -179.9)
        assertEquals(301.438479f, west, .001f)
        assertTrue(kotlin.math.abs(QiblaMath.shortestAngle(east-west)) < .2f)
    }
    @Test fun staleFutureAndInvalidCoordinatesAreRejected() {
        assertTrue(QiblaMath.usableFix(55.0, 37.0, 10.0, 5.0))
        assertFalse(QiblaMath.usableFix(55.0, 37.0, 10.0, 121.0))
        assertFalse(QiblaMath.usableFix(55.0, 37.0, 10.0, -1.0))
        assertFalse(QiblaMath.usableFix(91.0, 37.0, 10.0, 0.0))
        assertFalse(QiblaMath.usableFix(55.0, Double.NaN, 10.0, 0.0))
        assertFalse(QiblaMath.usableFix(55.0, 37.0, Double.NaN, 0.0))
    }
    @Test fun excessiveLocationUncertaintyIsRejected() {
        assertTrue(QiblaMath.usableFix(55.0, 37.0, 1000.0, 0.0))
        assertFalse(QiblaMath.usableFix(55.0, 37.0, 5001.0, 0.0))
        assertFalse(QiblaMath.usableFix(55.0, 37.0, -1.0, 0.0))
        assertTrue(QiblaMath.directionResolvable(55.0, 37.0, 1000.0))
    }
    @Test fun nearbyAndAntipodalDirectionIsNotPresentedAsCertain() {
        assertFalse(QiblaMath.directionResolvable(QiblaMath.KAABA_LATITUDE, QiblaMath.KAABA_LONGITUDE, 10.0))
        assertFalse(QiblaMath.directionResolvable(QiblaMath.KAABA_LATITUDE + .001, QiblaMath.KAABA_LONGITUDE, 100.0))
        assertFalse(QiblaMath.directionResolvable(-QiblaMath.KAABA_LATITUDE, QiblaMath.KAABA_LONGITUDE-180, 10.0))
    }
    @Test fun northWrapUsesTheShortestRotation() {
        assertEquals(2f, QiblaMath.shortestAngle(1f-359f), .001f)
        assertEquals(-2f, QiblaMath.shortestAngle(359f-1f), .001f)
    }
}
