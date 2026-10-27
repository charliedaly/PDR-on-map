package com.example.irishhike.performance

import com.example.irishhike.ekf.PdrEkfEngine
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Location
import com.example.irishhike.repository.HikeRepository
import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureNanoTime

@OptIn(ExperimentalCoroutinesApi::class)
class EkfLatencyBenchmarkTest {

    private lateinit var ekf: PdrEkfEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ekf = PdrEkfEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `single EKF step processing latency stays under 0_5 ms`() {
        // Warm up JVM JIT compiler
        repeat(100) {
            ekf.onStepDetected(0.5, 1000L + it)
        }

        val iterations = 1000
        val totalNano = measureNanoTime {
            repeat(iterations) { i ->
                ekf.onStepDetected(0.5, 2000L + i * 500L)
            }
        }

        val avgNano = totalNano / iterations
        val avgMs = avgNano / 1_000_000.0

        println("Average EKF Step Processing Latency: %.4f ms (%.1f μs)".format(avgMs, avgNano / 1000.0))
        assertTrue("Average EKF step latency ($avgMs ms) exceeded 0.5 ms limit", avgMs < 0.5)
    }

    @Test
    fun `single EKF GPS fix processing latency stays under 1_0 ms`() {
        // Warm up JIT
        repeat(50) {
            ekf.onGpsFix(53.349805, -6.260310, 5.0f, 1000L)
        }

        val iterations = 500
        val totalNano = measureNanoTime {
            repeat(iterations) { i ->
                ekf.onGpsFix(53.349805 + i * 0.00001, -6.260310 + i * 0.00001, 3.0f, 1000L)
            }
        }

        val avgNano = totalNano / iterations
        val avgMs = avgNano / 1_000_000.0

        println("Average EKF GPS Fix Processing Latency: %.4f ms (%.1f μs)".format(avgMs, avgNano / 1000.0))
        assertTrue("Average EKF GPS fix latency ($avgMs ms) exceeded 1.0 ms limit", avgMs < 1.0)
    }

    @Test
    fun `max single event latency spike across full telemetry stream stays under 10 ms`() {
        val fileName = "telemetry/sample_telemetry.csv"
        val stream = javaClass.classLoader?.getResourceAsStream(fileName)
            ?: javaClass.getResourceAsStream("/$fileName")
            ?: return

        val csvText = stream.bufferedReader().use { it.readText() }
        val events = TelemetryRecorder.fromCsvString(csvText)

        var maxLatencyMs = 0.0

        ekf.reset()

        for (event in events) {
            val startNano = System.nanoTime()
            when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    ekf.onGpsFix(event.latitude, event.longitude, event.accuracyMeters, event.activeIntervalMs)
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    ekf.onStepDetected(event.headingRawRad, event.timestampMs)
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    ekf.predict(event.dtSec)
                }
            }
            val elapsedNano = System.nanoTime() - startNano
            val elapsedMs = elapsedNano / 1_000_000.0
            if (elapsedMs > maxLatencyMs) {
                maxLatencyMs = elapsedMs
            }
        }

        println("Max single-event processing latency spike: %.3f ms".format(maxLatencyMs))
        assertTrue("Single-event processing latency spike ($maxLatencyMs ms) exceeded 10.0 ms threshold", maxLatencyMs < 10.0)
    }

    @Test
    fun `asynchronous repository flow emission latency stays under 15 ms`() = runBlocking(Dispatchers.Default) {
        val testState = ekf.onGpsFix(53.349805, -6.260310, 3.0f, 1000L)
        val breadcrumb = Breadcrumb(
            id = 1L,
            location = Location(testState.position.value.x, testState.position.value.y),
            timestampMs = testState.timestampMs,
            isGpsFix = true,
            ekfState = testState
        )

        // Warm up coroutine flow dispatcher
        HikeRepository.emitBreadcrumb(breadcrumb)

        val startNano = System.nanoTime()

        withTimeout(1000L) {
            HikeRepository.emitBreadcrumb(breadcrumb)
            HikeRepository.breadcrumbsFlow.first()
        }

        val elapsedNano = System.nanoTime() - startNano
        val elapsedMs = elapsedNano / 1_000_000.0

        println("Flow Propagation Latency: %.3f ms".format(elapsedMs))
        assertTrue("Flow propagation latency ($elapsedMs ms) exceeded 15.0 ms limit", elapsedMs < 15.0)
    }
}
