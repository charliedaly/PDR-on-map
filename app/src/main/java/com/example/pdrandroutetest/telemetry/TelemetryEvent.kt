package com.example.pdrandroutetest.telemetry

import com.example.pdrandroutetest.data.EkfState
import java.io.Serializable

sealed class TelemetryEvent : Serializable {
    abstract val timestampMs: Long
    abstract val recordedEkfState: EkfState?

    data class GpsFixEvent(
        override val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val activeIntervalMs: Long,
        override val recordedEkfState: EkfState? = null
    ) : TelemetryEvent()

    data class StepDetectedEvent(
        override val timestampMs: Long,
        val headingRawRad: Double,
        override val recordedEkfState: EkfState? = null
    ) : TelemetryEvent()

    data class PredictionTickEvent(
        override val timestampMs: Long,
        val dtSec: Double = 1.0,
        override val recordedEkfState: EkfState? = null
    ) : TelemetryEvent()
}
