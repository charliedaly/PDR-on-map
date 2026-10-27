package com.example.irishhike.util

import com.example.irishhike.model.Location
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

data class Wgs84Bounds(
    val latNorth: Double,
    val latSouth: Double,
    val lonWest: Double,
    val lonEast: Double
)

data class MetricTileCorners(
    val zoom: Int,
    val tileX: Int,
    val tileY: Int,
    val nwMetric: Location,
    val neMetric: Location,
    val seMetric: Location,
    val swMetric: Location
) {
    val minX: Double get() = minOf(nwMetric.x, neMetric.x, seMetric.x, swMetric.x)
    val maxX: Double get() = maxOf(nwMetric.x, neMetric.x, seMetric.x, swMetric.x)
    val minY: Double get() = minOf(nwMetric.y, neMetric.y, seMetric.y, swMetric.y)
    val maxY: Double get() = maxOf(nwMetric.y, neMetric.y, seMetric.y, swMetric.y)

    val spanX: Double get() = abs(maxX - minX)
    val spanY: Double get() = abs(maxY - minY)
}

object OsmTileProjectionUtils {

    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceAtLeast(0)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = 2.0.pow(zoom)
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceAtLeast(0)
    }

    fun tileToWgs84Bounds(zoom: Int, tileX: Int, tileY: Int): Wgs84Bounds {
        val n = 2.0.pow(zoom)
        val lonWest = tileX.toDouble() / n * 360.0 - 180.0
        val lonEast = (tileX + 1).toDouble() / n * 360.0 - 180.0

        val latNorthRad = atan(sinh(PI * (1.0 - 2.0 * tileY / n)))
        val latSouthRad = atan(sinh(PI * (1.0 - 2.0 * (tileY + 1) / n)))

        return Wgs84Bounds(
            latNorth = Math.toDegrees(latNorthRad),
            latSouth = Math.toDegrees(latSouthRad),
            lonWest = lonWest,
            lonEast = lonEast
        )
    }

    fun tileToMetricCorners(zoom: Int, tileX: Int, tileY: Int): MetricTileCorners {
        val wgs84 = tileToWgs84Bounds(zoom, tileX, tileY)

        val nwMetric = CoordinateUtils.latLonToMetric(wgs84.latNorth, wgs84.lonWest)
        val neMetric = CoordinateUtils.latLonToMetric(wgs84.latNorth, wgs84.lonEast)
        val seMetric = CoordinateUtils.latLonToMetric(wgs84.latSouth, wgs84.lonEast)
        val swMetric = CoordinateUtils.latLonToMetric(wgs84.latSouth, wgs84.lonWest)

        return MetricTileCorners(
            zoom = zoom,
            tileX = tileX,
            tileY = tileY,
            nwMetric = nwMetric,
            neMetric = neMetric,
            seMetric = seMetric,
            swMetric = swMetric
        )
    }

    fun getTilesForMetricArea(
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        zoom: Int = 10
    ): List<MetricTileCorners> {
        val effectiveMinX = minX.coerceIn(0.0, 450000.0)
        val effectiveMaxX = maxX.coerceIn(0.0, 450000.0)
        val effectiveMinY = minY.coerceIn(0.0, 500000.0)
        val effectiveMaxY = maxY.coerceIn(0.0, 500000.0)

        val swLatLon = CoordinateUtils.metricToLatLon(Location(effectiveMinX, effectiveMinY))
        val neLatLon = CoordinateUtils.metricToLatLon(Location(effectiveMaxX, effectiveMaxY))

        val minLat = minOf(swLatLon.first, neLatLon.first)
        val maxLat = maxOf(swLatLon.first, neLatLon.first)
        val minLon = minOf(swLatLon.second, neLatLon.second)
        val maxLon = maxOf(swLatLon.second, neLatLon.second)

        val minTileX = lonToTileX(minLon, zoom)
        val maxTileX = lonToTileX(maxLon, zoom)
        val minTileY = latToTileY(maxLat, zoom) // Higher latitude is smaller Y index
        val maxTileY = latToTileY(minLat, zoom)

        val tiles = LinkedHashSet<MetricTileCorners>()
        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                tiles.add(tileToMetricCorners(zoom, tx, ty))
            }
        }

        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0

        return tiles.sortedBy { tile ->
            val tcx = (tile.minX + tile.maxX) / 2.0
            val tcy = (tile.minY + tile.maxY) / 2.0
            kotlin.math.hypot(tcx - cx, tcy - cy)
        }
    }
}
