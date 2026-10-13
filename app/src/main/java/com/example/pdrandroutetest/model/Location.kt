package com.example.pdrandroutetest.model

import com.example.pdrandroutetest.util.CoordinateUtils

open class Location(
    open val x: Double = 0.0,
    open val y: Double = 0.0,
    open val altitude: Double = 0.0
) {
    fun getDistance(other: Location): Double {
        return CoordinateUtils.getDistance(this, other)
    }

    fun getBearing(other: Location): Double {
        return CoordinateUtils.getBearing(this, other)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Location) return false
        return x == other.x && y == other.y && altitude == other.altitude
    }

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + altitude.hashCode()
        return result
    }

    override fun toString(): String {
        return "Location(x=$x, y=$y, altitude=$altitude)"
    }
}
