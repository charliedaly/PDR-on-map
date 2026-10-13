package com.example.pdrandroutetest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Route
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class ViewportTracker {
    var minX = 0.0
    var maxX = 100.0
    var minY = 0.0
    var maxY = 100.0

    fun updateAll(route: Route?, breadcrumbs: List<Breadcrumb>, activeEkfState: EkfState) {
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

        breadcrumbs.forEach { bc ->
            val px = bc.location.x
            val py = bc.location.y
            if ((px != 0.0 || py != 0.0) || route == null) {
                if (px < minXVal) minXVal = px
                if (px > maxXVal) maxXVal = px
                if (py < minYVal) minYVal = py
                if (py > maxYVal) maxYVal = py
                hasPoints = true
            }
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

        val spanX = (maxXVal - minXVal).coerceAtLeast(20.0)
        val spanY = (maxYVal - minYVal).coerceAtLeast(20.0)
        val marginX = spanX * 0.15
        val marginY = spanY * 0.15

        minX = minXVal - marginX
        maxX = maxXVal + marginX
        minY = minYVal - marginY
        maxY = maxYVal + marginY
    }
}

@Composable
fun MapScreen(
    route: Route?,
    breadcrumbs: List<Breadcrumb>,
    activeEkfState: EkfState,
    modifier: Modifier = Modifier
) {
    val viewportTracker = remember { ViewportTracker() }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val validBreadcrumbs = remember(breadcrumbs, route) {
        breadcrumbs.filter { it.location.x != 0.0 || it.location.y != 0.0 || route == null }
    }

    viewportTracker.updateAll(
        route = route,
        breadcrumbs = validBreadcrumbs,
        activeEkfState = activeEkfState
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0F1A))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    zoomScale = (zoomScale * zoom).coerceIn(0.2f, 15f)
                    panOffset += pan
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val widthPx = size.width
            val heightPx = size.height

            val spanX = (viewportTracker.maxX - viewportTracker.minX).coerceAtLeast(1.0)
            val spanY = (viewportTracker.maxY - viewportTracker.minY).coerceAtLeast(1.0)

            val scaleX = (widthPx * 0.90f) / spanX.toFloat()
            val scaleY = (heightPx * 0.90f) / spanY.toFloat()
            val baseScale = minOf(scaleX, scaleY)

            val originCx = widthPx / 2f
            val originCy = heightPx / 2f

            val centerX = (viewportTracker.minX + viewportTracker.maxX) / 2.0
            val centerY = (viewportTracker.minY + viewportTracker.maxY) / 2.0

            fun toCanvasOffset(xMeters: Double, yMeters: Double): Offset {
                val rawCx = originCx + ((xMeters - centerX) * baseScale).toFloat()
                val rawCy = originCy - ((yMeters - centerY) * baseScale).toFloat()

                val zoomedX = originCx + (rawCx - originCx) * zoomScale + panOffset.x
                val zoomedY = originCy + (rawCy - originCy) * zoomScale + panOffset.y

                return Offset(zoomedX, zoomedY)
            }

            // Draw route legs (path lines)
            route?.legs?.forEach { leg ->
                val p1 = toCanvasOffset(leg.start.x, leg.start.y)
                val p2 = toCanvasOffset(leg.target.x, leg.target.y)
                drawLine(
                    color = Color(0xFF4CAF50),
                    start = p1,
                    end = p2,
                    strokeWidth = 4f * zoomScale.coerceIn(0.5f, 3f)
                )
            }

            // Draw route waypoints
            route?.waypoints?.forEach { wp ->
                val center = toCanvasOffset(wp.x, wp.y)
                drawCircle(
                    color = Color(0xFF81C784).copy(alpha = 0.35f),
                    radius = 16f * zoomScale.coerceIn(0.5f, 2.5f),
                    center = center
                )
                drawCircle(
                    color = Color(0xFF4CAF50),
                    radius = 6f * zoomScale.coerceIn(0.5f, 2.5f),
                    center = center
                )
            }

            // Draw historical breadcrumb path trajectory lines
            if (validBreadcrumbs.size >= 2) {
                val path = Path()
                val firstPoint = toCanvasOffset(validBreadcrumbs[0].location.x, validBreadcrumbs[0].location.y)
                path.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until validBreadcrumbs.size) {
                    val point = toCanvasOffset(validBreadcrumbs[i].location.x, validBreadcrumbs[i].location.y)
                    path.lineTo(point.x, point.y)
                }

                drawPath(
                    path = path,
                    color = Color(0xFF00E5FF),
                    style = Stroke(width = 3f * zoomScale.coerceIn(0.5f, 3f))
                )
            }

            // Draw historical breadcrumbs (cyan for PDR, bright red for GPS fixes)
            validBreadcrumbs.forEach { bc ->
                val center = toCanvasOffset(bc.location.x, bc.location.y)
                val dotColor = if (bc.isGpsFix) Color(0xFFFF3333) else Color(0xFF00E5FF)
                val haloColor = if (bc.isGpsFix) Color(0xFFFF3333).copy(alpha = 0.35f) else Color(0xFF00E5FF).copy(alpha = 0.15f)
                val dotRadius = if (bc.isGpsFix) 5.5f else 3f

                drawCircle(
                    color = haloColor,
                    radius = 8f * zoomScale.coerceIn(0.5f, 2.5f),
                    center = center
                )

                drawCircle(
                    color = dotColor,
                    radius = dotRadius * zoomScale.coerceIn(0.5f, 2.5f),
                    center = center
                )
            }

            // Draw current active EKF position indicator (only if initialized)
            if (activeEkfState.isInitializedWithGps) {
                val activePos = activeEkfState.position.value
                val currentCenter = toCanvasOffset(activePos.x, activePos.y)
                val currentRadiusPx = (sqrt(activeEkfState.position.variance) * baseScale.toDouble() * zoomScale.toDouble()).toFloat().coerceIn(6f, 60f)

                drawCircle(
                    color = Color(0xFFFFD700).copy(alpha = 0.25f),
                    radius = currentRadiusPx,
                    center = currentCenter
                )

                val bearing = activeEkfState.bearingRad.value
                val headingError = sqrt(activeEkfState.bearingRad.variance)
                val arrowLengthPx = 32f * zoomScale.coerceIn(0.5f, 2.5f)

                val wedgePath = Path().apply {
                    moveTo(currentCenter.x, currentCenter.y)
                    val leftAngle = bearing - headingError
                    val rightAngle = bearing + headingError

                    val leftX = currentCenter.x + (arrowLengthPx * 1.4f * sin(leftAngle)).toFloat()
                    val leftY = currentCenter.y - (arrowLengthPx * 1.4f * cos(leftAngle)).toFloat()
                    val rightX = currentCenter.x + (arrowLengthPx * 1.4f * sin(rightAngle)).toFloat()
                    val rightY = currentCenter.y - (arrowLengthPx * 1.4f * cos(rightAngle)).toFloat()

                    lineTo(leftX, leftY)
                    lineTo(rightX, rightY)
                    close()
                }
                drawPath(
                    path = wedgePath,
                    color = Color(0xFFFFD700).copy(alpha = 0.3f)
                )

                val arrowTipX = currentCenter.x + (arrowLengthPx * sin(bearing)).toFloat()
                val arrowTipY = currentCenter.y - (arrowLengthPx * cos(bearing)).toFloat()

                drawLine(
                    color = Color(0xFFFFD700),
                    start = currentCenter,
                    end = Offset(arrowTipX, arrowTipY),
                    strokeWidth = 5f
                )

                drawCircle(
                    color = Color(0xFFFFD700),
                    radius = 6f,
                    center = currentCenter
                )
            }
        }

        // Status overlay badge
        val validGpsCount = validBreadcrumbs.count { it.isGpsFix }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            val routeText = if (route != null) "${route.name} (${route.waypoints.size} WPs, ${route.legs.size} Legs)" else "No Route Loaded"
            Text(
                text = "$routeText | BCs: ${validBreadcrumbs.size} (GPS: $validGpsCount)",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        // Recenter / Fit All view button if zoomed or panned
        if (zoomScale != 1f || panOffset != Offset.Zero) {
            Button(
                onClick = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            ) {
                Text("Fit All", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
