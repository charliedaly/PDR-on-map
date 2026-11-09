package com.example.irishhike.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.example.irishhike.config.PhoneMount
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class StepDetectorConfig(
    val stepThresholdDelta: Double = 1.35,  // Dynamic peak threshold above gravity baseline
    val minStepIntervalMs: Long = 320L,     // Max human step rate ~3.1 steps/sec
    val maxStepIntervalMs: Long = 2000L,
    val alpha: Double = 0.18,
    val defaultMount: PhoneMount = PhoneMount.HANDHELD_OR_PACK_TOP
)

class PdrStepDetector(
    context: Context,
    val config: StepDetectorConfig = StepDetectorConfig(),
    private val onStepListener: (headingRawRad: Double, timestampMs: Long) -> Unit
) : SensorEventListener {

    var mount: PhoneMount = config.defaultMount
    var onHeadingChanged: ((headingRad: Double) -> Unit)? = null

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

    @Volatile
    private var currentHeadingRad: Double = 0.0

    fun start() {
        if (isListening) return
        isListening = true

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val eventTimestampMs = if (event.timestamp > 0L) {
            System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        } else {
            System.currentTimeMillis()
        }

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val east: Double
                val north: Double

                when (mount) {
                    PhoneMount.HANDHELD_OR_PACK_TOP -> {
                        // Phone top edge (+Y) points forward along trail
                        east = rotationMatrix[1].toDouble()
                        north = rotationMatrix[4].toDouble()
                    }
                    PhoneMount.SHOULDER_STRAP_SCREEN_FORWARD -> {
                        // Phone upright on strap, screen normal (+Z) points forward
                        east = rotationMatrix[2].toDouble()
                        north = rotationMatrix[5].toDouble()
                    }
                    PhoneMount.SHOULDER_STRAP_CAMERA_FORWARD -> {
                        // Phone upright on strap, camera normal (-Z) points forward
                        east = -rotationMatrix[2].toDouble()
                        north = -rotationMatrix[5].toDouble()
                    }
                }

                if (hypot(east, north) > 0.05) {
                    var azimuth = atan2(east, north)
                    if (azimuth < 0.0) {
                        azimuth += 2.0 * PI
                    }
                    currentHeadingRad = azimuth
                    onHeadingChanged?.invoke(azimuth)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val rawMag = sqrt((ax * ax + ay * ay + az * az).toDouble())

                // Slow baseline tracker (tracks true gravity 1g)
                gravityEstimate += 0.02 * (rawMag - gravityEstimate)

                // Low-pass filtered acceleration
                accelFiltered += config.alpha * (rawMag - accelFiltered)
                val dynamicDelta = accelFiltered - gravityEstimate

                if (dynamicDelta < -0.4) {
                    isArmedForStep = true
                }

                val timeSinceLastPeak = eventTimestampMs - lastPeakTimestampMs
                if (isArmedForStep && dynamicDelta > config.stepThresholdDelta && timeSinceLastPeak > config.minStepIntervalMs) {
                    lastPeakTimestampMs = eventTimestampMs
                    isArmedForStep = false
                    onStepListener(currentHeadingRad, eventTimestampMs)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (rotationVectorSensor == null) {
                    val gz = event.values[2].toDouble()
                    currentHeadingRad = (currentHeadingRad + gz * 0.02) % (2.0 * PI)
                    if (currentHeadingRad < 0.0) currentHeadingRad += 2.0 * PI
                    onHeadingChanged?.invoke(currentHeadingRad)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}