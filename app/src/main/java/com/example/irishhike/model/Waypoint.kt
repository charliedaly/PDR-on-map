package com.example.irishhike.model

import com.example.irishhike.util.CoordinateUtils

class Waypoint(
    override val x: Double = 0.0,
    override val y: Double = 0.0,
    override val altitude: Double = 0.0,
    val name: String = "",
    val description: String = "",
    var legDescription: String? = null,
    val arrive: Double = 10.0,
    val approach: Double = 40.0
) : Location(x, y, altitude) {

    companion object {
        fun fromGridReference(
            name: String,
            gridRefStr: String,
            altitude: Double = 0.0,
            threshold: Double? = null,
            description: String = "",
            legDescription: String? = null,
            arrive: Double = 15.0,
            approach: Double = 100.0
        ): Waypoint {
            val loc = CoordinateUtils.parseGridRefToLocation(gridRefStr, altitude)
            return Waypoint(
                x = loc.x,
                y = loc.y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription,
                arrive = threshold ?: arrive,
                approach = approach
            )
        }

        fun fromLatLon(
            name: String,
            lat: Double,
            lon: Double,
            altitude: Double = 0.0,
            threshold: Double? = null,
            description: String = "",
            legDescription: String? = null,
            arrive: Double = 15.0,
            approach: Double = 100.0
        ): Waypoint {
            val loc = CoordinateUtils.latLonToMetric(lat, lon)
            return Waypoint(
                x = loc.x,
                y = loc.y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription,
                arrive = threshold ?: arrive,
                approach = approach
            )
        }

        fun fromMetric(
            name: String,
            x: Double,
            y: Double,
            altitude: Double = 0.0,
            description: String = "",
            legDescription: String? = null,
            arrive: Double = 15.0,
            approach: Double = 100.0
        ): Waypoint {
            return Waypoint(
                x = x,
                y = y,
                altitude = altitude,
                name = name,
                description = description,
                legDescription = legDescription,
                arrive = arrive,
                approach = approach
            )
        }
    }
}
