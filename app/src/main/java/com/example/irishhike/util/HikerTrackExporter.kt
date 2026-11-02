package com.example.irishhike.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.irishhike.model.Breadcrumb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

object HikerTrackExporter {

    private const val TAG = "HikerTrackExporter"

    /**
     * Resolves the target directory:
     *   Download / hillwalking / <route_name> /
     *
     * Tries public Download first; falls back to app external Download if permission is restricted.
     */
    fun resolveRouteDirectory(context: Context, routeName: String? = null, routeUri: Uri? = null): File {
        val cleanRouteName = when {
            !routeName.isNullOrBlank() -> routeName.trim()
            routeUri != null -> RouteParser.extractRouteNameFromUri(context, routeUri)
            else -> "default"
        }.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        // 1. Primary: Public Downloads / hillwalking / <route_name>
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val primaryDir = File(File(publicDownloads, "hillwalking"), cleanRouteName)

        val targetDir = try {
            if (!primaryDir.exists()) {
                val created = primaryDir.mkdirs()
                if (created || primaryDir.exists()) primaryDir else null
            } else {
                primaryDir
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create public hillwalking dir (${e.message}), using app external storage")
            null
        }

        // 2. Guaranteed fallback: app external files directory (visible on PC via USB)
        // Path: /storage/emulated/0/Android/data/com.example.irishhike/files/Download/hillwalking/<route_name>
        if (targetDir != null && targetDir.canWrite()) {
            Log.i(TAG, "Resolved target route directory: ${targetDir.absolutePath}")
            return targetDir
        }

        val appExternalDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val fallbackDir = File(File(appExternalDownloads, "hillwalking"), cleanRouteName)
        if (!fallbackDir.exists()) {
            fallbackDir.mkdirs()
        }
        Log.i(TAG, "Resolved fallback target route directory: ${fallbackDir.absolutePath}")
        return fallbackDir
    }

    /**
     * Exports breadcrumbs to a .gpx file inside Download/hillwalking/<route_name>/
     * Designed to be called safely from a background coroutine.
     */
    fun exportBreadcrumbsToGpx(
        context: Context,
        breadcrumbs: List<Breadcrumb>,
        routeName: String? = null,
        routeUri: Uri? = null
    ): File? {
        if (breadcrumbs.isEmpty()) return null

        val targetDir = resolveRouteDirectory(context, routeName, routeUri)
        val timeFormatter = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        val timestampStr = timeFormatter.format(Date())
        val filename = "bc-$timestampStr.gpx"
        val file = File(targetDir, filename)

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Irish Hiker Navigation App\"\n")
        sb.append("  xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>Hike Breadcrumbs $timestampStr</name>\n")
        sb.append("    <trkseg>\n")

        for (bc in breadcrumbs) {
            val (lat, lon) = CoordinateUtils.metricToLatLon(bc.location)
            val timeIso = isoFormatter.format(Date(bc.timestampMs))
            val accuracy = if (bc.ekfState != null) {
                sqrt(abs(bc.ekfState.position.variance))
            } else {
                if (bc.isGpsFix) 5.0 else 2.0
            }
            val stride = bc.ekfState?.strideLengthMeters?.value ?: 0.75

            sb.append("      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n".format(Locale.US, lat, lon))
            sb.append("        <ele>%.1f</ele>\n".format(Locale.US, bc.location.altitude))
            sb.append("        <time>$timeIso</time>\n")
            sb.append("        <extensions>\n")
            sb.append("          <accuracy>%.2f</accuracy>\n".format(Locale.US, accuracy))
            sb.append("          <stride>%.2f</stride>\n".format(Locale.US, stride))
            sb.append("          <is_gps_fix>${bc.isGpsFix}</is_gps_fix>\n")
            sb.append("        </extensions>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return try {
            file.writeText(sb.toString())
            Log.i(TAG, "Successfully exported breadcrumbs to: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save breadcrumbs: ${e.message}", e)
            null
        }
    }
}