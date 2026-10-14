package com.example.pdrandroutetest.telemetry

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.data.Estimate
import com.example.pdrandroutetest.data.Vector2D
import java.io.File

class TelemetryRecorder {
    private val events = mutableListOf<TelemetryEvent>()

    @Synchronized
    fun recordGpsFix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        activeIntervalMs: Long,
        timestampMs: Long = System.currentTimeMillis(),
        recordedEkfState: EkfState? = null
    ) {
        events.add(
            TelemetryEvent.GpsFixEvent(
                timestampMs = timestampMs,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                activeIntervalMs = activeIntervalMs,
                recordedEkfState = recordedEkfState
            )
        )
    }

    @Synchronized
    fun recordStep(
        headingRawRad: Double,
        timestampMs: Long = System.currentTimeMillis(),
        recordedEkfState: EkfState? = null
    ) {
        events.add(
            TelemetryEvent.StepDetectedEvent(
                timestampMs = timestampMs,
                headingRawRad = headingRawRad,
                recordedEkfState = recordedEkfState
            )
        )
    }

    @Synchronized
    fun recordTick(
        dtSec: Double = 1.0,
        timestampMs: Long = System.currentTimeMillis(),
        recordedEkfState: EkfState? = null
    ) {
        events.add(
            TelemetryEvent.PredictionTickEvent(
                timestampMs = timestampMs,
                dtSec = dtSec,
                recordedEkfState = recordedEkfState
            )
        )
    }

    @Synchronized
    fun getEvents(): List<TelemetryEvent> {
        return events.toList()
    }

    @Synchronized
    fun clear() {
        events.clear()
    }

    @Synchronized
    fun toCsvString(): String {
        val sb = StringBuilder()
        events.forEach { event ->
            val ekf = event.recordedEkfState
            val ekfSuffix = if (ekf != null) {
                ",${ekf.position.value.x},${ekf.position.value.y},${ekf.velocity.value.x},${ekf.velocity.value.y},${ekf.strideLengthMeters.value},${ekf.compassBiasRad.value},${ekf.position.variance}"
            } else ""

            when (event) {
                is TelemetryEvent.GpsFixEvent -> {
                    sb.append("GPS,${event.timestampMs},${event.latitude},${event.longitude},${event.accuracyMeters},${event.activeIntervalMs}$ekfSuffix\n")
                }
                is TelemetryEvent.StepDetectedEvent -> {
                    sb.append("STEP,${event.timestampMs},${event.headingRawRad}$ekfSuffix\n")
                }
                is TelemetryEvent.PredictionTickEvent -> {
                    sb.append("TICK,${event.timestampMs},${event.dtSec}$ekfSuffix\n")
                }
            }
        }
        return sb.toString()
    }

    @Synchronized
    fun saveToFile(file: File) {
        file.writeText(toCsvString())
    }

    @Synchronized
    fun saveToTimestampedFile(dir: File): File? {
        if (events.isEmpty()) return null
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "telemetry_$timestamp.csv")
        saveToFile(file)
        return file
    }

    companion object {
        fun fromCsvString(csvString: String): List<TelemetryEvent> {
            val list = mutableListOf<TelemetryEvent>()
            csvString.lines().forEach { line ->
                val tokens = line.split(",").map { it.trim() }
                if (tokens.size > 1) {
                    when (tokens[0]) {
                        "GPS" -> {
                            if (tokens.size >= 6) {
                                val ekfState = parseEkfStateFromTokens(tokens, startIndex = 6)
                                list.add(
                                    TelemetryEvent.GpsFixEvent(
                                        timestampMs = tokens[1].toLong(),
                                        latitude = tokens[2].toDouble(),
                                        longitude = tokens[3].toDouble(),
                                        accuracyMeters = tokens[4].toFloat(),
                                        activeIntervalMs = tokens[5].toLong(),
                                        recordedEkfState = ekfState
                                    )
                                )
                            }
                        }
                        "STEP" -> {
                            if (tokens.size >= 3) {
                                val ekfState = parseEkfStateFromTokens(tokens, startIndex = 3)
                                list.add(
                                    TelemetryEvent.StepDetectedEvent(
                                        timestampMs = tokens[1].toLong(),
                                        headingRawRad = tokens[2].toDouble(),
                                        recordedEkfState = ekfState
                                    )
                                )
                            }
                        }
                        "TICK" -> {
                            if (tokens.size >= 3) {
                                val ekfState = parseEkfStateFromTokens(tokens, startIndex = 3)
                                list.add(
                                    TelemetryEvent.PredictionTickEvent(
                                        timestampMs = tokens[1].toLong(),
                                        dtSec = tokens[2].toDouble(),
                                        recordedEkfState = ekfState
                                    )
                                )
                            }
                        }
                    }
                }
            }
            return list
        }

        private fun parseEkfStateFromTokens(tokens: List<String>, startIndex: Int): EkfState? {
            if (tokens.size < startIndex + 7) return null
            return try {
                val posX = tokens[startIndex].toDouble()
                val posY = tokens[startIndex + 1].toDouble()
                val velX = tokens[startIndex + 2].toDouble()
                val velY = tokens[startIndex + 3].toDouble()
                val stride = tokens[startIndex + 4].toDouble()
                val bias = tokens[startIndex + 5].toDouble()
                val posVar = tokens[startIndex + 6].toDouble()

                EkfState(
                    position = Estimate(Vector2D(posX, posY), posVar),
                    velocity = Estimate(Vector2D(velX, velY), 0.1),
                    strideLengthMeters = Estimate(stride, 0.01),
                    compassBiasRad = Estimate(bias, 0.05),
                    isInitializedWithGps = true
                )
            } catch (e: Exception) {
                null
            }
        }

        fun loadFromFile(file: File): List<TelemetryEvent> {
            return fromCsvString(file.readText())
        }
    }
}
