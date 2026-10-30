package com.example.irishhike.playback

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object GpxTrackParser {

    fun parseGpxToPlaybackEvents(inputStream: InputStream): List<TrackPlaybackEvent> {
        val events = mutableListOf<TrackPlaybackEvent>()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

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

                    var timeMs = System.currentTimeMillis()
                    var accuracy = 5.0f
                    var isGpsFix = true

                    val timeNodes = node.getElementsByTagName("time")
                    if (timeNodes.length > 0) {
                        val timeStr = timeNodes.item(0).textContent.trim()
                        try {
                            timeMs = isoFormat.parse(timeStr)?.time ?: timeMs
                        } catch (_: Exception) {}
                    }

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

                    if (lastTimeMs > 0 && timeMs > lastTimeMs) {
                        val dtSec = (timeMs - lastTimeMs) / 1000.0
                        events.add(TrackPlaybackEvent.TimeTickEvent(timeMs, dtSec))
                    }
                    lastTimeMs = timeMs

                    events.add(
                        TrackPlaybackEvent.GpsPointEvent(
                            timestampMs = timeMs,
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
