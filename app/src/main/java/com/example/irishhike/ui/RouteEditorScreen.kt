// --- FILE: app/src/main/java/com/example/irishhike/ui/RouteEditorScreen.kt ---

package com.example.irishhike.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF1E293B),
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

    // Map Pan and Zoom states
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

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
            // Dedicated bottom save bar (replaces settings button while editing)
            Surface(
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            text = if (viewMode == RouteEditorViewMode.MAP) "Spreadsheet Entry" else "Interactive Map",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            val newRoute = Route.createFromWaypoints(routeName, waypoints)
                            val csv = waypoints.joinToString("\n") { wp ->
                                "${wp.name},${wp.x.toInt()},${wp.y.toInt()},${wp.altitude.toInt()}"
                            }
                            onSaveRoute(csv, newRoute)
                            onBack()
                        },
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
                            selectedWaypointIndex = index
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newZoom = (zoomScale * zoom).coerceIn(0.5f, 5.0f)
                    val effectiveZoom = newZoom / zoomScale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val f = centroid - center
                    val newPan = f + pan - (f - panOffset) * effectiveZoom
                    onZoomPanChange(newZoom, newPan)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val widthPx = size.width
            val heightPx = size.height

            val baseScale = minOf(
                (widthPx * 0.80f) / spanX.toFloat(),
                (heightPx * 0.80f) / spanY.toFloat()
            ) * zoomScale

            val originCx = widthPx / 2f + panOffset.x
            val originCy = heightPx / 2f + panOffset.y

            fun toOffset(x: Double, y: Double): Offset {
                val cx = originCx + ((x - centerX) * baseScale).toFloat()
                // Invert Y coordinate so Irish Grid North points upward
                val cy = originCy - ((y - centerY) * baseScale).toFloat()
                return Offset(cx, cy)
            }

            // --- 1. Draw OpenTopoMap Background Tiles (Contours & Relief) ---
            try {
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
                    val corners = OsmTileManager.getTileCorners(tile.zoom, tile.tileX, tile.tileY)
                    val pNW = toOffset(corners.nwMetric.x, corners.nwMetric.y)
                    val pNE = toOffset(corners.neMetric.x, corners.neMetric.y)
                    val pSE = toOffset(corners.seMetric.x, corners.seMetric.y)
                    val pSW = toOffset(corners.swMetric.x, corners.swMetric.y)

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
            } catch (e: Exception) {
                // Graceful fallback if offline tile decoding encounters transient error
            }

            // --- 2. Draw Route Segments ---
            for (i in 0 until waypoints.size - 1) {
                val p1 = toOffset(waypoints[i].x, waypoints[i].y)
                val p2 = toOffset(waypoints[i + 1].x, waypoints[i + 1].y)

                // Dark outline for visibility over bright topo contours
                drawLine(
                    color = Color(0xFF0F172A).copy(alpha = 0.8f),
                    start = p1,
                    end = p2,
                    strokeWidth = 10f
                )

                // Vivid green route track line
                drawLine(
                    color = Color(0xFF10B981),
                    start = p1,
                    end = p2,
                    strokeWidth = 5f
                )

                // (+) Segment split midpoint marker
                val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                drawCircle(color = Color(0xFF0F172A), radius = 18f, center = mid)
                drawCircle(color = Color(0xFF34D399), radius = 18f, center = mid, style = Stroke(width = 3f))
            }

            // --- 3. Draw Waypoint Circles ---
            waypoints.forEachIndexed { index, wp ->
                val pos = toOffset(wp.x, wp.y)
                val isSelected = selectedWaypointIndex == index
                val isStart = index == 0
                val isEnd = index == waypoints.size - 1

                val color = when {
                    isStart -> Color(0xFF10B981)
                    isEnd -> Color(0xFFEF4444)
                    else -> Color(0xFF38BDF8)
                }

                if (isSelected) {
                    drawCircle(color = color.copy(alpha = 0.35f), radius = 28f, center = pos)
                }
                drawCircle(color = Color(0xFF0F172A), radius = 18f, center = pos)
                drawCircle(color = color, radius = 18f, center = pos, style = Stroke(width = 4f))
            }
        }

        // Tap gestures for selecting waypoints and clicking (+) to split segments
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(waypoints, zoomScale, panOffset) {
                    detectTapGestures { tapOffset ->
                        val widthPx = size.width
                        val heightPx = size.height
                        val baseScale = minOf(
                            (widthPx * 0.80f) / spanX.toFloat(),
                            (heightPx * 0.80f) / spanY.toFloat()
                        ) * zoomScale
                        val originCx = widthPx / 2f + panOffset.x
                        val originCy = heightPx / 2f + panOffset.y

                        fun toOffset(x: Double, y: Double): Offset {
                            val cx = originCx + ((x - centerX) * baseScale).toFloat()
                            val cy = originCy - ((y - centerY) * baseScale).toFloat()
                            return Offset(cx, cy)
                        }

                        // Check if tap hit any Waypoint circle
                        for (i in waypoints.indices) {
                            val pos = toOffset(waypoints[i].x, waypoints[i].y)
                            if (hypot((tapOffset.x - pos.x).toDouble(), (tapOffset.y - pos.y).toDouble()) < 32.0) {
                                onSelectWaypoint(i)
                                return@detectTapGestures
                            }
                        }

                        // Check if tap hit any (+) midpoint circle
                        for (i in 0 until waypoints.size - 1) {
                            val p1 = toOffset(waypoints[i].x, waypoints[i].y)
                            val p2 = toOffset(waypoints[i + 1].x, waypoints[i + 1].y)
                            val mid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                            if (hypot((tapOffset.x - mid.x).toDouble(), (tapOffset.y - mid.y).toDouble()) < 28.0) {
                                onInsertWaypointOnSegment(i)
                                return@detectTapGestures
                            }
                        }
                    }
                }
                .pointerInput(waypoints, zoomScale, panOffset) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val widthPx = size.width
                            val heightPx = size.height
                            val baseScale = minOf(
                                (widthPx * 0.80f) / spanX.toFloat(),
                                (heightPx * 0.80f) / spanY.toFloat()
                            ) * zoomScale
                            val originCx = widthPx / 2f + panOffset.x
                            val originCy = heightPx / 2f + panOffset.y

                            for (i in waypoints.indices) {
                                val cx = originCx + ((waypoints[i].x - centerX) * baseScale).toFloat()
                                val cy = originCy - ((waypoints[i].y - centerY) * baseScale).toFloat()
                                if (hypot((startOffset.x - cx).toDouble(), (startOffset.y - cy).toDouble()) < 36.0) {
                                    draggingIndex = i
                                    onSelectWaypoint(i)
                                    break
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            draggingIndex?.let { idx ->
                                change.consume()
                                val widthPx = size.width
                                val heightPx = size.height
                                val baseScale = minOf(
                                    (widthPx * 0.80f) / spanX.toFloat(),
                                    (heightPx * 0.80f) / spanY.toFloat()
                                ) * zoomScale
                                val originCx = widthPx / 2f + panOffset.x
                                val originCy = heightPx / 2f + panOffset.y

                                val newX = ((change.position.x - originCx) / baseScale) + centerX
                                val newY = -((change.position.y - originCy) / baseScale) + centerY
                                onWaypointMoved(idx, newX, newY)
                            }
                        },
                        onDragEnd = { draggingIndex = null },
                        onDragCancel = { draggingIndex = null }
                    )
                }
        )

        // Selected Waypoint Floating Card with Delete Option
        selectedWaypointIndex?.let { idx ->
            if (idx in waypoints.indices) {
                val wp = waypoints[idx]
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "${idx + 1}. ${wp.name}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "E: ${wp.x.toInt()}  N: ${wp.y.toInt()}",
                                color = Color(0xFF34D399),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = { onDeleteWaypoint(idx) },
                            enabled = waypoints.size > 2,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("🗑 Delete", fontSize = 11.sp, color = Color.White)
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
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(waypoints) { index, wp ->
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B),
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
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1.0f)
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
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1.0f)
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
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(0.5f)
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