package com.example.irishhike.playback

import com.example.irishhike.data.EkfState

sealed class TrackPlaybackEvent {
    abstract val timestampMs: Long

    data class GpsPointEvent(
        override val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val activeIntervalMs: Long = 1000L,
        val recordedEkfState: EkfState? = null
    ) : TrackPlaybackEvent()

    data class PdrStepEvent(
        override val timestampMs: Long,
        val headingRawRad: Double,
        val recordedEkfState: EkfState? = null
    ) : TrackPlaybackEvent()

    data class TimeTickEvent(
        override val timestampMs: Long,
        val dtSec: Double,
        val recordedEkfState: EkfState? = null
    ) : TrackPlaybackEvent()
}
