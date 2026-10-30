package com.example.irishhike.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.irishhike.config.BreadcrumbColorMode
import com.example.irishhike.config.BreadcrumbConfig
import com.example.irishhike.config.HikeConfig
import com.example.irishhike.config.MapConfig
import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Leg
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import com.example.irishhike.util.CoordinateUtils
import com.example.irishhike.util.OsmTileManager
import com.example.irishhike.util.TileDownloadManifest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MapScreen(
    route: Route?,
    breadcrumbs: List<Breadcrumb>,
    activeEkfState: EkfState,
    currentTargetWaypoint: Waypoint? = null,
    mapConfig: MapConfig = MapConfig(),
    breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig(),
    hikeConfig: HikeConfig = HikeConfig(),
    activeLeg: Leg? = null,
    relativeBearingCorrection: Double = 0.0,
    onUpdateMapConfig: ((MapConfig) -> MapConfig) -> Unit = {},
    onUpdateBreadcrumbConfig: ((BreadcrumbConfig) -> BreadcrumbConfig) -> Unit = {},
    onUpdateHikeConfig: ((HikeConfig) -> HikeConfig) -> Unit = {},
    onIntervalSelected: (Long) -> Unit = {},
    onLoadTelemetryTrack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLegView = mapConfig.isLegView
    val show1kmGrid = mapConfig.show1kmGrid
    val show100mGrid = mapConfig.show100mGrid

    val viewportTracker = remember { ViewportTracker() }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // 2-second 20-step Leg Transition Animation State
    var transitionProgress by remember { mutableFloatStateOf(1.0f) }
    var previousLegState by remember { mutableStateOf<Leg?>(null) }

    LaunchedEffect(activeLeg, isLegView) {
        if (previousLegState != null && activeLeg != null && activeLeg != previousLegState && isLegView) {
            for (step in 0..20) {
                transitionProgress = step / 20.0f
                delay(100L) // 20 steps * 100ms = 2000ms (2 seconds)
            }
        }
        previousLegState = activeLeg
        transitionProgress = 1.0f
    }

    val textMeasurer = rememberTextMeasurer()

    val sharedTilePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    val sharedSrcPoly = remember {
        floatArrayOf(
            0f, 0f,       // Top-Left (NW)
            256f, 0f,     // Top-Right (NE)
            256f, 256f,   // Bottom-Right (SE)
            0f, 256f      // Bottom-Left (SW)
        )
    }
    val sharedDstPoly = remember { FloatArray(8) }
    val sharedPolyMatrix = remember { android.graphics.Matrix() }

    val validBreadcrumbs = remember(breadcrumbs, route) {
        breadcrumbs.filter { it.location.x != 0.0 || it.location.y != 0.0 || route == null }
    }

    if (!isLegView) {
        viewportTracker.updateAll(
            route = route,
            breadcrumbs = validBreadcrumbs,
            activeEkfState = activeEkfState
        )
    }

    var minSpeed = 0.0
    var maxSpeed = 2.5
    var minStride = 0.4
    var maxStride = 1.2

    if (validBreadcrumbs.isNotEmpty()) {
        var minS = Double.MAX_VALUE
        var maxS = -Double.MAX_VALUE
        var minSt = Double.MAX_VALUE
        var maxSt = -Double.MAX_VALUE

        validBreadcrumbs.forEachIndexed { index, bc ->
            val s = getBreadcrumbSpeed(index, validBreadcrumbs)
            if (s < minS) minS = s
            if (s > maxS) maxS = s

            val st = getBreadcrumbStride(index, validBreadcrumbs)
            if (st < minSt) minSt = st
            if (st > maxSt) maxSt = st
        }

        if (minS != Double.MAX_VALUE && maxS != -Double.MAX_VALUE) {
            minSpeed = minS
            maxSpeed = maxS
        }
        if (minSt != Double.MAX_VALUE && maxSt != -Double.MAX_VALUE) {
            minStride = minSt
            maxStride = maxSt
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0F1A))
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldZoomScale = zoomScale
                    val newZoomScale = (zoomScale * zoom).coerceIn(0.001f, 1000.0f)
                    val effectiveZoom = newZoomScale / oldZoomScale

                    val center = Offset(size.width / 2f, size.height / 2f)
                    val f = centroid - center

                    panOffset = f + pan - (f - panOffset) * effectiveZoom
                    zoomScale = newZoomScale
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (OsmTileManager.tileCacheVersion.intValue < 0) return@Canvas
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

            // Leg View Animation Interpolated Start & Target Points
            val legStart = activeLeg?.start
            val legTarget = activeLeg?.target
            val prevStart = previousLegState?.start
            val prevTarget = previousLegState?.target

            val interpStartX = if (transitionProgress < 1.0f && prevStart != null && legStart != null) {
                prevStart.x + transitionProgress * (legStart.x - prevStart.x)
            } else legStart?.x ?: 0.0

            val interpStartY = if (transitionProgress < 1.0f && prevStart != null && legStart != null) {
                prevStart.y + transitionProgress * (legStart.y - prevStart.y)
            } else legStart?.y ?: 0.0

            val interpTargetX = if (transitionProgress < 1.0f && prevTarget != null && legTarget != null) {
                prevTarget.x + transitionProgress * (legTarget.x - prevTarget.x)
            } else legTarget?.x ?: 1.0

            val interpTargetY = if (transitionProgress < 1.0f && prevTarget != null && legTarget != null) {
                prevTarget.y + transitionProgress * (legTarget.y - prevTarget.y)
            } else legTarget?.y ?: 1.0

            val legDx = interpTargetX - interpStartX
            val legDy = interpTargetY - interpStartY
            val legLen = hypot(legDx, legDy).coerceAtLeast(1.0)
            val ux = legDx / legLen
            val uy = legDy / legLen
            val vx = uy
            val vy = -ux

            val currentScale: Double = if (isLegView && legStart != null) {
                ((minOf(widthPx, heightPx) / (legLen * 1.35)) * zoomScale).toDouble()
            } else {
                (baseScale * zoomScale).toDouble()
            }

            fun toCanvasOffset(xMeters: Double, yMeters: Double): Offset {
                if (isLegView && legStart != null) {
                    val apx = xMeters - interpStartX
                    val apy = yMeters - interpStartY

                    val progress = apx * ux + apy * uy
                    val offset = apx * vx + apy * vy

                    val spanYLeg = legLen * 1.35
                    val spanXLeg = spanYLeg * (widthPx / heightPx).coerceIn(0.5f, 2.0f)
                    val scaleLeg = minOf(widthPx / spanXLeg, heightPx / spanYLeg) * zoomScale

                    val cx = originCx + (offset * scaleLeg).toFloat() + panOffset.x
                    val shiftY = legLen * 0.10
                    val cy = (originCy + ((spanYLeg / 2.0) - progress - shiftY) * scaleLeg + panOffset.y).toFloat()

                    return Offset(cx, cy)
                } else {
                    val cx = originCx + ((xMeters - centerX) * currentScale).toFloat() + panOffset.x
                    val cy = originCy - ((yMeters - centerY) * currentScale).toFloat() + panOffset.y
                    return Offset(cx, cy)
                }
            }

            if (currentScale > 0.0) {
                val minVisX: Double
                val maxVisX: Double
                val minVisY: Double
                val maxVisY: Double

                if (isLegView && legStart != null) {
                    minVisX = minOf(interpStartX, interpTargetX) - 1000.0
                    maxVisX = maxOf(interpStartX, interpTargetX) + 1000.0
                    minVisY = minOf(interpStartY, interpTargetY) - 1000.0
                    maxVisY = maxOf(interpStartY, interpTargetY) + 1000.0
                } else {
                    val leftMeters = centerX + (0f - originCx - panOffset.x) / currentScale
                    val rightMeters = centerX + (widthPx - originCx - panOffset.x) / currentScale
                    val topMeters = centerY - (0f - originCy - panOffset.y) / currentScale
                    val bottomMeters = centerY - (heightPx - originCy - panOffset.y) / currentScale

                    minVisX = minOf(leftMeters, rightMeters) - 500.0
                    maxVisX = maxOf(leftMeters, rightMeters) + 500.0
                    minVisY = minOf(topMeters, bottomMeters) - 500.0
                    maxVisY = maxOf(topMeters, bottomMeters) + 500.0
                }

                // --- Draw Offline OSM Map Background Tiles (Two-Pass Layered Renderer) ---
                if (mapConfig.showOsmBackground) {
                    try {
                        val startTime = System.currentTimeMillis()
                        val autoZoom = round(kotlin.math.log2(93113.0 * currentScale)).toInt().coerceIn(8, 18)
                        val baseZoom = autoZoom.coerceAtMost(14)

                        val drawSingleTile: (com.example.irishhike.util.MetricTileCorners, Boolean) -> Unit = { tile, fromCacheOnly ->
                            val corners = OsmTileManager.getTileCorners(tile.zoom, tile.tileX, tile.tileY)

                            val pNW = toCanvasOffset(corners.nwMetric.x, corners.nwMetric.y)
                            val pNE = toCanvasOffset(corners.neMetric.x, corners.neMetric.y)
                            val pSE = toCanvasOffset(corners.seMetric.x, corners.seMetric.y)
                            val pSW = toCanvasOffset(corners.swMetric.x, corners.swMetric.y)

                            if (pNW.x.isFinite() && pNW.y.isFinite() &&
                                pNE.x.isFinite() && pNE.y.isFinite() &&
                                pSE.x.isFinite() && pSE.y.isFinite() &&
                                pSW.x.isFinite() && pSW.y.isFinite()) {

                                sharedDstPoly[0] = pNW.x; sharedDstPoly[1] = pNW.y
                                sharedDstPoly[2] = pNE.x; sharedDstPoly[3] = pNE.y
                                sharedDstPoly[4] = pSE.x; sharedDstPoly[5] = pSE.y
                                sharedDstPoly[6] = pSW.x; sharedDstPoly[7] = pSW.y

                                sharedPolyMatrix.reset()
                                if (sharedPolyMatrix.setPolyToPoly(sharedSrcPoly, 0, sharedDstPoly, 0, 4)) {
                                    val tileImageBitmap = if (fromCacheOnly) {
                                        OsmTileManager.getTileBitmapFromCacheOnly(context, tile.zoom, tile.tileX, tile.tileY)
                                    } else {
                                        OsmTileManager.getTileBitmap(context, tile.zoom, tile.tileX, tile.tileY)
                                    }

                                    if (tileImageBitmap != null) {
                                        val androidBitmap = tileImageBitmap.asAndroidBitmap()
                                        if (!androidBitmap.isRecycled) {
                                            drawContext.canvas.nativeCanvas.save()
                                            drawContext.canvas.nativeCanvas.concat(sharedPolyMatrix)
                                            drawContext.canvas.nativeCanvas.drawBitmap(androidBitmap, 0f, 0f, sharedTilePaint)
                                            drawContext.canvas.nativeCanvas.restore()
                                        }
                                    }
                                }
                            }
                        }

                        // Pass 1: Draw continuous base map layer (Ireland base map, zooms 8..14)
                        val baseTiles = OsmTileManager.getTilesForArea(
                            minX = minVisX, maxX = maxVisX, minY = minVisY, maxY = maxVisY, zoom = baseZoom
                        )
                        for (tile in baseTiles) {
                            drawSingleTile(tile, false)
                        }

                        // Pass 2: Draw high-resolution route area tile overlay (zooms 15..18)
                        if (autoZoom > 14) {
                            val highResTiles = OsmTileManager.getTilesForArea(
                                minX = minVisX, maxX = maxVisX, minY = minVisY, maxY = maxVisY, zoom = autoZoom
                            )
                            for (tile in highResTiles) {
                                drawSingleTile(tile, true)
                            }
                        }

                        android.util.Log.d("PDR_DIAG", "CANVAS_DRAW_DONE -> time: ${System.currentTimeMillis() - startTime} ms")
                    } catch (e: Exception) {
                        android.util.Log.e("MapScreen", "Error rendering OSM background tiles: ${e.message}", e)
                    }
                }

                // 1) Draw 100m gridlines (unlabeled)
                if (show100mGrid) {
                    val spacing100mPx = 100.0 * currentScale
                    if (spacing100mPx >= 3.0) {
                        val start100mX = (kotlin.math.floor(minVisX / 100.0) * 100.0).toInt()
                        val end100mX = (kotlin.math.ceil(maxVisX / 100.0) * 100.0).toInt()

                        for (xMeters in start100mX..end100mX step 100) {
                            if (show1kmGrid && xMeters % 1000 == 0) continue
                            val p1 = toCanvasOffset(xMeters.toDouble(), minVisY)
                            val p2 = toCanvasOffset(xMeters.toDouble(), maxVisY)
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.4f),
                                start = p1,
                                end = p2,
                                strokeWidth = 1.25f
                            )
                        }

                        val start100mY = (kotlin.math.floor(minVisY / 100.0) * 100.0).toInt()
                        val end100mY = (kotlin.math.ceil(maxVisY / 100.0) * 100.0).toInt()

                        for (yMeters in start100mY..end100mY step 100) {
                            if (show1kmGrid && yMeters % 1000 == 0) continue
                            val p1 = toCanvasOffset(minVisX, yMeters.toDouble())
                            val p2 = toCanvasOffset(maxVisX, yMeters.toDouble())
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.4f),
                                start = p1,
                                end = p2,
                                strokeWidth = 1.25f
                            )
                        }
                    }
                }

                // 2) Draw 1km gridlines with 2-digit grid labels
                if (show1kmGrid) {
                    val start1kmX = (kotlin.math.floor(minVisX / 1000.0) * 1000.0).toInt()
                    val end1kmX = (kotlin.math.ceil(maxVisX / 1000.0) * 1000.0).toInt()

                    for (xMeters in start1kmX..end1kmX step 1000) {
                        val p1 = toCanvasOffset(xMeters.toDouble(), minVisY)
                        val p2 = toCanvasOffset(xMeters.toDouble(), maxVisY)
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.70f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2.0f
                        )

                        // 2-digit Easting label (drawn to the left of the vertical grid line)
                        val kmDigitX = String.format(Locale.US, "%02d", (abs(xMeters) / 1000) % 100)
                        val textResult = textMeasurer.measure(
                            text = kmDigitX,
                            style = TextStyle(color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        )
                        val labelY = (p1.y + 12f).coerceIn(12f, heightPx - textResult.size.height - 12f)
                        val labelX = p1.x - textResult.size.width - 4f
                        drawText(
                            textLayoutResult = textResult,
                            topLeft = Offset(labelX, labelY)
                        )
                    }

                    val start1kmY = (kotlin.math.floor(minVisY / 1000.0) * 1000.0).toInt()
                    val end1kmY = (kotlin.math.ceil(maxVisY / 1000.0) * 1000.0).toInt()

                    for (yMeters in start1kmY..end1kmY step 1000) {
                        val p1 = toCanvasOffset(minVisX, yMeters.toDouble())
                        val p2 = toCanvasOffset(maxVisX, yMeters.toDouble())
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.70f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2.0f
                        )

                        // 2-digit Northing label (drawn above the horizontal grid line)
                        val kmDigitY = String.format(Locale.US, "%02d", (abs(yMeters) / 1000) % 100)
                        val textResult = textMeasurer.measure(
                            text = kmDigitY,
                            style = TextStyle(color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        )
                        val labelX = (p1.x + 12f).coerceIn(12f, widthPx - textResult.size.width - 12f)
                        val labelY = p1.y - textResult.size.height - 2f
                        drawText(
                            textLayoutResult = textResult,
                            topLeft = Offset(labelX, labelY)
                        )
                    }
                }
            }

            // Draw route legs (connected waypoints)
            route?.legs?.forEach { leg ->
                val startOffset = toCanvasOffset(leg.start.x, leg.start.y)
                val targetOffset = toCanvasOffset(leg.target.x, leg.target.y)

                val isCurrentActiveLeg = (leg == activeLeg)
                val legColor = if (isCurrentActiveLeg) Color(0xFFFFD700) else Color(0xFF38BDF8)
                val strokeW = if (isCurrentActiveLeg) 5.0f else 3.0f

                drawLine(
                    color = legColor,
                    start = startOffset,
                    end = targetOffset,
                    strokeWidth = strokeW * zoomScale.coerceIn(0.5f, 3f)
                )
            }

            // Draw waypoints
            route?.waypoints?.forEach { wp ->
                val center = toCanvasOffset(wp.x, wp.y)
                val isTarget = (wp == currentTargetWaypoint)
                val wpColor = if (isTarget) Color(0xFFFF3333) else Color(0xFF38BDF8)
                val radiusPx = if (isTarget) 10f else 6f

                drawCircle(
                    color = wpColor,
                    radius = radiusPx * zoomScale.coerceIn(0.5f, 2.5f),
                    center = center
                )

                if (mapConfig.showWaypointNames || mapConfig.showWaypointHeights) {
                    val wpName = when {
                        mapConfig.showWaypointNames && mapConfig.showWaypointHeights -> "${wp.name} (%.0fm)".format(wp.altitude)
                        mapConfig.showWaypointNames -> wp.name
                        else -> "%.0fm".format(wp.altitude)
                    }

                    // Compute smart label placement: opposite the acute angle (outer bisector of connected legs)
                    val connectedLegs = route?.legs?.filter { it.start == wp || it.target == wp } ?: emptyList()
                    var normalX = 0f
                    var normalY = -1f // default upward offset

                    if (connectedLegs.isNotEmpty()) {
                        var sumDx = 0.0
                        var sumDy = 0.0
                        connectedLegs.forEach { leg ->
                            val other = if (leg.start == wp) leg.target else leg.start
                            val dx = other.x - wp.x
                            val dy = other.y - wp.y
                            val len = hypot(dx, dy)
                            if (len > 0.1) {
                                sumDx += dx / len
                                sumDy += dy / len
                            }
                        }
                        val sumLen = hypot(sumDx, sumDy)
                        if (sumLen > 0.01) {
                            // Point in the exact opposite direction of the sum of unit vectors (outer bisector / opposite acute angle)
                            normalX = (-sumDx / sumLen).toFloat()
                            normalY = (-sumDy / sumLen).toFloat()
                        } else {
                            val firstLeg = connectedLegs.first()
                            val other = if (firstLeg.start == wp) firstLeg.target else firstLeg.start
                            val dx = other.x - wp.x
                            val dy = other.y - wp.y
                            val len = hypot(dx, dy)
                            if (len > 0.1) {
                                val dirX = (dx / len).toFloat()
                                val dirY = (dy / len).toFloat()
                                normalX = -dirY
                                normalY = dirX
                            }
                        }
                    }

                    val labelDistancePx = 26f * zoomScale.coerceIn(0.5f, 2.0f)
                    val labelOffsetX = normalX * labelDistancePx
                    val labelOffsetY = normalY * labelDistancePx

                    val textResult = textMeasurer.measure(
                        text = wpName,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    val labelX = center.x + labelOffsetX - (textResult.size.width / 2f)
                    val labelY = center.y + labelOffsetY - (textResult.size.height / 2f)

                    val bgPadding = 3f
                    drawRect(
                        color = Color.Black.copy(alpha = 0.20f),
                        topLeft = Offset(labelX - bgPadding, labelY - bgPadding),
                        size = androidx.compose.ui.geometry.Size(
                            textResult.size.width + bgPadding * 2f,
                            textResult.size.height + bgPadding * 2f
                        )
                    )

                    drawText(
                        textLayoutResult = textResult,
                        topLeft = Offset(labelX, labelY)
                    )
                }
            }

            // 1-pixel adaptive decimation filter for breadcrumb rendering
            val renderBreadcrumbs = mutableListOf<Breadcrumb>()
            var lastOffset: Offset? = null
            for (bc in validBreadcrumbs) {
                val offset = toCanvasOffset(bc.location.x, bc.location.y)
                if (lastOffset == null || (offset.x - lastOffset.x).let { it * it } + (offset.y - lastOffset.y).let { it * it } >= 1.0f) {
                    renderBreadcrumbs.add(bc)
                    lastOffset = offset
                }
            }

            // Draw historical breadcrumbs with user's configured styling & diameter
            if (breadcrumbConfig.showBreadcrumbs) {
                val totalCount = renderBreadcrumbs.size.toFloat().coerceAtLeast(1f)

                var minSpeed = Double.MAX_VALUE
                var maxSpeed = -Double.MAX_VALUE
                var minStride = Double.MAX_VALUE
                var maxStride = -Double.MAX_VALUE

                validBreadcrumbs.forEachIndexed { index, _ ->
                    val s = getBreadcrumbSpeed(index, validBreadcrumbs)
                    if (s < minSpeed) minSpeed = s
                    if (s > maxSpeed) maxSpeed = s

                    val st = getBreadcrumbStride(index, validBreadcrumbs)
                    if (st < minStride) minStride = st
                    if (st > maxStride) maxStride = st
                }

                if (minSpeed == Double.MAX_VALUE || maxSpeed == -Double.MAX_VALUE) { minSpeed = 0.0; maxSpeed = 2.5 }
                if (minStride == Double.MAX_VALUE || maxStride == -Double.MAX_VALUE) { minStride = 0.4; maxStride = 1.2 }

                renderBreadcrumbs.forEachIndexed { index, bc ->
                    val center = toCanvasOffset(bc.location.x, bc.location.y)

                    val baseColor = when (breadcrumbConfig.colorMode) {
                        BreadcrumbColorMode.GPS_PDR -> {
                            if (bc.isGpsFix) Color(0xFFFF3333) else Color(0xFF2196F3)
                        }
                        BreadcrumbColorMode.SPEED -> {
                            val speedMps = getBreadcrumbSpeed(index, renderBreadcrumbs)
                            val range = (maxSpeed - minSpeed).toFloat().coerceAtLeast(0.001f)
                            val t = ((speedMps - minSpeed) / range).coerceIn(0.0, 1.0).toFloat()
                            lerp(Color(breadcrumbConfig.speedStartColor), Color(breadcrumbConfig.speedEndColor), t)
                        }
                        BreadcrumbColorMode.STRIDE -> {
                            val stride = getBreadcrumbStride(index, renderBreadcrumbs)
                            val range = (maxStride - minStride).toFloat().coerceAtLeast(0.001f)
                            val t = ((stride - minStride) / range).coerceIn(0.0, 1.0).toFloat()
                            lerp(Color(breadcrumbConfig.strideStartColor), Color(breadcrumbConfig.strideEndColor), t)
                        }
                    }

                    val alphaMultiplier = if (breadcrumbConfig.fadeWithAge) {
                        val progress = if (totalCount > 1f) index / (totalCount - 1f) else 1f
                        0.2f + 0.8f * progress
                    } else {
                        1.0f
                    }

                    val finalColor = baseColor.copy(alpha = baseColor.alpha * alphaMultiplier)

                    // Draw line segment connecting to previous breadcrumb with width 1.0 and gradient transparency
                    if (index > 0) {
                        val prevBc = renderBreadcrumbs[index - 1]
                        val prevCenter = toCanvasOffset(prevBc.location.x, prevBc.location.y)

                        val prevAlphaMultiplier = if (breadcrumbConfig.fadeWithAge) {
                            val progress = if (totalCount > 1f) (index - 1) / (totalCount - 1f) else 1f
                            0.2f + 0.8f * progress
                        } else {
                            1.0f
                        }
                        val prevColor = baseColor.copy(alpha = baseColor.alpha * prevAlphaMultiplier)

                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(prevColor, finalColor),
                                start = prevCenter,
                                end = center
                            ),
                            start = prevCenter,
                            end = center,
                            strokeWidth = 1.0f * zoomScale.coerceIn(0.5f, 2f)
                        )
                    }

                    if (breadcrumbConfig.showAccuracyCircles && bc.ekfState != null) {
                        val accRadiusPx = (sqrt(bc.ekfState.position.variance) * currentScale).toFloat().coerceIn(4f, 80f)
                        drawCircle(
                            color = finalColor.copy(alpha = 0.6f * alphaMultiplier),
                            radius = accRadiusPx,
                            center = center,
                            style = Stroke(width = 1.5f * zoomScale.coerceIn(0.5f, 2f))
                        )
                    }

                    val dotRadius = breadcrumbConfig.dotDiameterPx * zoomScale.coerceIn(0.5f, 2.0f)
                    drawCircle(
                        color = finalColor,
                        radius = dotRadius,
                        center = center
                    )
                }
            }

            // Draw Stationary Rest Stop Circles & Duration Labels
            if (breadcrumbConfig.showStationaryStops && renderBreadcrumbs.size >= 2) {
                var stopStartBc: Breadcrumb? = null
                var stopLastBc: Breadcrumb? = null

                for (i in 0 until renderBreadcrumbs.size) {
                    val bc = renderBreadcrumbs[i]
                    val speed = bc.ekfState?.speedMps?.value ?: 0.0

                    if (speed < 0.3) {
                        if (stopStartBc == null) {
                            stopStartBc = bc
                        }
                        stopLastBc = bc
                    } else {
                        if (stopStartBc != null && stopLastBc != null) {
                            val durationSec = (stopLastBc.timestampMs - stopStartBc.timestampMs) / 1000L
                            if (durationSec >= 30L) {
                                val stopCenter = toCanvasOffset(stopStartBc.location.x, stopStartBc.location.y)
                                val durationMin = durationSec / 60
                                val durationSecRem = durationSec % 60
                                val timeText = if (durationMin > 0) "${durationMin}m ${durationSecRem}s" else "${durationSecRem}s"

                                drawCircle(
                                    color = Color(0xFFFFB300).copy(alpha = 0.25f),
                                    radius = 16f * zoomScale.coerceIn(0.8f, 2.5f),
                                    center = stopCenter
                                )
                                drawCircle(
                                    color = Color(0xFFFFB300),
                                    radius = 16f * zoomScale.coerceIn(0.8f, 2.5f),
                                    center = stopCenter,
                                    style = Stroke(width = 2f)
                                )

                                val textResult = textMeasurer.measure(
                                    text = "Stopped $timeText",
                                    style = TextStyle(color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                )
                                drawText(
                                    textLayoutResult = textResult,
                                    topLeft = Offset(stopCenter.x - textResult.size.width / 2f, stopCenter.y + 18f)
                                )
                            }
                        }
                        stopStartBc = null
                        stopLastBc = null
                    }
                }
            }

            // Draw current active EKF position indicator (only if initialized)
            if (activeEkfState.isInitializedWithGps) {
                val activePos = activeEkfState.position.value
                val currentCenter = toCanvasOffset(activePos.x, activePos.y)
                val currentRadiusPx = if (isLegView) 12f else (sqrt(activeEkfState.position.variance) * currentScale).toFloat().coerceIn(6f, 60f)

                drawCircle(
                    color = Color(0xFFFFD700).copy(alpha = 0.25f),
                    radius = currentRadiusPx,
                    center = currentCenter
                )

                val bearing = activeEkfState.bearingRad.value
                val effectiveBearing = if (isLegView && activeLeg != null) {
                    val legBearingDeg = activeLeg.start.bearingTo(activeLeg.target)
                    bearing - Math.toRadians(legBearingDeg)
                } else {
                    bearing
                }
                val headingError = sqrt(activeEkfState.bearingRad.variance)
                val arrowLengthPx = 32f * zoomScale.coerceIn(0.5f, 2.5f)

                val wedgePath = Path().apply {
                    moveTo(currentCenter.x, currentCenter.y)
                    val leftAngle = effectiveBearing - headingError
                    val rightAngle = effectiveBearing + headingError

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

                val arrowTipX = currentCenter.x + (arrowLengthPx * sin(effectiveBearing)).toFloat()
                val arrowTipY = currentCenter.y - (arrowLengthPx * cos(effectiveBearing)).toFloat()

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

            // Draw direct line from current hiker position to current leg target waypoint
            val activeTarget = currentTargetWaypoint ?: activeLeg?.target ?: route?.legs?.firstOrNull()?.target
            if (activeEkfState.isInitializedWithGps && activeTarget != null) {
                val activePos = activeEkfState.position.value
                val hikerOffset = toCanvasOffset(activePos.x, activePos.y)
                val targetOffset = toCanvasOffset(activeTarget.x, activeTarget.y)

                drawLine(
                    color = Color(0xFFFFB300),
                    start = hikerOffset,
                    end = targetOffset,
                    strokeWidth = 3.5f * zoomScale.coerceIn(0.5f, 2.5f),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
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

        // Color Legend Overlay Card for Speed / Stride Color Modes (only shown when breadcrumbs exist on screen)
        if (breadcrumbConfig.showBreadcrumbs && validBreadcrumbs.isNotEmpty() && (breadcrumbConfig.colorMode == BreadcrumbColorMode.SPEED || breadcrumbConfig.colorMode == BreadcrumbColorMode.STRIDE)) {
            val isSpeed = breadcrumbConfig.colorMode == BreadcrumbColorMode.SPEED
            val minVal = if (isSpeed) minSpeed * 3.6 else minStride
            val maxVal = if (isSpeed) maxSpeed * 3.6 else maxStride
            val unitStr = if (isSpeed) "km/h" else "m"
            val titleStr = if (isSpeed) "Speed Legend" else "Stride Legend"
            val isSameVal = abs(maxVal - minVal) < 0.001

            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(titleStr, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    if (isSameVal) {
                        Text("Value: %.2f $unitStr".format(minVal), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("%.1f $unitStr".format(minVal), style = MaterialTheme.typography.labelSmall, color = Color.White)
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(10.dp)
                                    .background(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color(if (isSpeed) breadcrumbConfig.speedStartColor else breadcrumbConfig.strideStartColor),
                                                Color(if (isSpeed) breadcrumbConfig.speedEndColor else breadcrumbConfig.strideEndColor)
                                            )
                                        ),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                            Text("%.1f $unitStr".format(maxVal), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
        }

        // Lower Right Corner Controls (Fit All button and Gear Icon Settings button)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (zoomScale != 1f || panOffset != Offset.Zero) {
                Button(
                    onClick = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                ) {
                    Text("Fit All", style = MaterialTheme.typography.labelSmall)
                }
            }

            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), CircleShape)
                    .size(36.dp)
            ) {
                Text("⚙", fontSize = 18.sp)
            }
        }

        // 5-Tab Settings Overhaul Dialog
        if (showSettingsDialog) {
            var selectedTabIndex by remember { mutableIntStateOf(com.example.irishhike.repository.HikeRepository.loadSettingsTab()) }
            val tabs = listOf("Map", "Breadcrumbs", "Audio", "Navigation", "Dev")

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Column {
                        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        ScrollableTabRow(
                            selectedTabIndex = selectedTabIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = {
                                        selectedTabIndex = index
                                        com.example.irishhike.repository.HikeRepository.saveSettingsTab(index)
                                    },
                                    text = { Text(title, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (selectedTabIndex) {
                            0 -> MapSettingsTab(mapConfig, onUpdateMapConfig)
                            1 -> BreadcrumbsSettingsTab(breadcrumbConfig, onUpdateBreadcrumbConfig)
                            2 -> AudioSettingsTab(hikeConfig, onUpdateHikeConfig)
                            3 -> NavigationSettingsTab(context, route, mapConfig.gpsPollingIntervalMs, onIntervalSelected)
                            4 -> DevSettingsTab(mapConfig, onUpdateMapConfig, onLoadTelemetryTrack)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Close", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun MapSettingsTab(
    mapConfig: MapConfig,
    onUpdateMapConfig: ((MapConfig) -> MapConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Waypoint Names", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display names over route waypoints", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.showWaypointNames,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showWaypointNames = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Waypoint Heights", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display altitudes over route waypoints", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.showWaypointHeights,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showWaypointHeights = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("1km Gridlines", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display 1km major Irish gridlines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.show1kmGrid,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(show1kmGrid = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("100m Gridlines", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display 100m minor Irish gridlines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.show100mGrid,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(show100mGrid = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Offline OSM Street Tiles", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display OpenStreetMap background tiles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.showOsmBackground,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showOsmBackground = it) } }
            )
        }
    }
}

@Composable
private fun BreadcrumbsSettingsTab(
    breadcrumbConfig: BreadcrumbConfig,
    onUpdateBreadcrumbConfig: ((BreadcrumbConfig) -> BreadcrumbConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show Breadcrumbs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display breadcrumb trail points", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = breadcrumbConfig.showBreadcrumbs,
                onCheckedChange = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(showBreadcrumbs = it) } }
            )
        }

        Column {
            Text("Dot Diameter: %.1f px".format(breadcrumbConfig.dotDiameterPx), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = breadcrumbConfig.dotDiameterPx,
                onValueChange = { newSize -> onUpdateBreadcrumbConfig { cfg -> cfg.copy(dotDiameterPx = newSize) } },
                valueRange = 1.0f..5.0f,
                steps = 8
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Color Coding Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = breadcrumbConfig.colorMode == BreadcrumbColorMode.GPS_PDR,
                    onClick = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(colorMode = BreadcrumbColorMode.GPS_PDR) } },
                    label = { Text("GPS/PDR") }
                )
                FilterChip(
                    selected = breadcrumbConfig.colorMode == BreadcrumbColorMode.SPEED,
                    onClick = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(colorMode = BreadcrumbColorMode.SPEED) } },
                    label = { Text("Speed") }
                )
                FilterChip(
                    selected = breadcrumbConfig.colorMode == BreadcrumbColorMode.STRIDE,
                    onClick = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(colorMode = BreadcrumbColorMode.STRIDE) } },
                    label = { Text("Stride") }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Age Fading", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Older breadcrumbs gradually fade out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = breadcrumbConfig.fadeWithAge,
                onCheckedChange = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(fadeWithAge = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Position Accuracy Circles", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Light circle around breadcrumbs showing accuracy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = breadcrumbConfig.showAccuracyCircles,
                onCheckedChange = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(showAccuracyCircles = it) } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Stationary Rest Stops", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Circle & duration label when stationary > 30s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = breadcrumbConfig.showStationaryStops,
                onCheckedChange = { onUpdateBreadcrumbConfig { cfg -> cfg.copy(showStationaryStops = it) } }
            )
        }
    }
}

@Composable
private fun AudioSettingsTab(
    hikeConfig: HikeConfig,
    onUpdateHikeConfig: ((HikeConfig) -> HikeConfig) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Audio Directions", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Enable spoken TTS turn-by-turn prompts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = hikeConfig.audioEnabled,
                onCheckedChange = { onUpdateHikeConfig { cfg -> cfg.copy(audioEnabled = it) } }
            )
        }

        var arriveText by remember(hikeConfig) { mutableStateOf("%.0f".format(hikeConfig.arriveThresholdMeters)) }
        OutlinedTextField(
            value = arriveText,
            onValueChange = {
                arriveText = it
                it.toDoubleOrNull()?.let { newMeters ->
                    onUpdateHikeConfig { cfg -> cfg.copy(arriveThresholdMeters = newMeters) }
                }
            },
            singleLine = true,
            label = { Text("Target Arrival Radius (meters)") },
            modifier = Modifier.fillMaxWidth()
        )

        var approachText by remember(hikeConfig) { mutableStateOf("%.0f".format(hikeConfig.approachThresholdMeters)) }
        OutlinedTextField(
            value = approachText,
            onValueChange = {
                approachText = it
                it.toDoubleOrNull()?.let { newMeters ->
                    onUpdateHikeConfig { cfg -> cfg.copy(approachThresholdMeters = newMeters) }
                }
            },
            singleLine = true,
            label = { Text("Target Approach Radius (meters)") },
            modifier = Modifier.fillMaxWidth()
        )

        var offCourseText by remember(hikeConfig) { mutableStateOf("%.0f".format(hikeConfig.offCourseHeadingDevDeg)) }
        OutlinedTextField(
            value = offCourseText,
            onValueChange = {
                offCourseText = it
                it.toDoubleOrNull()?.let { newDeg ->
                    onUpdateHikeConfig { cfg -> cfg.copy(offCourseHeadingDevDeg = newDeg) }
                }
            },
            singleLine = true,
            label = { Text("Off-Course Heading Deviation (degrees)") },
            modifier = Modifier.fillMaxWidth()
        )

        Column {
            Text("Speech Rate: %.1fx".format(hikeConfig.speechRate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Slider(
                value = hikeConfig.speechRate,
                onValueChange = { rate -> onUpdateHikeConfig { cfg -> cfg.copy(speechRate = rate) } },
                valueRange = 0.5f..2.0f,
                steps = 15
            )
        }
    }
}

@Composable
private fun NavigationSettingsTab(
    context: android.content.Context,
    route: Route?,
    currentIntervalMs: Long,
    onIntervalSelected: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Download Offline Ireland Tiles:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Fetch OpenStreetMap tiles for Ireland (zooms 8-14)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            var isDownloading by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("") }
            val coroutineScope = rememberCoroutineScope()

            val isIrelandFullyCached = TileDownloadManifest.isIrelandDownloaded(context)

            Button(
                onClick = {
                    isDownloading = true
                    coroutineScope.launch {
                        val count = OsmTileManager.downloadIrelandTiles(context, listOf(8, 9, 10, 11, 12, 13, 14)) { current, total ->
                            progressText = "$current / $total"
                        }
                        isDownloading = false
                        Toast.makeText(context, "Downloaded $count Ireland map tiles for offline use!", Toast.LENGTH_LONG).show()
                    }
                },
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isIrelandFullyCached) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isDownloading -> "Downloading ($progressText)..."
                        isIrelandFullyCached -> "✓ Ireland Base Map Cached"
                        else -> "Download Ireland Map Tiles"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }

        if (route != null && route.waypoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Download Route Area Map Tiles:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Fetch high-res tiles for '${route.name}' (zooms 10-18)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                var isDownloadingRoute by remember { mutableStateOf(false) }
                var routeProgressText by remember { mutableStateOf("") }
                val coroutineScope = rememberCoroutineScope()

                val isRouteFullyCached = TileDownloadManifest.isRouteDownloaded(context, route.name)

                Button(
                    onClick = {
                        isDownloadingRoute = true
                        coroutineScope.launch {
                            val count = OsmTileManager.downloadRouteAreaTiles(context, route, listOf(10, 11, 12, 13, 14, 15, 16, 17, 18)) { current, total ->
                                routeProgressText = "$current / $total"
                            }
                            isDownloadingRoute = false
                            Toast.makeText(context, "Downloaded $count tiles for route '${route.name}'!", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isDownloadingRoute,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRouteFullyCached) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            isDownloadingRoute -> "Downloading Route ($routeProgressText)..."
                            isRouteFullyCached -> "✓ Route Area Tiles Cached"
                            else -> "Download Route Area Tiles"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GPS Polling Interval", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = currentIntervalMs == 1000L, onClick = { onIntervalSelected(1000L) }, label = { Text("1s") })
                FilterChip(selected = currentIntervalMs == 2000L, onClick = { onIntervalSelected(2000L) }, label = { Text("2s") })
                FilterChip(selected = currentIntervalMs == 5000L, onClick = { onIntervalSelected(5000L) }, label = { Text("5s") })
                FilterChip(selected = currentIntervalMs == 10000L, onClick = { onIntervalSelected(10000L) }, label = { Text("10s") })
            }
        }
    }
}

@Composable
private fun DevSettingsTab(
    mapConfig: MapConfig,
    onUpdateMapConfig: ((MapConfig) -> MapConfig) -> Unit,
    onLoadTelemetryTrack: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Telemetry Dashboard Viewport", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Display live EKF state telemetry dashboard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = mapConfig.showTelemetryViewport,
                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showTelemetryViewport = it) } }
            )
        }

        if (onLoadTelemetryTrack != null) {
            Button(
                onClick = onLoadTelemetryTrack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Recorded Telemetry Track (.csv)", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun getBreadcrumbSpeed(index: Int, list: List<Breadcrumb>): Double {
    val bc = list[index]
    val ekfSpeed = bc.ekfState?.speedMps?.value ?: 0.0
    if (ekfSpeed > 0.05) return ekfSpeed

    if (index > 0) {
        val prev = list[index - 1]
        val dist = CoordinateUtils.getDistance(prev.location, bc.location)
        val dt = (bc.timestampMs - prev.timestampMs) / 1000.0
        if (dt > 0.1) return (dist / dt)
    }
    if (index < list.size - 1) {
        val next = list[index + 1]
        val dist = CoordinateUtils.getDistance(bc.location, next.location)
        val dt = (next.timestampMs - bc.timestampMs) / 1000.0
        if (dt > 0.1) return (dist / dt)
    }
    return ekfSpeed
}

private fun getBreadcrumbStride(index: Int, list: List<Breadcrumb>): Double {
    val bc = list[index]
    return bc.ekfState?.strideLengthMeters?.value ?: 0.75
}
