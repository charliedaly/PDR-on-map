package com.example.irishhike.util

import com.example.irishhike.model.Leg
import com.example.irishhike.model.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteParserTest {

    @Test
    fun `parseCsv converts sample Irish grid CSV into waypoints`() {
        val sampleCsv = RouteParser.generateSampleIrishGridCsv()
        val waypoints = RouteParser.parseCsv(sampleCsv)

        assertEquals(5, waypoints.size)
        assertEquals("Start", waypoints[0].name)
        assertEquals("Colt", waypoints[1].name)
        assertEquals("Summit", waypoints[2].name)
        assertEquals("Ridge", waypoints[3].name)
        assertEquals("Finish", waypoints[4].name)

        assertEquals(120.0, waypoints[0].altitude, 1e-3)
        assertEquals("Heading north toward Colt", waypoints[0].legDescription)
    }

    @Test
    fun `parseFromCsvContent creates linked legs in Route`() {
        val sampleCsv = RouteParser.generateSampleIrishGridCsv()
        val route = Route.parseFromCsvContent("Test Irish Route", sampleCsv)

        assertEquals("Test Irish Route", route.name)
        assertEquals(4, route.numLegs)
        assertTrue("Route length must be positive", route.totalLengthMeters > 0.0)

        var currentLeg: Leg? = route.legs.firstOrNull()
        var legCount = 0
        while (currentLeg != null) {
            legCount++
            assertNotNull("Start waypoint must exist", currentLeg.start)
            assertNotNull("Target waypoint must exist", currentLeg.target)
            currentLeg = currentLeg.next
        }

        assertEquals(4, legCount)
    }
}
