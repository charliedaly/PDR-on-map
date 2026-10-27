package com.example.irishhike

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.example.irishhike.repository.HikeRepository
import com.example.irishhike.ui.ControlPanel
import com.example.irishhike.ui.HikeViewModel
import com.example.irishhike.ui.HikeNavigationPanel
import com.example.irishhike.ui.MapScreen
import com.example.irishhike.ui.NavigationMessagePanel
import com.example.irishhike.ui.RouteEditorScreen
import com.example.irishhike.ui.TelemetryDashboard
import com.example.irishhike.ui.theme.PDRWithEKFTestTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HikeViewModel by viewModels()

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
        HikeRepository.initPreferences(this)

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
                    onLoadTelemetryTrackUri = { uri ->
                        viewModel.loadTelemetryTrackFromUri(this, uri)
                    },
                    onRequestPermissionsAndStart = {
                        checkAndRequestPermissions()
                    },
                    onStopTracking = {
                        viewModel.stopTracking(this)
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
    viewModel: HikeViewModel,
    onLoadRouteUri: (Uri) -> Unit,
    onLoadSampleRoute: () -> Unit,
    onLoadTelemetryTrackUri: (Uri) -> Unit,
    onRequestPermissionsAndStart: () -> Unit,
    onStopTracking: () -> Unit
) {
    val ekfState by viewModel.ekfState.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val currentTargetWaypoint by viewModel.currentTargetWaypoint.collectAsState()
    val mapConfig by viewModel.mapConfig.collectAsState()
    val hikeConfig by viewModel.hikeConfig.collectAsState()
    val activeLeg by viewModel.activeLeg.collectAsState()
    val currentLegIndex by viewModel.currentLegIndex.collectAsState()
    val navigationMessages by viewModel.navigationMessages.collectAsState()
    val relativeBearingCorrection by viewModel.relativeBearingCorrection.collectAsState()

    val isPlaybackPlaying by viewModel.isPlaybackPlaying.collectAsState()
    val isPlaybackPaused by viewModel.isPlaybackPaused.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val playbackEvents by viewModel.playbackEvents.collectAsState()

    val context = LocalContext.current
    var showRouteEditor by remember { mutableStateOf(false) }
    var showSaveBreadcrumbsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(navigationMessages) {
        if (navigationMessages.any { it.text.contains("Hike completed", ignoreCase = true) }) {
            if (breadcrumbs.isNotEmpty()) {
                showSaveBreadcrumbsDialog = true
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onLoadRouteUri(it) }
    }

    val trackPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onLoadTelemetryTrackUri(it) }
    }

    if (showRouteEditor) {
        RouteEditorScreen(
            initialRoute = activeRoute,
            onSaveRoute = { _, newRoute ->
                viewModel.saveEditedRoute(activeRoute?.name ?: "Edited Route", newRoute)
            },
            onBack = {
                showRouteEditor = false
            }
        )
    } else {
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
                HikeNavigationPanel(
                    activeRoute = activeRoute,
                    activeLeg = activeLeg,
                    activeEkfState = ekfState,
                    currentLegIndex = currentLegIndex,
                    onPrevLeg = { viewModel.goToPreviousLeg() },
                    onNextLeg = { viewModel.goToNextLeg() },
                    onEditRoute = { showRouteEditor = true }
                )

                if (mapConfig.showTelemetryViewport) {
                    TelemetryDashboard(ekfState = ekfState)
                }

                NavigationMessagePanel(messages = navigationMessages)

                MapScreen(
                    route = activeRoute,
                    breadcrumbs = breadcrumbs,
                    activeEkfState = ekfState,
                    currentTargetWaypoint = currentTargetWaypoint,
                    mapConfig = mapConfig,
                    hikeConfig = hikeConfig,
                    activeLeg = activeLeg,
                    relativeBearingCorrection = relativeBearingCorrection,
                    onUpdateMapConfig = { viewModel.updateMapConfig(it) },
                    onUpdateHikeConfig = { viewModel.updateHikeConfig(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                ControlPanel(
                    isTracking = isTracking,
                    onToggleTracking = {
                        if (isTracking) {
                            onStopTracking()
                            if (breadcrumbs.isNotEmpty()) {
                                showSaveBreadcrumbsDialog = true
                            }
                        } else {
                            onRequestPermissionsAndStart()
                        }
                    },
                    onLoadTrack = { trackPickerLauncher.launch("*/*") },
                    hasLoadedTrack = playbackEvents.isNotEmpty(),
                    isPlaybackPlaying = isPlaybackPlaying,
                    isPlaybackPaused = isPlaybackPaused,
                    playbackSpeed = playbackSpeed,
                    playbackCurrentIndex = playbackProgress.currentIndex,
                    playbackTotalEvents = playbackProgress.totalEvents,
                    onPlayPlayback = { viewModel.startPlayback() },
                    onPausePlayback = { viewModel.pausePlayback() },
                    onStopPlayback = { viewModel.stopPlayback() },
                    onStepBackPlayback = { viewModel.stepBackPlayback() },
                    onStepForwardPlayback = { viewModel.stepForwardPlayback() },
                    onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) }
                )
            }
        }

        if (showSaveBreadcrumbsDialog) {
            AlertDialog(
                onDismissRequest = { showSaveBreadcrumbsDialog = false },
                title = { Text("Save Breadcrumbs?", fontWeight = FontWeight.Bold) },
                text = { Text("Would you like to save your recorded breadcrumb track as a .gpx file (bc-yyyymmdd-hhmm.gpx)?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSaveBreadcrumbsDialog = false
                            viewModel.exportBreadcrumbs(context)
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSaveBreadcrumbsDialog = false }
                    ) {
                        Text("Discard")
                    }
                }
            )
        }
    }
}
