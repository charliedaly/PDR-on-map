package com.example.pdrandroutetest.ekf

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.data.Estimate
import com.example.pdrandroutetest.data.Vector2D
import com.example.pdrandroutetest.util.CoordinateUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DiagnosticEkf {

    // State vector X = [x, y, vx, vy, L, b_theta]
    private val x = DoubleArray(6)

    // Covariance matrix P (6x6)
    private val p = Array(6) { DoubleArray(6) }

    // Process noise covariance Q (6x6) diagonal
    private val qDiag = doubleArrayOf(0.05, 0.05, 0.02, 0.02, 0.0001, 0.0001)

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
        x[0] = 0.0 // x (Irish Grid Easting in meters)
        x[1] = 0.0 // y (Irish Grid Northing in meters)
        x[2] = 0.0 // vx
        x[3] = 0.0 // vy
        x[4] = 0.75 // L (stride length in meters)
        x[5] = 0.0 // b_theta (compass bias in radians)

        for (i in 0..5) {
            for (j in 0..5) {
                p[i][j] = 0.0
            }
        }
        p[0][0] = 100.0 // Initial high variance until initial GPS fix
        p[1][1] = 100.0
        p[2][2] = 0.1   // vx var
        p[3][3] = 0.1   // vy var
        p[4][4] = 0.02  // L var
        p[5][5] = 0.05  // b_theta var

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

        // State transition: x = x + vx * dt, y = y + vy * dt
        x[0] += x[2] * dtSec
        x[1] += x[3] * dtSec

        val f = Array(6) { DoubleArray(6) }
        for (i in 0..5) f[i][i] = 1.0
        f[0][2] = dtSec
        f[1][3] = dtSec

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
            p[i][i] += qDiag[i]
        }

        return getEkfState()
    }

    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return

        stepCount++
        currentHeadingRad = headingRawRad
        val bTheta = x[5]
        val correctedHeading = headingRawRad - bTheta

        val dtStepSec = if (lastStepTimestampMs > 0) {
            (timestampMs - lastStepTimestampMs) / 1000.0
        } else {
            0.6
        }.coerceIn(0.2, 2.0)
        lastStepTimestampMs = timestampMs

        val strideL = x[4]
        x[2] = (strideL * sin(correctedHeading)) / dtStepSec
        x[3] = (strideL * cos(correctedHeading)) / dtStepSec
    }

    @Synchronized
    fun onGpsFix(lat: Double, lon: Double, accuracyMeters: Float, activeIntervalMs: Long): EkfState {
        gpsFixCount++

        val gpsLocation = CoordinateUtils.latLonToMetric(lat, lon)

        if (!isInitializedWithGps) {
            // Requirement 2: Initialize EKF state with initial GPS fix
            x[0] = gpsLocation.x
            x[1] = gpsLocation.y
            p[0][0] = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(1.0)
            p[1][1] = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(1.0)
            isInitializedWithGps = true
            return getEkfState(activeIntervalMs)
        }

        val deltaX = gpsLocation.x - x[0]
        val deltaY = gpsLocation.y - x[1]

        val correctedHeading = currentHeadingRad - x[5]
        val eLong = deltaX * sin(correctedHeading) + deltaY * cos(correctedHeading)
        val eLat = deltaX * cos(correctedHeading) - deltaY * sin(correctedHeading)

        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(1.0)

        val hLong = doubleArrayOf(sin(correctedHeading), cos(correctedHeading), 0.0, 0.0, 1.0, 0.0)
        updateScalarObservation(eLong, hLong, rVar)

        val hLat = doubleArrayOf(cos(correctedHeading), -sin(correctedHeading), 0.0, 0.0, 0.0, 1.0)
        updateScalarObservation(eLat, hLat, rVar)

        x[4] = x[4].coerceIn(0.4, 1.3)
        x[5] = normalizeAngleRad(x[5])

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
        val posXVar = p[0][0].coerceAtLeast(1e-4)
        val posYVar = p[1][1].coerceAtLeast(1e-4)
        val posVarCombined = sqrt(posXVar * posXVar + posYVar * posYVar)

        val velXVar = p[2][2].coerceAtLeast(1e-4)
        val velYVar = p[3][3].coerceAtLeast(1e-4)
        val velVarCombined = sqrt(velXVar * velXVar + velYVar * velYVar)

        return EkfState(
            timestampMs = System.currentTimeMillis(),
            position = Estimate(Vector2D(x[0], x[1]), posVarCombined),
            velocity = Estimate(Vector2D(x[2], x[3]), velVarCombined),
            bearingRad = Estimate(normalizeAngleRad(currentHeadingRad - x[5]), p[5][5]),
            compassBiasRad = Estimate(x[5], p[5][5]),
            strideLengthMeters = Estimate(x[4], p[4][4]),
            stepCount = stepCount,
            gpsFixCount = gpsFixCount,
            activeGpsIntervalMs = activeIntervalMs,
            isInitializedWithGps = isInitializedWithGps
        )
    }

    private fun normalizeAngleRad(angleRad: Double): Double {
        var a = angleRad % (2 * PI)
        if (a < 0) a += 2 * PI
        return a
    }
}
