package com.example.irishhike.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import com.example.irishhike.util.OsmTileManager
import kotlin.math.hypot
import kotlin.math.round

enum class RouteEditorViewMode {
    MAP,
    SPREADSHEET
}

@Composable
fun SpreadsheetCell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorScreen(
    initialRoute: Route?,
    onSaveRoute: (String, Route) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routeName = initialRoute?.name ?: "Editable Route"
    val initialWaypoints = remember(initialRoute) {
        if (initialRoute != null && initialRoute.legs.isNotEmpty()) {
            val list = mutableListOf<Waypoint>()
            initialRoute.legs.forEach { leg ->
                if (list.isEmpty() || list.last() != leg.start) {
                    list.add(leg.start)
                }
            }
            list.add(initialRoute.legs.last().target)
            list
        } else if (initialRoute != null) {
            initialRoute.waypoints
        } else {
            listOf(
                Waypoint.fromMetric("Start", 306500.0, 192800.0, 280.0),
                Waypoint.fromMetric("Target", 303240.0, 191720.0, 925.0)
            )
        }
    }

    var waypoints by remember { mutableStateOf(initialWaypoints) }
    var viewMode by remember { mutableStateOf(RouteEditorViewMode.MAP) }
    var selectedWaypointIndex by remember { mutableStateOf<Int?>(null) }

    // Map Pan & Zoom states
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current
    var pendingCsvToSave by remember { mutableStateOf<String?>(null) }
    var pendingRouteToSave by remember { mutableStateOf<Route?>(null) }

    // System Document Creator launcher for guaranteed write permissions across Android storage
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { targetUri: Uri? ->
        if (targetUri != null && pendingCsvToSave != null) {
            try {
                context.contentResolver.openOutputStream(targetUri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { writer ->
                        writer.write(pendingCsvToSave!!)
                        writer.flush()
                    }
                }
                Toast.makeText(context, "Saved route file successfully!", Toast.LENGTH_SHORT).show()
                val updatedRoute = Route.createFromWaypoints(routeName, waypoints, sourceUri = targetUri)
                onSaveRoute(pendingCsvToSave!!, updatedRoute)
                onBack()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed saving file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleSave(forceSaveAs: Boolean = false) {
        val newRoute = Route.createFromWaypoints(
            name = routeName,
            rawWaypoints = waypoints,
            sourceUri = initialRoute?.sourceUri
        )
        val csv = waypoints.joinToString("\n") { wp ->
            "${wp.name},${wp.x.toInt()},${wp.y.toInt()},${wp.altitude.toInt()}"
        }

        val sourceUri = initialRoute?.sourceUri
        var directSaveSuccess = false

        if (!forceSaveAs && sourceUri != null) {
            try {
                context.contentResolver.openOutputStream(sourceUri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { writer ->
                        writer.write(csv)
                        writer.flush()
                    }
                }
                directSaveSuccess = true
            } catch (_: Exception) {
                // When files are opened via GetContent(), Android grants read-only access.
                // Catch the SecurityException and proceed to createDocumentLauncher
                directSaveSuccess = false
            }
        }

        if (directSaveSuccess) {
            Toast.makeText(context, "Saved: $routeName.csv", Toast.LENGTH_SHORT).show()
            onSaveRoute(csv, newRoute)
            onBack()
        } else {
            // Prompt user with Android system file-save dialog to pick save location with granted write permissions
            pendingCsvToSave = csv
            pendingRouteToSave = newRoute
            try {
                createDocumentLauncher.launch("$routeName.csv")
            } catch (_: Exception) {
                Toast.makeText(context, "Route saved in app", Toast.LENGTH_SHORT).show()
                onSaveRoute(csv, newRoute)
                onBack()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Edit Route: $routeName",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "${waypoints.size} waypoints • Mode: ${viewMode.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9FB3C8),
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                actions = {
                    // Save As / Export to new file
                    IconButton(onClick = { handleSave(forceSaveAs = true) }) {
                        Text("💾+", fontSize = 16.sp, color = Color.White)
                    }

                    // Toggle between Map & Spreadsheet views
                    IconButton(onClick = {
                        viewMode = if (viewMode == RouteEditorViewMode.MAP) {
                            RouteEditorViewMode.SPREADSHEET
                        } else {
                            RouteEditorViewMode.MAP
                        }
                    }) {
                        Text(
                            text = if (viewMode == RouteEditorViewMode.MAP) "📊" else "🗺️",
                            fontSize = 18.sp
                        )
                    }

                    // Reverse Waypoint Direction (↕)
                    IconButton(onClick = {
                        waypoints = waypoints.reversed()
                    }) {
                        Text("↕", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewMode = if (viewMode == RouteEditorViewMode.MAP) {
                                RouteEditorViewMode.SPREADSHEET
                            } else {
                                RouteEditorViewMode.MAP
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                    ) {
                        Text(
                            text = if (viewMode == RouteEditorViewMode.MAP) "📊 Spreadsheet" else "🗺️ Map Editor",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = { handleSave(forceSaveAs = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("💾 Save Route", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF020617))
        ) {
            when (viewMode) {
                RouteEditorViewMode.MAP -> {
                    MapRouteEditor(
                        waypoints = waypoints,
                        selectedWaypointIndex = selectedWaypointIndex,
                        zoomScale = zoomScale,
                        panOffset = panOffset,
                        onZoomPanChange = { newZoom, newPan ->
                            zoomScale = newZoom
                            panOffset = newPan
                        },
                        onWaypointMoved = { index, newX, newY ->
                            val list = waypoints.toMutableList()
                            val cur = list[index]
                            list[index] = Waypoint.fromMetric(cur.name, newX, newY, cur.altitude)
                            waypoints = list
                        },
                        onInsertWaypointOnSegment = { segIndex ->
                            val a = waypoints[segIndex]
                            val b = waypoints[segIndex + 1]
                            val midX = (a.x + b.x) / 2.0
                            val midY = (a.y + b.y) / 2.0
                            val midAlt = (a.altitude + b.altitude) / 2.0
                            val newWp = Waypoint.fromMetric("WP ${segIndex + 2}", midX, midY, midAlt)
                            val list = waypoints.toMutableList()
                            list.add(segIndex + 1, newWp)
                            waypoints = list
                            selectedWaypointIndex = segIndex + 1
                        },
                        onDeleteWaypoint = { index ->
                            if (waypoints.size > 2) {
                                val list = waypoints.toMutableList()
                                list.removeAt(index)
                                waypoints = list
                                selectedWaypointIndex = null
                            }
                        },
                        onSelectWaypoint = { index ->
                            selectedWaypointIndex = if (index >= 0) index else null
                        }
                    )
                }

                RouteEditorViewMode.SPREADSHEET -> {
                    SpreadsheetRouteEditor(
                        waypoints = waypoints,
                        onWaypointsChange = { waypoints = it }
                    )
                }
            }
        }
    }
}

/**
 * Backward compatibility overload in case callers invoke RouteEditorScreen with (Route, (Route) -> Unit, () -> Unit)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteEditorScreen(
    route: Route,
    onSaveRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RouteEditorScreen(
        initialRoute = route,
        onSaveRoute = { _, r -> onSaveRoute(r) },
        onBack = onNavigateBack,
        modifier = modifier
    )
}

@Composable
fun MapRouteEditor(
    waypoints: List<Waypoint>,
    selectedWaypointIndex: Int?,
    zoomScale: Float,
    panOffset: Offset,
    onZoomPanChange: (Float, Offset) -> Unit,
    onWaypointMoved: (Int, Double, Double) -> Unit,
    onInsertWaypointOnSegment: (Int) -> Unit,
    onDeleteWaypoint: (Int) -> Unit,
    onSelectWaypoint: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    // Reusable paint and matrix objects for OpenTopoMap tile rendering
    val sharedTilePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
    }
    val sharedSrcPoly = remember {
        floatArrayOf(
            0f, 0f,
            256f, 0f,
            256f, 256f,
            0f, 256f
        )
    }
    val sharedDstPoly = remember { FloatArray(8) }
    val sharedPolyMatrix = remember { android.graphics.Matrix() }

    // Android native text paint for '+' and waypoint numbering
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val plusTextPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    // Compute bounding box for auto-centering
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    waypoints.forEach { wp ->
        if (wp.x < minX) minX = wp.x
        if (wp.x > maxX) maxX = wp.x
        if (wp.y < minY) minY = wp.y
        if (wp.y > maxY) maxY = wp.y
    }
    val padding = maxOf(250.0, maxOf(maxX - minX, maxY - minY) * 0.15)
    val spanX = maxOf(400.0, maxX - minX + padding * 2)
    val spanY = maxOf(400.0, maxY - minY + padding * 2)
    val centerX = (minX + maxX) / 2.0
    val centerY = (minY + maxY) / 2.0

    // Capture state references without restarting the gesture coroutine loop
    val currentWaypoints by rememberUpdatedState(waypoints)
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentSpanX by rememberUpdatedState(spanX)
    val currentSpanY by rememberUpdatedState(spanY)
    val currentCenterX by rememberUpdatedState(centerX)
    val currentCenterY by rememberUpdatedState(centerY)

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var touchedWaypointIdx: Int? = null
                        var touchedPlusIdx: Int? = null
                        var downPos = Offset.Zero
                        var hasMoved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.filter { it.pressed }

                            if (activePointers.isEmpty()) {
                                // All fingers released
                                if (!hasMoved) {
                                    if (touchedWaypointIdx != null) {
                                        onSelectWaypoint(touchedWaypointIdx)
                                    } else if (touchedPlusIdx != null) {
                                        onInsertWaypointOnSegment(touchedPlusIdx)
                                    } else {
                                        onSelectWaypoint(-1)
                                    }
                                }
                                touchedWaypointIdx = null
                                touchedPlusIdx = null
                                draggingIndex = null
                                hasMoved = false
                            } else if (activePointers.size == 1) {
                                val pointer = activePointers[0]
                                val isDown = event.changes.any { !it.previousPressed && it.pressed }

                                val widthPx = size.width.toFloat()
                                val heightPx = size.height.toFloat()
                                val baseScale = minOf(
                                    (widthPx * 0.85f) / currentSpanX.toFloat(),
                                    (heightPx * 0.85f) / currentSpanY.toFloat()
                                ) * currentZoomScale
                                val originCx = widthPx / 2f + currentPanOffset.x
                                val originCy = heightPx / 2f + currentPanOffset.y

                                fun toOffset(x: Double, y: Double): Offset {
                                    val cx = originCx + ((x - currentCenterX) * baseScale).toFloat()
                                    val cy = originCy - ((y - currentCenterY) * baseScale).toFloat()
                                    return Offset(cx, cy)
                                }

                                if (isDown) {
                                    downPos = pointer.position
                                    hasMoved = false
                                    touchedWaypointIdx = null
                                    touchedPlusIdx = null

                                    // 1. Check if user touched down on a waypoint marker
                                    for (i in currentWaypoints.indices.reversed()) {
                                        val p = toOffset(currentWaypoints[i].x, currentWaypoints[i].y)
                                        if (hypot((pointer.position.x - p.x).toDouble(), (pointer.position.y - p.y).toDouble()) <= 44.0) {
                                            touchedWaypointIdx = i
                                            draggingIndex = i
                                            break
                                        }
                                    }

                                    // 2. Check if user touched down on a '+' segment midpoint
                                    if (touchedWaypointIdx == null) {
                                        for (i in 0 until currentWaypoints.size - 1) {
                                            val p1 = toOffset(currentWaypoints[i].x, currentWaypoints[i].y)
                                            val p2 = toOffset(currentWaypoints[i + 1].x, currentWaypoints[i + 1].y)
                                            val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                            if (hypot((pointer.position.x - mid.x).toDouble(), (pointer.position.y - mid.y).toDouble()) <= 38.0) {
                                                touchedPlusIdx = i
                                                break
                                            }
                                        }
                                    }
                                } else {
                                    val delta = pointer.position - pointer.previousPosition
                                    if (!hasMoved && (pointer.position - downPos).getDistance() > 10f) {
                                        hasMoved = true
                                    }

                                    if (hasMoved) {
                                        if (touchedWaypointIdx != null) {
                                            if (touchedWaypointIdx in currentWaypoints.indices) {
                                                val cur = currentWaypoints[touchedWaypointIdx]
                                                val deltaX = delta.x / baseScale
                                                val deltaY = -delta.y / baseScale
                                                onWaypointMoved(
                                                    touchedWaypointIdx,
                                                    round(cur.x + deltaX),
                                                    round(cur.y + deltaY)
                                                )
                                            }
                                        } else {
                                            val newPan = currentPanOffset + delta
                                            onZoomPanChange(currentZoomScale, newPan)
                                        }
                                        pointer.consume()
                                    }
                                }
                            } else if (activePointers.size >= 2) {
                                hasMoved = true
                                touchedWaypointIdx = null
                                touchedPlusIdx = null
                                draggingIndex = null

                                val p1 = activePointers[0]
                                val p2 = activePointers[1]

                                val currCentroid = (p1.position + p2.position) / 2f
                                val prevCentroid = (p1.previousPosition + p2.previousPosition) / 2f
                                val panDelta = currCentroid - prevCentroid

                                val currDist = (p1.position - p2.position).getDistance()
                                val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()

                                if (prevDist > 10f) {
                                    val zoomRatio = currDist / prevDist
                                    val newZoom = (currentZoomScale * zoomRatio).coerceIn(0.4f, 8.0f)
                                    val effectiveZoom = newZoom / currentZoomScale

                                    val screenCenter = Offset(size.width / 2f, size.height / 2f)
                                    val f = currCentroid - screenCenter
                                    val newPan = f + panDelta - (f - currentPanOffset) * effectiveZoom

                                    onZoomPanChange(newZoom, newPan)
                                } else {
                                    onZoomPanChange(currentZoomScale, currentPanOffset + panDelta)
                                }

                                p1.consume()
                                p2.consume()
                            }
                        }
                    }
                }
        ) {
            val widthPx = size.width
            val heightPx = size.height
            val baseScale = minOf(
                (widthPx * 0.85f) / spanX.toFloat(),
                (heightPx * 0.85f) / spanY.toFloat()
            ) * zoomScale
            val originCx = widthPx / 2f + panOffset.x
            val originCy = heightPx / 2f + panOffset.y

            fun toOffset(x: Double, y: Double): Offset {
                val sx = originCx + (x - centerX).toFloat() * baseScale
                val sy = originCy - (y - centerY).toFloat() * baseScale
                return Offset(sx, sy)
            }

            // --- 1. Draw Offline OSM Map Background Tiles ---
            try {
                if (OsmTileManager.tileCacheVersion.intValue < 0) return@Canvas

                val currentScale = baseScale.toDouble()
                val autoZoom = round(kotlin.math.log2(93113.0 * currentScale)).toInt().coerceIn(10, 17)

                val leftMeters = centerX + (0f - originCx) / currentScale
                val rightMeters = centerX + (widthPx - originCx) / currentScale
                val topMeters = centerY - (0f - originCy) / currentScale
                val bottomMeters = centerY - (heightPx - originCy) / currentScale

                val minVisX = minOf(leftMeters, rightMeters) - 400.0
                val maxVisX = maxOf(leftMeters, rightMeters) + 400.0
                val minVisY = minOf(topMeters, bottomMeters) - 400.0
                val maxVisY = maxOf(topMeters, bottomMeters) + 400.0

                val topoTiles = OsmTileManager.getTilesForArea(
                    minX = minVisX, maxX = maxVisX, minY = minVisY, maxY = maxVisY, zoom = autoZoom
                )

                for (tile in topoTiles) {
                    val pNW = toOffset(tile.nwMetric.x, tile.nwMetric.y)
                    val pNE = toOffset(tile.neMetric.x, tile.neMetric.y)
                    val pSE = toOffset(tile.seMetric.x, tile.seMetric.y)
                    val pSW = toOffset(tile.swMetric.x, tile.swMetric.y)

                    if (pNW.x.isFinite() && pNW.y.isFinite() &&
                        pNE.x.isFinite() && pNE.y.isFinite() &&
                        pSE.x.isFinite() && pSE.y.isFinite() &&
                        pSW.x.isFinite() && pSW.y.isFinite()
                    ) {
                        sharedDstPoly[0] = pNW.x; sharedDstPoly[1] = pNW.y
                        sharedDstPoly[2] = pNE.x; sharedDstPoly[3] = pNE.y
                        sharedDstPoly[4] = pSE.x; sharedDstPoly[5] = pSE.y
                        sharedDstPoly[6] = pSW.x; sharedDstPoly[7] = pSW.y

                        sharedPolyMatrix.reset()
                        if (sharedPolyMatrix.setPolyToPoly(sharedSrcPoly, 0, sharedDstPoly, 0, 4)) {
                            val bitmap = OsmTileManager.getTileBitmap(context, tile.zoom, tile.tileX, tile.tileY)
                            if (bitmap != null) {
                                val androidBitmap = bitmap.asAndroidBitmap()
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
            } catch (_: Exception) {
                // Graceful fallback
            }

            // --- 2. Draw Route Segments ---
            for (i in 0 until waypoints.size - 1) {
                val p1 = toOffset(waypoints[i].x, waypoints[i].y)
                val p2 = toOffset(waypoints[i + 1].x, waypoints[i + 1].y)

                drawLine(
                    color = Color(0xFF0F172A).copy(alpha = 0.85f),
                    start = p1,
                    end = p2,
                    strokeWidth = 12f
                )

                drawLine(
                    color = Color(0xFF10B981),
                    start = p1,
                    end = p2,
                    strokeWidth = 6f
                )

                val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                drawCircle(color = Color(0xFF0F172A), radius = 24f, center = mid)
                drawCircle(color = Color.White, radius = 20f, center = mid)
                drawCircle(color = Color(0xFF059669), radius = 17f, center = mid)

                drawContext.canvas.nativeCanvas.drawText("+", mid.x, mid.y + 10f, plusTextPaint)
            }

            // --- 3. Draw Waypoint Markers ---
            waypoints.forEachIndexed { index, wp ->
                val pos = toOffset(wp.x, wp.y)
                val isSelected = selectedWaypointIndex == index
                val isDragging = draggingIndex == index
                val isStart = index == 0
                val isEnd = index == waypoints.size - 1

                val color = when {
                    isStart -> Color(0xFF10B981)
                    isEnd -> Color(0xFFEF4444)
                    isSelected -> Color(0xFF0284C7)
                    else -> Color(0xFF38BDF8)
                }

                if (isSelected || isDragging) {
                    drawCircle(color = Color(0xFF38BDF8).copy(alpha = 0.35f), radius = 42f, center = pos)
                    drawCircle(color = Color(0xFF38BDF8), radius = 34f, center = pos, style = Stroke(width = 3f))
                }

                drawCircle(color = Color(0xFF0F172A), radius = 26f, center = pos)
                drawCircle(color = color, radius = 22f, center = pos)

                drawContext.canvas.nativeCanvas.drawText("${index + 1}", pos.x, pos.y + 9f, textPaint)
            }
        }

        // --- 4. Floating On-Screen Zoom Controls (+ / - / Fit) ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF1E293B).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0xFF475569)),
                modifier = Modifier.size(46.dp)
            ) {
                IconButton(onClick = {
                    val newZoom = (zoomScale * 1.35f).coerceAtMost(8.0f)
                    onZoomPanChange(newZoom, panOffset)
                }) {
                    Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Surface(
                shape = CircleShape,
                color = Color(0xFF1E293B).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0xFF475569)),
                modifier = Modifier.size(46.dp)
            ) {
                IconButton(onClick = {
                    val newZoom = (zoomScale / 1.35f).coerceAtLeast(0.4f)
                    onZoomPanChange(newZoom, panOffset)
                }) {
                    Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .width(46.dp)
                    .height(30.dp)
            ) {
                IconButton(onClick = {
                    onZoomPanChange(1.0f, Offset.Zero)
                }) {
                    Text("Fit", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                }
            }
        }

        // --- 5. Selected Waypoint Info / Delete Card ---
        if (selectedWaypointIndex != null && selectedWaypointIndex in waypoints.indices) {
            val wp = waypoints[selectedWaypointIndex]
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${selectedWaypointIndex + 1}. ${wp.name}",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "E: ${wp.x.toInt()}  N: ${wp.y.toInt()}",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    if (waypoints.size > 2) {
                        IconButton(
                            onClick = { onDeleteWaypoint(selectedWaypointIndex) },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Text("🗑", color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpreadsheetRouteEditor(
    waypoints: List<Waypoint>,
    onWaypointsChange: (List<Waypoint>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Table Header
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF1E293B),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.width(20.dp))
                Text("Name", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                Text("Easting (X)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                Text("Northing (Y)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                Text("Alt", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.weight(0.5f))
                Text("Actions", style = MaterialTheme.typography.labelSmall, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.2f))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(waypoints) { index, wp ->
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (index % 2 == 0) Color(0xFF131D31) else Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.width(20.dp)
                        )

                        // 1. Name cell
                        SpreadsheetCell(
                            value = wp.name,
                            onValueChange = { newName ->
                                val list = waypoints.toMutableList()
                                list[index] = Waypoint.fromMetric(newName, wp.x, wp.y, wp.altitude)
                                onWaypointsChange(list)
                            },
                            modifier = Modifier.weight(1.0f)
                        )

                        // 2. Easting (X) cell
                        SpreadsheetCell(
                            value = wp.x.toInt().toString(),
                            onValueChange = { newXStr ->
                                val newX = newXStr.toDoubleOrNull() ?: wp.x
                                val list = waypoints.toMutableList()
                                list[index] = Waypoint.fromMetric(wp.name, newX, wp.y, wp.altitude)
                                onWaypointsChange(list)
                            },
                            modifier = Modifier.weight(1.0f),
                            keyboardType = KeyboardType.Number
                        )

                        // 3. Northing (Y) cell
                        SpreadsheetCell(
                            value = wp.y.toInt().toString(),
                            onValueChange = { newYStr ->
                                val newY = newYStr.toDoubleOrNull() ?: wp.y
                                val list = waypoints.toMutableList()
                                list[index] = Waypoint.fromMetric(wp.name, wp.x, newY, wp.altitude)
                                onWaypointsChange(list)
                            },
                            modifier = Modifier.weight(1.0f),
                            keyboardType = KeyboardType.Number
                        )

                        // 4. Altitude cell
                        SpreadsheetCell(
                            value = wp.altitude.toInt().toString(),
                            onValueChange = { newAltStr ->
                                val newAlt = newAltStr.toDoubleOrNull() ?: wp.altitude
                                val list = waypoints.toMutableList()
                                list[index] = Waypoint.fromMetric(wp.name, wp.x, wp.y, newAlt)
                                onWaypointsChange(list)
                            },
                            modifier = Modifier.weight(0.5f),
                            keyboardType = KeyboardType.Number
                        )

                        // 5. In-place action buttons (Up, Down, Plus, Delete)
                        Row(
                            modifier = Modifier.weight(1.2f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Up arrow (↑)
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val list = waypoints.toMutableList()
                                        val item = list.removeAt(index)
                                        list.add(index - 1, item)
                                        onWaypointsChange(list)
                                    }
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("↑", color = if (index > 0) Color.White else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Down arrow (↓)
                            IconButton(
                                onClick = {
                                    if (index < waypoints.size - 1) {
                                        val list = waypoints.toMutableList()
                                        val item = list.removeAt(index)
                                        list.add(index + 1, item)
                                        onWaypointsChange(list)
                                    }
                                },
                                enabled = index < waypoints.size - 1,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("↓", color = if (index < waypoints.size - 1) Color.White else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Plus (+)
                            IconButton(
                                onClick = {
                                    val list = waypoints.toMutableList()
                                    val newWp = Waypoint.fromMetric(
                                        name = "W${waypoints.size + 1}",
                                        x = wp.x + 50.0,
                                        y = wp.y + 50.0,
                                        altitude = wp.altitude
                                    )
                                    list.add(index + 1, newWp)
                                    onWaypointsChange(list)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("+", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            // Delete (🗑)
                            IconButton(
                                onClick = {
                                    if (waypoints.size > 2) {
                                        val list = waypoints.toMutableList()
                                        list.removeAt(index)
                                        onWaypointsChange(list)
                                    }
                                },
                                enabled = waypoints.size > 2,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("🗑", color = if (waypoints.size > 2) Color(0xFFFF5252) else Color.Gray, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}