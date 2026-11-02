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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class StationaryStop(
    val x: Double,
    val y: Double,
    val durationSec: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long
)

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
    val show1kmGrid = mapConfig.show1kmGrid      // Major adaptive grid toggle
    val show100mGrid = mapConfig.show100mGrid    // Minor adaptive grid toggle

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

    // --- OPTIMIZATION: Speed & Stride statistics cached outside frame draw loop ---
    val speedAndStrideStats = remember(validBreadcrumbs.size, breadcrumbConfig.colorMode) {
        var minS = Double.MAX_VALUE
        var maxS = -Double.MAX_VALUE
        var minSt = Double.MAX_VALUE
        var maxSt = -Double.MAX_VALUE

        if (validBreadcrumbs.isNotEmpty()) {
            for (i in validBreadcrumbs.indices) {
                val s = getBreadcrumbSpeed(i, validBreadcrumbs)
                if (s < minS) minS = s
                if (s > maxS) maxS = s

                val st = getBreadcrumbStride(i, validBreadcrumbs)
                if (st < minSt) minSt = st
                if (st > maxSt) maxSt = st
            }
        }
        val effMinS = if (minS == Double.MAX_VALUE || maxS <= minS) 0.0 else minS
        val effMaxS = if (maxS == -Double.MAX_VALUE || maxS <= minS) 2.5 else maxS
        val effMinSt = if (minSt == Double.MAX_VALUE || maxSt <= minSt) 0.4 else minSt
        val effMaxSt = if (maxSt == -Double.MAX_VALUE || maxSt <= minSt) 1.2 else maxSt
        floatArrayOf(effMinS.toFloat(), effMaxS.toFloat(), effMinSt.toFloat(), effMaxSt.toFloat())
    }

    val minSpeed = speedAndStrideStats[0].toDouble()
    val maxSpeed = speedAndStrideStats[1].toDouble()
    val minStride = speedAndStrideStats[2].toDouble()
    val maxStride = speedAndStrideStats[3].toDouble()

    // --- FEATURE: Detect and Merge Stationary Rest Stops within 1 Minute ---
    val mergedStationaryStops = remember(validBreadcrumbs.size, breadcrumbConfig.showStationaryStops) {
        if (!breadcrumbConfig.showStationaryStops || validBreadcrumbs.size < 2) {
            emptyList<StationaryStop>()
        } else {
            val rawStops = mutableListOf<StationaryStop>()
            var stopStartBc: Breadcrumb? = null
            var stopLastBc: Breadcrumb? = null
            var sumX = 0.0
            var sumY = 0.0
            var stopPointCount = 0

            // 1. Identify raw stops where speed is under 0.3 m/s for at least 30 seconds
            for (bc in validBreadcrumbs) {
                val speed = bc.ekfState?.speedMps?.value ?: 0.0
                if (speed < 0.3) {
                    if (stopStartBc == null) {
                        stopStartBc = bc
                        sumX = 0.0
                        sumY = 0.0
                        stopPointCount = 0
                    }
                    stopLastBc = bc
                    sumX += bc.location.x
                    sumY += bc.location.y
                    stopPointCount++
                } else {
                    if (stopStartBc != null && stopLastBc != null) {
                        val durationSec = (stopLastBc.timestampMs - stopStartBc.timestampMs) / 1000L
                        if (durationSec >= 30L && stopPointCount > 0) {
                            rawStops.add(
                                StationaryStop(
                                    x = sumX / stopPointCount,
                                    y = sumY / stopPointCount,
                                    durationSec = durationSec,
                                    startTimestampMs = stopStartBc.timestampMs,
                                    endTimestampMs = stopLastBc.timestampMs
                                )
                            )
                        }
                    }
                    stopStartBc = null
                    stopLastBc = null
                }
            }

            if (stopStartBc != null && stopLastBc != null) {
                val durationSec = (stopLastBc.timestampMs - stopStartBc.timestampMs) / 1000L
                if (durationSec >= 30L && stopPointCount > 0) {
                    rawStops.add(
                        StationaryStop(
                            x = sumX / stopPointCount,
                            y = sumY / stopPointCount,
                            durationSec = durationSec,
                            startTimestampMs = stopStartBc.timestampMs,
                            endTimestampMs = stopLastBc.timestampMs
                        )
                    )
                }
            }

            // 2. Merge stops within 1 minute (60,000 ms) of each other
            val merged = mutableListOf<StationaryStop>()
            for (stop in rawStops) {
                if (merged.isEmpty()) {
                    merged.add(stop)
                } else {
                    val prev = merged.last()
                    val gapMs = stop.startTimestampMs - prev.endTimestampMs
                    if (gapMs in 0..60000L) {
                        val totalSec = prev.durationSec + stop.durationSec
                        val w1 = prev.durationSec.toDouble() / totalSec
                        val w2 = stop.durationSec.toDouble() / totalSec
                        val combinedX = prev.x * w1 + stop.x * w2
                        val combinedY = prev.y * w1 + stop.y * w2

                        merged[merged.lastIndex] = StationaryStop(
                            x = combinedX,
                            y = combinedY,
                            durationSec = totalSec,
                            startTimestampMs = prev.startTimestampMs,
                            endTimestampMs = stop.endTimestampMs
                        )
                    } else {
                        merged.add(stop)
                    }
                }
            }
            merged
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
                // Exact visible world boundaries derived directly from screen pixels and zoom
                val leftMeters = centerX + (0f - originCx - panOffset.x) / currentScale
                val rightMeters = centerX + (widthPx - originCx - panOffset.x) / currentScale
                val topMeters = centerY - (0f - originCy - panOffset.y) / currentScale
                val bottomMeters = centerY - (heightPx - originCy - panOffset.y) / currentScale

                val minVisX = minOf(leftMeters, rightMeters)
                val maxVisX = maxOf(leftMeters, rightMeters)
                val minVisY = minOf(topMeters, bottomMeters)
                val maxVisY = maxOf(topMeters, bottomMeters)

                // --- Draw Offline OSM Map Background Tiles ---
                if (mapConfig.showOsmBackground) {
                    try {
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

                        val baseTiles = OsmTileManager.getTilesForArea(
                            minX = minVisX - 500.0, maxX = maxVisX + 500.0, minY = minVisY - 500.0, maxY = maxVisY + 500.0, zoom = baseZoom
                        )
                        for (tile in baseTiles) {
                            drawSingleTile(tile, false)
                        }

                        if (autoZoom > 14) {
                            val highResTiles = OsmTileManager.getTilesForArea(
                                minX = minVisX - 200.0, maxX = maxVisX + 200.0, minY = minVisY - 200.0, maxY = maxVisY + 200.0, zoom = autoZoom
                            )
                            for (tile in highResTiles) {
                                drawSingleTile(tile, true)
                            }
                        }
                    } catch (_: Exception) {}
                }

                // =========================================================================
                // ADAPTIVE DECADE GRID SCALING (POWERS OF 10)
                // Constraint 1: Maximum 5 major gridlines in each direction
                // Constraint 2: Minimum 2 minor gridlines in each direction
                // =========================================================================
                val curSpanX = (maxVisX - minVisX).coerceAtLeast(1.0)
                val curSpanY = (maxVisY - minVisY).coerceAtLeast(1.0)
                val maxSpan = maxOf(curSpanX, curSpanY)
                val minSpan = minOf(curSpanX, curSpanY)

                val minMajorStepRequired = maxSpan / 5.0
                val maxMajorStepAllowed = (minSpan / 2.0) * 10.0

                var powerK = ceil(log10(minMajorStepRequired.coerceAtLeast(1.0))).toInt()
                if (10.0.pow(powerK.toDouble()) > maxMajorStepAllowed && powerK > 0) {
                    powerK--
                }

                val clampedPowerK = powerK.coerceIn(0, 5)
                val majorStep = 10.0.pow(clampedPowerK.toDouble()).toInt().coerceAtLeast(1)
                val minorStep = (majorStep / 10).coerceAtLeast(1)

                // 1) Draw Minor Gridlines (Unlabeled, 10x smaller subdivision)
                if (show100mGrid) {
                    val minorSpacingPx = minorStep * currentScale
                    if (minorSpacingPx >= 3.0) {
                        val startMinorX = (floor(minVisX / minorStep) * minorStep).toInt()
                        val endMinorX = (ceil(maxVisX / minorStep) * minorStep).toInt()

                        for (xM in startMinorX..endMinorX step minorStep) {
                            if (xM % majorStep == 0) continue // Skip where major line will be drawn
                            val p1 = toCanvasOffset(xM.toDouble(), minVisY)
                            val p2 = toCanvasOffset(xM.toDouble(), maxVisY)
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.28f),
                                start = p1,
                                end = p2,
                                strokeWidth = 1.0f
                            )
                        }

                        val startMinorY = (floor(minVisY / minorStep) * minorStep).toInt()
                        val endMinorY = (ceil(maxVisY / minorStep) * minorStep).toInt()

                        for (yM in startMinorY..endMinorY step minorStep) {
                            if (yM % majorStep == 0) continue
                            val p1 = toCanvasOffset(minVisX, yM.toDouble())
                            val p2 = toCanvasOffset(maxVisX, yM.toDouble())
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.28f),
                                start = p1,
                                end = p2,
                                strokeWidth = 1.0f
                            )
                        }
                    }
                }

                // 2) Draw Major Gridlines (Labeled, max 5 in each direction)
                if (show1kmGrid) {
                    val startMajorX = (floor(minVisX / majorStep) * majorStep).toInt()
                    val endMajorX = (ceil(maxVisX / majorStep) * majorStep).toInt()

                    for (xM in startMajorX..endMajorX step majorStep) {
                        val p1 = toCanvasOffset(xM.toDouble(), minVisY)
                        val p2 = toCanvasOffset(xM.toDouble(), maxVisY)
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.70f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2.0f
                        )

                        // Easting label with on-screen boundary check (stops piling in lower-left corner)
                        val labelStr = formatGridLabel(xM, clampedPowerK)
                        val textResult = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        )
                        val textWidth = textResult.size.width.toFloat()
                        val textHeight = textResult.size.height.toFloat()

                        if (p1.x >= textWidth + 8f && p1.x <= widthPx - 8f) {
                            val labelX = p1.x - textWidth - 4f
                            val labelY = heightPx - textHeight - 12f

                            drawRect(
                                color = Color.Black.copy(alpha = 0.60f),
                                topLeft = Offset(labelX - 3f, labelY - 2f),
                                size = Size(textWidth + 6f, textHeight + 4f)
                            )
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset(labelX, labelY)
                            )
                        }
                    }

                    val startMajorY = (floor(minVisY / majorStep) * majorStep).toInt()
                    val endMajorY = (ceil(maxVisY / majorStep) * majorStep).toInt()

                    for (yM in startMajorY..endMajorY step majorStep) {
                        val p1 = toCanvasOffset(minVisX, yM.toDouble())
                        val p2 = toCanvasOffset(maxVisX, yM.toDouble())
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.70f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2.0f
                        )

                        // Northing label with on-screen boundary check (stops piling in lower-left corner)
                        val labelStr = formatGridLabel(yM, clampedPowerK)
                        val textResult = textMeasurer.measure(
                            text = labelStr,
                            style = TextStyle(color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        )
                        val textWidth = textResult.size.width.toFloat()
                        val textHeight = textResult.size.height.toFloat()

                        if (p1.y >= textHeight + 8f && p1.y <= heightPx - textHeight - 24f) {
                            val labelX = 12f
                            val labelY = p1.y - textHeight - 2f

                            drawRect(
                                color = Color.Black.copy(alpha = 0.60f),
                                topLeft = Offset(labelX - 3f, labelY - 2f),
                                size = Size(textWidth + 6f, textHeight + 4f)
                            )
                            drawText(
                                textLayoutResult = textResult,
                                topLeft = Offset(labelX, labelY)
                            )
                        }
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

            // Draw waypoints with outer bisector smart label placement
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

                    val connectedLegs = route.legs.filter { it.start == wp || it.target == wp }
                    var normalX = 0f
                    var normalY = -1f

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
                            normalX = (-sumDx / sumLen).toFloat()
                            normalY = (-sumDy / sumLen).toFloat()
                        } else {
                            val firstLeg = connectedLegs.first()
                            val other = if (firstLeg.start == wp) firstLeg.target else firstLeg.start
                            val dx = other.x - wp.x
                            val dy = other.y - wp.y
                            val len = hypot(dx, dy)
                            if (len > 0.1) {
                                normalX = -(dy / len).toFloat()
                                normalY = (dx / len).toFloat()
                            }
                        }
                    }

                    val textResult = textMeasurer.measure(
                        text = wpName,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    val radiusScaled = radiusPx * zoomScale.coerceIn(0.5f, 2.5f)
                    val clearance = radiusScaled + 8f

                    val labelX: Float
                    val labelY: Float

                    if (abs(normalX) >= abs(normalY)) {
                        if (normalX < 0f) {
                            labelX = center.x - clearance - textResult.size.width
                            labelY = center.y - (textResult.size.height / 2f)
                        } else {
                            labelX = center.x + clearance
                            labelY = center.y - (textResult.size.height / 2f)
                        }
                    } else {
                        if (normalY < 0f) {
                            labelY = center.y - clearance - textResult.size.height
                            labelX = center.x - (textResult.size.width / 2f)
                        } else {
                            labelY = center.y + clearance
                            labelX = center.x - (textResult.size.width / 2f)
                        }
                    }

                    val bgPadding = 3f
                    drawRect(
                        color = Color.Black.copy(alpha = 0.20f),
                        topLeft = Offset(labelX - bgPadding, labelY - bgPadding),
                        size = Size(
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

            // --- DRAW BREADCRUMBS ---
            if (breadcrumbConfig.showBreadcrumbs && renderBreadcrumbs.isNotEmpty()) {
                val totalCount = renderBreadcrumbs.size.toFloat()
                val speedRange = (maxSpeed - minSpeed).toFloat().coerceAtLeast(0.001f)
                val strideRange = (maxStride - minStride).toFloat().coerceAtLeast(0.001f)
                val speedColor1 = Color(breadcrumbConfig.speedStartColor)
                val speedColor2 = Color(breadcrumbConfig.speedEndColor)
                val strideColor1 = Color(breadcrumbConfig.strideStartColor)
                val strideColor2 = Color(breadcrumbConfig.strideEndColor)

                val offsets = Array(renderBreadcrumbs.size) { i ->
                    toCanvasOffset(renderBreadcrumbs[i].location.x, renderBreadcrumbs[i].location.y)
                }

                // 1. Line segments connecting points
                val lineStroke = 1.2f * zoomScale.coerceIn(0.5f, 2f)
                for (i in 1 until renderBreadcrumbs.size) {
                    val p1 = offsets[i - 1]
                    val p2 = offsets[i]

                    val alpha = if (breadcrumbConfig.fadeWithAge) {
                        0.25f + 0.75f * (i / totalCount)
                    } else 1.0f

                    val lineColor = if (breadcrumbConfig.colorMode == BreadcrumbColorMode.GPS_PDR) {
                        if (renderBreadcrumbs[i].isGpsFix) Color(0xFFFF5252).copy(alpha = 0.5f * alpha)
                        else Color(0xFF00E5FF).copy(alpha = 0.7f * alpha)
                    } else {
                        Color(0xFF00E5FF).copy(alpha = 0.6f * alpha)
                    }

                    drawLine(
                        color = lineColor,
                        start = p1,
                        end = p2,
                        strokeWidth = lineStroke
                    )
                }

                // 2. Dots and accuracy circles
                val baseDotRadius = breadcrumbConfig.dotDiameterPx * zoomScale.coerceIn(0.5f, 2.0f)

                renderBreadcrumbs.forEachIndexed { i, bc ->
                    val center = offsets[i]

                    val baseColor = when (breadcrumbConfig.colorMode) {
                        BreadcrumbColorMode.GPS_PDR -> {
                            if (bc.isGpsFix) Color(0xFFFF3333) else Color(0xFF2196F3)
                        }
                        BreadcrumbColorMode.SPEED -> {
                            val speedMps = getBreadcrumbSpeed(i, renderBreadcrumbs)
                            val t = ((speedMps - minSpeed) / speedRange).coerceIn(0.0, 1.0).toFloat()
                            lerp(speedColor1, speedColor2, t)
                        }
                        BreadcrumbColorMode.STRIDE -> {
                            val stride = getBreadcrumbStride(i, renderBreadcrumbs)
                            val t = ((stride - minStride) / strideRange).coerceIn(0.0, 1.0).toFloat()
                            lerp(strideColor1, strideColor2, t)
                        }
                    }

                    val alphaMultiplier = if (breadcrumbConfig.fadeWithAge) {
                        0.2f + 0.8f * (i / totalCount)
                    } else 1.0f

                    val finalColor = baseColor.copy(alpha = baseColor.alpha * alphaMultiplier)

                    if (breadcrumbConfig.showAccuracyCircles && bc.ekfState != null) {
                        val accRadiusPx = (sqrt(bc.ekfState.position.variance) * currentScale).toFloat().coerceIn(4f, 80f)
                        drawCircle(
                            color = finalColor.copy(alpha = 0.35f * alphaMultiplier),
                            radius = accRadiusPx,
                            center = center,
                            style = Stroke(width = 1.0f)
                        )
                    }

                    drawCircle(
                        color = finalColor,
                        radius = baseDotRadius,
                        center = center
                    )
                }
            }

            // --- FEATURE: Draw Combined Stationary Rest Stops within 1 Minute ---
            if (breadcrumbConfig.showStationaryStops && mergedStationaryStops.isNotEmpty()) {
                for (stop in mergedStationaryStops) {
                    val stopCenter = toCanvasOffset(stop.x, stop.y)

                    val durationMin = stop.durationSec / 60
                    val durationSecRem = stop.durationSec % 60
                    val stopRadiusPx = (16f + (stop.durationSec / 60f).coerceAtMost(20f) * 1.0f) * zoomScale.coerceIn(0.8f, 2.5f)

                    drawCircle(
                        color = Color(0xFFFFB300).copy(alpha = 0.22f),
                        radius = stopRadiusPx,
                        center = stopCenter
                    )
                    drawCircle(
                        color = Color(0xFFFFB300),
                        radius = stopRadiusPx,
                        center = stopCenter,
                        style = Stroke(width = 2.0f * zoomScale.coerceIn(0.8f, 2.0f))
                    )

                    val timeText = if (durationMin > 0) "${durationMin}m ${durationSecRem}s" else "${durationSecRem}s"
                    val labelText = "Stopped $timeText"
                    val textResult = textMeasurer.measure(
                        text = labelText,
                        style = TextStyle(
                            color = Color(0xFFFFD700),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    val labelX = stopCenter.x - textResult.size.width / 2f
                    val labelY = stopCenter.y + stopRadiusPx + 4f

                    drawRect(
                        color = Color.Black.copy(alpha = 0.65f),
                        topLeft = Offset(labelX - 4f, labelY - 2f),
                        size = Size(
                            textResult.size.width + 8f,
                            textResult.size.height + 4f
                        )
                    )

                    drawText(
                        textLayoutResult = textResult,
                        topLeft = Offset(labelX, labelY)
                    )
                }
            }

            // Draw current active EKF position indicator
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

            // Draw direct line from current hiker position to target waypoint
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

        // Color Legend Overlay Card
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

        // Lower Right Corner Controls
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

        // Settings Dialog
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

/**
 * Formats coordinates for adaptive decade gridlines.
 * Power 0 (1m) -> 5-digit local meters (00000 to 99999)
 * Power 1 (10m) -> 4-digit (0000 to 9999)
 * Power 2 (100m) -> 3-digit (000 to 999)
 * Power 3 (1km) -> 2-digit kilometer number (00 to 99)
 * Power 4 (10km) -> 10km decade marker
 */
private fun formatGridLabel(coordMeters: Int, power: Int): String {
    val localMeters = (coordMeters % 100000).let { if (it < 0) it + 100000 else it }
    return when (power) {
        0 -> String.format(Locale.US, "%05d", localMeters)
        1 -> String.format(Locale.US, "%04d", localMeters / 10)
        2 -> String.format(Locale.US, "%03d", localMeters / 100)
        3 -> String.format(Locale.US, "%02d", (localMeters / 1000) % 100)
        4 -> String.format(Locale.US, "%d0", (localMeters / 10000) % 10)
        else -> String.format(Locale.US, "%d", coordMeters / 1000)
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
                Text("Major Gridlines", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Adaptive decade lines with labels (max 5 per axis)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Minor Gridlines", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("10x smaller subdivision lines without labels", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Combined circle and duration when stationary for 30s or more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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