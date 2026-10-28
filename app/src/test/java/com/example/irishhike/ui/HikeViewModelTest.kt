package com.example.irishhike.ui

import com.example.irishhike.playback.TrackPlaybackEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI

@OptIn(ExperimentalCoroutinesApi::class)
class HikeViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var viewModel: HikeViewModel

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        HikeRepository.clearBreadcrumbs()
        HikeRepository.trackPlaybackEngine.stop()
        viewModel = HikeViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        HikeRepository.clearBreadcrumbs()
        HikeRepository.trackPlaybackEngine.stop()
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
            TrackPlaybackEvent.GpsPointEvent(1000L, 53.349805, -6.260310, 4.0f, 1000L),
            TrackPlaybackEvent.PdrStepEvent(1600L, PI / 2.0),
            TrackPlaybackEvent.GpsPointEvent(3000L, 53.349810, -6.260305, 4.0f, 1000L)
        )
        HikeRepository.trackPlaybackEngine.loadEvents(sampleEvents)

        assertEquals(0, viewModel.playbackProgress.value.currentIndex)

        viewModel.stepForwardPlayback()

        assertEquals(1, viewModel.playbackProgress.value.currentIndex)

        viewModel.stepForwardPlayback()

        assertEquals(2, viewModel.playbackProgress.value.currentIndex)

        viewModel.stepBackPlayback()

        assertEquals(1, viewModel.playbackProgress.value.currentIndex)
    }

    @Test
    fun `startPlayback and stopPlayback update playback engine state`() = runTest {
        val sampleEvents = listOf(
            TrackPlaybackEvent.GpsPointEvent(1000L, 53.349805, -6.260310, 4.0f, 1000L)
        )
        HikeRepository.trackPlaybackEngine.loadEvents(sampleEvents)

        viewModel.startPlayback()
        assertTrue(viewModel.isPlaybackPlaying.value)

        viewModel.stopPlayback()
        assertFalse(viewModel.isPlaybackPlaying.value)
        assertEquals(0, viewModel.playbackProgress.value.currentIndex)
    }

    @Test
    fun `grid visibility setters update ViewModel state flows`() {
        assertFalse(viewModel.mapConfig.value.show1kmGrid)
        assertFalse(viewModel.mapConfig.value.show100mGrid)

        viewModel.setShow1kmGrid(true)
        assertTrue(viewModel.mapConfig.value.show1kmGrid)

        viewModel.setShow100mGrid(true)
        assertTrue(viewModel.mapConfig.value.show100mGrid)
    }
}
