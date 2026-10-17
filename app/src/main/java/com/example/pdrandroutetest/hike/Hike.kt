package com.example.pdrandroutetest.hike

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Leg
import com.example.pdrandroutetest.model.Location
import com.example.pdrandroutetest.model.Route
import kotlin.math.abs
import kotlin.math.round

enum class LegState {
    TARGET_REACHED,
    HIKE_ENDED,
    TOO_FAR,
    APPROACHING_TARGET,
    LEG_START,
    OFFCOURSE,
    OK
}

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

class Hike(val route: Route?) {
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

    var driftCoolOffSec: Long = 4L
    var steadyCoolOffSec: Long = 6L

    val tracker = LegAnnouncementTracker()

    private var breadcrumbIdSeq = 1L
    private var previousLocation: Location? = null

    private var lastOffCourseTimeMs: Long = 0L
    private var lastOffCourseOffset: Double = 0.0

    fun stop() {
        isActive = false
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

        // 1. TARGET_REACHED & 2. HIKE_ENDED
        if (!tracker.announcedReached) {
            val distanceToTarget = location.distanceTo(leg.target)
            if (distanceToTarget < leg.target.arrive) {
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
        if (!tracker.announcedHikeEnded && leg.next == null && location.distanceTo(leg.target) < leg.target.arrive) {
            tracker.announcedHikeEnded = true
            return LegState.HIKE_ENDED
        }

        // 3. TOO_FAR
        if (!tracker.announcedTooFar) {
            if (isPastTarget(location, leg)) {
                tracker.announcedTooFar = true
                return LegState.TOO_FAR
            }
        }

        // 4. APPROACHING_TARGET
        if (!tracker.announcedApproaching) {
            val distanceToTarget = location.distanceTo(leg.target)
            if (distanceToTarget < leg.target.approach) {
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
                if (distanceFromStart > leg.start.arrive) {
                    tracker.announcedLegStart = true
                    return LegState.LEG_START
                }
            }
        }

        // 6. OFFCOURSE
        val requiredBearing = location.bearingTo(leg.target)
        val hikerBearing = ekfState?.let { Math.toDegrees(it.bearingRad.value) }
            ?: previousLocation?.bearingTo(location)
            ?: requiredBearing

        val bearingOffset = angleDifference(hikerBearing, requiredBearing)
        if (bearingOffset > 10.0) {
            val isDriftingFurther = bearingOffset > (lastOffCourseOffset + 2.0)
            val coolOffMs = if (isDriftingFurther) driftCoolOffSec * 1000L else steadyCoolOffSec * 1000L

            if (currentTimeMs - lastOffCourseTimeMs >= coolOffMs) {
                lastOffCourseTimeMs = currentTimeMs
                lastOffCourseOffset = bearingOffset
                return LegState.OFFCOURSE
            }
        }

        // 7. OK
        return LegState.OK
    }

    private fun isPastTarget(p: Location, leg: Leg): Boolean {
        val abx = leg.target.x - leg.start.x
        val aby = leg.target.y - leg.start.y
        val apx = p.x - leg.start.x
        val apy = p.y - leg.start.y

        val abLenSq = abx * abx + aby * aby
        if (abLenSq == 0.0) return false

        val t = (apx * abx + apy * aby) / abLenSq
        val distToTarget = p.distanceTo(leg.target)

        return t > 1.05 && distToTarget > leg.target.arrive
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
            LegState.OK -> {
                null
            }
        }
    }

    fun update(
        location: Location,
        timestampMs: Long = System.currentTimeMillis(),
        isGpsFix: Boolean = false,
        ekfState: EkfState? = null
    ): String? {
        if (!isActive) return null

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
        return msg
    }
}
