package com.example.irishhike.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class StepDetectorConfig(
    val stepThresholdDelta: Double = 1.35,  // Dynamic peak threshold above gravity baseline
    val minStepIntervalMs: Long = 320L,     // Max human step rate ~3.1 steps/sec
    val maxStepIntervalMs: Long = 2000L,
    val alpha: Double = 0.18
)

class PdrStepDetector(
    context: Context,
    val config: StepDetectorConfig = StepDetectorConfig(),
    private val onStepListener: (headingRawRad: Double, timestampMs: Long) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var isListening = false

    // Step detection parameters
    private var gravityEstimate = 9.80665
    private var accelFiltered = 9.80665
    private var lastPeakTimestampMs = 0L
    private var isArmedForStep = false

    // Heading calculation & matrices
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    @Volatile
    private var currentHeadingRad: Double = 0.0

    fun start() {
        if (isListening) return
        isListening = true

        // Use maxReportLatencyUs = 0 to forbid sensor batching when screen is turned off
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0)
        } ?: gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 0)
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val eventTimestampMs = if (event.timestamp > 0L) {
            // Convert hardware sensor monotonic nanos to current wall-clock epoch ms
            System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        } else {
            System.currentTimeMillis()
        }

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Remap coordinate system if phone is held upright or in pocket
                // Check if device is tilted vertically (> 45 degrees inclination)
                val isUpright = abs(rotationMatrix[7]) > 0.707f
                val targetMatrix = if (isUpright) {
                    SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        SensorManager.AXIS_X,
                        SensorManager.AXIS_Z,
                        remappedMatrix
                    )
                    remappedMatrix
                } else {
                    rotationMatrix
                }

                SensorManager.getOrientation(targetMatrix, orientationAngles)
                var azimuth = orientationAngles[0].toDouble()
                if (azimuth < 0.0) {
                    azimuth += 2.0 * PI
                }
                currentHeadingRad = azimuth
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val rawMag = sqrt((ax * ax + ay * ay + az * az).toDouble())

                // Slow baseline tracker (tracks true gravity 1g)
                gravityEstimate = gravityEstimate + 0.02 * (rawMag - gravityEstimate)

                // Low-pass filtered acceleration
                accelFiltered = accelFiltered + config.alpha * (rawMag - accelFiltered)
                val dynamicDelta = accelFiltered - gravityEstimate

                // Peak & Valley detection:
                // When dynamic acceleration dips below -0.4, arm for the next strike peak
                if (dynamicDelta < -0.4) {
                    isArmedForStep = true
                }

                // Trigger step when rising above threshold and armed
                val timeSinceLastPeak = eventTimestampMs - lastPeakTimestampMs
                if (isArmedForStep && dynamicDelta > config.stepThresholdDelta && timeSinceLastPeak > config.minStepIntervalMs) {
                    lastPeakTimestampMs = eventTimestampMs
                    isArmedForStep = false
                    onStepListener(currentHeadingRad, eventTimestampMs)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                // Fallback gyro integration if rotation vector is unavailable
                if (rotationVectorSensor == null) {
                    val gz = event.values[2].toDouble() // yaw rate around Z
                    currentHeadingRad = (currentHeadingRad + gz * 0.02) % (2.0 * PI)
                    if (currentHeadingRad < 0.0) currentHeadingRad += 2.0 * PI
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}