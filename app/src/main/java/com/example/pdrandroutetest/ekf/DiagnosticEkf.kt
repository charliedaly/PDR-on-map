package com.example.pdrandroutetest.ekf

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.data.Estimate
import com.example.pdrandroutetest.data.Vector2D
import com.example.pdrandroutetest.util.CoordinateUtils
import com.example.pdrandroutetest.util.GpsValidationResult
import com.example.pdrandroutetest.util.GpsValidationUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EkfConfig(
    val qDiag: DoubleArray = doubleArrayOf(0.01, 0.01, 0.01, 0.01, 0.0001, 0.005),
    val initialPosVariance: Double = 100.0,
    val initialVelVariance: Double = 0.1,
    val initialStrideVariance: Double = 0.02,
    val initialBiasVariance: Double = 0.5,
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

class DiagnosticEkf(val config: EkfConfig = EkfConfig()) {

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

        val f = Array(6) { DoubleArray(6) }
        for (i in 0..5) f[i][i] = 1.0
        f[IDX_X][IDX_VX] = dtSec
        f[IDX_Y][IDX_VY] = dtSec

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
            p[i][i] += config.qDiag[i] * dtSec
        }

        p[IDX_X][IDX_X] = p[IDX_X][IDX_X].coerceAtMost(100.0)
        p[IDX_Y][IDX_Y] = p[IDX_Y][IDX_Y].coerceAtMost(100.0)

        return getEkfState()
    }

    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return

        val currentSpeedMps = kotlin.math.hypot(x[IDX_VX], x[IDX_VY])
        // Suppress PDR step integration when vehicle speed > 3.0 m/s is detected
        if (currentSpeedMps > 3.0) return

        stepCount++
        currentHeadingRad = headingRawRad
        val bTheta = x[IDX_COMPASS_BIAS]
        val correctedHeading = normalizeAngleRad(headingRawRad - bTheta)

        val dtStepSec = if (lastStepTimestampMs > 0) {
            (timestampMs - lastStepTimestampMs) / 1000.0
        } else {
            0.6
        }.coerceIn(0.2, 2.0)
        lastStepTimestampMs = timestampMs

        val strideL = x[IDX_STRIDE]

        // Update velocity
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

        // Decouple velocity from compass bias in F_step to prevent variance explosion
        fStep[IDX_VX][IDX_STRIDE] = 0.0
        fStep[IDX_VY][IDX_STRIDE] = 0.0
        fStep[IDX_VX][IDX_COMPASS_BIAS] = 0.0
        fStep[IDX_VY][IDX_COMPASS_BIAS] = 0.0

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

        p[IDX_X][IDX_X] = p[IDX_X][IDX_X].coerceAtMost(100.0)
        p[IDX_Y][IDX_Y] = p[IDX_Y][IDX_Y].coerceAtMost(100.0)
    }

    @Synchronized
    fun onGpsFix(lat: Double, lon: Double, accuracyMeters: Float, activeIntervalMs: Long): EkfState {
        gpsFixCount++

        val gpsLocation = CoordinateUtils.latLonToMetric(lat, lon)

        if (!isInitializedWithGps) {
            x[IDX_X] = gpsLocation.x
            x[IDX_Y] = gpsLocation.y
            p[IDX_X][IDX_X] = (accuracyMeters * accuracyMeters).toDouble().coerceIn(1.0, 100.0)
            p[IDX_Y][IDX_Y] = (accuracyMeters * accuracyMeters).toDouble().coerceIn(1.0, 100.0)
            isInitializedWithGps = true
            return getEkfState(activeIntervalMs)
        }

        val deltaX = gpsLocation.x - x[IDX_X]
        val deltaY = gpsLocation.y - x[IDX_Y]
        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(1.0)

        val correctedHeading = normalizeAngleRad(currentHeadingRad - x[IDX_COMPASS_BIAS])
        val crossTrackErr = abs(deltaX * cos(correctedHeading) - deltaY * sin(correctedHeading))

        // Adaptive compass bias variance inflation on large cross-track drift
        if (crossTrackErr > 2.0) {
            p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] = (p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS] + 0.05 * crossTrackErr * crossTrackErr).coerceAtMost(2.0)
        }

        // Direct position observation updates
        val hX = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(deltaX, hX, rVar)

        val hY = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        updateScalarObservation(deltaY, hY, rVar)

        // Clamp velocity to human hiking speed ceiling (3.0 m/s max)
        val speedMps = kotlin.math.hypot(x[IDX_VX], x[IDX_VY])
        if (speedMps > 3.0) {
            val scale = 3.0 / speedMps
            x[IDX_VX] *= scale
            x[IDX_VY] *= scale
        }

        p[IDX_X][IDX_X] = p[IDX_X][IDX_X].coerceAtMost(100.0)
        p[IDX_Y][IDX_Y] = p[IDX_Y][IDX_Y].coerceAtMost(100.0)

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

        if (s <= 1e-9) return

        val k = DoubleArray(6) { phT[it] / s }

        for (i in 0..5) {
            x[i] += k[i] * residual
        }

        val newP = Array(6) { DoubleArray(6) }
        for (i in 0..5) {
            for (j in 0..5) {
                var sum = 0.0
                for (m in 0..5) {
                    val eyeMinusKh = (if (i == m) 1.0 else 0.0) - k[i] * h[m]
                    sum += eyeMinusKh * p[m][j]
                }
                newP[i][j] = sum
            }
        }

        for (i in 0..5) {
            for (j in 0..5) {
                p[i][j] = newP[i][j]
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

        return EkfState(
            timestampMs = System.currentTimeMillis(),
            position = Estimate(Vector2D(x[IDX_X], x[IDX_Y]), posVarCombined),
            velocity = Estimate(Vector2D(x[IDX_VX], x[IDX_VY]), velVarCombined),
            bearingRad = Estimate(normalizeAngleRad(currentHeadingRad - x[IDX_COMPASS_BIAS]), p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS]),
            compassBiasRad = Estimate(x[IDX_COMPASS_BIAS], p[IDX_COMPASS_BIAS][IDX_COMPASS_BIAS]),
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
