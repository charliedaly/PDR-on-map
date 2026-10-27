package com.example.irishhiketest.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.irishhike.ui.ControlPanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun controlPanel_withoutLoadedTrack_showsTrackingControls() {
        var trackingToggled = false

        composeTestRule.setContent {
            ControlPanel(
                isTracking = false,
                onToggleTracking = { trackingToggled = true },
                onLoadTrack = {},
                hasLoadedTrack = false,
                isPlaybackPlaying = false,
                isPlaybackPaused = false,
                playbackSpeed = 1.0,
                playbackCurrentIndex = 0,
                playbackTotalEvents = 0,
                onPlayPlayback = {},
                onPausePlayback = {},
                onStopPlayback = {},
                onStepBackPlayback = {},
                onStepForwardPlayback = {},
                onSpeedSelected = {}
            )
        }

        composeTestRule.onNodeWithText("Live PDR Tracking:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Tracking").assertIsDisplayed()
        composeTestRule.onNodeWithText("Load Track").assertIsDisplayed()

        composeTestRule.onNodeWithText("Start Tracking").performClick()
        assertTrue(trackingToggled)
    }

    @Test
    fun controlPanel_withLoadedTrack_showsPlaybackControlsAndEnablesSteppingAtBounds() {
        var playClicked = false
        var stepForwardClicked = false
        var stopClicked = false

        composeTestRule.setContent {
            ControlPanel(
                isTracking = false,
                onToggleTracking = {},
                onLoadTrack = {},
                hasLoadedTrack = true,
                isPlaybackPlaying = false,
                isPlaybackPaused = false,
                playbackSpeed = 1.0,
                playbackCurrentIndex = 0,
                playbackTotalEvents = 10,
                onPlayPlayback = { playClicked = true },
                onPausePlayback = {},
                onStopPlayback = { stopClicked = true },
                onStepBackPlayback = {},
                onStepForwardPlayback = { stepForwardClicked = true },
                onSpeedSelected = {}
            )
        }

        composeTestRule.onNodeWithText("Track Playback:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Event 0 / 10").assertIsDisplayed()

        // At index 0, Step Back (⏮) should be disabled, Step Forward (⏭) enabled
        composeTestRule.onNodeWithText("⏮").assertIsNotEnabled()
        composeTestRule.onNodeWithText("⏭").assertIsEnabled()

        composeTestRule.onNodeWithText("⏭").performClick()
        assertTrue(stepForwardClicked)

        composeTestRule.onNodeWithText("▶ Play").performClick()
        assertTrue(playClicked)

        composeTestRule.onNodeWithText("⏹ Stop").performClick()
        assertTrue(stopClicked)
    }

    @Test
    fun controlPanel_withLoadedTrack_disablesStepForwardAtEnd() {
        composeTestRule.setContent {
            ControlPanel(
                isTracking = false,
                onToggleTracking = {},
                onLoadTrack = {},
                hasLoadedTrack = true,
                isPlaybackPlaying = false,
                isPlaybackPaused = false,
                playbackSpeed = 1.0,
                playbackCurrentIndex = 10,
                playbackTotalEvents = 10,
                onPlayPlayback = {},
                onPausePlayback = {},
                onStopPlayback = {},
                onStepBackPlayback = {},
                onStepForwardPlayback = {},
                onSpeedSelected = {}
            )
        }

        composeTestRule.onNodeWithText("Event 10 / 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("⏮").assertIsEnabled()
        composeTestRule.onNodeWithText("⏭").assertIsNotEnabled()
    }
}
