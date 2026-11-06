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

    fun updateRoute(newRoute: Route?) {
        route = newRoute
        val legs = newRoute?.legs ?: emptyList()
        if (currentLegIndex >= legs.size) {
            currentLegIndex = (legs.size - 1).coerceAtLeast(0)
        }
        currentLeg = legs.getOrNull(currentLegIndex)
    }

    val tracker = LegAnnouncementTracker()

    private var breadcrumbIdSeq = 1L
    private var previousLocation: Location? = null

    private var lastOffCourseTimeMs: Long = 0L
    private var lastOffCourseOffset: Double = 0.0

    fun stop() {
        isActive = false
    }

    fun setCurrentLegIndex(index: Int) {
        val routeLegs = route?.legs ?: return
        if (index in routeLegs.indices) {
            currentLegIndex = index
            currentLeg = routeLegs[index]
            tracker.reset()
            isCompleted = false
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

        // 1. TARGET_REACHED & 2. HIKE_ENDED
        if (!tracker.announcedReached) {
            val distanceToTarget = location.distanceTo(leg.target)
            if (distanceToTarget < arriveRadius) {
                tracker.announcedReached = true
                return if (leg.next == null) {
                    tracker.announcedHikeEnded = true
                    LegState.HIKE_ENDED
                } else {
                    LegState.TARGET_REACHED
                }
            }
        }

        // 2. HIKE_ENDED (fallback check if already marked)
        if (!tracker.announcedHikeEnded && leg.next == null && location.distanceTo(leg.target) < arriveRadius) {
            tracker.announcedHikeEnded = true
            return LegState.HIKE_ENDED
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
            val distanceToTarget = location.distanceTo(leg.target)
            if (distanceToTarget < approachRadius) {
                tracker.announcedApproaching = true
                return LegState.APPROACHING_TARGET
            }
        }

        // 5. LEG_START
        if (!tracker.announcedLegStart) {
            // Do not play LEG_START if any of TARGET_REACHED, APPROACHING_TARGET, HIKE_ENDED, or TOO_FAR have occurred
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

        // 6. OFFCOURSE
// 6. OFFCOURSE CHECK
        val currentSpeed = ekfState?.speedMps?.value ?: 0.0
        if (currentSpeed >= config.offCourseMinSpeedMps) {
            val bearingVariance = ekfState?.bearingRad?.variance ?: 0.0
            val bearingStdDevDeg = Math.toDegrees(sqrt(bearingVariance))

            // GATE 1: Confidence Gate
            // Only trigger off-course guidance if the bearing estimate is reliable (<= 12.0°)
            // If the hiker is paused, turning in place, or phone is swinging, wait until stable
            if (bearingStdDevDeg <= 12.0) {
                val requiredBearing = location.bearingTo(leg.target)
                val hikerBearing = ekfState?.let { Math.toDegrees(it.bearingRad.value) }
                    ?: previousLocation?.bearingTo(location)
                    ?: requiredBearing

                val bearingOffset = angleDifference(hikerBearing, requiredBearing)

                // GATE 2: Statistically Confident Threshold
                // Base tolerance (e.g. 10°) plus 2-sigma sensor uncertainty (~8-10° when walking)
                // Creates an adaptive ~18° to 20° corridor around the path
                val twoSigmaUncertaintyDeg = 2.0 * bearingStdDevDeg
                val dynamicThreshold = config.offCourseHeadingDevDeg + twoSigmaUncertaintyDeg

                if (bearingOffset > dynamicThreshold) {
                    val isDriftingFurther = bearingOffset > (lastOffCourseOffset + 2.0)
                    val coolOffMs = if (isDriftingFurther) {
                        config.driftCoolOffSec * 1000L      // Rapid reminder (4s) if walking further astray
                    } else {
                        config.steadyCoolOffSec * 1000L     // Gentle reminder (6s) if holding steady offset
                    }

                    if (currentTimeMs - lastOffCourseTimeMs >= coolOffMs) {
                        lastOffCourseTimeMs = currentTimeMs
                        lastOffCourseOffset = bearingOffset
                        return LegState.OFFCOURSE
                    }
                } else if (bearingOffset <= config.offCourseHeadingDevDeg) {
                    // RESET: When back on track, reset the baseline so the next drift is caught immediately
                    lastOffCourseOffset = 0.0
                }
            }
        }

        // 7. OK
        return LegState.OK
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
            LegState.TARGET_REACHED -> {
                "Reached waypoint $targetName. Proceeding to next leg."
            }
            LegState.HIKE_ENDED -> {
                "Reached waypoint $targetName. Congratulations! You have completed the hike."
            }
            LegState.TOO_FAR -> {
                "Warning! You have gone past waypoint $targetName."
            }
            LegState.APPROACHING_TARGET -> {
                "Approaching waypoint $targetName. $targetDesc"
            }
            LegState.LEG_START -> {
                "Starting leg towards $targetName. $legDesc"
            }
            LegState.OFFCOURSE -> {
                val loc = location ?: previousLocation ?: leg.start
                bearingCorrectionString(loc, ekfState)
            }
            LegState.VEHICLE_SPEED_DETECTED -> {
                "Vehicle speed detected. Step tracking paused."
            }
            LegState.HIKE_SPEED_RESUMED -> {
                "Hike speed resumed. Step tracking active."
            }
            LegState.OK -> {
                null
            }
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

    // gemini 3.8 suggests wrapping update in synchronized cos of possible lost update.
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

        if (isCompleted) {
            return null
        }

        val leg = currentLeg ?: run {
            isCompleted = true
            return null
        }

        val state = evaluateLegState(location, ekfState, timestampMs)

        if (state == LegState.TARGET_REACHED) {
            val nextLeg = leg.next
            if (nextLeg != null) {
                currentLeg = nextLeg
                currentLegIndex++
                tracker.reset()
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
