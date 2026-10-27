package com.example.irishhike.ui

import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Route

internal class ViewportTracker {
    var minX = 0.0
    var maxX = 100.0
    var minY = 0.0
    var maxY = 100.0

    private var lastObservedSize = 0

    fun updateAll(route: Route?, breadcrumbs: List<Breadcrumb>, activeEkfState: EkfState) {
        if (breadcrumbs.size == lastObservedSize && route == null) return
        lastObservedSize = breadcrumbs.size

        var minXVal = Double.MAX_VALUE
        var maxXVal = -Double.MAX_VALUE
        var minYVal = Double.MAX_VALUE
        var maxYVal = -Double.MAX_VALUE
        var hasPoints = false

        route?.waypoints?.forEach { wp ->
            if (wp.x < minXVal) minXVal = wp.x
            if (wp.x > maxXVal) maxXVal = wp.x
            if (wp.y < minYVal) minYVal = wp.y
            if (wp.y > maxYVal) maxYVal = wp.y
            hasPoints = true
        }

        val sampledBreadcrumbs = if (breadcrumbs.size > 1000) {
            breadcrumbs.filterIndexed { index, _ -> index % 5 == 0 }
        } else {
            breadcrumbs
        }

        sampledBreadcrumbs.forEach { bc ->
            val px = bc.location.x
            val py = bc.location.y
            if (px < minXVal) minXVal = px
            if (px > maxXVal) maxXVal = px
            if (py < minYVal) minYVal = py
            if (py > maxYVal) maxYVal = py
            hasPoints = true
        }

        if (activeEkfState.isInitializedWithGps) {
            val ax = activeEkfState.position.value.x
            val ay = activeEkfState.position.value.y
            if (ax < minXVal) minXVal = ax
            if (ax > maxXVal) maxXVal = ax
            if (ay < minYVal) minYVal = ay
            if (ay > maxYVal) maxYVal = ay
            hasPoints = true
        }

        if (!hasPoints) {
            minXVal = 0.0; maxXVal = 100.0; minYVal = 0.0; maxYVal = 100.0
        }

        val spanX = (maxXVal - minXVal).coerceAtLeast(1000.0)
        val spanY = (maxYVal - minYVal).coerceAtLeast(1000.0)
        val marginX = spanX * 0.15
        val marginY = spanY * 0.15

        minX = minXVal - marginX
        maxX = maxXVal + marginX
        minY = minYVal - marginY
        maxY = maxYVal + marginY
    }
}
