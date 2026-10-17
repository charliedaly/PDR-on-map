package com.example.pdrandroutetest.config

data class HikeConfig(
    val arriveThresholdMeters: Double = 10.0,
    val approachThresholdMeters: Double = 40.0,
    val offCourseHeadingDevDeg: Double = 10.0,
    val offCourseMinSpeedMps: Double = 0.4,
    val driftCoolOffSec: Long = 4L,
    val steadyCoolOffSec: Long = 6L
)

data class MapConfig(
    val isLegView: Boolean = false,
    val show1kmGrid: Boolean = false,
    val show100mGrid: Boolean = false,
    val gpsPollingIntervalMs: Long = 1000L,
    val showTelemetryViewport: Boolean = true
)
