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
    val initialVelVariance: Double = 0.10,
    val initialStrideVariance: Double = 0.02,
    val initialBiasVariance: Double = 0.10,
    val minStrideMeters: Double = 0.45,
    val maxStrideMeters: Double = 1.25,
    val defaultStrideMeters: Double = 0.75
)

class PdrEkfEngine(val config: EkfConfig = EkfConfig()) {

    // State: [x (Easting m), y (Northing m), vx (m/s), vy (m/s), stride (m), compassBias (rad)]
    private var posX: Double = 0.0
    private var posY: Double = 0.0
    private var velX: Double = 0.0
    private var velY: Double = 0.0
    private var strideMeters: Double = config.defaultStrideMeters
    private var compassBiasRad: Double = 0.0

    // Variances
    private var varPosX: Double = config.initialPosVariance
    private var varPosY: Double = config.initialPosVariance
    private var varVelX: Double = config.initialVelVariance
    private var varVelY: Double = config.initialVelVariance
    private var varStride: Double = config.initialStrideVariance
    private var varBias: Double = config.initialBiasVariance

    private var isInitializedWithGps = false
    val isInitialized: Boolean get() = isInitializedWithGps

    private var lastStepTimestampMs: Long = 0L
    private var lastGpsTimestampMs: Long = 0L
    private var currentHeadingRad: Double = 0.0
    private var lastRawSensorHeadingRad: Double = 0.0
    private var isHeadingInitialized = false

    // Tracking metrics between consecutive GPS fixes for stride and bias adaptation
    private var stepsSinceGpsFix: Int = 0
    private var distanceSinceGpsFix: Double = 0.0
    private var headingSinAccum: Double = 0.0
    private var headingCosAccum: Double = 0.0

    private var stepCount: Int = 0
    private var gpsFixCount: Int = 0

    val stateX: Double get() = posX
    val stateY: Double get() = posY
    val stateVx: Double get() = velX
    val stateVy: Double get() = velY
    val strideLength: Double get() = strideMeters
    val compassBias: Double get() = compassBiasRad

    init {
        reset()
    }

    fun reset() {
        posX = 0.0
        posY = 0.0
        velX = 0.0
        velY = 0.0
        strideMeters = config.defaultStrideMeters
        compassBiasRad = 0.0

        varPosX = config.initialPosVariance
        varPosY = config.initialPosVariance
        varVelX = config.initialVelVariance
        varVelY = config.initialVelVariance
        varStride = config.initialStrideVariance
        varBias = config.initialBiasVariance

        isInitializedWithGps = false
        lastStepTimestampMs = 0L
        lastGpsTimestampMs = 0L
        currentHeadingRad = 0.0
        lastRawSensorHeadingRad = 0.0
        isHeadingInitialized = false

        resetInterGpsMetrics()
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
            // Circular exponential moving average: smooths chest bobbing while tracking turns
            currentHeadingRad = normalizeAngleRad(currentHeadingRad + 0.25 * diff)
        }
    }

    @Synchronized
    fun predict(dtSec: Double = 1.0, currentEventTimeMs: Long = 0L): EkfState {
        if (!isInitializedWithGps) return getEkfState()

        val clampedDt = dtSec.coerceIn(0.01, 2.0)
        val now = if (currentEventTimeMs > 0L) currentEventTimeMs else System.currentTimeMillis()
        val timeSinceStepSec = if (lastStepTimestampMs > 0L) {
            ((now - lastStepTimestampMs) / 1000.0).coerceAtLeast(0.0)
        } else {
            2.0
        }

        // Velocity decay if stationary
        if (timeSinceStepSec > 1.2) {
            val decay = exp(-3.0 * clampedDt)
            velX *= decay
            velY *= decay
        }

        varPosX += 0.05 * clampedDt
        varPosY += 0.05 * clampedDt

        return getEkfState()
    }

    /**
     * PDR Step Propagation.
     * Advances position along effective heading (raw sensor heading minus learned compass bias)
     * using the dynamically calibrated stride length.
     */
    @Synchronized
    fun onStepDetected(headingRawRad: Double, timestampMs: Long) {
        if (!isInitializedWithGps) return
        if (headingRawRad.isNaN() || headingRawRad.isInfinite()) return

        stepCount++
        lastRawSensorHeadingRad = headingRawRad

        // 1. Subtract learned compass bias so dead-reckoning and navigation follow true ground motion
        val effectiveHeading = normalizeAngleRad(headingRawRad - compassBiasRad)
        setHeadingRad(effectiveHeading)

        val dtStepSec = if (lastStepTimestampMs > 0L) {
            ((timestampMs - lastStepTimestampMs) / 1000.0).coerceIn(0.25, 2.0)
        } else {
            0.65
        }
        lastStepTimestampMs = timestampMs

        // 2. Dead-reckon position using dynamically estimated stride length
        val dX = strideMeters * sin(currentHeadingRad)
        val dY = strideMeters * cos(currentHeadingRad)

        posX += dX
        posY += dY

        velX = dX / dtStepSec
        velY = dY / dtStepSec

        varPosX += 0.04
        varPosY += 0.04

        // 3. Accumulate step metrics since last GPS fix for calibration
        stepsSinceGpsFix++
        distanceSinceGpsFix += strideMeters
        headingSinAccum += sin(currentHeadingRad)
        headingCosAccum += cos(currentHeadingRad)
    }

    /**
     * Calibrate mount bias directly against satellite Course Over Ground when moving at walking speed.
     */
    @Synchronized
    fun onGpsHeadingUpdate(gpsBearingRad: Double) {
        if (!isHeadingInitialized) {
            currentHeadingRad = gpsBearingRad
            isHeadingInitialized = true
            return
        }

        val observedBias = normalizeAngleRad(lastRawSensorHeadingRad - gpsBearingRad)
        val diff = normalizeAngleRad(observedBias - compassBiasRad)
        compassBiasRad = normalizeAngleRad(compassBiasRad + 0.06 * diff)
    }

    /**
     * Kalman Innovation Update for GPS Position, Stride Length, and Compass Bias.
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
            strideMeters = config.defaultStrideMeters
            compassBiasRad = 0.0
            varPosX = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 64.0)
            varPosY = varPosX
            isInitializedWithGps = true
            resetInterGpsMetrics()
            return getEkfState(activeIntervalMs)
        }

        val deltaX = gpsLocation.x - posX
        val deltaY = gpsLocation.y - posY
        val distanceToGps = hypot(deltaX, deltaY)

        // Watchdog: If PDR ever drifts > 25m from good GPS (<= 15m), re-anchor cleanly
        if (distanceToGps > 25.0 && accuracyMeters <= 15.0f) {
            posX = gpsLocation.x
            posY = gpsLocation.y
            varPosX = (accuracyMeters * accuracyMeters).toDouble().coerceIn(4.0, 36.0)
            varPosY = varPosX
            resetInterGpsMetrics()
            return getEkfState(activeIntervalMs)
        }

        // Standard Kalman Innovation Update on Position
        val rVar = (accuracyMeters * accuracyMeters).toDouble().coerceAtLeast(4.0)

        val sX = varPosX + rVar
        val kX = (varPosX / sX).coerceIn(0.15, 0.85)
        posX += kX * deltaX
        varPosX = (1.0 - kX) * varPosX

        val sY = varPosY + rVar
        val kY = (varPosY / sY).coerceIn(0.15, 0.85)
        posY += kY * deltaY
        varPosY = (1.0 - kY) * varPosY

        varPosX = varPosX.coerceIn(1.0, 100.0)
        varPosY = varPosY.coerceIn(1.0, 100.0)

        // -------------------------------------------------------------
        // DYNAMIC STRIDE LENGTH & COMPASS BIAS ESTIMATION
        // -------------------------------------------------------------
        // Only adapt when:
        // 1. GPS accuracy is trustworthy (accuracy <= 12m)
        // 2. We have walked meaningful distance since last fix (distance >= 3.5m and at least 3 steps)
        // 3. Headings during this interval were consistent (not spinning in place or turning a sharp bend)
        if (accuracyMeters <= 12.0f && distanceSinceGpsFix >= 3.5 && stepsSinceGpsFix >= 3) {
            val meanSin = headingSinAccum / stepsSinceGpsFix
            val meanCos = headingCosAccum / stepsSinceGpsFix
            val headingConsistency = hypot(meanSin, meanCos)

            if (headingConsistency > 0.88) {
                val avgIntervalHeading = atan2(meanSin, meanCos)

                // Along-track error: displacement parallel to walking direction
                val alongTrackError = deltaX * sin(avgIntervalHeading) + deltaY * cos(avgIntervalHeading)
                // Cross-track error: displacement perpendicular to walking direction (positive = GPS is right of PDR)
                val crossTrackError = deltaX * cos(avgIntervalHeading) - deltaY * sin(avgIntervalHeading)

                // A. Stride Length Adaptation
                val impliedDistance = distanceSinceGpsFix + alongTrackError
                if (impliedDistance > 1.0) {
                    val observedStride = impliedDistance / stepsSinceGpsFix
                    val strideWeight = (0.12 * (1.0 - (accuracyMeters / 15.0))).coerceIn(0.03, 0.15)
                    strideMeters += strideWeight * (observedStride - strideMeters)
                    strideMeters = strideMeters.coerceIn(config.minStrideMeters, config.maxStrideMeters)
                    varStride = (varStride * 0.95 + 0.001).coerceIn(0.001, 0.05)
                }

                // B. Compass Bias Adaptation
                val impliedAngularError = atan2(crossTrackError, distanceSinceGpsFix)
                val clampedAngularError = impliedAngularError.coerceIn(-0.10, 0.10)
                val biasWeight = (0.10 * (1.0 - (accuracyMeters / 15.0))).coerceIn(0.02, 0.12)
                compassBiasRad = normalizeAngleRad(compassBiasRad - biasWeight * clampedAngularError)
                varBias = (varBias * 0.95 + 0.002).coerceIn(0.002, 0.10)
            }
        }

        resetInterGpsMetrics()
        return getEkfState(activeIntervalMs)
    }

    private fun resetInterGpsMetrics() {
        stepsSinceGpsFix = 0
        distanceSinceGpsFix = 0.0
        headingSinAccum = 0.0
        headingCosAccum = 0.0
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
            compassBiasRad = Estimate(compassBiasRad, varBias),
            strideLengthMeters = Estimate(strideMeters, varStride),
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