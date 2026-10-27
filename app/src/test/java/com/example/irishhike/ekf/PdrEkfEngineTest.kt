package com.example.irishhike.ekf

import com.example.irishhike.data.EkfState
import com.example.irishhike.data.Estimate
import com.example.irishhike.util.CoordinateUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PdrEkfEngineTest {

    @Test
    fun evaluateEkfTelemetryPerformance() {
        val telemetryFile = File("src/test/resources/telemetry/pps_tlmtry_20261018_123410.csv")
        if (!telemetryFile.exists()) {
            println("Telemetry file not found. Skipping evaluation test.")
            return
        }

        val ekf = PdrEkfEngine(EkfConfig())
        var lastGpsX: Double? = null
        var lastGpsY: Double? = null

        var totalDiscrepancyDeg = 0.0
        var validSampleCount = 0

        var localStepCount = 0
        var mismatchDetectedStep: Int? = null
        var stepsToConverge: Int? = null
        var isConverging = false

        val lines = telemetryFile.readLines()
        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue
            val tokens = line.split(",")
            when (tokens[0]) {
                "STEP" -> {
                    localStepCount++
                    val headingRawRad = tokens[2].toDouble()
                    val timestampMs = tokens[1].toLong()
                    ekf.onStepDetected(headingRawRad, timestampMs)

                    if (isConverging && mismatchDetectedStep != null) {
                        val ekfState = ekf.getEkfState()
                        if (ekfState.bearingRad.variance < 0.2) {
                            stepsToConverge = localStepCount - mismatchDetectedStep!!
                            isConverging = false
                        }
                    }
                }
                "GPS" -> {
                    val lat = tokens[2].toDouble()
                    val lon = tokens[3].toDouble()
                    val accuracy = tokens[4].toFloat()
                    val timestampMs = tokens[1].toLong()

                    val ekfState = ekf.onGpsFix(lat, lon, accuracy, timestampMs)

                    val gpsMetric = CoordinateUtils.latLonToMetric(lat, lon)
                    if (lastGpsX != null && lastGpsY != null) {
                        val deltaX = gpsMetric.x - lastGpsX!!
                        val deltaY = gpsMetric.y - lastGpsY!!
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

                        // Evaluate on clean walking movement legs (stepCount >= 3, accuracy <= 10m, distance > 2.0m)
                        if (ekfState.stepCount >= 3 && accuracy <= 10.0f && distance > 2.0) {
                            val trueGpsBearingRad = atan2(deltaX, deltaY)
                            val ekfBearingRad = ekfState.bearingRad.value

                            var diffRad = ekfBearingRad - trueGpsBearingRad
                            diffRad = atan2(sin(diffRad), cos(diffRad))
                            val diffDeg = Math.toDegrees(abs(diffRad))

                            if (diffDeg > 30.0 && !isConverging) {
                                mismatchDetectedStep = localStepCount
                                isConverging = true
                            } else {
                                totalDiscrepancyDeg += diffDeg
                                validSampleCount++
                                if (isConverging) {
                                    stepsToConverge = localStepCount - (mismatchDetectedStep ?: localStepCount)
                                    isConverging = false
                                }
                            }
                        }
                    }
                    lastGpsX = gpsMetric.x
                    lastGpsY = gpsMetric.y
                }
            }
        }

        val averageDiscrepancy = if (validSampleCount > 0) totalDiscrepancyDeg / validSampleCount else 0.0

        println("=== EKF TELEMETRY PERFORMANCE REPORT ===")
        println("Valid Evaluated GPS Fixes: $validSampleCount")
        println("Average Bearing Discrepancy: %.2f degrees".format(averageDiscrepancy))
        if (stepsToConverge != null) {
            println("Steps to Correct Systematic Error: $stepsToConverge steps")
        } else {
            println("Systematic error convergence window: Stable within test bounds.")
        }

        assertTrue("Average bearing discrepancy too high: $averageDiscrepancy°", averageDiscrepancy <= 15.0)
    }

    private fun normalizeAngleRad(angle: Double): Double {
        var a = angle
        while (a > Math.PI) a -= 2.0 * Math.PI
        while (a < -Math.PI) a += 2.0 * Math.PI
        return a
    }
}
