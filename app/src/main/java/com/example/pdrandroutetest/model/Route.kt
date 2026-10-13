package com.example.pdrandroutetest.model

import com.example.pdrandroutetest.util.RouteParser

class Route(
    val name: String,
    val waypoints: List<Waypoint>,
    val legs: List<Leg>
) {
    val totalLengthMeters: Double
        get() = legs.sumOf { it.distanceMeters }

    val numLegs: Int
        get() = legs.size

    companion object {
        fun createFromWaypoints(name: String, rawWaypoints: List<Waypoint>): Route {
            if (rawWaypoints.isEmpty()) {
                return Route(name, emptyList(), emptyList())
            }

            // 1. Process legs sequentially and fill in next pointer & leg descriptions
            val legs = mutableListOf<Leg>()
            for (i in 0 until rawWaypoints.size - 1) {
                val startWp = rawWaypoints[i]
                val targetWp = rawWaypoints[i + 1]
                val legDesc = startWp.legDescription
                val leg = Leg(start = startWp, target = targetWp, legDescription = legDesc)
                legs.add(leg)
            }

            // Wire next pointers
            for (i in 0 until legs.size - 1) {
                legs[i].next = legs[i + 1]
            }

            // Clear legDescription on start waypoints now that legs hold them
            rawWaypoints.forEach { it.legDescription = null }

            // 2. Remove duplicate waypoints by position (matching x and y)
            val uniqueWaypoints = mutableListOf<Waypoint>()
            for (wp in rawWaypoints) {
                val exists = uniqueWaypoints.any { it.x == wp.x && it.y == wp.y }
                if (!exists) {
                    uniqueWaypoints.add(wp)
                }
            }

            return Route(name = name, waypoints = uniqueWaypoints, legs = legs)
        }

        fun parseFromCsvContent(routeName: String, csvContent: String): Route {
            val waypoints = RouteParser.parseCsv(csvContent)
            return createFromWaypoints(routeName, waypoints)
        }
    }
}
