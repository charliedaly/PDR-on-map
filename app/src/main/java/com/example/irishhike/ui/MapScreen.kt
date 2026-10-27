package com.example.irishhike.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.irishhike.config.HikeConfig
import com.example.irishhike.config.MapConfig
import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Leg
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import com.example.irishhike.util.OsmTileManager
import com.example.irishhike.util.TileDownloadManifest
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MapScreen(
    route: Route?,
    breadcrumbs: List<Breadcrumb>,
    activeEkfState: EkfState,
    currentTargetWaypoint: Waypoint? = null,
    mapConfig: MapConfig = MapConfig(),
    hikeConfig: HikeConfig = HikeConfig(),
    activeLeg: Leg? = null,
    relativeBearingCorrection: Double = 0.0,
    onUpdateMapConfig: ((MapConfig) -> MapConfig) -> Unit = {},
    onUpdateHikeConfig: ((HikeConfig) -> HikeConfig) -> Unit = {},
    onIntervalSelected: (Long) -> Unit = {},
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

                if (isLegView && legStart != null && legTarget != null) {
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
                        val autoZoom = kotlin.math.round(kotlin.math.log2(93113.0 * currentScale)).toInt().coerceIn(8, 18)
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

                // 1) Draw 100m gridlines (unlabeled, rotated)
                if (show100mGrid) {
                    val spacing100mPx = 100.0 * currentScale.toDouble()
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

                // 2) Draw 1km gridlines (labeled by 2-digit Irish Grid reference, rotated)
                if (show1kmGrid) {
                    val start1kmX = (kotlin.math.floor(minVisX / 1000.0) * 1000.0).toInt()
                    val end1kmX = (kotlin.math.ceil(maxVisX / 1000.0) * 1000.0).toInt()

                    for (xMeters in start1kmX..end1kmX step 1000) {
                        val p1 = toCanvasOffset(xMeters.toDouble(), minVisY)
                        val p2 = toCanvasOffset(xMeters.toDouble(), maxVisY)

                        drawLine(
                            color = Color(0xFF94A3B8).copy(alpha = 0.85f),
                            start = p1,
                            end = p2,
                            strokeWidth = 1.75f
                        )

                        val digits = (kotlin.math.abs(xMeters) / 1000) % 100
                        val labelStr = String.format(Locale.US, "%02d", digits)

                        val textResult = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(
                                color = Color(0xFF9FB3C8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )

                        val canvasX = p1.x
                        if (canvasX in 0f..widthPx) {
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset((canvasX + 4f).coerceIn(4f, widthPx - textResult.size.width - 4f), 8f)
                            )
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset((canvasX + 4f).coerceIn(4f, widthPx - textResult.size.width - 4f), heightPx - textResult.size.height - 8f)
                            )
                        }
                    }

                    val start1kmY = (kotlin.math.floor(minVisY / 1000.0) * 1000.0).toInt()
                    val end1kmY = (kotlin.math.ceil(maxVisY / 1000.0) * 1000.0).toInt()

                    for (yMeters in start1kmY..end1kmY step 1000) {
                        val p1 = toCanvasOffset(minVisX, yMeters.toDouble())
                        val p2 = toCanvasOffset(maxVisX, yMeters.toDouble())

                        drawLine(
                            color = Color(0xFF334E68).copy(alpha = 0.75f),
                            start = p1,
                            end = p2,
                            strokeWidth = 1.5f
                        )

                        val digits = (kotlin.math.abs(yMeters) / 1000) % 100
                        val labelStr = String.format(Locale.US, "%02d", digits)

                        val textResult = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(
                                color = Color(0xFF9FB3C8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )

                        val canvasY = p1.y
                        if (canvasY in 0f..heightPx) {
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset(8f, (canvasY - textResult.size.height / 2f).coerceIn(4f, heightPx - textResult.size.height - 4f))
                            )
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset(widthPx - textResult.size.width - 8f, (canvasY - textResult.size.height / 2f).coerceIn(4f, heightPx - textResult.size.height - 4f))
                            )
                        }
                    }
                }
            }

            // Draw current leg arrival & approach circles centered on current leg target (perimeter strokes only, subtle)
            if (activeLeg != null) {
                val targetCenter = toCanvasOffset(activeLeg.target.x, activeLeg.target.y)
                val effectiveScale = if (isLegView) {
                    (minOf(widthPx, heightPx) / (legLen * 1.35)) * zoomScale
                } else {
                    baseScale.toDouble() * zoomScale.toDouble()
                }

                // Approach circle (100m) - perimeter only
                val approachRadiusPx = (activeLeg.target.approach * effectiveScale).toFloat()
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                    radius = approachRadiusPx,
                    center = targetCenter,
                    style = Stroke(width = 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f))
                )

                // Arrival circle (15m) - perimeter only
                val arriveRadiusPx = (activeLeg.target.arrive * effectiveScale).toFloat()
                drawCircle(
                    color = Color(0xFF4CAF50),
                    radius = arriveRadiusPx,
                    center = targetCenter,
                    style = Stroke(width = 1.8f)
                )
            }

            // Highlight current leg subtly with translucent yellow line
            if (activeLeg != null) {
                val p1 = toCanvasOffset(activeLeg.start.x, activeLeg.start.y)
                val p2 = toCanvasOffset(activeLeg.target.x, activeLeg.target.y)
                drawLine(
                    color = Color(0xFFFFEE58).copy(alpha = 0.45f),
                    start = p1,
                    end = p2,
                    strokeWidth = 10f * zoomScale.coerceIn(0.5f, 3f)
                )
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

            // Draw larger target direction arrow ON TOP OF the leg line
            if (activeLeg != null) {
                val p1 = toCanvasOffset(activeLeg.start.x, activeLeg.start.y)
                val p2 = toCanvasOffset(activeLeg.target.x, activeLeg.target.y)
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val legLenPx = hypot(dx, dy)
                if (legLenPx > 15f) {
                    val uxLeg = dx / legLenPx
                    val uyLeg = dy / legLenPx
                    val vxLeg = -uyLeg
                    val vyLeg = uxLeg

                    val arrowCenter = Offset(p1.x + 0.55f * dx, p1.y + 0.55f * dy)
                    val arrowSize = 18f * zoomScale.coerceIn(0.8f, 3f)

                    val tipX = arrowCenter.x + uxLeg * arrowSize
                    val tipY = arrowCenter.y + uyLeg * arrowSize
                    val leftX = arrowCenter.x - uxLeg * (arrowSize * 0.7f) + vxLeg * (arrowSize * 0.6f)
                    val leftY = arrowCenter.y - uyLeg * (arrowSize * 0.7f) + vyLeg * (arrowSize * 0.6f)
                    val rightX = arrowCenter.x - uxLeg * (arrowSize * 0.7f) - vxLeg * (arrowSize * 0.6f)
                    val rightY = arrowCenter.y - uyLeg * (arrowSize * 0.7f) - vyLeg * (arrowSize * 0.6f)

                    val arrowPath = Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(leftX, leftY)
                        lineTo(rightX, rightY)
                        close()
                    }

                    drawPath(
                        path = arrowPath,
                        color = Color(0xFFFFD700)
                    )
                    drawPath(
                        path = arrowPath,
                        color = Color(0xFF000000).copy(alpha = 0.6f),
                        style = Stroke(width = 1.5f)
                    )
                }
            }

            // Draw route waypoints and intelligent waypoint names
            val legBearingDeg = if (isLegView && activeLeg != null) activeLeg.start.bearingTo(activeLeg.target) else 0.0
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

                // Intelligent Waypoint Name Placement
                val wpName = wp.name.ifBlank { "" }
                if (wpName.isNotBlank()) {
                    val attachedLegs = route.legs.filter { it.start == wp || it.target == wp }
                    val labelAngleRad = computeIntelligentLabelAngle(wp, attachedLegs, isLegView, legBearingDeg)

                    val labelDistancePx = 22f * zoomScale.coerceIn(0.5f, 2.0f)
                    val labelOffsetX = (labelDistancePx * sin(labelAngleRad)).toFloat()
                    val labelOffsetY = (-labelDistancePx * cos(labelAngleRad)).toFloat()

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
                        color = Color.Black.copy(alpha = 0.70f),
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

            // 1-pixel adaptive decimation filter
            val renderBreadcrumbs = mutableListOf<Breadcrumb>()
            var lastOffset: Offset? = null
            for (bc in validBreadcrumbs) {
                val offset = toCanvasOffset(bc.location.x, bc.location.y)
                if (bc.isGpsFix || lastOffset == null || (offset.x - lastOffset.x).let { it * it } + (offset.y - lastOffset.y).let { it * it } >= 1.0f) {
                    renderBreadcrumbs.add(bc)
                    lastOffset = offset
                }
            }

            // Draw historical breadcrumb path trajectory lines
            if (renderBreadcrumbs.size >= 2) {
                val path = Path()
                val firstPoint = toCanvasOffset(renderBreadcrumbs[0].location.x, renderBreadcrumbs[0].location.y)
                path.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until renderBreadcrumbs.size) {
                    val point = toCanvasOffset(renderBreadcrumbs[i].location.x, renderBreadcrumbs[i].location.y)
                    path.lineTo(point.x, point.y)
                }

                drawPath(
                    path = path,
                    color = Color(0xFF00E5FF),
                    style = Stroke(width = 3f * zoomScale.coerceIn(0.5f, 3f))
                )
            }

            // Draw historical breadcrumbs (cyan for PDR, bright red for GPS fixes)
            renderBreadcrumbs.forEach { bc ->
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
                val currentRadiusPx = if (isLegView) 12f else (sqrt(activeEkfState.position.variance) * baseScale.toDouble() * zoomScale.toDouble()).toFloat().coerceIn(6f, 60f)

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

        // Upper Right UI Stack (North Indicator + Relative Bearing Indicator - Always Visible)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            // 1. North / Compass Button (Always Visible - Toggles Map View <-> Leg View)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { onUpdateMapConfig { it.copy(isLegView = !it.isLegView) } }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f - 2f
                        val northAngle = if (isLegView && activeLeg != null) {
                            -activeLeg.start.bearingTo(activeLeg.target)
                        } else {
                            0.0
                        }
                        val angleRad = Math.toRadians(northAngle)
                        val tipX = center.x + (radius * 0.8f * sin(angleRad)).toFloat()
                        val tipY = center.y - (radius * 0.8f * cos(angleRad)).toFloat()

                        drawCircle(color = Color(0xFF1E293B), radius = radius, center = center)
                        drawLine(
                            color = Color(0xFFFF5252),
                            start = center,
                            end = Offset(tipX, tipY),
                            strokeWidth = 3f
                        )
                        drawCircle(color = Color.White, radius = 2f, center = center)
                    }
                    Text(
                        text = if (isLegView) "Leg View" else "North",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }

            // 2. Relative Bearing Indicator (Visible when EKF initialized with GPS)
            if (activeEkfState.isInitializedWithGps) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(modifier = Modifier.size(24.dp)) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val radius = size.width / 2f - 2f
                            drawCircle(color = Color(0xFF1E293B), radius = radius, center = center)
                            drawCircle(color = Color(0xFF475569), radius = radius, center = center, style = Stroke(1f))

                            val angleRad = Math.toRadians(relativeBearingCorrection)
                            val tipX = center.x + (radius * 0.75f * sin(angleRad)).toFloat()
                            val tipY = center.y - (radius * 0.75f * cos(angleRad)).toFloat()

                            drawLine(
                                color = Color(0xFFFFB300),
                                start = center,
                                end = Offset(tipX, tipY),
                                strokeWidth = 3f
                            )
                            drawCircle(color = Color(0xFFFFD700), radius = 2f, center = center)
                        }

                        val correctionText = when {
                            relativeBearingCorrection > 0 -> "+%.0f°".format(relativeBearingCorrection)
                            relativeBearingCorrection < 0 -> "%.0f°".format(relativeBearingCorrection)
                            else -> "+0°"
                        }
                        Text(
                            text = correctionText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )
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
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            ) {
                Text("⚙", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }

        // Comprehensive Map & Hike Settings Dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = {
                    Text("Map & Navigation Settings", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Telemetry Dashboard", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Toggle visibility of live EKF state viewport", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = mapConfig.showTelemetryViewport,
                                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showTelemetryViewport = it) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Offline OSM Map Background", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Display OpenStreetMap tiles as map background", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = mapConfig.showOsmBackground,
                                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(showOsmBackground = it) } }
                            )
                        }

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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("1km Grid Lines (Labeled)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Labels lines using 2-digit Irish Grid reference", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = show1kmGrid,
                                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(show1kmGrid = it) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("100m Grid Lines (Unlabeled)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Displays fine unlabeled 100m sub-grid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = show100mGrid,
                                onCheckedChange = { onUpdateMapConfig { cfg -> cfg.copy(show100mGrid = it) } }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("GPS Polling Interval:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Frequency of GPS updates during active tracking", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    1000L to "1s",
                                    5000L to "5s",
                                    10000L to "10s",
                                    30000L to "30s",
                                    100000L to "100s"
                                ).forEach { (ms, label) ->
                                    FilterChip(
                                        selected = mapConfig.gpsPollingIntervalMs == ms,
                                        onClick = {
                                            onUpdateMapConfig { cfg -> cfg.copy(gpsPollingIntervalMs = ms) }
                                            onIntervalSelected(ms)
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Target Arrival Threshold:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Distance to trigger TARGET_REACHED (meters)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5.0, 10.0, 15.0, 20.0).forEach { dist ->
                                    FilterChip(
                                        selected = hikeConfig.arriveThresholdMeters == dist,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(arriveThresholdMeters = dist) } },
                                        label = { Text("%.0fm".format(dist), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Target Approach Threshold:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Distance to trigger APPROACHING_TARGET (meters)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(20.0, 40.0, 60.0, 100.0).forEach { dist ->
                                    FilterChip(
                                        selected = hikeConfig.approachThresholdMeters == dist,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(approachThresholdMeters = dist) } },
                                        label = { Text("%.0fm".format(dist), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Off-Course Heading Deviation:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Degrees deviation to trigger OFFCOURSE alert", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5.0, 10.0, 15.0, 20.0).forEach { deg ->
                                    FilterChip(
                                        selected = hikeConfig.offCourseHeadingDevDeg == deg,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(offCourseHeadingDevDeg = deg) } },
                                        label = { Text("%.0f°".format(deg), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Off-Course Min Speed:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Minimum speed (m/s) to evaluate off-course heading", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.2, 0.4, 0.6, 0.8).forEach { speed ->
                                    FilterChip(
                                        selected = hikeConfig.offCourseMinSpeedMps == speed,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(offCourseMinSpeedMps = speed) } },
                                        label = { Text("%.1f".format(speed), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Off-Course Drift Cool-off:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Cool-off when course drifts further (seconds)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1L, 2L, 4L, 6L).forEach { sec ->
                                    FilterChip(
                                        selected = hikeConfig.driftCoolOffSec == sec,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(driftCoolOffSec = sec) } },
                                        label = { Text("${sec}s", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Off-Course Steady Cool-off:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Cool-off when holding steady off-course heading (seconds)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(4L, 6L, 10L, 15L).forEach { sec ->
                                    FilterChip(
                                        selected = hikeConfig.steadyCoolOffSec == sec,
                                        onClick = { onUpdateHikeConfig { cfg -> cfg.copy(steadyCoolOffSec = sec) } },
                                        label = { Text("${sec}s", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

private fun computeIntelligentLabelAngle(
    wp: Waypoint,
    attachedLegs: List<Leg>,
    isLegView: Boolean,
    legBearing: Double
): Double {
    if (attachedLegs.isEmpty()) return Math.toRadians(45.0)

    val angles = attachedLegs.map { leg ->
        val otherWp = if (leg.start == wp) leg.target else leg.start
        var bearing = wp.bearingTo(otherWp)
        if (isLegView) {
            bearing -= legBearing
        }
        Math.toRadians(bearing)
    }.sorted()

    if (angles.size == 1) {
        return angles[0] + Math.PI
    }

    var maxGap = 0.0
    var bestBisector = angles[0] + Math.PI

    for (i in angles.indices) {
        val nextIdx = (i + 1) % angles.size
        var gap = angles[nextIdx] - angles[i]
        if (gap < 0) gap += 2.0 * Math.PI

        if (gap > maxGap) {
            maxGap = gap
            bestBisector = angles[i] + gap / 2.0
        }
    }

    return bestBisector
}
