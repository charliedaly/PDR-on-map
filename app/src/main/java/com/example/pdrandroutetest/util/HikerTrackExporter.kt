package com.example.pdrandroutetest.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.example.pdrandroutetest.model.Breadcrumb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

object HikerTrackExporter {

    private const val TAG = "HikerTrackExporter"

    fun exportBreadcrumbsToGpx(
        context: Context,
        breadcrumbs: List<Breadcrumb>,
        routeUri: Uri? = null
    ): File? {
        if (breadcrumbs.isEmpty()) return null

        val targetDir = resolveTargetDirectory(context, routeUri)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

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
            sb.append("        </extensions>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return try {
            file.writeText(sb.toString())
            Log.i(TAG, "Successfully exported breadcrumbs to: ${file.absolutePath}")
            Toast.makeText(context, "Saved breadcrumbs to:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save breadcrumbs: ${e.message}", e)
            Toast.makeText(context, "Failed to save breadcrumbs: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun resolveTargetDirectory(context: Context, routeUri: Uri?): File {
        if (routeUri != null) {
            val uriString = routeUri.toString()
            Log.d(TAG, "Resolving target directory for routeUri: $uriString")

            if (routeUri.scheme == "file") {
                val file = File(routeUri.path ?: "")
                file.parentFile?.let {
                    Log.d(TAG, "Resolved parent dir from file scheme: ${it.absolutePath}")
                    return it
                }
            }

            val decoded = Uri.decode(uriString)
            val rawPath = when {
                decoded.contains("raw:") -> decoded.substringAfter("raw:")
                decoded.startsWith("/storage/") -> decoded
                else -> null
            }
            if (rawPath != null) {
                val file = File(rawPath)
                val parent = file.parentFile
                if (parent != null) {
                    Log.d(TAG, "Resolved parent dir from content raw path: ${parent.absolutePath}")
                    return parent
                }
            }

            try {
                context.contentResolver.query(routeUri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        if (colIdx != -1) {
                            val dataPath = cursor.getString(colIdx)
                            if (!dataPath.isNullOrBlank()) {
                                val file = File(dataPath)
                                file.parentFile?.let {
                                    Log.d(TAG, "Resolved parent dir via MediaColumns.DATA: ${it.absolutePath}")
                                    return it
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query MediaColumns.DATA: ${e.message}")
            }
        }

        val fallback = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "freehike")
        Log.d(TAG, "Fallback to freehike dir: ${fallback.absolutePath}")
        return fallback
    }
}
