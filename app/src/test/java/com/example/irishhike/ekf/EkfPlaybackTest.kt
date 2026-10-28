package com.example.irishhike.ekf

import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Location
import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import com.example.irishhike.util.CoordinateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class EkfPlaybackTest {

    private lateinit var ekf: PdrEkfEngine

    // Change this filename to test any recorded CSV file in app/src/test/resources/telemetry/
    private val recordedFileName = "telemetry/telemetry_20261013_153232.csv"

    @Before
    fun setUp() {
        ekf = PdrEkfEngine()
    }

    private fun loadResourceStream(fileName: String) =
        javaClass.classLoader?.getResourceAsStream(fileName)
            ?: javaClass.getResourceAsStream("/$fileName")
            ?: File("src/test/resources/$fileName").takeIf { it.exists() }?.inputStream()

    private fun replaySession(events: List<TelemetryEvent>): List<EkfState> {
        val history = mutableListOf<EkfState>()
        val sortedEvents = events.sortedBy { it.timestampMs }

        for (event in sortedEvents) {
            when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    val state = ekf.onGpsFix(
                        lat = event.latitude,
                        lon = event.longitude,
                        accuracyMeters = event.accuracyMeters,
                        activeIntervalMs = event.activeIntervalMs
                    )
                    history.add(state)
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    ekf.onStepDetected(event.headingRawRad, event.timestampMs)
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    val state = ekf.predict(event.dtSec)
                    history.add(state)
                }
            }
        }
        return history
    }

    @Test
    fun `ekf initializes position on first GPS fix`() {
        val initialLat = 53.349805
        val initialLon = -6.260310

        assertFalse("EKF should be uninitialized before initial GPS fix", ekf.isInitialized)

        val events = listOf(
            TelemetryEvent.GpsFixEvent(
                timestampMs = 1000L,
                latitude = initialLat,
                longitude = initialLon,
                accuracyMeters = 5.0f,
                activeIntervalMs = 1000L
            )
        )

        val history = replaySession(events)
        val finalState = history.last()

        assertTrue("EKF must be initialized after GPS fix", finalState.isInitializedWithGps)
        val expectedMetric = CoordinateUtils.latLonToMetric(initialLat, initialLon)
        assertEquals(expectedMetric.x, finalState.position.value.x, 0.001)
        assertEquals(expectedMetric.y, finalState.position.value.y, 0.001)
    }

    @Test
    fun `ekf remains stable during synthetic walk playback`() {
        val events = generateSyntheticStraightWalkEvents(
            startLat = 53.349805,
            startLon = -6.260310,
            durationSec = 60,
            stepIntervalMs = 600L,
            gpsIntervalMs = 5000L
        )

        val history = replaySession(events)
        val finalState = history.last()

        assertTrue("EKF must remain initialized throughout playback", finalState.isInitializedWithGps)

        val stride = finalState.strideLengthMeters.value
        assertTrue("Stride length ($stride m) must remain within valid physical bounds [0.4, 1.3]", stride in 0.4..1.3)

        assertFalse("Position variance cannot be NaN", finalState.position.variance.isNaN())
        assertFalse("Position variance cannot be infinite", finalState.position.variance.isInfinite())
        assertTrue("Position variance must stay bounded", finalState.position.variance < 100.0)

        assertTrue("Step count should be tracked during playback", finalState.stepCount > 0)
        assertTrue("GPS fix count should be accumulated", finalState.gpsFixCount > 0)
    }

    @Test
    fun `ekf replays csv telemetry session deterministically`() {
        val recorder = TelemetryRecorder()

        val startLat = 53.349805
        val startLon = -6.260310
        recorder.recordGpsFix(startLat, startLon, 3.0f, 1000L, 1000L)
        recorder.recordStep(PI / 2.0, 1600L)
        recorder.recordTick(1.0, 2000L)
        recorder.recordStep(PI / 2.0, 2200L)
        recorder.recordGpsFix(startLat + 0.00001, startLon + 0.00001, 3.0f, 1000L, 3000L)

        val csvString = recorder.toCsvString()
        val loadedEvents = TelemetryRecorder.fromCsvString(csvString)

        val ekf1 = PdrEkfEngine()
        val ekf2 = PdrEkfEngine()

        var lastState1: EkfState? = null
        for (event in recorder.getEvents()) {
            when (event) {
                is TelemetryEvent.GpsFixEvent -> lastState1 = ekf1.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                is TelemetryEvent.StepDetectedEvent -> ekf1.onStepDetected(event.headingRawRad, event.timestampMs)
                is TelemetryEvent.PredictionTickEvent -> lastState1 = ekf1.predict(event.dtSec)
            }
        }

        var lastState2: EkfState? = null
        for (event in loadedEvents) {
            when (event) {
                is TelemetryEvent.GpsFixEvent -> lastState2 = ekf2.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                is TelemetryEvent.StepDetectedEvent -> ekf2.onStepDetected(event.headingRawRad, event.timestampMs)
                is TelemetryEvent.PredictionTickEvent -> lastState2 = ekf2.predict(event.dtSec)
            }
        }

        assertNotNull(lastState1)
        assertNotNull(lastState2)
        assertEquals(lastState1!!.position.value.x, lastState2!!.position.value.x, 1e-6)
        assertEquals(lastState1.position.value.y, lastState2.position.value.y, 1e-6)
        assertEquals(lastState1.strideLengthMeters.value, lastState2.strideLengthMeters.value, 1e-6)
    }

    @Test
    fun `ekf replays telemetry from test resources`() {
        val stream = loadResourceStream(recordedFileName)
            ?: return // Skip gracefully if resource is not present

        val csvText = stream.bufferedReader().use { it.readText() }
        val events = TelemetryRecorder.fromCsvString(csvText)

        val startTime = System.currentTimeMillis()
        val history = replaySession(events)
        val elapsedMs = System.currentTimeMillis() - startTime
        val finalState = history.last()

        println("Replayed ${events.size} telemetry events from $recordedFileName in ${elapsedMs} ms.")
        println("Final State -> Step Count: ${finalState.stepCount}, GPS Fixes: ${finalState.gpsFixCount}")
        println("Final Position -> E: %.2f m, N: %.2f m, Stride: %.2f m, Bias: %.3f rad".format(
            finalState.position.value.x,
            finalState.position.value.y,
            finalState.strideLengthMeters.value,
            finalState.compassBiasRad.value
        ))

        assertTrue("EKF must be initialized after replay", finalState.isInitializedWithGps)
        assertFalse("Position variance cannot be NaN", finalState.position.variance.isNaN())
        assertTrue("Position variance must remain bounded", finalState.position.variance < 100.0)
    }

    @Test
    fun `replayed EKF state matches live recorded EKF state`() {
        val stream = loadResourceStream(recordedFileName)
            ?: return // Skip gracefully if file is not present

        val csvText = stream.bufferedReader().use { it.readText() }
        val loadedEvents = TelemetryRecorder.fromCsvString(csvText)

        val replayEkf = PdrEkfEngine()
        var comparedCount = 0

        for (event in loadedEvents) {
            val currentState = when (event) {
                is TelemetryEvent.GpsFixEvent -> replayEkf.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                is TelemetryEvent.StepDetectedEvent -> {
                    replayEkf.onStepDetected(event.headingRawRad, event.timestampMs)
                    replayEkf.getEkfState()
                }
                is TelemetryEvent.PredictionTickEvent -> replayEkf.predict(event.dtSec)
            }

            event.recordedEkfState?.let { liveState ->
                comparedCount++
                assertEquals("Position X mismatch at t=${event.timestampMs}", liveState.position.value.x % 100000.0, currentState.position.value.x % 100000.0, 30.0)
                assertEquals("Position Y mismatch at t=${event.timestampMs}", liveState.position.value.y % 100000.0, currentState.position.value.y % 100000.0, 30.0)
                assertEquals("Stride Length mismatch at t=${event.timestampMs}", liveState.strideLengthMeters.value, currentState.strideLengthMeters.value, 0.5)
                val biasDiff = atan2(sin(liveState.compassBiasRad.value - currentState.compassBiasRad.value), cos(liveState.compassBiasRad.value - currentState.compassBiasRad.value))
                assertEquals("Compass Bias mismatch at t=${event.timestampMs}", 0.0, biasDiff, 1.0)
            }
        }
        println("Verified $comparedCount recorded live EKF state snapshots against replayed EKF state!")
    }

    private fun generateSyntheticStraightWalkEvents(
        startLat: Double,
        startLon: Double,
        durationSec: Int,
        stepIntervalMs: Long,
        gpsIntervalMs: Long
    ): List<TelemetryEvent> {
        val events = mutableListOf<TelemetryEvent>()
        var currentTimeMs = 10000L
        val endTimeMs = currentTimeMs + (durationSec * 1000L)

        val headingEastRad = PI / 2.0
        val startMetric = CoordinateUtils.latLonToMetric(startLat, startLon)

        var lastStepMs = currentTimeMs
        var lastGpsMs = currentTimeMs
        var lastTickMs = currentTimeMs

        events.add(TelemetryEvent.GpsFixEvent(currentTimeMs, startLat, startLon, 4.0f, gpsIntervalMs))

        var distanceWalkedMeters = 0.0
        val strideLengthMeters = 0.75

        while (currentTimeMs < endTimeMs) {
            currentTimeMs += 100L

            if (currentTimeMs - lastStepMs >= stepIntervalMs) {
                lastStepMs = currentTimeMs
                distanceWalkedMeters += strideLengthMeters
                events.add(TelemetryEvent.StepDetectedEvent(currentTimeMs, headingEastRad))
            }

            if (currentTimeMs - lastTickMs >= 1000L) {
                lastTickMs = currentTimeMs
                events.add(TelemetryEvent.PredictionTickEvent(currentTimeMs, 1.0))
            }

            if (currentTimeMs - lastGpsMs >= gpsIntervalMs) {
                lastGpsMs = currentTimeMs
                val currentMetric = Location(x = startMetric.x + distanceWalkedMeters, y = startMetric.y, altitude = startMetric.altitude)
                val (lat, lon) = CoordinateUtils.metricToLatLon(currentMetric)
                events.add(TelemetryEvent.GpsFixEvent(currentTimeMs, lat, lon, 4.0f, gpsIntervalMs))
            }
        }

        return events
    }

    @Test
    fun `inspect telemetry playback and compass bias convergence`() {
        // Try loading from test resources classpath first
        val resourceStream = javaClass.classLoader?.getResourceAsStream("pps_tlmtry_20261018_123410.csv")
            ?: javaClass.classLoader?.getResourceAsStream("telemetry/pps_tlmtry_20261018_123410.csv")

        val csvText = if (resourceStream != null) {
            resourceStream.bufferedReader().use { it.readText() }
        } else {
            // Fallback to absolute path if placed in app/ root
            val file = File("pps_tlmtry_20261018_123410.csv")
            org.junit.Assert.assertTrue("Telemetry file not found at ${file.absolutePath}", file.exists())
            file.readText()
        }

        val events = TelemetryRecorder.fromCsvString(csvText)
        org.junit.Assert.assertTrue("No telemetry events parsed!", events.isNotEmpty())

        val debugEkf = PdrEkfEngine()
        var stepCount = 0

        for ((index, event) in events.withIndex()) {
            when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    debugEkf.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    debugEkf.onStepDetected(event.headingRawRad, event.timestampMs)
                    stepCount++
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    debugEkf.predict(event.dtSec)
                }
            }

            if (index % 500 == 0) {
                val state = debugEkf.getEkfState()
                println("EVENT_CHECKPOINT_$index -> Pos: (%.1f, %.1f), Bias: %.2f°, Speed: %.2f m/s".format(
                    state.position.value.x,
                    state.position.value.y,
                    Math.toDegrees(state.compassBiasRad.value),
                    state.speedMps.value
                ))
            }
        }
        println("REPLAY_COMPLETE -> Replayed $stepCount steps successfully.")
        org.junit.Assert.assertTrue("Replayed 0 steps!", stepCount > 0)
    }
}
