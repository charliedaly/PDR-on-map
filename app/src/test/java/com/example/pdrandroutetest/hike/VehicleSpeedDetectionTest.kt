package com.example.pdrandroutetest.hike

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.data.Estimate
import com.example.pdrandroutetest.data.Vector2D
import com.example.pdrandroutetest.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSpeedDetectionTest {

    @Test
    fun testVehicleSpeedDetectedAndResumed() {
        val hike = Hike(null)
        val startTime = 1000000L

        val slowEkf = EkfState(velocity = Estimate(Vector2D(1.2, 0.0), 0.1))
        val fastEkf = EkfState(velocity = Estimate(Vector2D(5.0, 0.0), 0.1))
        val loc = Location(80000.0, 80000.0)

        // 1. Initial 5 seconds at high speed (should not trigger yet, needs 10s)
        var result = hike.update(loc, startTime, true, fastEkf)
        assertNull(result)

        result = hike.update(loc, startTime + 5000L, true, fastEkf)
        assertNull(result)

        // 2. At 10 seconds of continuous high speed -> trigger VEHICLE_SPEED_DETECTED
        result = hike.update(loc, startTime + 10000L, true, fastEkf)
        assertNotNull(result)
        assertEquals(LegState.VEHICLE_SPEED_DETECTED, result?.state)

        // 3. Drop back to hiking speed (1.2 m/s) for 2 seconds (needs 3s to resume)
        result = hike.update(loc, startTime + 12000L, true, slowEkf)
        assertNull(result)

        // 4. At 3.5 seconds of hiking speed -> trigger HIKE_SPEED_RESUMED
        result = hike.update(loc, startTime + 15500L, true, slowEkf)
        assertNotNull(result)
        assertEquals(LegState.HIKE_SPEED_RESUMED, result?.state)
    }
}
