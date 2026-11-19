package com.example.irishhike.ekf

import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Location
import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import com.example.irishhike.util.CoordinateUtils
import org.junit.Test
import java.io.File
import kotlin.math.*

class BearingComparisonBenchmarkTest {

    // Path to your recorded telemetry CSV inside app/src/test/resources/
    private val telemetryFileName = "telemetry/gouganbarra/telemetry_20261115_160033.csv"

    private fun loadResourceText(fileName: String): String? {
        val stream = javaClass.classLoader?.getResourceAsStream(fileName)
            ?: javaClass.getResourceAsStream("/$fileName")
            ?: File("src/test/resources/$fileName").takeIf { it.exists() }?.inputStream()
            ?: File(fileName).takeIf { it.exists() }?.inputStream()

        return stream?.bufferedReader()?.use { it.readText() }
    }

    private data class SamplePoint(
        val timestampMs: Long,
        val location: Location,
        val speedMps: Double,
        val compassBearingDeg: Double,
        val ekfVelocityBearingDeg: Double
    )

    @Test
    fun `benchmark bearing methods against future ground truth`() {
        val csvText = loadResourceText(telemetryFileName)
        if (csvText == null) {
            println("SKIPPED: Telemetry file '$telemetryFileName' not found.")
            println("Place your .csv file in 'app/src/test/resources/telemetry/' to run this benchmark.")
            return
        }

        val events = TelemetryRecorder.fromCsvString(csvText).sortedBy { it.timestampMs }
        val ekf = PdrEkfEngine()

        val samples = mutableListOf<SamplePoint>()

        // 1. Replay session and collect calculated bearings
        for (event in events) {
            val state: EkfState = when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    ekf.onGpsFix(
                        lat = event.latitude,
                        lon = event.longitude,
                        accuracyMeters = event.accuracyMeters,
                        activeIntervalMs = event.activeIntervalMs
                    )
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    ekf.onStepDetected(event.headingRawRad, event.timestampMs)
                    ekf.getEkfState()
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    ekf.predict(event.dtSec) // Single argument
                }
            }

            if (state.isInitializedWithGps) {
                val loc = Location(state.position.value.x, state.position.value.y)
                val compassDeg = (Math.toDegrees(state.bearingRad.value) % 360.0 + 360.0) % 360.0

                // Velocity vector bearing: atan2(vx, vy) converted to degrees clockwise from North
                val vx = state.velocity.value.x
                val vy = state.velocity.value.y
                val velBearingDeg = ((Math.toDegrees(atan2(vx, vy)) % 360.0) + 360.0) % 360.0

                samples.add(
                    SamplePoint(
                        timestampMs = event.timestampMs,
                        location = loc,
                        speedMps = state.speedMps.value,
                        compassBearingDeg = compassDeg,
                        ekfVelocityBearingDeg = velBearingDeg
                    )
                )
            }
        }

        println("Collected ${samples.size} replay samples for evaluation.")

        // 2. Evaluate against Future Ground Truth (Vector to position 8 seconds ahead)
        val lookaheadSec = 8.0
        val lookaheadMs = (lookaheadSec * 1000L).toLong()

        var totalCompassError = 0.0
        var totalLocationVectorError = 0.0
        var totalEkfVelocityError = 0.0
        var evaluatedCount = 0

        var compassJitterCount = 0
        var locationVectorJitterCount = 0
        var prevCompass = -1.0
        var prevLocVector = -1.0

        // Trailing window for recent location vector (last 6 seconds)
        val trailingWindowMs = 6000L

        for (i in samples.indices) {
            val current = samples[i]

            // Find future point (~8s ahead) to establish true course
            val futurePoint = samples.drop(i).firstOrNull { it.timestampMs - current.timestampMs >= lookaheadMs }
                ?: continue

            val distanceMoved = current.location.distanceTo(futurePoint.location)
            // Only evaluate when the hiker was actually walking (moved > 4 meters)
            if (distanceMoved < 4.0 || current.speedMps < 0.4) continue

            val trueCourseDeg = current.location.bearingTo(futurePoint.location)

            // Method 1: Compass Heading
            val compassErr = angleDiff(current.compassBearingDeg, trueCourseDeg)

            // Method 2: Location Displacement (Line through last 6s of positions)
            val pastPoint = samples.take(i).lastOrNull { current.timestampMs - it.timestampMs >= trailingWindowMs }
                ?: samples.first()
            val locVectorDeg = pastPoint.location.bearingTo(current.location)
            val locVectorErr = angleDiff(locVectorDeg, trueCourseDeg)

            // Method 3: EKF Velocity Vector
            val ekfVelErr = angleDiff(current.ekfVelocityBearingDeg, trueCourseDeg)

            totalCompassError += compassErr
            totalLocationVectorError += locVectorErr
            totalEkfVelocityError += ekfVelErr
            evaluatedCount++

            // Check for sudden rapid flickers (> 20 degree swing from last sample)
            if (prevCompass >= 0 && angleDiff(current.compassBearingDeg, prevCompass) > 20.0) {
                compassJitterCount++
            }
            if (prevLocVector >= 0 && angleDiff(locVectorDeg, prevLocVector) > 20.0) {
                locationVectorJitterCount++
            }
            prevCompass = current.compassBearingDeg
            prevLocVector = locVectorDeg
        }

        if (evaluatedCount == 0) {
            println("No walking segments longer than ${lookaheadSec}s met the movement criteria.")
            return
        }

        val avgCompassErr = totalCompassError / evaluatedCount
        val avgLocVectorErr = totalLocationVectorError / evaluatedCount
        val avgEkfVelErr = totalEkfVelocityError / evaluatedCount

        println("\n==========================================================================")
        println("BEARING METHOD ACCURACY BENCHMARK REPORT")
        println("Evaluated $evaluatedCount active walking points against Future Trajectory (~${lookaheadSec}s ahead)")
        println("==========================================================================")
        println("%-30s | %-18s | %s".format("Method", "Mean Angular Error", "Jitter / Spikes (>20°)"))
        println("--------------------------------------------------------------------------")
        println("%-30s | %16.2f° | %d".format("1. Sensor/Compass Heading", avgCompassErr, compassJitterCount))
        println("%-30s | %16.2f° | %d".format("2. Recent Locations Vector", avgLocVectorErr, locationVectorJitterCount))
        println("%-30s | %16.2f° | -".format("3. EKF Velocity Vector", avgEkfVelErr))
        println("==========================================================================\n")
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff < -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0
        return abs(diff)
    }
}