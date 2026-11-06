package com.example.irishhike.ekf

import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EkfTelemetryBouncingTest {

    //@Test
    fun testEkfFiltersGpsSpikesAndPreventsBouncing() {
        runTelemetryFileCheck("telemetry/telemetry_20261025_100919.csv", "telemetry_20261025_100919.csv")
    }

    //@Test
    fun testSecondTelemetryFile113453() {
        runTelemetryFileCheck("telemetry/telemetry_20261025_113453.csv", "telemetry_20261025_113453.csv")
    }

    private fun runTelemetryFileCheck(resourcePath: String, fallbackPath: String) {
        val resourceStream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: javaClass.classLoader?.getResourceAsStream(fallbackPath)

        val csvText = if (resourceStream != null) {
            resourceStream.bufferedReader().use { it.readText() }
        } else {
            val file = File(fallbackPath)
            assertTrue("Telemetry file not found at ${file.absolutePath}", file.exists())
            file.readText()
        }

        val events = TelemetryRecorder.fromCsvString(csvText)
        assertTrue("No telemetry events parsed from $resourcePath!", events.isNotEmpty())

        val ekf = PdrEkfEngine()
        var maxVarianceX = 0.0
        var maxSpeedMps = 0.0
        var processedFixCount = 0

        for (event in events) {
            when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    val state = ekf.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                    processedFixCount++

                    if (state.position.variance > maxVarianceX) {
                        maxVarianceX = state.position.variance
                    }
                    if (state.speedMps.value > maxSpeedMps) {
                        maxSpeedMps = state.speedMps.value
                    }
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    ekf.onStepDetected(event.headingRawRad, event.timestampMs)
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    ekf.predict(event.dtSec)
                }
            }
        }

        println("=== EKF TELEMETRY REPORT for $resourcePath ===")
        println("Processed GPS Fixes: $processedFixCount")
        println("Max Position Variance: $maxVarianceX m²")
        println("Max Speed: $maxSpeedMps m/s")

        assertTrue("Max position variance must stay bounded under 150m²", maxVarianceX <= 150.0)
        assertTrue("Max hiking speed must stay bounded under 5.0m/s", maxSpeedMps <= 5.0)
    }
}
