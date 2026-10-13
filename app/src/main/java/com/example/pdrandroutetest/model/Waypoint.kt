package com.example.pdrandroutetest.model

import com.example.pdrandroutetest.util.CoordinateUtils

class Waypoint(
    override val x: Double = 0.0,
    override val y: Double = 0.0,
    override val altitude: Double = 0.0,
    val name: String = "",
    val description: String = "",
    var legDescription: String? = null
) : Location(x, y, altitude) {

    companion object {
        fun fromGridReference(
            name: String,
            gridRefStr: String,
            altitude: Double = 0.0,
            threshold: Double? = null,
            description: String = "",
            legDescription: String? = null
        ): Waypoint {
            val (lat, lon) = CoordinateUtils.metricToLatLon(Location(0.0, 0.0)) // internal helper call placeholder
            val loc = CoordinateUtils.latLonToMetric(lat, lon)
            return Waypoint(
                x = loc.x,
                y = loc.y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription
            )
        }

        fun fromLatLon(
            name: String,
            lat: Double,
            lon: Double,
            altitude: Double = 0.0,
            threshold: Double? = null,
            description: String = "",
            legDescription: String? = null
        ): Waypoint {
            val loc = CoordinateUtils.latLonToMetric(lat, lon)
            return Waypoint(
                x = loc.x,
                y = loc.y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription
            )
        }

        fun fromMetric(
            name: String,
            x: Double,
            y: Double,
            altitude: Double = 0.0,
            description: String = "",
            legDescription: String? = null
        ): Waypoint {
            return Waypoint(
                x = x,
                y = y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription
            )
        }
    }
}
