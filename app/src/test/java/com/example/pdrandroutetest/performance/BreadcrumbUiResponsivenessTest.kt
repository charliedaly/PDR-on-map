package com.example.pdrandroutetest.performance

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Location
import com.example.pdrandroutetest.repository.HikeRepository
import com.example.pdrandroutetest.ui.DiagnosticViewModel
import com.example.pdrandroutetest.ui.ViewportTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureNanoTime

@OptIn(ExperimentalCoroutinesApi::class)
class BreadcrumbUiResponsivenessTest {

    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        HikeRepository.clearBreadcrumbs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        HikeRepository.clearBreadcrumbs()
    }

    @Test
    fun `viewModel breadcrumb state propagation responsiveness under single update`() = runBlocking {
        val viewModel = DiagnosticViewModel()

        val sampleBreadcrumb = Breadcrumb(
            id = 101L,
            location = Location(120.5, 340.8),
            timestampMs = 5000L,
            isGpsFix = false
        )

        val startNano = System.nanoTime()
        HikeRepository.emitBreadcrumb(sampleBreadcrumb)

        withTimeout(1000L) {
            while (viewModel.breadcrumbs.value.isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }
        val elapsedNano = System.nanoTime() - startNano
        val elapsedMs = elapsedNano / 1_000_000.0

        println("Single Breadcrumb ViewModel State Propagation Latency: %.3f ms".format(elapsedMs))
        assertEquals(1, viewModel.breadcrumbs.value.size)
        assertEquals(101L, viewModel.breadcrumbs.value.first().id)
        assertTrue("ViewModel breadcrumb update latency ($elapsedMs ms) exceeded 15.0 ms limit", elapsedMs < 15.0)
    }

    @Test
    fun `viewModel breadcrumb state propagation latency under rapid stream burst`() = runBlocking {
        val viewModel = DiagnosticViewModel()
        val burstCount = 200
        var totalMs = 0.0
        var maxMs = 0.0

        repeat(burstCount) { i ->
            val breadcrumb = Breadcrumb(
                id = i.toLong(),
                location = Location(100.0 + i * 0.5, 200.0 + i * 0.5),
                timestampMs = 1000L + i * 100L,
                isGpsFix = (i % 5 == 0)
            )

            val startNano = System.nanoTime()
            HikeRepository.emitBreadcrumb(breadcrumb)

            val targetSize = i + 1
            while (viewModel.breadcrumbs.value.size < targetSize) {
                kotlinx.coroutines.yield()
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0

            totalMs += elapsedMs
            if (elapsedMs > maxMs) {
                maxMs = elapsedMs
            }
        }

        val avgMs = totalMs / burstCount
        println("Rapid Burst Breadcrumb ViewModel Updates -> Avg: %.4f ms, Max Spike: %.3f ms across $burstCount items".format(avgMs, maxMs))

        assertEquals(burstCount, viewModel.breadcrumbs.value.size)
        assertTrue("Average breadcrumb state update latency ($avgMs ms) exceeded 1.0 ms", avgMs < 1.0)
        assertTrue("Max breadcrumb state update latency spike ($maxMs ms) exceeded 10.0 ms", maxMs < 10.0)
    }

    @Test
    fun `map viewport auto scaling calculation latency scales efficiently for 5000 breadcrumbs`() {
        val viewportTracker = ViewportTracker()
        val breadcrumbs = List(5000) { i ->
            Breadcrumb(
                id = i.toLong(),
                location = Location(i * 0.2, i * 0.3),
                timestampMs = 1000L + i * 500L,
                isGpsFix = (i % 10 == 0)
            )
        }
        val emptyEkfState = EkfState()

        repeat(5) {
            viewportTracker.updateAll(route = null, breadcrumbs = breadcrumbs, activeEkfState = emptyEkfState)
        }

        val iterations = 100
        val totalNano = measureNanoTime {
            repeat(iterations) {
                viewportTracker.updateAll(route = null, breadcrumbs = breadcrumbs, activeEkfState = emptyEkfState)
            }
        }

        val avgMs = (totalNano / iterations) / 1_000_000.0
        println("Viewport Auto-Scaling Recalculation Latency for 5000 Breadcrumbs: %.4f ms".format(avgMs))

        assertTrue("Viewport auto-scaling for 5,000 breadcrumbs ($avgMs ms) exceeded 2.0 ms UI budget", avgMs < 2.0)
    }

    @Test
    fun `breadcrumb filtering and canvas offset projection throughput within frame budget`() {
        val breadcrumbs = List(1000) { i ->
            Breadcrumb(
                id = i.toLong(),
                location = Location(100.0 + (i % 50) * 2.0, 200.0 + (i / 50) * 2.0),
                timestampMs = 1000L + i * 200L,
                isGpsFix = (i % 20 == 0)
            )
        }

        val baseScale = 5.0f
        val zoomScale = 1.0f
        val panX = 0f
        val panY = 0f
        val originCx = 500f
        val originCy = 500f
        val centerX = 150.0
        val centerY = 250.0

        val iterations = 50
        val totalNano = measureNanoTime {
            repeat(iterations) {
                val valid = breadcrumbs.filter { it.location.x != 0.0 || it.location.y != 0.0 }

                val offsets = ArrayList<Pair<Float, Float>>(valid.size)
                for (bc in valid) {
                    val rawCx = originCx + ((bc.location.x - centerX) * baseScale).toFloat()
                    val rawCy = originCy - ((bc.location.y - centerY) * baseScale).toFloat()
                    val zoomedX = originCx + (rawCx - originCx) * zoomScale + panX
                    val zoomedY = originCy + (rawCy - originCy) * zoomScale + panY
                    offsets.add(zoomedX to zoomedY)
                }
            }
        }

        val avgMs = (totalNano / iterations) / 1_000_000.0
        println("Breadcrumb filtering & canvas projection latency for 1000 points: %.4f ms".format(avgMs))

        assertTrue("Breadcrumb UI projection latency ($avgMs ms) exceeded 3.0 ms frame rendering budget", avgMs < 3.0)
    }

    @Test
    fun `end to end breadcrumb update pipeline latency stays under frame budget`() = runBlocking {
        val viewModel = DiagnosticViewModel()
        val viewportTracker = ViewportTracker()
        val emptyEkfState = EkfState()

        val sampleBreadcrumb = Breadcrumb(
            id = 999L,
            location = Location(500.0, 600.0),
            timestampMs = 10000L,
            isGpsFix = true
        )

        val startNano = System.nanoTime()

        HikeRepository.emitBreadcrumb(sampleBreadcrumb)

        while (viewModel.breadcrumbs.value.isEmpty()) {
            kotlinx.coroutines.yield()
        }

        viewportTracker.updateAll(
            route = null,
            breadcrumbs = viewModel.breadcrumbs.value,
            activeEkfState = emptyEkfState
        )

        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
        println("End-to-End Breadcrumb Pipeline Latency (Repo -> ViewModel -> Map Viewport): %.3f ms".format(elapsedMs))

        assertTrue("End-to-end breadcrumb update pipeline latency ($elapsedMs ms) exceeded 10.0 ms", elapsedMs < 10.0)
    }
}
