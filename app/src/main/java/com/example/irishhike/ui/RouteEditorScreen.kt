package com.example.irishhike.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint

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
                Waypoint.fromMetric("Start", 0.0, 0.0),
                Waypoint.fromMetric("Target", 0.0, 100.0)
            )
        }
    }

    var waypoints by remember { mutableStateOf(initialWaypoints) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Edit Route: $routeName", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                },
                navigationIcon = {
                    // Return Arrow (←)
                    IconButton(onClick = {
                        val newRoute = Route.createFromWaypoints(routeName, waypoints)
                        val csv = waypoints.joinToString("\n") { wp ->
                            "${wp.name},${wp.x},${wp.y},${wp.altitude}"
                        }
                        onSaveRoute(csv, newRoute)
                        onBack()
                    }) {
                        Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                actions = {
                    // Vertical / Reverse Arrow on top right (↕)
                    IconButton(onClick = {
                        waypoints = waypoints.reversed()
                    }) {
                        Text("↕", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Column Headers (rendered once at the top)
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Name", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                    Text("Easting (X)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                    Text("Northing (Y)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.0f))
                    Text("Alt", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF9FB3C8), modifier = Modifier.weight(0.5f))
                    Text("Actions", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF9FB3C8), modifier = Modifier.weight(1.2f))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(waypoints) { index, wp ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF0F172A),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var nameVal by remember(wp) { mutableStateOf(wp.name) }
                            var xVal by remember(wp) { mutableStateOf(wp.x.toInt().toString()) }
                            var yVal by remember(wp) { mutableStateOf(wp.y.toInt().toString()) }
                            var altVal by remember(wp) { mutableStateOf(wp.altitude.toInt().toString()) }

                            // Name cell
                            SpreadsheetCell(
                                value = nameVal,
                                onValueChange = {
                                    nameVal = it
                                    val list = waypoints.toMutableList()
                                    list[index] = Waypoint.fromMetric(
                                        name = it,
                                        x = xVal.toDoubleOrNull() ?: wp.x,
                                        y = yVal.toDoubleOrNull() ?: wp.y,
                                        altitude = altVal.toDoubleOrNull() ?: wp.altitude,
                                        arrive = wp.arrive,
                                        approach = wp.approach
                                    )
                                    waypoints = list
                                },
                                modifier = Modifier.weight(1.0f)
                            )

                            // Easting (X) cell
                            SpreadsheetCell(
                                value = xVal,
                                onValueChange = {
                                    xVal = it
                                    val list = waypoints.toMutableList()
                                    list[index] = Waypoint.fromMetric(
                                        name = nameVal,
                                        x = it.toDoubleOrNull() ?: wp.x,
                                        y = yVal.toDoubleOrNull() ?: wp.y,
                                        altitude = altVal.toDoubleOrNull() ?: wp.altitude,
                                        arrive = wp.arrive,
                                        approach = wp.approach
                                    )
                                    waypoints = list
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1.0f)
                            )

                            // Northing (Y) cell
                            SpreadsheetCell(
                                value = yVal,
                                onValueChange = {
                                    yVal = it
                                    val list = waypoints.toMutableList()
                                    list[index] = Waypoint.fromMetric(
                                        name = nameVal,
                                        x = xVal.toDoubleOrNull() ?: wp.x,
                                        y = it.toDoubleOrNull() ?: wp.y,
                                        altitude = altVal.toDoubleOrNull() ?: wp.altitude,
                                        arrive = wp.arrive,
                                        approach = wp.approach
                                    )
                                    waypoints = list
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1.0f)
                            )

                            // Altitude cell
                            SpreadsheetCell(
                                value = altVal,
                                onValueChange = {
                                    altVal = it
                                    val list = waypoints.toMutableList()
                                    list[index] = Waypoint.fromMetric(
                                        name = nameVal,
                                        x = xVal.toDoubleOrNull() ?: wp.x,
                                        y = yVal.toDoubleOrNull() ?: wp.y,
                                        altitude = it.toDoubleOrNull() ?: wp.altitude,
                                        arrive = wp.arrive,
                                        approach = wp.approach
                                    )
                                    waypoints = list
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.5f)
                            )

                            // In-place action buttons (Up, Down, Plus, Delete)
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
                                            waypoints = list
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
                                            waypoints = list
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
                                        waypoints = list
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
                                            waypoints = list
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
}
