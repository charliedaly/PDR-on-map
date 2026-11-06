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
    val isInitialized: Boolean
        get() = isInitializedWithGps

    private var lastStepTimestampMs: Long = 0L
    private var lastGpsTimestampMs: Long = 0L
    private var lastGpsMetricX: Double? = null
    private var lastGpsMetricY: Double? = null
    private var currentHeadingRad: Double = 0.0
    private var stepCount: Int = 0
    private var gpsFixCount: Int = 0
    private var consecutiveGpsRejections: Int = 0

    @Synchronized
    fun setHeadingRad(headingRad: Double) {
        currentHeadingRad = normalizeAngleRad(headingRad)
    }

    init {
        reset()
    }

    fun reset() {
        x[IDX_X] = 0.0 // x (Irish Grid Easting in meters)
        x[IDX_Y] = 0.0 // y (Irish Grid Northing in meters)
        x[IDX_VX] = 0.0 // vx
        x[IDX_VY] = 0.0 // vy
        x[IDX_STRIDE] = 0.75 // L (stride length in meters)
        x[IDX_COMPASS_BIAS] = 0.0 // b_theta (compass bias in radians)

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
        lastGpsMetricX = null
        lastGpsMetricY = null
        currentHeadingRad = 0.0
        stepCount = 0
        gpsFixCount = 0
        consecutiveGpsRejections = 0
        sanitizeCovariance()
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

        // Velocity decay if stationary / no steps
        if (timeSinceLastStepSec > 1.2) {
            val decay = exp(-3.0 * clampedDt)
            x[IDX_VX] *= decay
            x[IDX_VY] *= decay
        }

        // Covariance propagation P = F * P * F^T + Q
        val f = Array(6) { DoubleArray(6) }
        for (i in 0..5) f[i][i] = 1.0
        f[IDX_X][IDX_VX] = clampedDt
        f[IDX_Y][IDX_VY] = clampedDt

        val fp = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (k in 0..5) {
                    sum += f[i][k] * p[k][j]
                }
                fp[i][j] = sum
            }
        }

        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (k in 0..5) {
                    sum += fp[i][k] * f[j][k]
                }
                p[i][j] = sum
            }
            p[i][i] += config.qDiag[i] * clampedDt
        }

        sanitizeCovariance()
        return getEkfState()
    }

    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return
        if (headingRawRad.isNaN() || headingRawRad.isInfinite()) return

        val currentSpeedMps = hypot(x[IDX_VX], x[IDX_VY])
        // Suppress step integration if vehicle speed (> 3.5 m/s) is detected
        if (currentSpeedMps > 3.5) return

        stepCount++
        currentHeadingRad = normalizeAngleRad(headingRawRad)
        val bTheta = x[IDX_COMPASS_BIAS]
        val correctedHeading = normalizeAngleRad(currentHeadingRad - bTheta)

        val dtStepSec = if (lastStepTimestampMs > 0L) {
            ((timestampMs - lastStepTimestampMs) / 1000.0).coerceIn(0.2, 2.0)
        } else {
            0.6
        }
        lastStepTimestampMs = timestampMs

        val strideL = x[IDX_STRIDE]

        // Instantaneous pedestrian velocity for this step
        x[IDX_VX] = (strideL * sin(correctedHeading)) / dtStepSec
        x[IDX_VY] = (strideL * cos(correctedHeading)) / dtStepSec

        // Integrate step displacement into position
        x[IDX_X] += strideL * sin(correctedHeading)
        x[IDX_Y] += strideL * cos(correctedHeading)

        // Step Jacobian F_step
        val fStep = Array(6) { DoubleArray(6) }
        for (i in 0..5) fStep[i][i] = 1.0

        fStep[IDX_X][IDX_STRIDE] = sin(correctedHeading)
        fStep[IDX_Y][IDX_STRIDE] = cos(correctedHeading)
        fStep[IDX_X][IDX_COMPASS_BIAS] = -strideL * cos(correctedHeading)
        fStep[IDX_Y][IDX_COMPASS_BIAS] = strideL * sin(correctedHeading)

        // Propagate P = F_step * P * F_step^T
        val fp = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (k in 0..5) {
                    sum += fStep[i][k] * p[k][j]
                }
                fp[i][j] = sum
            }
        }

        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (k in 0..5) {
                    sum += fp[i][k] * fStep[j][k]
                }
                p[i][j] = sum
            }
        }

        p[IDX_STRIDE][IDX_STRIDE] += config.qDiag[IDX_STRIDE]
        p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] += config.qDiag[IDX_COMPASS_BIAS]

        sanitizeCovariance()
    }

    @Synchronized
    fun onGpsFix(
        lat: Double,
        lon: Double,
        accuracyMeters: Float,
        activeIntervalMs: Long = 1000L,
        timestampMs: Long = System.currentTimeMillis()
    ): EkfState {
        gpsFixCount++

        val gpsLocation = CoordinateUtils.latLonToMetric(lat, lon)

        if (!isInitializedWithGps) {
            x[IDX_X] = gpsLocation.x
            x[IDX_Y] = gpsLocation.y
            val initialVar = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 100.0)
            p[IDX_X][IDX_X] = initialVar
            p[IDX_Y][IDX_Y] = initialVar
            isInitializedWithGps = true
            lastGpsTimestampMs = timestampMs
            lastGpsMetricX = gpsLocation.x
            lastGpsMetricY = gpsLocation.y
            sanitizeCovariance()
            return getEkfState(activeIntervalMs)
        }

        val dtGpsSec = if (lastGpsTimestampMs > 0L) {
            ((timestampMs - lastGpsTimestampMs) / 1000.0).coerceIn(0.1, 30.0)
        } else {
            1.0
        }

        val deltaX = gpsLocation.x - x[IDX_X]
        val deltaY = gpsLocation.y - x[IDX_Y]
        val distanceToGps = hypot(deltaX, deltaY)

        // Course-over-ground initial compass bias seeding and refinement
        val prevGpsX = lastGpsMetricX
        val prevGpsY = lastGpsMetricY
        if (prevGpsX != null && prevGpsY != null) {
            val dGpsX = gpsLocation.x - prevGpsX
            val dGpsY = gpsLocation.y - prevGpsY
            val gpsDist = hypot(dGpsX, dGpsY)
            if (accuracyMeters <= 10.0f && gpsDist > 1.5 && stepCount >= 2) {
                val gpsBearingRad = atan2(dGpsX, dGpsY)
                val rawBias = normalizeAngleRad(currentHeadingRad - gpsBearingRad)
                if (abs(x[IDX_COMPASS_BIAS]) < 1e-4) {
                    x[IDX_COMPASS_BIAS] = rawBias.coerceIn(-0.35, 0.35)
                    p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] = 0.05
                } else {
                    val biasError = normalizeAngleRad(rawBias - x[IDX_COMPASS_BIAS])
                    x[IDX_COMPASS_BIAS] = normalizeAngleRad(x[IDX_COMPASS_BIAS] + 0.15 * biasError).coerceIn(-0.35, 0.35)
                }
            }
        }
        lastGpsMetricX = gpsLocation.x
        lastGpsMetricY = gpsLocation.y

        val maxAllowableDistance = 25.0 + (dtGpsSec * 1.5)

        // Gate evaluation
        if (distanceToGps > maxAllowableDistance) {
            if (accuracyMeters <= 10.0f) {
                consecutiveGpsRejections++

                // WATCHDOG: Re-anchor directly to GPS position after 2 consecutive good fixes
                if (consecutiveGpsRejections >= MAX_ALLOWED_REJECTIONS) {
                    x[IDX_X] = gpsLocation.x
                    x[IDX_Y] = gpsLocation.y
                    x[IDX_VX] = 0.0
                    x[IDX_VY] = 0.0

                    val reanchorVar = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 36.0)
                    for (j in 0..5) {
                        p[IDX_X][j] = 0.0
                        p[j][IDX_X] = 0.0
                        p[IDX_Y][j] = 0.0
                        p[j][IDX_Y] = 0.0
                    }
                    p[IDX_X][IDX_X] = reanchorVar
                    p[IDX_Y][IDX_Y] = reanchorVar

                    consecutiveGpsRejections = 0
                    lastGpsTimestampMs = timestampMs
                    sanitizeCovariance()
                    return getEkfState(activeIntervalMs)
                }
            }

            lastGpsTimestampMs = timestampMs
            return getEkfState(activeIntervalMs)
        }

        // Fix is within gate
        consecutiveGpsRejections = 0
        lastGpsTimestampMs = timestampMs

        // ADAPTIVE DRIFT RECOVERY:
        // When high-quality GPS (accuracy <= 10m) shows drift (> 6.0m) from PDR,
        // blend aggressively toward GPS to immediately eliminate post-canopy error (< 4m)
        if (accuracyMeters <= 10.0f && distanceToGps > 6.0) {
            val blendFactor = 0.80
            x[IDX_X] += blendFactor * deltaX
            x[IDX_Y] += blendFactor * deltaY
            p[IDX_X][IDX_X] = 16.0
            p[IDX_Y][IDX_Y] = 16.0
        }

        // --- 1. Robust Measurement Noise Inflation in Canopy ---
        // When accuracy is degraded (> 7m) or speed is low, inflate R so GPS only
        // pulls gently over time rather than jerking the hiker sideways on every fix
        var rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(4.0)
        if (accuracyMeters > 7.0f) {
            // Under canopy, GPS accuracy is overly optimistic; inflate R quadratically
            val canopyFactor = (accuracyMeters / 6.0).toDouble()
            rVar *= (canopyFactor * canopyFactor).coerceIn(2.0, 8.0)
        }

        // --- 2. Max Pedestrian Step Jump Clamping ---
        // A single 1-second GPS fix should never pull the hiker more than a realistic
        // walking distance (max 2.0m per fix)
        val maxAllowableDelta = 2.0 + (dtGpsSec * 1.5)
        val clampedDeltaX = deltaX.coerceIn(-maxAllowableDelta, maxAllowableDelta)
        val clampedDeltaY = deltaY.coerceIn(-maxAllowableDelta, maxAllowableDelta)

        // Direct position observation updates (X and Y independently)
        val hX = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(clampedDeltaX, hX, rVar)

        val hY = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(clampedDeltaY, hY, rVar)

        val speedMps = hypot(x[IDX_VX], x[IDX_VY])
        if (speedMps > 3.0) {
            val scale = 3.0 / speedMps
            x[IDX_VX] *= scale
            x[IDX_VY] *= scale
        }

        x[IDX_STRIDE] = x[IDX_STRIDE].coerceIn(config.minStrideMeters, config.maxStrideMeters)
        x[IDX_COMPASS_BIAS] = x[IDX_COMPASS_BIAS].coerceIn(-0.35, 0.35)

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

    private fun updateScalarObservation(residual: Double, h: DoubleArray, rVariance: Double) {
        if (residual.isNaN() || rVariance.isNaN() || rVariance <= 0.0) return

        var s = rVariance
        for (i in 0..5) {
            for (j in 0..5) {
                s += h[i] * p[i][j] * h[j]
            }
        }

        if (s <= 1e-9 || s.isNaN() || s.isInfinite()) return

        val k = DoubleArray(6)
        for (i in 0..5) {
            var sum = 0.0
            for (j in 0..5) {
                sum += p[i][j] * h[j]
            }
            k[i] = sum / s
            if (k[i].isNaN() || k[i].isInfinite()) k[i] = 0.0
        }

        val isPositionUpdate = (h[IDX_X] == 1.0 || h[IDX_Y] == 1.0)
        if (isPositionUpdate) {
            k[IDX_STRIDE] = 0.0
            k[IDX_COMPASS_BIAS] = 0.0
        }

        for (i in 0..5) {
            val delta = k[i] * residual
            val clampedDelta = when (i) {
                IDX_X, IDX_Y -> delta.coerceIn(-50.0, 50.0)
                IDX_STRIDE -> delta.coerceIn(-0.05, 0.05)
                IDX_COMPASS_BIAS -> delta.coerceIn(-0.05, 0.05)
                else -> delta
            }
            if (!clampedDelta.isNaN() && !clampedDelta.isInfinite()) {
                x[i] += clampedDelta
            }
        }

        x[IDX_STRIDE] = x[IDX_STRIDE].coerceIn(config.minStrideMeters, config.maxStrideMeters)
        x[IDX_COMPASS_BIAS] = x[IDX_COMPASS_BIAS].coerceIn(-0.35, 0.35)

        val newP = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (m in 0..5) {
                    val eye = if (i == m) 1.0 else 0.0
                    val iMinusKH = eye - (k[i] * h[m])
                    sum += iMinusKH * p[m][j]
                }
                newP[i][j] = sum
            }
        }

        for (i in 0..5) {
            for (j in 0..5) {
                p[i][j] = newP[i][j]
            }
        }

        sanitizeCovariance()
    }

    fun getEkfState(activeIntervalMs: Long = 1000L): EkfState {
        val posXVar = p[IDX_X][IDX_X].coerceAtLeast(1e-4)
        val posYVar = p[IDX_Y][IDX_Y].coerceAtLeast(1e-4)
        val posVarCombined = sqrt(posXVar * posXVar + posYVar * posYVar)

        val velXVar = p[IDX_VX][IDX_VX].coerceAtLeast(1e-4)
        val velYVar = p[IDX_VY][IDX_VY].coerceAtLeast(1e-4)
        val velVarCombined = sqrt(velXVar * velXVar + velYVar * velYVar)

        val biasVar = p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS].coerceAtLeast(1e-4)

        return EkfState(
            timestampMs = System.currentTimeMillis(),
            position = Estimate(Vector2D(x[IDX_X], x[IDX_Y]), posVarCombined),
            velocity = Estimate(Vector2D(x[IDX_VX], x[IDX_VY]), velVarCombined),
            bearingRad = Estimate(normalizeAngleRad(currentHeadingRad - x[IDX_COMPASS_BIAS]), biasVar),
            compassBiasRad = Estimate(x[IDX_COMPASS_BIAS], biasVar),
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