package com.example.pdrandroutetest

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.pdrandroutetest.model.Route
import com.example.pdrandroutetest.ui.ControlPanel
import com.example.pdrandroutetest.ui.DiagnosticViewModel
import com.example.pdrandroutetest.ui.MapScreen
import com.example.pdrandroutetest.ui.TelemetryDashboard
import com.example.pdrandroutetest.ui.theme.PDRWithEKFTestTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DiagnosticViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            viewModel.startTracking(this)
        } else {
            Toast.makeText(this, "Location permission required for PDR tracking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PDRWithEKFTestTheme {
                MainDiagnosticScreen(
                    viewModel = viewModel,
                    onLoadRouteUri = { uri ->
                        viewModel.loadRouteFromUri(this, uri)
                    },
                    onLoadSampleRoute = {
                        viewModel.loadSampleRoute()
                    },
                    onRequestPermissionsAndStart = {
                        checkAndRequestPermissions()
                    },
                    onStopTracking = {
                        viewModel.stopTracking(this)
                    },
                    onIntervalSelected = { intervalMs ->
                        viewModel.setGpsPollingInterval(this, intervalMs)
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            viewModel.startTracking(this)
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDiagnosticScreen(
    viewModel: DiagnosticViewModel,
    onLoadRouteUri: (Uri) -> Unit,
    onLoadSampleRoute: () -> Unit,
    onRequestPermissionsAndStart: () -> Unit,
    onStopTracking: () -> Unit,
    onIntervalSelected: (Long) -> Unit
) {
    val ekfState by viewModel.ekfState.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val gpsIntervalMs by viewModel.gpsPollingIntervalMs.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onLoadRouteUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (activeRoute == null) {
                            Text(
                                text = "PDR & 6-State EKF Diagnostic",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            val route = activeRoute!!
                            val lengthKm = route.totalLengthMeters / 1000.0
                            Text(
                                text = route.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${route.numLegs} Legs • %.2f km".format(lengthKm),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeRoute == null) {
                            OutlinedButton(
                                onClick = onLoadSampleRoute,
                                modifier = Modifier.padding(end = 2.dp)
                            ) {
                                Text("Sample", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                if (activeRoute == null) "Load Route" else "Load New",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
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
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Requirement 3.2: Telemetry dashboard with EKF components & errors
            TelemetryDashboard(ekfState = ekfState)

            // Requirement 3.3: Maximized abstract map auto-scaled to show all waypoints & breadcrumbs
            MapScreen(
                route = activeRoute,
                breadcrumbs = breadcrumbs,
                activeEkfState = ekfState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Requirement 3.4: Control panel
            ControlPanel(
                isTracking = isTracking,
                currentIntervalMs = gpsIntervalMs,
                onToggleTracking = {
                    if (isTracking) {
                        onStopTracking()
                    } else {
                        onRequestPermissionsAndStart()
                    }
                },
                onIntervalSelected = onIntervalSelected
            )
        }
    }
}
