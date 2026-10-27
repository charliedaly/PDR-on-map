package com.example.irishhike.util

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.math.hypot

data class GpsValidationConfig(
    val modeName: String = "Hiking",
    val minLat: Double = 51.0,
    val maxLat: Double = 56.0,
    val minLon: Double = -11.0,
    val maxLon: Double = -5.0,
    val notifySpeedKmh: Double = 10.0,
    val rejectSpeedKmh: Double = 200.0,
    val maxGpsAccuracyMeters: Float = 50.0f
) {
    companion object {
        val HIKING = GpsValidationConfig(
            modeName = "Hiking",
            notifySpeedKmh = 10.0,
            rejectSpeedKmh = 150.0,
            maxGpsAccuracyMeters = 300.0f
        )
        val CYCLING = GpsValidationConfig(
            modeName = "Cycling",
            notifySpeedKmh = 35.0,
            rejectSpeedKmh = 120.0,
            maxGpsAccuracyMeters = 50.0f
        )
        val DRIVING = GpsValidationConfig(
            modeName = "Driving",
            notifySpeedKmh = 130.0,
            rejectSpeedKmh = 250.0,
            maxGpsAccuracyMeters = 100.0f
        )
    }
}

enum class GpsValidationResult {
    VALID,
    REJECT_NULL_ISLAND,
    REJECT_OUT_OF_BOUNDS,
    REJECT_ACCURACY,
    REJECT_IMPOSSIBLE_SPEED,
    WARN_HIGH_SPEED
}

object GpsValidationUtils {
    var config: GpsValidationConfig = GpsValidationConfig.HIKING

    private var lastValidLat: Double = 0.0
    var lastValidLon: Double = 0.0
    private var lastValidTimestampMs: Long = 0L

    fun reset() {
        lastValidLat = 0.0
        lastValidLon = 0.0
        lastValidTimestampMs = 0L
    }

    fun validateFix(lat: Double, lon: Double, accuracyMeters: Float, timestampMs: Long): GpsValidationResult {
        if (lat == 0.0 && lon == 0.0) {
            return GpsValidationResult.REJECT_NULL_ISLAND
        }

        if (lat !in config.minLat..config.maxLat || lon !in config.minLon..config.maxLon) {
            return GpsValidationResult.REJECT_OUT_OF_BOUNDS
        }

        if (accuracyMeters > config.maxGpsAccuracyMeters) {
            return GpsValidationResult.REJECT_ACCURACY
        }

        if (lastValidTimestampMs > 0 && timestampMs > lastValidTimestampMs) {
            val prevLoc = CoordinateUtils.latLonToMetric(lastValidLat, lastValidLon)
            val currLoc = CoordinateUtils.latLonToMetric(lat, lon)
            val distMeters = prevLoc.distanceTo(currLoc)
            val dtSec = (timestampMs - lastValidTimestampMs) / 1000.0
            if (dtSec > 0.0) {
                val speedMps = distMeters / dtSec
                val speedKmh = speedMps * 3.6

                if (speedKmh > config.rejectSpeedKmh) {
                    return GpsValidationResult.REJECT_IMPOSSIBLE_SPEED
                }
                if (speedKmh > config.notifySpeedKmh) {
                    lastValidLat = lat
                    lastValidLon = lon
                    lastValidTimestampMs = timestampMs
                    return GpsValidationResult.WARN_HIGH_SPEED
                }
            }
        }

        lastValidLat = lat
        lastValidLon = lon
        lastValidTimestampMs = timestampMs
        return GpsValidationResult.VALID
    }

    fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_NACK, 250)
        } catch (_: Exception) {}
    }
}
