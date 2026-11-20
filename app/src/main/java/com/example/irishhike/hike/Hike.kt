package com.example.irishhike.hike

import com.example.irishhike.config.HikeConfig
import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Leg
import com.example.irishhike.model.Location
import com.example.irishhike.model.Route
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

enum class LegState {
    TARGET_REACHED,
    HIKE_ENDED,
    TOO_FAR,
    APPROACHING_TARGET,
    LEG_START,
    OFFCOURSE,
    VEHICLE_SPEED_DETECTED,
    HIKE_SPEED_RESUMED,
    MISSED_WAYPOINT,
    OK
}

data class HikeResult(
    val state: LegState,
    val message: String?
)

class LegAnnouncementTracker {
    var announcedLegStart: Boolean = false
    var announcedApproaching: Boolean = false
    var announcedTooFar: Boolean = false
    var announcedReached: Boolean = false
    var announcedHikeEnded: Boolean = false

    fun reset() {
        announcedLegStart = false
        announcedApproaching = false
        announcedTooFar = false
        announcedReached = false
        announcedHikeEnded = false
    }
}

class Hike(var route: Route?, val config: HikeConfig = HikeConfig()) {
    private val _breadcrumbs = mutableListOf<Breadcrumb>()
    val breadcrumbs: List<Breadcrumb>
        get() = _breadcrumbs.toList()

    var currentLegIndex: Int = 0
        private set

    var currentLeg: Leg? = route?.legs?.firstOrNull()
        private set

    var isCompleted: Boolean = false
        private set

    var isActive: Boolean = true
        private set

    // Maps waypoint coordinate string "${x}_${y}" -> arrival timestamp in ms
    val waypointArrivalTimes: MutableMap<String, Long> = mutableMapOf()

    // Tracking closest approach to current leg target (for bypassed waypoints)
    private var closestDistanceToTarget: Double = Double.MAX_VALUE
    private var closestTargetTimestampMs: Long = 0L

    fun updateRoute(newRoute: Route?) {
        route = newRoute
        val legs = newRoute?.legs ?: emptyList()
        if (currentLegIndex >= legs.size) {
            currentLegIndex = (legs.size - 1).coerceAtLeast(0)
        }
        currentLeg = legs.getOrNull(currentLegIndex)
        resetTargetApproachTracker()
    }

    val tracker = LegAnnouncementTracker()

    private var breadcrumbIdSeq = 1L
    private var previousLocation: Location? = null

    private var lastOffCourseTimeMs: Long = 0L
    private var lastOffCourseOffset: Double = 0.0
    private var consecutiveOffCourseTicks: Int = 0

    fun stop() {
        isActive = false
    }

    private fun wpKey(loc: Location): String = "${loc.x.toInt()}_${loc.y.toInt()}"

    private fun resetTargetApproachTracker() {
        closestDistanceToTarget = Double.MAX_VALUE
        closestTargetTimestampMs = 0L
    }

    fun setCurrentLegIndex(index: Int) {
        val routeLegs = route?.legs ?: return
        if (index in routeLegs.indices) {
            currentLegIndex = index
            currentLeg = routeLegs[index]
            tracker.reset()
            isCompleted = false
            resetTargetApproachTracker()
        }
    }

    fun relativeBearingCorrection(location: Location, ekfState: EkfState? = null): Double {
        val leg = currentLeg ?: return 0.0
        val requiredBearing = location.bearingTo(leg.target)
        val hikerBearing = ekfState?.let { Math.toDegrees(it.bearingRad.value) }
            ?: previousLocation?.bearingTo(location)
            ?: requiredBearing

        var rawDiff = requiredBearing - hikerBearing
        rawDiff %= 360.0
        if (rawDiff > 180.0) rawDiff -= 360.0
        if (rawDiff < -180.0) rawDiff += 360.0
        return rawDiff
    }

    fun bearingCorrectionString(location: Location, ekfState: EkfState? = null): String {
        val correction = relativeBearingCorrection(location, ekfState)
        val rounded = abs(round(correction)).toInt()
        return if (correction >= 0) {
            "plus $rounded degrees"
        } else {
            "minus $rounded degrees"
        }
    }

    fun evaluateLegState(
        location: Location,
        ekfState: EkfState? = null,
        currentTimeMs: Long = System.currentTimeMillis()
    ): LegState {
        val leg = currentLeg ?: return LegState.HIKE_ENDED
        if (isCompleted) return LegState.HIKE_ENDED

        val arriveRadius = if (leg.target.arrive != 10.0) leg.target.arrive else config.arriveThresholdMeters
        val approachRadius = if (leg.target.approach != 40.0) leg.target.approach else config.approachThresholdMeters

        // Continuously update closest approach to current target
        val currentTargetDist = location.distanceTo(leg.target)
        if (currentTargetDist < closestDistanceToTarget) {
            closestDistanceToTarget = currentTargetDist
            closestTargetTimestampMs = currentTimeMs
        }

        // 1. TARGET_REACHED & HIKE_ENDED
        if (!tracker.announcedReached) {
            if (currentTargetDist < arriveRadius) {
                tracker.announcedReached = true
                waypointArrivalTimes[wpKey(leg.target)] = currentTimeMs
                return if (leg.next == null) {
                    tracker.announcedHikeEnded = true
                    LegState.HIKE_ENDED
                } else {
                    LegState.TARGET_REACHED
                }
            }
        }

        if (!tracker.announcedHikeEnded && leg.next == null && currentTargetDist < arriveRadius) {
            tracker.announcedHikeEnded = true
            waypointArrivalTimes[wpKey(leg.target)] = currentTimeMs
            return LegState.HIKE_ENDED
        }

        // 2. MISSED WAYPOINT CHECK: Past target (t >= 1.0) and closer to next waypoint
        val nextLeg = leg.next ?: route?.legs?.getOrNull(currentLegIndex + 1)
        if (nextLeg != null) {
            val nextTargetDist = location.distanceTo(nextLeg.target)

            if (isPastTargetWaypoint(location, leg) && nextTargetDist < currentTargetDist) {
                // Stamped with timestamp when closest to the bypassed waypoint
                waypointArrivalTimes[wpKey(leg.target)] = if (closestTargetTimestampMs > 0L) closestTargetTimestampMs else currentTimeMs
                return LegState.MISSED_WAYPOINT
            }
        }

        // 3. TOO_FAR
        if (!tracker.announcedTooFar) {
            if (isPastTarget(location, leg, arriveRadius)) {
                tracker.announcedTooFar = true
                return LegState.TOO_FAR
            }
        }

        // 4. APPROACHING_TARGET
        if (!tracker.announcedApproaching) {
            if (currentTargetDist < approachRadius) {
                tracker.announcedApproaching = true
                return LegState.APPROACHING_TARGET
            }
        }

        // 5. LEG_START
        if (!tracker.announcedLegStart) {
            if (tracker.announcedReached || tracker.announcedHikeEnded || tracker.announcedTooFar || tracker.announcedApproaching) {
                tracker.announcedLegStart = true
            } else {
                val distanceFromStart = location.distanceTo(leg.start)
                if (distanceFromStart > arriveRadius) {
                    tracker.announcedLegStart = true
                    return LegState.LEG_START
                }
            }
        }

        // 6. OFFCOURSE CHECK
        val currentSpeed = ekfState?.speedMps?.value ?: 0.0
        val posVariance = ekfState?.position?.variance ?: 0.0

        if (currentSpeed >= config.offCourseMinSpeedMps) {
            if (posVariance <= 64.0) {
                val bearingVariance = ekfState?.bearingRad?.variance ?: 0.0
                val bearingStdDevDeg = Math.toDegrees(sqrt(bearingVariance))

                if (bearingStdDevDeg <= 12.0) {
                    val requiredBearing = location.bearingTo(leg.target)
                    val hikerBearing = ekfState?.let { Math.toDegrees(it.bearingRad.value) }
                        ?: previousLocation?.bearingTo(location)
                        ?: requiredBearing

                    val bearingOffset = angleDifference(hikerBearing, requiredBearing)
                    val minAngle = config.offCourseMinAngleDeg

                    if (bearingOffset >= minAngle) {
                        consecutiveOffCourseTicks++
                        if (consecutiveOffCourseTicks >= 2) {
                            val severeAngle = config.offCourseSevereAngleDeg
                            val range = (severeAngle - minAngle).coerceAtLeast(1.0)
                            val t = ((bearingOffset - minAngle) / range).coerceIn(0.0, 1.0)
                            val coolOffSec = config.offCourseMaxCooldownSec - t * (config.offCourseMaxCooldownSec - config.offCourseMinCooldownSec)
                            val coolOffMs = (coolOffSec * 1000.0).toLong()

                            if (currentTimeMs - lastOffCourseTimeMs >= coolOffMs) {
                                lastOffCourseTimeMs = currentTimeMs
                                lastOffCourseOffset = bearingOffset
                                return LegState.OFFCOURSE
                            }
                        }
                    } else if (bearingOffset <= (minAngle - 3.0)) {
                        consecutiveOffCourseTicks = 0
                        lastOffCourseOffset = 0.0
                    }
                }
            }
        } else {
            consecutiveOffCourseTicks = 0
        }

        // 7. OK
        return LegState.OK
    }

    private fun isPastTargetWaypoint(p: Location, leg: Leg): Boolean {
        val abx = leg.target.x - leg.start.x
        val aby = leg.target.y - leg.start.y
        val apx = p.x - leg.start.x
        val apy = p.y - leg.start.y

        val abLenSq = abx * abx + aby * aby
        if (abLenSq == 0.0) return false

        val t = (apx * abx + apy * aby) / abLenSq
        return t >= 1.0
    }

    private fun isPastTarget(p: Location, leg: Leg, arriveRadius: Double): Boolean {
        val abx = leg.target.x - leg.start.x
        val aby = leg.target.y - leg.start.y
        val apx = p.x - leg.start.x
        val apy = p.y - leg.start.y

        val abLenSq = abx * abx + aby * aby
        if (abLenSq == 0.0) return false

        val t = (apx * abx + apy * aby) / abLenSq
        val distToTarget = p.distanceTo(leg.target)

        return t > 1.05 && distToTarget > arriveRadius
    }

    private fun angleDifference(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff < -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0
        return abs(diff)
    }

    fun stringify(state: LegState, leg: Leg?, ekfState: EkfState?, location: Location? = null): String? {
        if (leg == null) return null
        val targetName = leg.target.name.ifBlank { "Next Waypoint" }
        val legDesc = leg.legDescription ?: ""
        val targetDesc = leg.target.description ?: ""

        return when (state) {
            LegState.TARGET_REACHED -> "Reached waypoint $targetName. Proceeding to next leg."
            LegState.HIKE_ENDED -> "Reached waypoint $targetName. Congratulations! You have completed the hike."
            LegState.TOO_FAR -> "You have gone past waypoint $targetName."
            LegState.APPROACHING_TARGET -> "Approaching waypoint $targetName. $targetDesc"
            LegState.LEG_START -> "Starting leg towards $targetName. $legDesc"
            LegState.OFFCOURSE -> {
                val loc = location ?: previousLocation ?: leg.start
                bearingCorrectionString(loc, ekfState)
            }
            LegState.VEHICLE_SPEED_DETECTED -> "Vehicle speed detected. Step tracking paused."
            LegState.HIKE_SPEED_RESUMED -> "Hike speed resumed. Step tracking active."
            LegState.MISSED_WAYPOINT -> "Waypoint $targetName passed. Moving to next waypoint."
            LegState.OK -> null
        }
    }

    private var vehicleSpeedStartTimeMs: Long = 0L
    private var normalSpeedStartTimeMs: Long = 0L
    var isVehicleSpeedDetected: Boolean = false
        private set

    fun checkVehicleSpeed(speedMps: Double, currentTimeMs: Long): HikeResult? {
        if (!isActive) return null

        if (speedMps > 3.5) {
            normalSpeedStartTimeMs = 0L
            if (vehicleSpeedStartTimeMs == 0L) {
                vehicleSpeedStartTimeMs = currentTimeMs
            } else if (!isVehicleSpeedDetected && (currentTimeMs - vehicleSpeedStartTimeMs >= 10000L)) {
                isVehicleSpeedDetected = true
                return HikeResult(
                    LegState.VEHICLE_SPEED_DETECTED,
                    "Vehicle speed detected (%.0f km/h). Step tracking paused.".format(speedMps * 3.6)
                )
            }
        } else if (speedMps < 2.5) {
            vehicleSpeedStartTimeMs = 0L
            if (isVehicleSpeedDetected) {
                if (normalSpeedStartTimeMs == 0L) {
                    normalSpeedStartTimeMs = currentTimeMs
                } else if (currentTimeMs - normalSpeedStartTimeMs >= 3000L) {
                    isVehicleSpeedDetected = false
                    return HikeResult(
                        LegState.HIKE_SPEED_RESUMED,
                        "Hike speed resumed. Step tracking active."
                    )
                }
            }
        } else {
            vehicleSpeedStartTimeMs = 0L
            normalSpeedStartTimeMs = 0L
        }
        return null
    }

    @Synchronized
    fun update(
        location: Location,
        timestampMs: Long = System.currentTimeMillis(),
        isGpsFix: Boolean = false,
        ekfState: EkfState? = null
    ): HikeResult? {
        if (!isActive) return null

        val speedMps = ekfState?.speedMps?.value ?: 0.0
        val vehicleResult = checkVehicleSpeed(speedMps, timestampMs)
        if (vehicleResult != null) {
            return vehicleResult
        }

        val breadcrumb = Breadcrumb(
            id = breadcrumbIdSeq++,
            location = location,
            timestampMs = timestampMs,
            isGpsFix = isGpsFix,
            ekfState = ekfState
        )
        _breadcrumbs.add(breadcrumb)

        val currentRoute = route
        if (currentRoute == null || currentRoute.legs.isEmpty()) {
            previousLocation = location
            return null
        }

        // Stamp start waypoint with time of first fix
        val startWp = currentRoute.waypoints.firstOrNull()
        if (startWp != null && !waypointArrivalTimes.containsKey(wpKey(startWp))) {
            waypointArrivalTimes[wpKey(startWp)] = timestampMs
        }

        if (isCompleted) {
            return null
        }

        val leg = currentLeg ?: run {
            isCompleted = true
            return null
        }

        val state = evaluateLegState(location, ekfState, timestampMs)

        if (state == LegState.TARGET_REACHED || state == LegState.MISSED_WAYPOINT) {
            val nextLeg = leg.next ?: route?.legs?.getOrNull(currentLegIndex + 1)
            if (nextLeg != null) {
                currentLeg = nextLeg
                currentLegIndex++
                tracker.reset()
                resetTargetApproachTracker()
            } else {
                isCompleted = true
            }
        } else if (state == LegState.HIKE_ENDED) {
            isCompleted = true
        }

        val msg = stringify(state, leg, ekfState, location)
        previousLocation = location
        return HikeResult(state, msg)
    }
}