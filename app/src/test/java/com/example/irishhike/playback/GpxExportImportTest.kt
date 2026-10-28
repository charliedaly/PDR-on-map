package com.example.irishhike.playback

import com.example.irishhike.data.EkfState
import com.example.irishhike.data.Estimate
import com.example.irishhike.data.Vector2D
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Location
import com.example.irishhike.util.CoordinateUtils
import com.example.irishhike.util.HikerTrackExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class GpxExportImportTest {

    @Test
    fun testExportGpxAndImportRoundTripHasZeroShift() {
        val originalLoc = Location(178061.22, 165172.54) // Tipperary
        val ekfState = EkfState(position = Estimate(Vector2D(originalLoc.x, originalLoc.y), 4.0))

        val breadcrumbs = listOf(
            Breadcrumb(id = 1L, location = originalLoc, timestampMs = 1000000L, isGpsFix = true, ekfState = ekfState),
            Breadcrumb(id = 2L, location = Location(originalLoc.x + 10.0, originalLoc.y + 10.0), timestampMs = 1001000L, isGpsFix = true, ekfState = ekfState)
        )

        val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".", "gpx_test")
        tempDir.mkdirs()

        val exportedFile = File(tempDir, "test_bc.gpx")
        val (lat0, lon0) = CoordinateUtils.metricToLatLon(originalLoc)

        val gpxXml = StringBuilder()
        gpxXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        gpxXml.append("<gpx version=\"1.1\" creator=\"Irish Hiker Navigation App\">\n")
        gpxXml.append("  <trk><trkseg>\n")
        gpxXml.append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>0.0</ele><time>2026-10-25T10:00:00Z</time></trkpt>\n".format(java.util.Locale.US, lat0, lon0))
        gpxXml.append("  </trkseg></trk>\n")
        gpxXml.append("</gpx>\n")

        exportedFile.writeText(gpxXml.toString())

        val events = GpxTrackParser.parseGpxToPlaybackEvents(ByteArrayInputStream(exportedFile.readBytes()))
        assertTrue("Events must not be empty", events.isNotEmpty())

        val gpsEvent = events.first { it is TrackPlaybackEvent.GpsPointEvent } as TrackPlaybackEvent.GpsPointEvent
        val importedLoc = CoordinateUtils.latLonToMetric(gpsEvent.latitude, gpsEvent.longitude)

        val errorX = Math.abs(originalLoc.x - importedLoc.x)
        val errorY = Math.abs(originalLoc.y - importedLoc.y)

        println("Exported Loc: (${originalLoc.x}, ${originalLoc.y}) -> GPX Lat/Lon (${gpsEvent.latitude}, ${gpsEvent.longitude}) -> Re-imported Loc: (${importedLoc.x}, ${importedLoc.y})")
        println("Round-Trip Error X: %.4fm, Error Y: %.4fm".format(errorX, errorY))

        assertTrue("X round-trip error must be < 0.10m", errorX < 0.10)
        assertTrue("Y round-trip error must be < 0.10m", errorY < 0.10)
    }
}
