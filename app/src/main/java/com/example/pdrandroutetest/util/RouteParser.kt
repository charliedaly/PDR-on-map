package com.example.pdrandroutetest.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pdrandroutetest.model.Waypoint
import java.io.BufferedReader
import java.io.StringReader

object RouteParser {
    /**
     * Queries OpenableColumns.DISPLAY_NAME via context.contentResolver when given a content:// URI.
     * Extracts the display name and strips the extension (e.g. "glounthaune.csv" -> "glounthaune").
     */
    fun extractRouteNameFromUri(context: Context, uri: Uri, fallbackName: String? = null): String {
        var displayName: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (columnIndex != -1) {
                            displayName = cursor.getString(columnIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to uri.lastPathSegment or fallbackName if query fails
            }
        }
        if (displayName.isNullOrBlank()) {
            displayName = fallbackName ?: uri.lastPathSegment
        }
        if (displayName.isNullOrBlank()) {
            displayName = "Imported Route"
        }
        return displayName.substringBeforeLast(".")
    }

    fun extractRouteNameFromUri(context: Context, uriString: String, fallbackName: String? = null): String {
        return try {
            val uri = Uri.parse(uriString)
            extractRouteNameFromUri(context, uri, fallbackName)
        } catch (e: Exception) {
            (fallbackName ?: uriString.substringAfterLast("/")).substringBeforeLast(".")
        }
    }

    /**
     * Parses CSV route content into a list of Waypoints.
     */
    fun parseCsv(csvContent: String): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        val reader = BufferedReader(StringReader(csvContent))
        val lines = reader.readLines()
        if (lines.isEmpty()) return waypoints

        val headerLine = lines[0].trim()
        val headers = parseCsvLine(headerLine).map { it.lowercase().trim() }

        val nameIdx = headers.indexOfFirst { it in listOf("name", "title") }
        val gridIdx = headers.indexOfFirst { it in listOf("grid_ref", "grid", "location") }
        val latIdx = headers.indexOfFirst { it in listOf("latitude", "lat") }
        val lonIdx = headers.indexOfFirst { it in listOf("longitude", "lon") }
        val altIdx = headers.indexOfFirst { it in listOf("altitude", "alt", "elevation", "ele") }
        val descIdx = headers.indexOfFirst { it in listOf("description", "desc", "waypoint_desc", "waypoint_description", "notes", "comments") }
        val legDescIdx = headers.indexOfFirst { it in listOf("leg_description", "leg_desc", "leg_notes", "leg_comments") }

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val tokens = parseCsvLine(line)
            if (tokens.isEmpty()) continue

            val name = if (nameIdx >= 0 && nameIdx < tokens.size) {
                tokens[nameIdx].trim().removeSurrounding("\"", "\"")
            } else {
                "Waypoint $i"
            }

            val alt = if (altIdx >= 0 && altIdx < tokens.size) {
                tokens[altIdx].trim().removeSurrounding("\"", "\"").toDoubleOrNull() ?: 0.0
            } else {
                0.0
            }

            val description = if (descIdx >= 0 && descIdx < tokens.size) {
                tokens[descIdx].trim().removeSurrounding("\"", "\"")
            } else {
                ""
            }

            val legDescription = if (legDescIdx >= 0 && legDescIdx < tokens.size) {
                tokens[legDescIdx].trim().removeSurrounding("\"", "\"")
            } else {
                ""
            }

            val waypoint = if (gridIdx >= 0 && gridIdx < tokens.size && tokens[gridIdx].isNotBlank()) {
                val gridRef = tokens[gridIdx].trim().removeSurrounding("\"", "\"")
                val loc = CoordinateUtils.parseGridRefToLocation(gridRef, alt)
                Waypoint(
                    x = loc.x,
                    y = loc.y,
                    altitude = alt,
                    name = name,
                    description = description,
                    legDescription = legDescription
                )
            } else if (latIdx >= 0 && lonIdx >= 0 && latIdx < tokens.size && lonIdx < tokens.size) {
                val lat = tokens[latIdx].trim().removeSurrounding("\"", "\"").toDoubleOrNull() ?: 0.0
                val lon = tokens[lonIdx].trim().removeSurrounding("\"", "\"").toDoubleOrNull() ?: 0.0
                Waypoint.fromLatLon(
                    name = name,
                    lat = lat,
                    lon = lon,
                    altitude = alt,
                    description = description,
                    legDescription = legDescription
                )
            } else {
                continue
            }

            waypoints.add(waypoint)
        }

        return waypoints
    }

    fun generateSampleIrishGridCsv(): String {
        return """
            name,grid_ref,altitude,leg_description
            Start,V860870,120.0,Heading north toward Colt
            Colt,V875882,450.0,Ascend ridge toward Summit
            Summit,V890895,850.0,Follow ridge line down
            Ridge,V905910,650.0,Descend to finish point
            Finish,V920925,100.0,Route complete
        """.trimIndent()
    }

    private fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        tokens.add(sb.toString())
        return tokens
    }
}
