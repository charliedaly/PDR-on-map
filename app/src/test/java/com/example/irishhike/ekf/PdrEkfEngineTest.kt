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
    val qDiag: DoubleArray = doubleArrayOf(0.01, 0.01, 0.01, 0.01, 0.0001, 0.002),
    val initialPosVariance: Double = 100.0,
    val initialVelVariance: Double = 0.1,
    val initialStrideVariance: Double = 0.02,
    val initialBiasVariance: Double = 0.2,
    val minStrideMeters: Double = 0.4,
    val maxStrideMeters: Double = 1.3
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EkfConfig) return false
        return qDiag.contentEquals(other.qDiag) &&
                initialPosVariance == other.initialPosVariance &&
                initialVelVariance == other.initialVelVariance &&
                initialStrideVariance == other.initialStrideVariance &&
                initialBiasVariance == other.initialBiasVariance &&
                minStrideMeters == other.minStrideMeters &&
                maxStrideMeters == other.maxStrideMeters
    }

    override fun hashCode(): Int {
        var result = qDiag.contentHashCode()
        result = 31 * result + initialPosVariance.hashCode()
        result = 31 * result + initialVelVariance.hashCode()
        result = 31 * result + initialStrideVariance.hashCode()
        result = 31 * result + initialBiasVariance.hashCode()
        result = 31 * result + minStrideMeters.hashCode()
        result = 31 * result + maxStrideMeters.hashCode()
        return result
    }
}

class PdrEkfEngine(val config: EkfConfig = EkfConfig()) {

    companion object {
        const val IDX_X = 0
        const val IDX_Y = 1
        const val IDX_VX = 2
        const val IDX_VY = 3
        const val IDX_STRIDE = 4
        const val IDX_COMPASS_BIAS = 5
        private const val MAX_ALLOWED_REJECTIONS = 2
    }

    // State vector X = [x, y, vx, vy, L, b_theta]
    private val x = DoubleArray(6)

    // Covariance matrix P (6x6)
    private val p = Array(6) { DoubleArray(6) }

    val stateX: Double get() = x[IDX_X]
    val stateY: Double get() = x[IDX_Y]
    val stateVx: Double get() = x[IDX_VX]
    val stateVy: Double get() = x[IDX_VY]
    val strideLength: Double get() = x[IDX_STRIDE]
    val compassBias: Double get() = x[IDX_COMPASS_BIAS]

    private var isInitializedWithGps = false
    val isInitialized: Boolean get() = isInitializedWithGps

    private var lastStepTimestampMs: Long = 0L
    private var lastGpsTimestampMs: Long = 0L
    private var currentHeadingRad: Double = 0.0
    private var isHeadingInitialized = false
    private var stepCount: Int = 0
    private var gpsFixCount: Int = 0
    private var consecutiveGpsRejections: Int = 0

    init {
        reset()
    }

    fun reset() {
        x[IDX_X] = 0.0
        x[IDX_Y] = 0.0
        x[IDX_VX] = 0.0
        x[IDX_VY] = 0.0
        x[IDX_STRIDE] = 0.75
        x[IDX_COMPASS_BIAS] = 0.0

        for (i in 0..5) {
            for (j in 0..5) {
                p[i][j] = 0.0
            }
        }
        p[IDX_X][IDX_X] = config.initialPosVariance
        p[IDX_Y][IDX_Y] = config.initialPosVariance
        p[IDX_VX][IDX_VX] = config.initialVelVariance
        p[IDX_VY][IDX_VY] = config.initialVelVariance
        p[IDX_STRIDE][IDX_STRIDE] = config.initialStrideVariance
        p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] = config.initialBiasVariance

        isInitializedWithGps = false
        lastStepTimestampMs = 0L
        lastGpsTimestampMs = 0L
        currentHeadingRad = 0.0
        isHeadingInitialized = false
        stepCount = 0
        gpsFixCount = 0
        consecutiveGpsRejections = 0
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
            // Circular exponential moving average: smooths arm bobbing without lagging real turns
            currentHeadingRad = normalizeAngleRad(currentHeadingRad + 0.20 * diff)
        }
    }

    @Synchronized
    fun predict(dtSec: Double = 1.0): EkfState {
        if (!isInitializedWithGps) {
            return getEkfState()
        }

        val clampedDt = dtSec.coerceIn(0.01, 2.0)
        val now = System.currentTimeMillis()
        val timeSinceLastStepSec = if (lastStepTimestampMs > 0L) {
            (now - lastStepTimestampMs) / 1000.0
        } else {
            2.0
        }

        // Velocity decay if stationary
        if (timeSinceLastStepSec > 1.2) {
            val decay = exp(-3.0 * clampedDt)
            x[IDX_VX] *= decay
            x[IDX_VY] *= decay
        }

        // Covariance propagation: P = P + Q * dt
        p[IDX_X][IDX_X] += config.qDiag[IDX_X] * clampedDt
        p[IDX_Y][IDX_Y] += config.qDiag[IDX_Y] * clampedDt
        p[IDX_VX][IDX_VX] += config.qDiag[IDX_VX] * clampedDt
        p[IDX_VY][IDX_VY] += config.qDiag[IDX_VY] * clampedDt

        sanitizeCovariance()
        return getEkfState()
    }

    /**
     * Standard PDR Step Propagation.
     * Advances position strictly along the true smoothed sensor heading.
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

        val strideL = x[IDX_STRIDE]
        val dX = strideL * sin(currentHeadingRad)
        val dY = strideL * cos(currentHeadingRad)

        // Advance position dead-reckoning
        x[IDX_X] += dX
        x[IDX_Y] += dY

        // Instantaneous pedestrian velocity
        x[IDX_VX] = dX / dtStepSec
        x[IDX_VY] = dY / dtStepSec

        // Covariance growth per step
        p[IDX_X][IDX_X] += 0.04
        p[IDX_Y][IDX_Y] += 0.04

        sanitizeCovariance()
    }

    /**
     * Standard Linear Kalman Measurement Update for GPS position.
     * Uses textbook innovation weighting based on real GPS accuracy variance.
     * High accuracy pulls smoothly; canopy multipath noise is rejected by high R.
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
            x[IDX_X] = gpsLocation.x
            x[IDX_Y] = gpsLocation.y
            x[IDX_VX] = 0.0
            x[IDX_VY] = 0.0
            val initialVar = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 64.0)
            p[IDX_X][IDX_X] = initialVar
            p[IDX_Y][IDX_Y] = initialVar
            isInitializedWithGps = true
            sanitizeCovariance()
            return getEkfState(activeIntervalMs)
        }

        val deltaX = gpsLocation.x - x[IDX_X]
        val deltaY = gpsLocation.y - x[IDX_Y]
        val distanceToGps = hypot(deltaX, deltaY)

        // Gate: reject severe outliers (> 30m away when accuracy is poor)
        val maxAllowableDistance = 25.0 + (accuracyMeters * 1.5)
        if (distanceToGps > maxAllowableDistance && accuracyMeters > 10.0f) {
            consecutiveGpsRejections++
            return getEkfState(activeIntervalMs)
        }

        // Watchdog: If accurate GPS (<= 8m) disagrees by > 20m, re-anchor cleanly after 2 consecutive fixes
        if (accuracyMeters <= 8.0f && distanceToGps > 20.0) {
            consecutiveGpsRejections++
            if (consecutiveGpsRejections >= MAX_ALLOWED_REJECTIONS) {
                x[IDX_X] = gpsLocation.x
                x[IDX_Y] = gpsLocation.y
                x[IDX_VX] = 0.0
                x[IDX_VY] = 0.0
                val reanchorVar = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 36.0)
                p[IDX_X][IDX_X] = reanchorVar
                p[IDX_Y][IDX_Y] = reanchorVar
                consecutiveGpsRejections = 0
                sanitizeCovariance()
                return getEkfState(activeIntervalMs)
            }
            return getEkfState(activeIntervalMs)
        }

        consecutiveGpsRejections = 0

        // Standard Kalman Innovation Update
        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(4.0)

        val sX = p[IDX_X][IDX_X] + rVar
        val kX = (p[IDX_X][IDX_X] / sX).coerceIn(0.0, 0.90)
        x[IDX_X] += kX * deltaX
        p[IDX_X][IDX_X] = (1.0 - kX) * p[IDX_X][IDX_X]

        val sY = p[IDX_Y][IDX_Y] + rVar
        val kY = (p[IDX_Y][IDX_Y] / sY).coerceIn(0.0, 0.90)
        x[IDX_Y] += kY * deltaY
        p[IDX_Y][IDX_Y] = (1.0 - kY) * p[IDX_Y][IDX_Y]

        sanitizeCovariance()
        return getEkfState(activeIntervalMs)
    }

    private fun sanitizeCovariance() {
        for (i in 0..5) {
            for (j in 0..5) {
                var sym = 0.5 * (p[i][j] + p[j][i])
                if (sym.isNaN() || sym.isInfinite()) {
                    sym = if (i == j) 1.0 else 0.0
                }
                if (i == j) {
                    val minVar = when (i) {
                        IDX_X, IDX_Y -> 1.0
                        IDX_VX, IDX_VY -> 0.01
                        IDX_STRIDE -> 0.001
                        IDX_COMPASS_BIAS -> 0.005
                        else -> 0.01
                    }
                    val maxVar = when (i) {
                        IDX_X, IDX_Y -> 150.0
                        IDX_VX, IDX_VY -> 10.0
                        IDX_STRIDE -> 0.5
                        IDX_COMPASS_BIAS -> 1.0
                        else -> 100.0
                    }
                    p[i][j] = sym.coerceIn(minVar, maxVar)
                } else {
                    p[i][j] = sym.coerceIn(-50.0, 50.0)
                }
            }
        }
    }

    fun getEkfState(activeIntervalMs: Long = 1000L): EkfState {
        val posXVar = p[IDX_X][IDX_X].coerceAtLeast(1e-4)
        val posYVar = p[IDX_Y][IDX_Y].coerceAtLeast(1e-4)
        val posVarCombined = sqrt(posXVar * posXVar + posYVar * posYVar)

        val velXVar = p[IDX_VX][IDX_VX].coerceAtLeast(1e-4)
        val velYVar = p[IDX_VY][IDX_VY].coerceAtLeast(1e-4)
        val velVarCombined = sqrt(velXVar * velXVar + velYVar * velYVar)

        val speed = hypot(x[IDX_VX], x[IDX_VY])
        val bearingVar = when {
            speed < 0.3 -> 0.05
            speed < 0.8 -> 0.02
            else -> 0.008
        }

        return EkfState(
            timestampMs = System.currentTimeMillis(),
            position = Estimate(Vector2D(x[IDX_X], x[IDX_Y]), posVarCombined),
            velocity = Estimate(Vector2D(x[IDX_VX], x[IDX_VY]), velVarCombined),
            bearingRad = Estimate(currentHeadingRad, bearingVar),
            compassBiasRad = Estimate(0.0, p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS]),
            strideLengthMeters = Estimate(x[IDX_STRIDE], p[IDX_STRIDE][IDX_STRIDE]),
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