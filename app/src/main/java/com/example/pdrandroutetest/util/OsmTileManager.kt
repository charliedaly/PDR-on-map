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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OsmTileManager {

    private val memoryCache = LruCache<String, ImageBitmap>(300)
    private val cornerCache = LruCache<String, MetricTileCorners>(1000)

    // Reusable single static fallback bitmap to avoid allocation thrashing/GC ANR during tile loading
    private val fallbackImageBitmap: ImageBitmap by lazy {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#151D2A")
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
        canvas.drawText("OSM Map Tile", 12f, 28f, textPaint)

        bitmap.asImageBitmap()
    }

    // Ireland WGS84 Bounding Box
    const val IRELAND_MIN_LAT = 51.4
    const val IRELAND_MAX_LAT = 55.4
    const val IRELAND_MIN_LON = -10.5
    const val IRELAND_MAX_LON = -5.5

    fun clearCache() {
        memoryCache.evictAll()
        cornerCache.evictAll()
    }

    fun lonToTileX(lon: Double, zoom: Int): Int = OsmTileProjectionUtils.lonToTileX(lon, zoom)

    fun latToTileY(lat: Double, zoom: Int): Int = OsmTileProjectionUtils.latToTileY(lat, zoom)

    fun getTileCorners(zoom: Int, tileX: Int, tileY: Int): MetricTileCorners {
        val cacheKey = "$zoom/$tileX/$tileY"
        cornerCache.get(cacheKey)?.let { return it }

        val corners = OsmTileProjectionUtils.tileToMetricCorners(zoom, tileX, tileY)
        cornerCache.put(cacheKey, corners)
        return corners
    }

    fun getTilesForArea(
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        zoom: Int = 10
    ): List<MetricTileCorners> {
        return OsmTileProjectionUtils.getTilesForMetricArea(minX, maxX, minY, maxY, zoom)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun getTileBitmap(context: Context, zoom: Int, tileX: Int, tileY: Int): ImageBitmap {
        val cacheKey = "$zoom/$tileX/$tileY"
        memoryCache.get(cacheKey)?.let { return it }

        // Non-blocking background load (returns fallback immediately to prevent main thread blocking)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // 1. Check local file storage
                val tileFile = File(context.filesDir, "tiles/$zoom/$tileX/$tileY.png")
                if (tileFile.exists() && tileFile.length() > 0) {
                    val bitmap = BitmapFactory.decodeFile(tileFile.absolutePath)
                    if (bitmap != null) {
                        memoryCache.put(cacheKey, bitmap.asImageBitmap())
                        return@launch
                    }
                }

                // 2. Check assets folder
                val assetPath = "tiles/$zoom/$tileX/$tileY.png"
                context.assets.open(assetPath).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        memoryCache.put(cacheKey, bitmap.asImageBitmap())
                        return@launch
                    }
                }

                // 3. Single-level parent fallback (if zoom > 8)
                if (zoom > 8) {
                    val pZoom = zoom - 1
                    val pX = tileX / 2
                    val pY = tileY / 2
                    val pFile = File(context.filesDir, "tiles/$pZoom/$pX/$pY.png")
                    if (pFile.exists() && pFile.length() > 0) {
                        val bitmap = BitmapFactory.decodeFile(pFile.absolutePath)
                        if (bitmap != null) {
                            memoryCache.put(cacheKey, bitmap.asImageBitmap())
                            return@launch
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return fallbackImageBitmap
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
        zoomLevels: List<Int> = listOf(10, 11, 12, 13, 14),
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
