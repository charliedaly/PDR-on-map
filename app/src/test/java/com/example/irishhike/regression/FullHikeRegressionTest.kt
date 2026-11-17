package com.example.irishhike.regression

import com.example.irishhike.data.EkfState
import com.example.irishhike.ekf.PdrEkfEngine
import com.example.irishhike.hike.Hike
import com.example.irishhike.hike.LegState
import com.example.irishhike.model.Location
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FullHikeRegressionTest {

    // File names inside app/src/test/resources/
    private val telemetryFileName = "telemetry/gouganbarra/telemetry_20261115_160033.csv"
    private val routeFileName = "telemetry/gouganbarra/adapted_to_test.csv"

    /**
     * Formats Irish Grid metric (x, y) into the standard alphanumeric reference.
     * Example: x=178174.0, y=73999.0 -> "W7817473999" (10-figure, 1-meter precision)
     */
    private fun toAlphanumericGrid(x: Double, y: Double): String {
        val gridLetters = arrayOf(
            arrayOf('A', 'B', 'C', 'D', 'E'),
            arrayOf('F', 'G', 'H', 'J', 'K'),
            arrayOf('L', 'M', 'N', 'O', 'P'),
            arrayOf('Q', 'R', 'S', 'T', 'U'),
            arrayOf('V', 'W', 'X', 'Y', 'Z')
        )
        val col = (x / 100000.0).toInt().coerceIn(0, 4)
        val row = (4 - (y / 100000.0).toInt()).coerceIn(0, 4)
        val letter = gridLetters[row][col]

        val localE = ((x % 100000.0).toInt().let { if (it < 0) it + 100000 else it })
        val localN = ((y % 100000.0).toInt().let { if (it < 0) it + 100000 else it })

        val eastingStr = localE.toString().padStart(5, '0')
        val northingStr = localN.toString().padStart(5, '0')

        return "$letter$eastingStr$northingStr"
    }

    private fun loadResourceText(fileName: String): String? {
        val stream = javaClass.classLoader?.getResourceAsStream(fileName)
            ?: javaClass.getResourceAsStream("/$fileName")
            ?: File("src/test/resources/$fileName").takeIf { it.exists() }?.inputStream()
            ?: File(fileName).takeIf { it.exists() }?.inputStream()

        return stream?.bufferedReader()?.use { it.readText() }
    }

    private data class WaypointApproachTracker(
        val waypoint: Waypoint,
        var closestDistanceMeters: Double = Double.MAX_VALUE,
        var closestHikerLocation: Location? = null,
        var closestTimestampMs: Long = 0L,
        var wasReached: Boolean = false
    )

    @Test
    fun `replaying recorded walk analyzes waypoint arrivals and grid references`() {
        val telemetryCsv = loadResourceText(telemetryFileName)
        val routeCsv = loadResourceText(routeFileName)

        if (telemetryCsv == null || routeCsv == null) {
            println("SKIPPED: Telemetry file '$telemetryFileName' or Route '$routeFileName' not found.")
            println("Place your .csv files in 'app/src/test/resources/' to run this test.")
            return
        }

        val route = Route.parseFromCsvContent("Regression Route", routeCsv)
        val hike = Hike(route)
        val ekf = PdrEkfEngine()

        val events = TelemetryRecorder.fromCsvString(telemetryCsv)
        assertTrue("Telemetry file must contain events", events.isNotEmpty())
        assertTrue("Route must contain waypoints", route.waypoints.isNotEmpty())

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        // 1. Print Waypoint Table with Alphanumeric Irish Grid Reference
        println("\n==========================================================================")
        println("ROUTE WAYPOINTS (ALPHANUMERIC IRISH GRID):")
        println("==========================================================================")
        println("%-4s | %-16s | %-14s | %s".format("#", "Name", "Alphanumeric", "Altitude"))
        println("--------------------------------------------------------------------------")
        route.waypoints.forEachIndexed { i, wp ->
            val gridAlpha = toAlphanumericGrid(wp.x, wp.y)
            println("%-4d | %-16s | %-14s | %.0fm".format(i + 1, wp.name, gridAlpha, wp.altitude))
        }
        println("==========================================================================\n")

        val approachTrackers = route.waypoints.map { WaypointApproachTracker(it) }

        var maxPosVariance = 0.0
        var maxSpeedMps = 0.0

        println("=== REPLAYING RECORDED WALK EVENTS ===")

        val sortedEvents = events.sortedBy { it.timestampMs }

        for (event in sortedEvents) {
            val ekfState: EkfState = when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    if (event.recordedEkfState != null && event.recordedEkfState.speedMps.value > 0.8) {
                        ekf.setHeadingRad(event.recordedEkfState.bearingRad.value)
                    }
                    ekf.onGpsFix(
                        lat = event.latitude,
                        lon = event.longitude,
                        accuracyMeters = event.accuracyMeters,
                        activeIntervalMs = event.activeIntervalMs,
                        timestampMs = event.timestampMs
                    )
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    ekf.onStepDetected(event.headingRawRad, event.timestampMs)
                    ekf.getEkfState()
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    ekf.predict(event.dtSec)
                }
            }

            if (ekfState.position.variance > maxPosVariance) {
                maxPosVariance = ekfState.position.variance
            }
            if (ekfState.speedMps.value > maxSpeedMps) {
                maxSpeedMps = ekfState.speedMps.value
            }

            if (ekfState.isInitializedWithGps) {
                val hikerPos = ekfState.position.value
                val hikerLoc = Location(hikerPos.x, hikerPos.y)

                // Track closest approach to every waypoint
                for (tracker in approachTrackers) {
                    val dist = hikerLoc.distanceTo(tracker.waypoint)
                    if (dist < tracker.closestDistanceMeters) {
                        tracker.closestDistanceMeters = dist
                        tracker.closestHikerLocation = hikerLoc
                        tracker.closestTimestampMs = event.timestampMs
                    }
                }

                val hikeResult = hike.update(
                    location = hikerLoc,
                    timestampMs = event.timestampMs,
                    isGpsFix = (event is TelemetryEvent.GpsFixEvent),
                    ekfState = ekfState
                )

                if (hikeResult != null && hikeResult.state != LegState.OK) {
                    val timeStr = timeFormatter.format(Date(event.timestampMs))
                    val hikerGrid = toAlphanumericGrid(hikerPos.x, hikerPos.y)

                    when (hikeResult.state) {
                        LegState.TARGET_REACHED -> {
                            val targetWp = hike.currentLeg?.start
                            val targetGrid = targetWp?.let { toAlphanumericGrid(it.x, it.y) } ?: ""
                            println("  [$timeStr] REACHED: '${targetWp?.name}' ($targetGrid) | Hiker at: $hikerGrid")
                        }
                        LegState.HIKE_ENDED -> {
                            val finalWp = route.waypoints.lastOrNull()
                            val finalGrid = finalWp?.let { toAlphanumericGrid(it.x, it.y) } ?: ""
                            println("  [$timeStr] HIKE COMPLETED at final waypoint: '${finalWp?.name}' ($finalGrid)")
                        }
                        LegState.APPROACHING_TARGET -> {
                            val target = hike.currentLeg?.target
                            val targetGrid = target?.let { toAlphanumericGrid(it.x, it.y) } ?: ""
                            println("  [$timeStr] Approaching '${target?.name}' ($targetGrid)")
                        }
                        LegState.LEG_START -> {
                            val target = hike.currentLeg?.target
                            val targetGrid = target?.let { toAlphanumericGrid(it.x, it.y) } ?: ""
                            println("  [$timeStr] Leg Start towards '${target?.name}' ($targetGrid)")
                        }
                        else -> {}
                    }
                }
            }
        }

        // Mark waypoints reached within 15 meters
        approachTrackers.forEach { tracker ->
            tracker.wasReached = tracker.closestDistanceMeters <= 15.0
        }

        // 2. Waypoint Status & Bypass Summary
        println("\n==========================================================================")
        println("WAYPOINT ANALYSIS (STATUS & BYPASS COORDINATES):")
        println("==========================================================================")
        approachTrackers.forEachIndexed { i, t ->
            val targetGrid = toAlphanumericGrid(t.waypoint.x, t.waypoint.y)
            val status = if (t.wasReached) {
                "REACHED (%.1fm)".format(t.closestDistanceMeters)
            } else {
                "BYPASSED (Closest: %.1fm)".format(t.closestDistanceMeters)
            }
            val timeStr = if (t.closestTimestampMs > 0) timeFormatter.format(Date(t.closestTimestampMs)) else "--:--:--"

            println("#%d %-16s | Target: %s | %s at %s".format(i + 1, "'${t.waypoint.name}'", targetGrid, status, timeStr))

            // If bypassed, print the exact alphanumeric reference where you walked closest
            if (!t.wasReached && t.closestHikerLocation != null) {
                val bypassGrid = toAlphanumericGrid(t.closestHikerLocation!!.x, t.closestHikerLocation!!.y)
                println("   └── Walked Bypass Coordinate: $bypassGrid")
            }
        }

        // 3. Ready-to-Copy CSV Output with Alphanumeric Grid References
        println("\n==========================================================================")
        println("UPDATED ROUTE CSV (Alphanumeric grid references ready to copy):")
        println("==========================================================================")
        println("name,grid_ref,altitude")
        approachTrackers.forEach { t ->
            val actualLoc = if (t.wasReached || t.closestHikerLocation == null) {
                Location(t.waypoint.x, t.waypoint.y)
            } else {
                t.closestHikerLocation!!
            }
            val alphaRef = toAlphanumericGrid(actualLoc.x, actualLoc.y)
            println("${t.waypoint.name},$alphaRef,${t.waypoint.altitude.toInt()}")
        }
        println("==========================================================================\n")

        // Sanity assertions
        assertTrue("EKF must be initialized", ekf.isInitialized)
        assertFalse("Position X must not be NaN", ekf.stateX.isNaN())
        assertFalse("Position Y must not be NaN", ekf.stateY.isNaN())
        // Allow up to 150m² (covers the initial pre-GPS lock uncertainty of ~141m²)
        assertTrue(
            "Position variance must stay bounded under 150m² (actual peak was: %.2f m²)".format(maxPosVariance),
            maxPosVariance <= 150.0
        )
    }
}