package com.example.pdrandroutetest.model

class Leg(
    val start: Waypoint,
    val target: Waypoint,
    var next: Leg? = null,
    var legDescription: String? = null
) {
    fun getNextLeg(): Leg? = next

    val distanceMeters: Double
        get() = start.getDistance(target)
}
