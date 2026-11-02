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
    private var currentHeadingRad: Double = 0.0
    private var stepCount: Int = 0
    private var gpsFixCount: Int = 0

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
        currentHeadingRad = 0.0
        stepCount = 0
        gpsFixCount = 0
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

        // DO NOT add velocity to position here! Position is already integrated in onStepDetected().

        // Ensure diagonal variances stay strictly positive and bounded
        for (i in 0..5) {
            if (p[i][i] < 1e-6 || p[i][i].isNaN()) p[i][i] = 1e-4
        }

        return getEkfState()
    }

    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return

        val currentSpeedMps = hypot(x[IDX_VX], x[IDX_VY])
        // Suppress step integration if vehicle speed (> 3.5 m/s) is detected
        if (currentSpeedMps > 3.5) return

        stepCount++
        currentHeadingRad = normalizeAngleRad(headingRawRad)
        val bTheta = x[IDX_COMPASS_BIAS]
        val correctedHeading = normalizeAngleRad(currentHeadingRad - bTheta)

        val dtStepSec = if (lastStepTimestampMs > 0L) {
            (timestampMs - lastStepTimestampMs) / 1000.0
        } else {
            0.6
        }.coerceIn(0.2, 2.0)
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

        p[IDX_X][IDX_X] = p[IDX_X][IDX_X].coerceIn(0.1, 150.0)
        p[IDX_Y][IDX_Y] = p[IDX_Y][IDX_Y].coerceIn(0.1, 150.0)
    }

    @Synchronized
    fun onGpsFix(lat: Double, lon: Double, accuracyMeters: Float, activeIntervalMs: Long): EkfState {
        gpsFixCount++

        val gpsLocation = CoordinateUtils.latLonToMetric(lat, lon)

        if (!isInitializedWithGps) {
            x[IDX_X] = gpsLocation.x
            x[IDX_Y] = gpsLocation.y
            val initialVar = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 100.0)
            p[IDX_X][IDX_X] = initialVar
            p[IDX_Y][IDX_Y] = initialVar
            isInitializedWithGps = true
            return getEkfState(activeIntervalMs)
        }

        val deltaX = gpsLocation.x - x[IDX_X]
        val deltaY = gpsLocation.y - x[IDX_Y]
        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(4.0)

        val correctedHeading = normalizeAngleRad(currentHeadingRad - x[IDX_COMPASS_BIAS])
        val crossTrackErr = abs(deltaX * cos(correctedHeading) - deltaY * sin(correctedHeading))
        val currentSpeed = hypot(x[IDX_VX], x[IDX_VY])

        // --- COMPASS BIAS INFLATION GUARD ---
        // ONLY inflate compass bias uncertainty if:
        // 1. GPS fix is clean and accurate (accuracy <= 8.0m)
        // 2. The hiker is actively moving (speed >= 0.4 m/s)
        // When the phone is in a pocket, degraded GPS fixes (accuracy > 15m) would
        // otherwise cause crossTrackErr to blow compass bias uncertainty out of control.
        if (accuracyMeters <= 8.0f && currentSpeed >= 0.4 && crossTrackErr > 2.5) {
            val clampedErr = crossTrackErr.coerceAtMost(6.0)
            val inflation = 0.02 * clampedErr * clampedErr
            p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] = (p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] + inflation).coerceAtMost(0.4)
        }

        // Direct position observation updates (X and Y independently)
        val hX = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(deltaX, hX, rVar)

        val hY = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(deltaY, hY, rVar)

        // Clamp velocity to human walking ceiling
        val speedMps = hypot(x[IDX_VX], x[IDX_VY])
        if (speedMps > 3.0) {
            val scale = 3.0 / speedMps
            x[IDX_VX] *= scale
            x[IDX_VY] *= scale
        }

        p[IDX_X][IDX_X] = p[IDX_X][IDX_X].coerceIn(0.1, 150.0)
        p[IDX_Y][IDX_Y] = p[IDX_Y][IDX_Y].coerceIn(0.1, 150.0)

        x[IDX_STRIDE] = x[IDX_STRIDE].coerceIn(config.minStrideMeters, config.maxStrideMeters)
        x[IDX_COMPASS_BIAS] = normalizeAngleRad(x[IDX_COMPASS_BIAS])

        return getEkfState(activeIntervalMs)
    }

    private fun updateScalarObservation(residual: Double, h: DoubleArray, rVar: Double) {
        var s = rVar
        val phT = DoubleArray(6)
        for (i in 0..5) {
            var sum = 0.0
            for (j in 0..5) {
                sum += p[i][j] * h[j]
            }
            phT[i] = sum
            s += h[i] * sum
        }

        if (s <= 1e-6 || s.isNaN()) return

        // Kalman gain K
        val k = DoubleArray(6) { (phT[it] / s).coerceIn(-5.0, 5.0) }

        // Clamp state correction to prevent wild jumps
        for (i in 0..5) {
            val delta = k[i] * residual
            // Never allow a single observation to teleport position by > 50m
            val clampedDelta = if (i == IDX_X || i == IDX_Y) delta.coerceIn(-50.0, 50.0) else delta
            x[i] += clampedDelta
        }

        // Joseph form covariance update: P = (I - KH) * P * (I - KH)^T + K * R * K^T
        // Guaranteed mathematically to stay symmetric and positive semi-definite!
        val iMinusKh = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                iMinusKh[i][j] = (if (i == j) 1.0 else 0.0) - k[i] * h[j]
            }
        }

        val temp = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (m in 0..5) {
                    sum += iMinusKh[i][m] * p[m][j]
                }
                temp[i][j] = sum
            }
        }

        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (m in 0..5) {
                    sum += temp[i][m] * iMinusKh[j][m]
                }
                p[i][j] = sum + k[i] * k[j] * rVar
            }
            // Force positive diagonal variance
            p[i][i] = p[i][i].coerceAtLeast(1e-4)
        }
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