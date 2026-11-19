package com.example.irishhike.playback

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object GpxTrackParser {

    private fun parseIsoTimestamp(timeStr: String): Long? {
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(timeStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return null
    }

    fun parseGpxToPlaybackEvents(inputStream: InputStream): List<TrackPlaybackEvent> {
        val events = mutableListOf<TrackPlaybackEvent>()

        try {
            val dbFactory = DocumentBuilderFactory.newInstance()
            dbFactory.isNamespaceAware = true
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(inputStream)
            doc.documentElement.normalize()

            val trkptList = doc.getElementsByTagName("trkpt")
            var lastTimeMs = 0L

            for (i in 0 until trkptList.length) {
                val node = trkptList.item(i)
                if (node is Element) {
                    val latStr = node.getAttribute("lat")
                    val lonStr = node.getAttribute("lon")
                    val lat = latStr.toDoubleOrNull() ?: continue
                    val lon = lonStr.toDoubleOrNull() ?: continue

                    var timeMs: Long? = null
                    var accuracy = 5.0f
                    var isGpsFix = true

                    val timeNodes = node.getElementsByTagName("time")
                    if (timeNodes.length > 0) {
                        val timeStr = timeNodes.item(0).textContent.trim()
                        timeMs = parseIsoTimestamp(timeStr)
                    }

                    // Fallback: If timestamp is missing or unparseable, simulate 1 Hz interval
                    val effectiveTimeMs = timeMs ?: if (lastTimeMs > 0L) lastTimeMs + 1000L else System.currentTimeMillis()

                    val accuracyNodes = node.getElementsByTagName("accuracy")
                    if (accuracyNodes.length > 0) {
                        accuracy = accuracyNodes.item(0).textContent.trim().toFloatOrNull() ?: 5.0f
                    }

                    val fixNodes = node.getElementsByTagName("is_gps_fix")
                    if (fixNodes.length > 0) {
                        isGpsFix = fixNodes.item(0).textContent.trim().toBooleanStrictOrNull() ?: true
                    } else {
                        val altFixNodes = node.getElementsByTagName("isGpsFix")
                        if (altFixNodes.length > 0) {
                            isGpsFix = altFixNodes.item(0).textContent.trim().toBooleanStrictOrNull() ?: true
                        }
                    }

                    if (lastTimeMs > 0 && effectiveTimeMs > lastTimeMs) {
                        val dtSec = (effectiveTimeMs - lastTimeMs) / 1000.0
                        events.add(TrackPlaybackEvent.TimeTickEvent(effectiveTimeMs, dtSec))
                    }
                    lastTimeMs = effectiveTimeMs

                    events.add(
                        TrackPlaybackEvent.GpsPointEvent(
                            timestampMs = effectiveTimeMs,
                            latitude = lat,
                            longitude = lon,
                            accuracyMeters = accuracy,
                            activeIntervalMs = 1000L,
                            isGpsFix = isGpsFix
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return events.sortedBy { it.timestampMs }
    }
}