package com.example.irishhike.ekf

import com.example.irishhike.data.EkfState
import com.example.irishhike.data.Estimate
import com.example.irishhike.data.Vector2D
import com.example.irishhike.util.CoordinateUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class EkfConfig(
    val initialPosVariance: Double = 25.0,
    val minStrideMeters: Double = 0.50,
    val maxStrideMeters: Double = 1.20,
    val defaultStrideMeters: Double = 0.75
)

/**
 * Standard, textbook GNSS/PDR Fusion Engine.
 *
 * - Eliminates ad-hoc GPS position differencing and fake compass bias corruption.
 * - Integrates PDR steps strictly along the true smoothed device heading.
 * - Updates position via textbook Kalman innovation weighting based on real GPS accuracy variance.
 */
class PdrEkfEngine(val config: EkfConfig = EkfConfig()) {

    // State: [x (Easting m), y (Northing m), vx (m/s), vy (m/s)]
    private var posX: Double = 0.0
    private var posY: Double = 0.0
    private var velX: Double = 0.0
    private var velY: Double = 0.0

    // Variances
    private var varPosX: Double = config.initialPosVariance
    private var varPosY: Double = config.initialPosVariance

    // Stride length (meters)
    private var strideMeters: Double = config.defaultStrideMeters

    private var isInitializedWithGps = false
    val isInitialized: Boolean get() = isInitializedWithGps

    private var lastStepTimestampMs: Long = 0L
    private var lastGpsTimestampMs: Long = 0L
    private var currentHeadingRad: Double = 0.0
    private var isHeadingInitialized = false

    private var stepCount: Int = 0
    private var gpsFixCount: Int = 0

    val stateX: Double get() = posX
    val stateY: Double get() = posY
    val stateVx: Double get() = velX
    val stateVy: Double get() = velY
    val strideLength: Double get() = strideMeters
    val compassBias: Double get() = 0.0

    init {
        reset()
    }

    fun reset() {
        posX = 0.0
        posY = 0.0
        velX = 0.0
        velY = 0.0
        varPosX = config.initialPosVariance
        varPosY = config.initialPosVariance
        strideMeters = config.defaultStrideMeters

        isInitializedWithGps = false
        lastStepTimestampMs = 0L
        lastGpsTimestampMs = 0L
        currentHeadingRad = 0.0
        isHeadingInitialized = false
        stepCount = 0
        gpsFixCount = 0
    }

    @Synchronized
    fun setHeadingRad(headingRad: Double) {
        if (headingRad.isNaN() || headingRad.isInfinite()) return
        val normalized = normalizeAngleRad(headingRad)
        if (!isHeadingInitialized) {
            isHeadingInitialized = true
            currentHeadingRad = normalized
        } else {
            val diff = normalizeAngleRad(normalized - currentHeadingRad)
            // Circular exponential moving average: smooths out arm sway while tracking real turns
            currentHeadingRad = normalizeAngleRad(currentHeadingRad + 0.20 * diff)
        }
    }

    @Synchronized
    fun predict(dtSec: Double = 1.0): EkfState {
        if (!isInitializedWithGps) {
            return getEkfState()
        }

        val clampedDt = dtSec.coerceIn(0.1, 2.0)
        val now = System.currentTimeMillis()
        val timeSinceStepSec = if (lastStepTimestampMs > 0L) {
            (now - lastStepTimestampMs) / 1000.0
        } else {
            2.0
        }

        // Velocity decay if stationary
        if (timeSinceStepSec > 1.2) {
            val decay = exp(-3.0 * clampedDt)
            velX *= decay
            velY *= decay
        }

        // PDR variance growth over time when no fixes arrive
        varPosX += 0.05 * clampedDt
        varPosY += 0.05 * clampedDt

        return getEkfState()
    }

    /**
     * Standard PDR Step Propagation.
     * Advances position strictly along the current smoothed heading.
     * Guaranteed straight trajectory when walking straight.
     */
    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return
        if (headingRawRad.isNaN() || headingRawRad.isInfinite()) return

        stepCount++
        setHeadingRad(headingRawRad)

        val dtStepSec = if (lastStepTimestampMs > 0L) {
            ((timestampMs - lastStepTimestampMs) / 1000.0).coerceIn(0.25, 2.0)
        } else {
            0.65
        }
        lastStepTimestampMs = timestampMs

        val dX = strideMeters * sin(currentHeadingRad)
        val dY = strideMeters * cos(currentHeadingRad)

        // Update position dead-reckoning
        posX += dX
        posY += dY

        // Instantaneous pedestrian velocity
        velX = dX / dtStepSec
        velY = dY / dtStepSec

        // Covariance growth per step
        varPosX += 0.04
        varPosY += 0.04
    }

    /**
     * Standard Linear Kalman Measurement Update for GPS position.
     * Uses real measurement variance R = accuracy^2.
     * High accuracy pulls smoothly; canopy noise is rejected by high R.
     */
    @Synchronized
    fun onGpsFix(
        lat: Double,
        lon: Double,
        accuracyMeters: Float,
        activeIntervalMs: Long = 1000L,
        timestampMs: Long = System.currentTimeMillis()
    ): EkfState {
        gpsFixCount++
        lastGpsTimestampMs = timestampMs

        val gpsLocation = CoordinateUtils.latLonToMetric(lat, lon)

        // First fix initialization
        if (!isInitializedWithGps) {
            posX = gpsLocation.x
            posY = gpsLocation.y
            velX = 0.0
            velY = 0.0
            varPosX = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 64.0)
            varPosY = varPosX
            isInitializedWithGps = true
            return getEkfState(activeIntervalMs)
        }

        // Measurement noise variance R = sigma^2
        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(4.0)

        // Standard Kalman Innovation Update for X
        val residualX = gpsLocation.x - posX
        val sX = varPosX + rVar
        val kX = (varPosX / sX).coerceIn(0.0, 0.90)
        posX += kX * residualX
        varPosX = (1.0 - kX) * varPosX

        // Standard Kalman Innovation Update for Y
        val residualY = gpsLocation.y - posY
        val sY = varPosY + rVar
        val kY = (varPosY / sY).coerceIn(0.0, 0.90)
        posY += kY * residualY
        varPosY = (1.0 - kY) * varPosY

        // Clamp variances to reasonable bounds
        varPosX = varPosX.coerceIn(1.0, 100.0)
        varPosY = varPosY.coerceIn(1.0, 100.0)

        return getEkfState(activeIntervalMs)
    }

    fun getEkfState(activeIntervalMs: Long = 1000L): EkfState {
        val posVarCombined = sqrt(varPosX * varPosX + varPosY * varPosY)
        val speed = hypot(velX, velY)

        val bearingVar = when {
            speed < 0.3 -> 0.05
            speed < 0.8 -> 0.02
            else -> 0.008
        }

        return EkfState(
            timestampMs = System.currentTimeMillis(),
            position = Estimate(Vector2D(posX, posY), posVarCombined),
            velocity = Estimate(Vector2D(velX, velY), 0.05),
            bearingRad = Estimate(currentHeadingRad, bearingVar),
            compassBiasRad = Estimate(0.0, 0.0),
            strideLengthMeters = Estimate(strideMeters, 0.01),
            stepCount = stepCount,
            gpsFixCount = gpsFixCount,
            activeGpsIntervalMs = activeIntervalMs,
            isInitializedWithGps = isInitializedWithGps
        )
    }

    private fun normalizeAngleRad(angleRad: Double): Double {
        return atan2(sin(angleRad), cos(angleRad))
    }
}