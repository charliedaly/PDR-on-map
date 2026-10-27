package com.example.irishhike.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

data class StepDetectorConfig(
    val stepThreshold: Double = 11.2,
    val minStepIntervalMs: Long = 300L,
    val alpha: Double = 0.15
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
    private var accelFiltered = 9.81
    private var lastPeakTimestampMs = 0L

    // Heading calculation
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    @Volatile
    private var currentHeadingRad: Double = 0.0

    fun start() {
        if (isListening) return
        isListening = true

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val eventTimestampMs = if (event.timestamp > 0) {
            // Convert elapsed realtime nanos to current wall-clock epoch milliseconds
            System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        } else {
            System.currentTimeMillis()
        }

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // Azimuth angle in radians clockwise from North
                var azimuth = orientationAngles[0].toDouble()
                if (azimuth < 0) azimuth += 2 * PI
                currentHeadingRad = azimuth
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt((ax * ax + ay * ay + az * az).toDouble())

                // Apply low pass filter
                accelFiltered = accelFiltered + config.alpha * (mag - accelFiltered)

                if (accelFiltered > config.stepThreshold && (eventTimestampMs - lastPeakTimestampMs) > config.minStepIntervalMs) {
                    lastPeakTimestampMs = eventTimestampMs
                    onStepListener(currentHeadingRad, eventTimestampMs)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (rotationVectorSensor == null) {
                    val gx = event.values[0].toDouble()
                    val gy = event.values[1].toDouble()
                    currentHeadingRad += (atan2(gy, gx) * 0.02)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
