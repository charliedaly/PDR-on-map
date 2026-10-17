package com.example.pdrandroutetest.util

import com.example.pdrandroutetest.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateUtilsTest {

    @Test
    fun `latLonToMetric converts Irish location to valid metric coordinates`() {
        val lat = 53.349805
        val lon = -6.260310

        val location = CoordinateUtils.latLonToMetric(lat, lon)

        assertTrue("X coordinate must be positive", location.x > 0.0)
        assertTrue("Y coordinate must be positive", location.y > 0.0)
    }

    @Test
    fun `getDistance calculates Euclidean distance in meters`() {
        val loc1 = Location(100.0, 200.0)
        val loc2 = Location(103.0, 204.0) // 3-4-5 triangle

        val distance = CoordinateUtils.getDistance(loc1, loc2)

        assertEquals(5.0, distance, 1e-6)
    }

    @Test
    fun `getBearing calculates clockwise grid bearing in degrees`() {
        val origin = Location(0.0, 0.0)

        val northLoc = Location(0.0, 100.0)
        val eastLoc = Location(100.0, 0.0)
        val southLoc = Location(0.0, -100.0)
        val westLoc = Location(-100.0, 0.0)

        assertEquals(0.0, CoordinateUtils.getBearing(origin, northLoc), 1e-3)
        assertEquals(90.0, CoordinateUtils.getBearing(origin, eastLoc), 1e-3)
        assertEquals(180.0, CoordinateUtils.getBearing(origin, southLoc), 1e-3)
        assertEquals(270.0, CoordinateUtils.getBearing(origin, westLoc), 1e-3)
    }

    @Test
    fun `parseGridRefToLocation parses alphanumeric Irish grid reference`() {
        val gridRef = "V860870"
        val location = CoordinateUtils.parseGridRefToLocation(gridRef, 120.0)

        assertEquals(86000.0, location.x, 1e-3)
        assertEquals(87000.0, location.y, 1e-3)
        assertEquals(120.0, location.altitude, 1e-3)
    }
}
