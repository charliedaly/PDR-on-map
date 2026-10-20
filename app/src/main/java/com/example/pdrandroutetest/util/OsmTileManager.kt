package com.example.pdrandroutetest.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.pdrandroutetest.model.Location
import com.example.pdrandroutetest.model.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

data class TileBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val zoom: Int,
    val tileX: Int,
    val tileY: Int
)

data class TileCorners(
    val zoom: Int,
    val tileX: Int,
    val tileY: Int,
    val nwMetric: Location,
    val neMetric: Location,
    val seMetric: Location,
    val swMetric: Location
)

object OsmTileManager {

    private val memoryCache = LruCache<String, ImageBitmap>(300)

    // Ireland WGS84 Bounding Box
    const val IRELAND_MIN_LAT = 51.4
    const val IRELAND_MAX_LAT = 55.4
    const val IRELAND_MIN_LON = -10.5
    const val IRELAND_MAX_LON = -5.5

    fun clearCache() {
        memoryCache.evictAll()
    }

    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom)
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceAtLeast(0)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = 2.0.pow(zoom)
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceAtLeast(0)
    }

    private fun cos(rad: Double): Double = kotlin.math.cos(rad)

    fun tileToBoundingBox(zoom: Int, tileX: Int, tileY: Int): TileBounds {
        val corners = getTileCorners(zoom, tileX, tileY)
        val minX = minOf(corners.nwMetric.x, corners.swMetric.x)
        val maxX = maxOf(corners.neMetric.x, corners.seMetric.x)
        val minY = minOf(corners.swMetric.y, corners.seMetric.y)
        val maxY = maxOf(corners.nwMetric.y, corners.neMetric.y)

        return TileBounds(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            zoom = zoom,
            tileX = tileX,
            tileY = tileY
        )
    }

    fun getTileCorners(zoom: Int, tileX: Int, tileY: Int): TileCorners {
        val n = 2.0.pow(zoom)
        val lonWest = tileX.toDouble() / n * 360.0 - 180.0
        val lonEast = (tileX + 1).toDouble() / n * 360.0 - 180.0

        val latNorthRad = atan(sinh(PI * (1.0 - 2.0 * tileY / n)))
        val latSouthRad = atan(sinh(PI * (1.0 - 2.0 * (tileY + 1) / n)))

        val latNorth = Math.toDegrees(latNorthRad)
        val latSouth = Math.toDegrees(latSouthRad)

        val nwMetric = CoordinateUtils.latLonToMetric(latNorth, lonWest)
        val neMetric = CoordinateUtils.latLonToMetric(latNorth, lonEast)
        val seMetric = CoordinateUtils.latLonToMetric(latSouth, lonEast)
        val swMetric = CoordinateUtils.latLonToMetric(latSouth, lonWest)

        return TileCorners(
            zoom = zoom,
            tileX = tileX,
            tileY = tileY,
            nwMetric = nwMetric,
            neMetric = neMetric,
            seMetric = seMetric,
            swMetric = swMetric
        )
    }

    fun getTilesForArea(
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        zoom: Int = 10
    ): List<TileBounds> {
        val swLatLon = CoordinateUtils.metricToLatLon(Location(minX, minY))
        val neLatLon = CoordinateUtils.metricToLatLon(Location(maxX, maxY))

        val minLat = minOf(swLatLon.first, neLatLon.first)
        val maxLat = maxOf(swLatLon.first, neLatLon.first)
        val minLon = minOf(swLatLon.second, neLatLon.second)
        val maxLon = maxOf(swLatLon.second, neLatLon.second)

        val minTileX = lonToTileX(minLon, zoom)
        val maxTileX = lonToTileX(maxLon, zoom)
        val minTileY = latToTileY(maxLat, zoom) // Higher latitude is smaller Y index
        val maxTileY = latToTileY(minLat, zoom)

        val tiles = mutableListOf<TileBounds>()
        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                tiles.add(tileToBoundingBox(zoom, tx, ty))
            }
        }
        return tiles
    }

    fun getTileBitmap(context: Context, zoom: Int, tileX: Int, tileY: Int): ImageBitmap {
        val cacheKey = "$zoom/$tileX/$tileY"
        memoryCache.get(cacheKey)?.let { return it }

        // 1. Check local file storage (app filesDir/tiles/{z}/{x}/{y}.png)
        val tileFile = File(context.filesDir, "tiles/$zoom/$tileX/$tileY.png")
        if (tileFile.exists() && tileFile.length() > 0) {
            val bitmap = BitmapFactory.decodeFile(tileFile.absolutePath)
            if (bitmap != null) {
                val img = bitmap.asImageBitmap()
                memoryCache.put(cacheKey, img)
                return img
            }
        }

        // 2. Check assets folder (assets/tiles/{z}/{x}/{y}.png)
        try {
            val assetPath = "tiles/$zoom/$tileX/$tileY.png"
            context.assets.open(assetPath).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    val img = bitmap.asImageBitmap()
                    memoryCache.put(cacheKey, img)
                    return img
                }
            }
        } catch (_: Exception) {
        }

        // 3. Parent tile fallback (up to 3 zoom levels higher up for seamless high-zoom panning)
        if (zoom > 8) {
            val parentZoom = zoom - 1
            val parentX = tileX / 2
            val parentY = tileY / 2
            val parentBitmap = getTileBitmap(context, parentZoom, parentX, parentY)
            memoryCache.put(cacheKey, parentBitmap)
            return parentBitmap
        }

        // 4. Fallback: Generate offline topographical background tile
        val fallbackBitmap = generateFallbackTile(zoom, tileX, tileY)
        val img = fallbackBitmap.asImageBitmap()
        memoryCache.put(cacheKey, img)
        return img
    }

    private fun generateFallbackTile(zoom: Int, tileX: Int, tileY: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#151D2A") // Dark topographic background
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, 256f, 256f, bgPaint)

        val gridPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#233146")
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(0f, 0f, 256f, 256f, gridPaint)

        val textPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#3B4D66")
            textSize = 18f
            isAntiAlias = true
        }
        canvas.drawText("OSM $zoom/$tileX/$tileY", 12f, 28f, textPaint)

        return bitmap
    }

    /**
     * Programmatically downloads OSM tiles for Ireland across specified zoom levels and saves to app files directory.
     */
    suspend fun downloadIrelandTiles(
        context: Context,
        zoomLevels: List<Int> = listOf(8, 9, 10, 11, 12, 13, 14),
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val totalTilesList = mutableListOf<Triple<Int, Int, Int>>()

        for (z in zoomLevels) {
            val minX = lonToTileX(IRELAND_MIN_LON, z)
            val maxX = lonToTileX(IRELAND_MAX_LON, z)
            val minY = latToTileY(IRELAND_MAX_LAT, z)
            val maxY = latToTileY(IRELAND_MIN_LAT, z)

            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    totalTilesList.add(Triple(z, x, y))
                }
            }
        }

        val total = totalTilesList.size
        var downloaded = 0
        var successCount = 0

        Log.i("OsmTileManager", "Starting download of $total tiles for Ireland across zooms $zoomLevels")

        for (tile in totalTilesList) {
            val (z, x, y) = tile
            val tileDir = File(context.filesDir, "tiles/$z/$x")
            if (!tileDir.exists()) tileDir.mkdirs()

            val tileFile = File(tileDir, "$y.png")
            if (!tileFile.exists() || tileFile.length() == 0L) {
                try {
                    val tileUrl = "https://tile.openstreetmap.org/$z/$x/$y.png"
                    val conn = URL(tileUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "HikingTool/1.0 (Android PDR Diagnostic App)")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    if (conn.responseCode == 200) {
                        conn.inputStream.use { input ->
                            FileOutputStream(tileFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tileFile.length() > 0) successCount++
                    } else {
                        Log.w("OsmTileManager", "HTTP ${conn.responseCode} for tile $z/$x/$y")
                    }
                } catch (e: Exception) {
                    Log.e("OsmTileManager", "Failed tile $z/$x/$y: ${e.message}")
                }
            } else {
                successCount++
            }
            downloaded++
            onProgress(downloaded, total)
        }

        clearCache()
        Log.i("OsmTileManager", "Completed tile downloads. Successfully ready: $successCount / $total")
        return@withContext successCount
    }

    /**
     * Programmatically downloads OSM tiles for a specific Route area (with 1km buffer)
     * across specified zoom levels and saves to local file storage.
     */
    suspend fun downloadRouteAreaTiles(
        context: Context,
        route: Route,
        zoomLevels: List<Int> = listOf(10, 11, 12, 13, 14, 15, 16, 17, 18),
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        if (route.waypoints.isEmpty()) return@withContext 0

        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        route.waypoints.forEach { wp ->
            if (wp.x < minX) minX = wp.x
            if (wp.x > maxX) maxX = wp.x
            if (wp.y < minY) minY = wp.y
            if (wp.y > maxY) maxY = wp.y
        }

        // Add 1km safety buffer around route
        minX -= 1000.0
        maxX += 1000.0
        minY -= 1000.0
        maxY += 1000.0

        val swLatLon = CoordinateUtils.metricToLatLon(Location(minX, minY))
        val neLatLon = CoordinateUtils.metricToLatLon(Location(maxX, maxY))

        val minLat = minOf(swLatLon.first, neLatLon.first)
        val maxLat = maxOf(swLatLon.first, neLatLon.first)
        val minLon = minOf(swLatLon.second, neLatLon.second)
        val maxLon = maxOf(swLatLon.second, neLatLon.second)

        val totalTilesList = mutableListOf<Triple<Int, Int, Int>>()

        for (z in zoomLevels) {
            val minTileX = lonToTileX(minLon, z)
            val maxTileX = lonToTileX(maxLon, z)
            val minTileY = latToTileY(maxLat, z)
            val maxTileY = latToTileY(minLat, z)

            for (x in minTileX..maxTileX) {
                for (y in minTileY..maxTileY) {
                    totalTilesList.add(Triple(z, x, y))
                }
            }
        }

        val total = totalTilesList.size
        var downloaded = 0
        var successCount = 0

        Log.i("OsmTileManager", "Downloading $total route area tiles for '${route.name}' across zooms $zoomLevels")

        for (tile in totalTilesList) {
            val (z, x, y) = tile
            val tileDir = File(context.filesDir, "tiles/$z/$x")
            if (!tileDir.exists()) tileDir.mkdirs()

            val tileFile = File(tileDir, "$y.png")
            if (!tileFile.exists() || tileFile.length() == 0L) {
                try {
                    val tileUrl = "https://tile.openstreetmap.org/$z/$x/$y.png"
                    val conn = URL(tileUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "HikingTool/1.0 (Android PDR Diagnostic App)")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    if (conn.responseCode == 200) {
                        conn.inputStream.use { input ->
                            FileOutputStream(tileFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tileFile.length() > 0) successCount++
                    }
                } catch (e: Exception) {
                    Log.e("OsmTileManager", "Failed route tile $z/$x/$y: ${e.message}")
                }
            } else {
                successCount++
            }
            downloaded++
            onProgress(downloaded, total)
        }

        clearCache()
        Log.i("OsmTileManager", "Completed route area tile downloads: $successCount / $total ready.")
        return@withContext successCount
    }
}
