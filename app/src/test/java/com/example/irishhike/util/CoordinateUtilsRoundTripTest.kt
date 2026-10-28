package com.example.irishhike.util

import com.example.irishhike.model.Location
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateUtilsRoundTripTest {

    @Test
    fun testMetricToLatLonAndBackRoundTripAccuracy() {
        // Test points spanning major Irish hiking regions across squares V, W, X, Q, R, S, L, M, N, O
        val testLocations = listOf(
            Location(178061.22, 165172.54), // Tipperary / Newport
            Location(86000.0, 87000.0),     // Kerry / Killarney
            Location(315000.0, 230000.0),   // Wicklow / Dublin Mountains
            Location(125000.0, 280000.0),   // Galway / Connemara
            Location(210000.0, 380000.0),   // Sligo / Donegal
            Location(140000.0, 120000.0)    // Cork / Comeragh
        )

        for (loc in testLocations) {
            val (lat, lon) = CoordinateUtils.metricToLatLon(loc)
            val convertedBackLoc = CoordinateUtils.latLonToMetric(lat, lon)

            val errorX = Math.abs(loc.x - convertedBackLoc.x)
            val errorY = Math.abs(loc.y - convertedBackLoc.y)

            println("Location (${loc.x}, ${loc.y}) -> Lat/Lon ($lat, $lon) -> Back (${convertedBackLoc.x}, ${convertedBackLoc.y}) | Error X: %.4fm, Error Y: %.4fm".format(errorX, errorY))

            assertEquals("X coordinate conversion round-trip error must be < 0.10m for (${loc.x}, ${loc.y})", loc.x, convertedBackLoc.x, 0.10)
            assertEquals("Y coordinate conversion round-trip error must be < 0.10m for (${loc.x}, ${loc.y})", loc.y, convertedBackLoc.y, 0.10)
        }
    }
}
