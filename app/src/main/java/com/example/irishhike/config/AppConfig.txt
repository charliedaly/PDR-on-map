package com.example.irishhike.config

enum class BreadcrumbColorMode {
    GPS_PDR,
    SPEED,
    STRIDE
}

data class BreadcrumbConfig(
    val showBreadcrumbs: Boolean = true,
    val dotDiameterPx: Float = 3.0f,
    val colorMode: BreadcrumbColorMode = BreadcrumbColorMode.GPS_PDR,
    val speedStartColor: Long = 0xFF00FF00, // Green
    val speedEndColor: Long = 0xFFFF0000,   // Red
    val strideStartColor: Long = 0xFF00E5FF, // Cyan
    val strideEndColor: Long = 0xFFFFD700,   // Gold
    val fadeWithAge: Boolean = false,
    val showAccuracyCircles: Boolean = true,
    val showStationaryStops: Boolean = true
)

data class HikeConfig(
    val audioEnabled: Boolean = true,
    val arriveThresholdMeters: Double = 10.0,
    val approachThresholdMeters: Double = 40.0,
    val offCourseHeadingDevDeg: Double = 10.0,
    val offCourseMinSpeedMps: Double = 0.4,
    val driftCoolOffSec: Long = 4L,
    val steadyCoolOffSec: Long = 6L,
    val speechRate: Float = 1.0f
)

data class MapConfig(
    val isLegView: Boolean = false,
    val show1kmGrid: Boolean = false,
    val show100mGrid: Boolean = false,
    val showWaypointNames: Boolean = true,
    val showWaypointHeights: Boolean = true,
    val gpsPollingIntervalMs: Long = 1000L,
    val showTelemetryViewport: Boolean = true,
    val showOsmBackground: Boolean = false
)
