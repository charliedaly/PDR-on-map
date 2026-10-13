package com.example.pdrandroutetest.data

import kotlin.math.hypot

data class Vector2D(
    val x: Double = 0.0,
    val y: Double = 0.0
)

data class Estimate<T>(
    val value: T,
    val variance: Double
)

data class EkfState(
    val timestampMs: Long = System.currentTimeMillis(),
    val position: Estimate<Vector2D> = Estimate(Vector2D(0.0, 0.0), 1.0),
    val velocity: Estimate<Vector2D> = Estimate(Vector2D(0.0, 0.0), 0.1),
    val bearingRad: Estimate<Double> = Estimate(0.0, 0.05),
    val compassBiasRad: Estimate<Double> = Estimate(0.0, 0.05),
    val strideLengthMeters: Estimate<Double> = Estimate(0.75, 0.01),
    val stepCount: Int = 0,
    val gpsFixCount: Int = 0,
    val activeGpsIntervalMs: Long = 1000L,
    val isInitializedWithGps: Boolean = false
) {
    val speedMps: Estimate<Double>
        get() {
            val speedVal = hypot(velocity.value.x, velocity.value.y)
            return Estimate(speedVal, velocity.variance)
        }
}
