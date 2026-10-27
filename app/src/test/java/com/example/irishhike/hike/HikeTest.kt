package com.example.irishhike.hike

import com.example.irishhike.data.EkfState
import com.example.irishhike.data.Estimate
import com.example.irishhike.data.Vector2D
import com.example.irishhike.model.Location
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HikeTest {

    private fun create300mSampleRoute(): Route {
        val wp1 = Waypoint.fromMetric("Start", 0.0, 0.0, legDescription = "Leg 1 to WP2")
        val wp2 = Waypoint.fromMetric("WP2", 0.0, 300.0, legDescription = "Leg 2 to Finish")
        val wp3 = Waypoint.fromMetric("Finish", 0.0, 600.0)
        return Route.createFromWaypoints("Test Route", listOf(wp1, wp2, wp3))
    }

    @Test
    fun `evaluateLegState checks states in strict priority order`() {
        val route = create300mSampleRoute()
        val hike = Hike(route)

        // 1. LEG_START: when leaving start (> 15m from start, at 20m, distance to target is 280m > 100m)
        val stateLegStart = hike.evaluateLegState(Location(0.0, 20.0), currentTimeMs = 1000L)
        assertEquals(LegState.LEG_START, stateLegStart)

        // Second tick at 0.0, 25.0 -> LEG_START already announced, returns OK
        val stateAfterLegStart = hike.evaluateLegState(Location(0.0, 25.0), currentTimeMs = 2000L)
        assertEquals(LegState.OK, stateAfterLegStart)

        // 2. APPROACHING_TARGET: entering approach window (< 100m from target wp2 at 300m, e.g. at 220m)
        val stateApproaching = hike.evaluateLegState(Location(0.0, 220.0), currentTimeMs = 3000L)
        assertEquals(LegState.APPROACHING_TARGET, stateApproaching)

        // Second tick in approach window -> APPROACHING already announced, returns OK
        val stateAfterApproaching = hike.evaluateLegState(Location(0.0, 225.0), currentTimeMs = 4000L)
        assertEquals(LegState.OK, stateAfterApproaching)

        // 3. TARGET_REACHED: arriving at target (< 15m from wp2 at 300m, e.g. at 295m)
        val stateReached = hike.evaluateLegState(Location(0.0, 295.0), currentTimeMs = 5000L)
        assertEquals(LegState.TARGET_REACHED, stateReached)
    }

    @Test
    fun `relativeBearingCorrection and bearingCorrectionString calculate accurate angle offsets`() {
        val wp1 = Waypoint.fromMetric("Start", 0.0, 0.0)
        val wp2 = Waypoint.fromMetric("WP2", 0.0, 150.0) // Target is directly North (0 degrees)
        val route = Route.createFromWaypoints("Route", listOf(wp1, wp2))
        val hike = Hike(route)

        // Case 1: Hiker heading is 345 deg (15 deg left of North) -> correction should be +15 deg (plus 15 degrees)
        val ekfLeft = EkfState(bearingRad = Estimate(Math.toRadians(345.0), 0.01))
        val corrLeft = hike.relativeBearingCorrection(Location(0.0, 20.0), ekfLeft)
        assertEquals(15.0, corrLeft, 0.001)
        assertEquals("plus 15 degrees", hike.bearingCorrectionString(Location(0.0, 20.0), ekfLeft))

        // Case 2: Hiker heading is 30 deg (30 deg right of North) -> correction should be -30 deg (minus 30 degrees)
        val ekfRight = EkfState(bearingRad = Estimate(Math.toRadians(30.0), 0.01))
        val corrRight = hike.relativeBearingCorrection(Location(0.0, 20.0), ekfRight)
        assertEquals(-30.0, corrRight, 0.001)
        assertEquals("minus 30 degrees", hike.bearingCorrectionString(Location(0.0, 20.0), ekfRight))
    }

    @Test
    fun `short leg suppresses LEG_START if APPROACHING_TARGET triggered first`() {
        val wp1 = Waypoint.fromMetric("Start", 0.0, 0.0)
        val wp2 = Waypoint.fromMetric("WP2", 0.0, 80.0)
        val route = Route.createFromWaypoints("Short Route", listOf(wp1, wp2))
        val hike = Hike(route)

        // At location (0, 20): distance to WP2 is 60m (< 100m approach threshold).
        // APPROACHING_TARGET triggers first.
        val state1 = hike.evaluateLegState(Location(0.0, 20.0), currentTimeMs = 1000L)
        assertEquals(LegState.APPROACHING_TARGET, state1)
        assertTrue(hike.tracker.announcedApproaching)

        // Subsequent update at (0, 30): since APPROACHING_TARGET has occurred, LEG_START is suppressed!
        val state2 = hike.evaluateLegState(Location(0.0, 30.0), currentTimeMs = 2000L)
        assertEquals(LegState.OK, state2)
        assertTrue(hike.tracker.announcedLegStart)
    }

    @Test
    fun `evaluateLegState detects TOO_FAR when passing target`() {
        val wp1 = Waypoint.fromMetric("Start", 0.0, 0.0)
        val wp2 = Waypoint.fromMetric("WP2", 0.0, 100.0)
        val route = Route.createFromWaypoints("Route", listOf(wp1, wp2))
        val hike = Hike(route)

        // Position past target along leg direction (t > 1.05 and distance > 15m)
        val stateTooFar = hike.evaluateLegState(Location(0.0, 125.0), currentTimeMs = 1000L)
        assertEquals(LegState.TOO_FAR, stateTooFar)
    }

    @Test
    fun `OFFCOURSE cool-off logic respects 2s drift and 15s steady thresholds`() {
        val route = create300mSampleRoute()
        val hike = Hike(route, com.example.irishhike.config.HikeConfig(driftCoolOffSec = 2L, steadyCoolOffSec = 15L))

        // Mark LEG_START as announced
        hike.evaluateLegState(Location(0.0, 20.0), currentTimeMs = 1000L)

        // First OFFCOURSE event at t = 2000ms with 30-deg deviation at speed >= 0.4 m/s
        val ekf1 = EkfState(
            velocity = Estimate(Vector2D(0.0, 1.2), 0.01),
            bearingRad = Estimate(Math.toRadians(30.0), 0.0001)
        )
        val stateOffCourse1 = hike.evaluateLegState(Location(0.0, 20.0), ekfState = ekf1, currentTimeMs = 2000L)
        assertEquals(LegState.OFFCOURSE, stateOffCourse1)

        // At t = 2500ms (500ms later), same 30-deg deviation -> suppressed by 15s cool-off
        val stateOffCourse2 = hike.evaluateLegState(Location(0.0, 20.0), ekfState = ekf1, currentTimeMs = 2500L)
        assertEquals(LegState.OK, stateOffCourse2)

        // At t = 4500ms (2500ms after first offcourse), drifting further to 40-deg (> last + 2.0) -> triggers 2s cool-off
        val ekf2 = EkfState(
            velocity = Estimate(Vector2D(0.0, 1.2), 0.01),
            bearingRad = Estimate(Math.toRadians(40.0), 0.0001)
        )
        val stateOffCourse3 = hike.evaluateLegState(Location(0.0, 20.0), ekfState = ekf2, currentTimeMs = 4500L)
        assertEquals(LegState.OFFCOURSE, stateOffCourse3)
    }

    @Test
    fun `stringify returns appropriate message for each LegState`() {
        val route = create300mSampleRoute()
        val hike = Hike(route)
        val leg = route.legs[0]

        assertNull(hike.stringify(LegState.OK, leg, null))

        val msgLegStart = hike.stringify(LegState.LEG_START, leg, null)
        assertTrue(msgLegStart!!.contains("Starting leg"))

        val msgApproaching = hike.stringify(LegState.APPROACHING_TARGET, leg, null)
        assertTrue(msgApproaching!!.contains("Approaching"))

        val msgReached = hike.stringify(LegState.TARGET_REACHED, leg, null)
        assertTrue(msgReached!!.contains("Reached waypoint"))

        val msgTooFar = hike.stringify(LegState.TOO_FAR, leg, null)
        assertTrue(msgTooFar!!.contains("Warning"))

        val msgHikeEnded = hike.stringify(LegState.HIKE_ENDED, leg, null)
        assertTrue(msgHikeEnded!!.contains("Congratulations"))

        val ekfOffCourse = EkfState(bearingRad = Estimate(Math.toRadians(345.0), 0.01))
        val msgOffCourse = hike.stringify(LegState.OFFCOURSE, leg, ekfOffCourse, Location(0.0, 20.0))
        assertEquals("plus 15 degrees", msgOffCourse)
    }

    @Test
    fun `update handles leg progression and string message generation`() {
        val route = create300mSampleRoute()
        val hike = Hike(route)

        val res1 = hike.update(Location(0.0, 20.0), 1000L, true)
        assertNotNull(res1)
        assertNotNull(res1?.message)
        assertTrue(res1!!.message!!.contains("Starting leg"))
        assertEquals(0, hike.currentLegIndex)

        // Reach WP2 (at 300m)
        val res2 = hike.update(Location(0.0, 295.0), 2000L, true)
        assertNotNull(res2)
        assertNotNull(res2?.message)
        assertTrue(res2!!.message!!.contains("Reached waypoint"))
        assertEquals(1, hike.currentLegIndex)

        // Reach Finish (at 600m)
        val res3 = hike.update(Location(0.0, 595.0), 3000L, true)
        assertNotNull(res3)
        assertNotNull(res3?.message)
        assertTrue(res3!!.message!!.contains("Congratulations"))
        assertTrue(hike.isCompleted)
    }

    @Test
    fun `stopping hike ceases activity but preserves state`() {
        val route = create300mSampleRoute()
        val hike = Hike(route)

        hike.update(Location(0.0, 50.0), 1000L, true)
        assertEquals(1, hike.breadcrumbs.size)

        hike.stop()
        assertFalse(hike.isActive)

        val res = hike.update(Location(0.0, 60.0), 2000L, true)
        assertNull(res)
        assertEquals(1, hike.breadcrumbs.size)
    }
}
