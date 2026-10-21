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
    fun `parseGridRefToLocation parses alphanumeric Irish grid reference for letter V`() {
        val gridRef = "V860870"
        val location = CoordinateUtils.parseGridRefToLocation(gridRef, 120.0)

        // Letter V -> Col 0 (base 0), Row 4 (base 0)
        assertEquals(86000.0, location.x, 1e-3)
        assertEquals(87000.0, location.y, 1e-3)
        assertEquals(120.0, location.altitude, 1e-3)
    }

    @Test
    fun `parseGridRefToLocation correctly computes global 100km base offsets across grid letters`() {
        // Letter H -> Dublin/Wicklow (Col 2: base 200,000m, Row 1: base 300,000m)
        val hLoc = CoordinateUtils.parseGridRefToLocation("H312456")
        assertEquals(231200.0, hLoc.x, 1.0)
        assertEquals(345600.0, hLoc.y, 1.0)

        // Letter O -> Meath/Louth (Col 3: base 300,000m, Row 2: base 200,000m)
        val oLoc = CoordinateUtils.parseGridRefToLocation("O123456")
        assertEquals(312300.0, oLoc.x, 1.0)
        assertEquals(245600.0, oLoc.y, 1.0)

        // Letter S -> Tipperary (Col 2: base 200,000m, Row 3: base 100,000m)
        val sLoc = CoordinateUtils.parseGridRefToLocation("S100200")
        assertEquals(210000.0, sLoc.x, 1.0)
        assertEquals(120000.0, sLoc.y, 1.0)
    }

    @Test
    fun roundTripMetricToLatLonPreservesCoordinatesAcrossIreland() {
        val testLocations = listOf(
            Location(86000.0, 87000.0),    // Kerry (V)
            Location(231200.0, 345600.0),  // Dublin/Wicklow (H)
            Location(312300.0, 245600.0),  // Meath (O)
            Location(210000.0, 120000.0)   // Tipperary (S)
        )

        for (originalLoc in testLocations) {
            val (lat, lon) = CoordinateUtils.metricToLatLon(originalLoc)
            val restoredLoc = CoordinateUtils.latLonToMetric(lat, lon)

            assertEquals("X Easting must match within 30m for $originalLoc", originalLoc.x, restoredLoc.x, 30.0)
            assertEquals("Y Northing must match within 30m for $originalLoc", originalLoc.y, restoredLoc.y, 30.0)
        }
    }
}
