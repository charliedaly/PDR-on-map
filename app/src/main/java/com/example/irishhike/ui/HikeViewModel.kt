package com.example.irishhike.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.irishhike.config.HikeConfig
import com.example.irishhike.config.MapConfig
import com.example.irishhike.data.EkfState
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Route
import com.example.irishhike.repository.HikeRepository
import com.example.irishhike.service.PdrTrackingService
import com.example.irishhike.telemetry.PlaybackProgress
import com.example.irishhike.telemetry.TelemetryEvent
import com.example.irishhike.telemetry.TelemetryRecorder
import com.example.irishhike.util.HikerTrackExporter
import com.example.irishhike.util.RouteParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class HikeViewModel : ViewModel() {

    val ekfState: StateFlow<EkfState> = HikeRepository.ekfStateFlow
    val isTracking: StateFlow<Boolean> = HikeRepository.isTracking
    val gpsPollingIntervalMs: StateFlow<Long> = HikeRepository.gpsPollingIntervalMs
    val mapConfig: StateFlow<MapConfig> = HikeRepository.mapConfig
    val hikeConfig: StateFlow<HikeConfig> = HikeRepository.hikeConfig
    val activeRoute: StateFlow<Route?> = HikeRepository.activeRoute
    val hikeMessage: StateFlow<String?> = HikeRepository.hikeMessage
    val navigationMessages: StateFlow<List<com.example.irishhike.hike.NavigationMessage>> = HikeRepository.navigationMessages
    val relativeBearingCorrection: StateFlow<Double> = HikeRepository.relativeBearingCorrection
    val currentTargetWaypoint: StateFlow<com.example.irishhike.model.Waypoint?> = HikeRepository.currentTargetWaypoint
    val activeLeg: StateFlow<com.example.irishhike.model.Leg?> = HikeRepository.activeLeg
    val currentLegIndex: StateFlow<Int> = HikeRepository.currentLegIndex

    fun goToPreviousLeg() {
        val hike = HikeRepository.activeHike ?: return
        val newIndex = (hike.currentLegIndex - 1).coerceAtLeast(0)
        HikeRepository.setCurrentLegIndex(newIndex)
    }

    fun goToNextLeg() {
        val hike = HikeRepository.activeHike ?: return
        val routeLegs = HikeRepository.activeRoute.value?.legs ?: return
        val newIndex = (hike.currentLegIndex + 1).coerceAtMost(routeLegs.size - 1)
        HikeRepository.setCurrentLegIndex(newIndex)
    }

    val isPlaybackPlaying: StateFlow<Boolean> = HikeRepository.telemetryPlaybackEngine.isPlaying
    val isPlaybackPaused: StateFlow<Boolean> = HikeRepository.telemetryPlaybackEngine.isPaused
    val playbackSpeed: StateFlow<Double> = HikeRepository.telemetryPlaybackEngine.speedMultiplier
    val playbackProgress: StateFlow<PlaybackProgress> = HikeRepository.telemetryPlaybackEngine.progress
    val playbackEvents: StateFlow<List<TelemetryEvent>> = HikeRepository.telemetryPlaybackEngine.events

    private val _breadcrumbs = MutableStateFlow<List<Breadcrumb>>(emptyList())
    val breadcrumbs: StateFlow<List<Breadcrumb>> = _breadcrumbs.asStateFlow()

    init {
        HikeRepository.breadcrumbsFlow
            .onEach {
                _breadcrumbs.value = HikeRepository.getBreadcrumbHistory()
            }
            .launchIn(viewModelScope)
    }

    fun loadRouteFromUri(context: Context, uri: Uri) {
        try {
            val csvContent = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return
            val routeName = RouteParser.extractRouteNameFromUri(context, uri)
            val parsedRoute = Route.parseFromCsvContent(routeName, csvContent, sourceUri = uri)
            android.util.Log.d("HikeViewModel", "Loaded route '$routeName' with sourceUri: $uri")
            HikeRepository.setActiveRouteWithContent(routeName, csvContent, parsedRoute)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun exportBreadcrumbs(context: Context) {
        val history = HikeRepository.getBreadcrumbHistory()
        val currentRoute = activeRoute.value
        android.util.Log.d("HikeViewModel", "Exporting breadcrumbs. Active route: ${currentRoute?.name}, sourceUri: ${currentRoute?.sourceUri}")
        HikerTrackExporter.exportBreadcrumbsToGpx(context, history, currentRoute?.sourceUri)
    }

    fun loadSampleRoute() {
        val sampleCsv = RouteParser.generateSampleIrishGridCsv()
        val route = Route.parseFromCsvContent("Sample Irish Route", sampleCsv)
        HikeRepository.setActiveRouteWithContent("Sample Irish Route", sampleCsv, route)
    }

    fun updateHikeConfig(transform: (HikeConfig) -> HikeConfig) {
        HikeRepository.updateHikeConfig(transform)
    }

    fun updateMapConfig(transform: (MapConfig) -> MapConfig) {
        HikeRepository.updateMapConfig(transform)
    }

    fun loadTelemetryTrackFromUri(context: Context, uri: Uri) {
        try {
            val csvContent = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return
            val events = TelemetryRecorder.fromCsvString(csvContent)
            HikeRepository.setActiveRoute(null)
            HikeRepository.telemetryPlaybackEngine.loadEvents(events)
            _breadcrumbs.value = emptyList()
            Toast.makeText(
                context,
                "Loaded track with ${events.size} events",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error loading telemetry track", Toast.LENGTH_LONG).show()
        }
    }

    fun startPlayback() {
        viewModelScope.launch {
            _breadcrumbs.value = emptyList()
            HikeRepository.telemetryPlaybackEngine.play(viewModelScope)
        }
    }

    fun pausePlayback() {
        HikeRepository.telemetryPlaybackEngine.pause()
    }

    fun stopPlayback() {
        HikeRepository.telemetryPlaybackEngine.stop()
    }

    fun stepForwardPlayback() {
        viewModelScope.launch {
            HikeRepository.telemetryPlaybackEngine.stepForward()
            _breadcrumbs.value = HikeRepository.getBreadcrumbHistory()
        }
    }

    fun stepBackPlayback() {
        viewModelScope.launch {
            HikeRepository.telemetryPlaybackEngine.stepBack()
            _breadcrumbs.value = HikeRepository.getBreadcrumbHistory()
        }
    }

    fun setPlaybackSpeed(speed: Double) {
        HikeRepository.telemetryPlaybackEngine.setSpeedMultiplier(speed)
    }

    fun setShow1kmGrid(show: Boolean) {
        updateMapConfig { it.copy(show1kmGrid = show) }
    }

    fun setShow100mGrid(show: Boolean) {
        updateMapConfig { it.copy(show100mGrid = show) }
    }

    fun setLegView(isLeg: Boolean) {
        updateMapConfig { it.copy(isLegView = isLeg) }
    }

    fun setDriftCoolOffSec(sec: Long) {
        updateHikeConfig { it.copy(driftCoolOffSec = sec) }
    }

    fun setSteadyCoolOffSec(sec: Long) {
        updateHikeConfig { it.copy(steadyCoolOffSec = sec) }
    }

    fun startTracking(context: Context) {
        stopPlayback()
        _breadcrumbs.value = emptyList()
        val intent = Intent(context, PdrTrackingService::class.java).apply {
            action = PdrTrackingService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopTracking(context: Context) {
        val intent = Intent(context, PdrTrackingService::class.java).apply {
            action = PdrTrackingService.ACTION_STOP
        }
        context.startService(intent)

        val targetDir = HikerTrackExporter.resolveTargetDirectory(context, activeRoute.value?.sourceUri)
        val file = HikeRepository.telemetryRecorder.saveToTimestampedFile(targetDir)
        if (file != null) {
            val count = HikeRepository.telemetryRecorder.getEvents().size
            android.util.Log.i("HikeViewModel", "Saved $count telemetry events to ${file.absolutePath}")
            Toast.makeText(
                context,
                "Saved $count telemetry events to:\n${file.name}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun setGpsPollingInterval(context: Context, intervalMs: Long) {
        HikeRepository.setGpsPollingInterval(intervalMs)
        val intent = Intent(context, PdrTrackingService::class.java).apply {
            action = PdrTrackingService.ACTION_UPDATE_INTERVAL
            putExtra(PdrTrackingService.EXTRA_INTERVAL_MS, intervalMs)
        }
        context.startService(intent)
    }

    fun saveEditedRoute(routeName: String, route: Route) {
        val csv = route.waypoints.joinToString("\n") { wp ->
            "${wp.name},${wp.x},${wp.y},${wp.altitude}"
        }
        HikeRepository.setActiveRouteWithContent(routeName, csv, route, preserveTracking = true)
    }

    fun initTts(context: Context) {
        HikeRepository.initTts(context)
    }
}
