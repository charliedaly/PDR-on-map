package com.example.pdrandroutetest.ui

import com.example.pdrandroutetest.repository.HikeRepository
import com.example.pdrandroutetest.telemetry.TelemetryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var viewModel: DiagnosticViewModel

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        HikeRepository.clearBreadcrumbs()
        HikeRepository.telemetryPlaybackEngine.stop()
        viewModel = DiagnosticViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        HikeRepository.clearBreadcrumbs()
        HikeRepository.telemetryPlaybackEngine.stop()
    }

    @Test
    fun `loadSampleRoute populates activeRoute state`() {
        viewModel.loadSampleRoute()

        val route = viewModel.activeRoute.value
        assertNotNull(route)
        assertEquals("Sample Irish Route", route?.name)
        assertTrue((route?.legs?.size ?: 0) > 0)
    }

    @Test
    fun `playback speed updates ViewModel state`() {
        viewModel.setPlaybackSpeed(5.0)

        assertEquals(5.0, viewModel.playbackSpeed.value, 0.001)
    }

    @Test
    fun `playback step forward and step back propagate through ViewModel`() = runTest {
        val sampleEvents = listOf(
            TelemetryEvent.GpsFixEvent(1000L, 53.349805, -6.260310, 4.0f, 1000L),
            TelemetryEvent.StepDetectedEvent(1600L, PI / 2.0),
            TelemetryEvent.GpsFixEvent(3000L, 53.349810, -6.260305, 4.0f, 1000L)
        )
        HikeRepository.telemetryPlaybackEngine.loadEvents(sampleEvents)

        assertEquals(0, viewModel.playbackProgress.value.currentIndex)

        viewModel.stepForwardPlayback()

        assertEquals(1, viewModel.playbackProgress.value.currentIndex)
        assertEquals(1, viewModel.breadcrumbs.value.size)

        viewModel.stepForwardPlayback()

        assertEquals(2, viewModel.playbackProgress.value.currentIndex)

        viewModel.stepBackPlayback()

        assertEquals(1, viewModel.playbackProgress.value.currentIndex)
    }

    @Test
    fun `startPlayback and stopPlayback update playback engine state`() = runTest {
        val sampleEvents = listOf(
            TelemetryEvent.GpsFixEvent(1000L, 53.349805, -6.260310, 4.0f, 1000L)
        )
        HikeRepository.telemetryPlaybackEngine.loadEvents(sampleEvents)

        viewModel.startPlayback()
        assertTrue(viewModel.isPlaybackPlaying.value)

        viewModel.stopPlayback()
        assertFalse(viewModel.isPlaybackPlaying.value)
        assertEquals(0, viewModel.playbackProgress.value.currentIndex)
    }

    @Test
    fun `grid visibility setters update ViewModel state flows`() {
        assertFalse(viewModel.show1kmGrid.value)
        assertFalse(viewModel.show100mGrid.value)

        viewModel.setShow1kmGrid(true)
        assertTrue(viewModel.show1kmGrid.value)

        viewModel.setShow100mGrid(true)
        assertTrue(viewModel.show100mGrid.value)
    }
}
