package com.example.irishhike.config

enum class PhoneMount {
    HANDHELD_OR_PACK_TOP,
    SHOULDER_STRAP_SCREEN_FORWARD,
    SHOULDER_STRAP_CAMERA_FORWARD
}

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
    val showAudioMessages: Boolean = false,
    val arriveThresholdMeters: Double = 10.0,
    val approachThresholdMeters: Double = 40.0,
    val offCourseMinSpeedMps: Double = 0.6,
    val offCourseMinAngleDeg: Double = 20.0,
    val offCourseMaxCooldownSec: Long = 25L,
    val offCourseSevereAngleDeg: Double = 90.0,
    val offCourseMinCooldownSec: Long = 4L,
    val offCourseHeadingDevDeg: Double = 20.0,
    val driftCoolOffSec: Long = 4L,
    val steadyCoolOffSec: Long = 25L,
    val speechRate: Float = 1.0f,
    val phoneMount: PhoneMount = PhoneMount.HANDHELD_OR_PACK_TOP
)

data class MapConfig(
    val isLegView: Boolean = false,
    val show1kmGrid: Boolean = false,
    val show100mGrid: Boolean = false,
    val showWaypointNames: Boolean = true,
    val showWaypointHeights: Boolean = true,
    val showWaypointTimes: Boolean = true, // Shows arrival time (HH:mm) for reached/passed waypoints
    val gpsPollingIntervalMs: Long = 1000L,
    val showTelemetryViewport: Boolean = true,
    val showOsmBackground: Boolean = false
)