package com.example.irishhike.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordUtilsGroundTruthTest {

    @Test
    fun testGroundTruthPair1_W12608_83583() {
        val gridRef = "W12608 83583"
        val loc = CoordinateUtils.parseGridRefToLocation(gridRef)
        assertEquals(112608.0, loc.x, 0.1)
        assertEquals(83583.0, loc.y, 0.1)

        val (lat, lon) = CoordinateUtils.metricToLatLon(loc)
        val restoredLoc = CoordinateUtils.latLonToMetric(lat, lon)
        val errorMeters = CoordinateUtils.getDistance(loc, restoredLoc)
        println("Pair 1 Grid '$gridRef' -> Metric (${loc.x}, ${loc.y}) -> WGS84 ($lat, $lon) -> Restored (${restoredLoc.x}, ${restoredLoc.y}) | Round-Trip Error: %.4fm".format(errorMeters))

        assertTrue("Round trip error for Pair 1 must be <= 30.0m (was %.4fm)".format(errorMeters), errorMeters <= 1.0)
    }

    @Test
    fun testGroundTruthPair2_W_78175_73998() {
        val gridRef = "W 78175 73998"
        val loc = CoordinateUtils.parseGridRefToLocation(gridRef)
        assertEquals(178175.0, loc.x, 0.1)
        assertEquals(73998.0, loc.y, 0.1)

        val (lat, lon) = CoordinateUtils.metricToLatLon(loc)
        val restoredLoc = CoordinateUtils.latLonToMetric(lat, lon)
        val errorMeters = CoordinateUtils.getDistance(loc, restoredLoc)
        println("Pair 2 Grid '$gridRef' -> Metric (${loc.x}, ${loc.y}) -> WGS84 ($lat, $lon) -> Restored (${restoredLoc.x}, ${restoredLoc.y}) | Round-Trip Error: %.4fm".format(errorMeters))

        assertTrue("Round trip error for Pair 2 must be <= 1.0m (was %.4fm)".format(errorMeters), errorMeters <= 1.0)
    }

    @Test
    fun testGroundTruthPair2_W_78174_73999() {
        val gridRef = "W 78174 73999"
        val targetLat = 51.918249
        val targetLon = -8.317936

        val loc = CoordinateUtils.parseGridRefToLocation(gridRef)
        assertEquals(178174.0, loc.x, 0.1)
        assertEquals(73999.0, loc.y, 0.1)

        val (lat, lon) = CoordinateUtils.metricToLatLon(loc)
        val targetLoc = CoordinateUtils.latLonToMetric(targetLat, targetLon)
        val offsetFromTarget = CoordinateUtils.getDistance(loc, targetLoc)
        println("Pair 2b Grid '$gridRef' -> Metric (${loc.x}, ${loc.y}) -> Calculated Lat/Lon ($lat, $lon) | Target ($targetLat, $targetLon) | Distance from target: %.2fm".format(offsetFromTarget))

        // Without 10km grid offsets loaded, 100km block average gives ~22m offset from target
        assertTrue("Distance from target without 10km offset file must be <= 1.0m (was %.2fm)".format(offsetFromTarget), offsetFromTarget <= 1.0)
    }

    @Test
    fun testGroundTruthPair2_W_78227_73942() { // se corner
        val gridRef = "W 78227 73942"
        val targetLat = 51.917735
        val targetLon = -8.317162

        val loc = CoordinateUtils.parseGridRefToLocation(gridRef)
        assertEquals(178227.0, loc.x, 0.1)
        assertEquals(73942.0, loc.y, 0.1)

        val (lat, lon) = CoordinateUtils.metricToLatLon(loc)
        val targetLoc = CoordinateUtils.latLonToMetric(targetLat, targetLon)
        val offsetFromTarget = CoordinateUtils.getDistance(loc, targetLoc)
        println("Pair 2c Grid '$gridRef' -> Metric (${loc.x}, ${loc.y}) -> Calculated Lat/Lon ($lat, $lon) | Target ($targetLat, $targetLon) | Distance from target: %.2fm".format(offsetFromTarget))

        // Without 10km grid offsets loaded, 100km block average gives ~22m offset from target
        assertTrue("Distance from target without 10km offset file must be <= 1.0m (was %.2fm)".format(offsetFromTarget), offsetFromTarget <= 1.0)
    }
}
