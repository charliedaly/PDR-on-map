package com.example.irishhike.telemetry

import com.example.irishhike.repository.HikeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.PI

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryPlaybackEngineTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var engine: TelemetryPlaybackEngine

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        HikeRepository.clearBreadcrumbs()
        engine = TelemetryPlaybackEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        HikeRepository.clearBreadcrumbs()
    }

    private fun createSampleEvents(): List<TelemetryEvent> {
        return listOf(
            TelemetryEvent.GpsFixEvent(1000L, 53.349805, -6.260310, 4.0f, 1000L),
            TelemetryEvent.StepDetectedEvent(1600L, PI / 2.0),
            TelemetryEvent.PredictionTickEvent(2000L, 1.0),
            TelemetryEvent.StepDetectedEvent(2200L, PI / 2.0),
            TelemetryEvent.GpsFixEvent(3000L, 53.349810, -6.260305, 4.0f, 1000L)
        )
    }

    @Test
    fun `stepForward advances single event and updates progress`() = runTest {
        val events = createSampleEvents()
        engine.loadEvents(events)

        assertEquals(0, engine.progress.value.currentIndex)
        assertEquals(5, engine.progress.value.totalEvents)

        engine.stepForward()

        assertEquals(1, engine.progress.value.currentIndex)
        assertEquals(1000L, engine.progress.value.currentTimestampMs)
        assertEquals(1, HikeRepository.getBreadcrumbHistory().size)

        engine.stepForward()

        assertEquals(2, engine.progress.value.currentIndex)
        assertEquals(1600L, engine.progress.value.currentTimestampMs)
    }

    @Test
    fun `stepBack rewinds single event and updates breadcrumbs`() = runTest {
        val events = createSampleEvents()
        engine.loadEvents(events)

        engine.stepForward()
        engine.stepForward()
        engine.stepForward()

        assertEquals(3, engine.progress.value.currentIndex)

        engine.stepBack()

        assertEquals(2, engine.progress.value.currentIndex)
        assertEquals(1600L, engine.progress.value.currentTimestampMs)
    }

    @Test
    fun `stepForward and stepBack at boundaries stay within limits`() = runTest {
        val events = createSampleEvents()
        engine.loadEvents(events)

        // Stepping back at 0 does nothing
        engine.stepBack()
        assertEquals(0, engine.progress.value.currentIndex)

        // Step forward 5 times to reach end
        repeat(5) {
            engine.stepForward()
        }
        assertEquals(5, engine.progress.value.currentIndex)

        // Stepping forward at end does nothing
        engine.stepForward()
        assertEquals(5, engine.progress.value.currentIndex)
    }
}
