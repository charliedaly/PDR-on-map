package com.example.pdrandroutetest.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Route
import com.example.pdrandroutetest.repository.HikeRepository
import com.example.pdrandroutetest.service.DiagnosticForegroundService
import com.example.pdrandroutetest.telemetry.PlaybackProgress
import com.example.pdrandroutetest.telemetry.TelemetryEvent
import com.example.pdrandroutetest.telemetry.TelemetryRecorder
import com.example.pdrandroutetest.util.RouteParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class DiagnosticViewModel : ViewModel() {

    val ekfState: StateFlow<EkfState> = HikeRepository.ekfStateFlow
    val isTracking: StateFlow<Boolean> = HikeRepository.isTracking
    val gpsPollingIntervalMs: StateFlow<Long> = HikeRepository.gpsPollingIntervalMs
    val activeRoute: StateFlow<Route?> = HikeRepository.activeRoute
    val show1kmGrid: StateFlow<Boolean> = HikeRepository.show1kmGrid
    val show100mGrid: StateFlow<Boolean> = HikeRepository.show100mGrid
    val isLegView: StateFlow<Boolean> = HikeRepository.isLegView
    val driftCoolOffSec: StateFlow<Long> = HikeRepository.driftCoolOffSec
    val steadyCoolOffSec: StateFlow<Long> = HikeRepository.steadyCoolOffSec
    val hikeMessage: StateFlow<String?> = HikeRepository.hikeMessage
    val navigationMessages: StateFlow<List<com.example.pdrandroutetest.hike.NavigationMessage>> = HikeRepository.navigationMessages
    val relativeBearingCorrection: StateFlow<Double> = HikeRepository.relativeBearingCorrection
    val currentTargetWaypoint: StateFlow<com.example.pdrandroutetest.model.Waypoint?> = HikeRepository.currentTargetWaypoint
    val activeLeg: StateFlow<com.example.pdrandroutetest.model.Leg?> = HikeRepository.activeLeg

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
            val parsedRoute = Route.parseFromCsvContent(routeName, csvContent)
            HikeRepository.setActiveRoute(parsedRoute)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadSampleRoute() {
        val sampleCsv = RouteParser.generateSampleIrishGridCsv()
        val route = Route.parseFromCsvContent("Sample Irish Route", sampleCsv)
        HikeRepository.setActiveRoute(route)
    }

    fun loadTelemetryTrackFromUri(context: Context, uri: Uri) {
        try {
            val csvContent = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            } ?: return
            val events = TelemetryRecorder.fromCsvString(csvContent)
            HikeRepository.telemetryPlaybackEngine.loadEvents(events)
            _breadcrumbs.value = emptyList()
            Toast.makeText(
                context,
                "Loaded track with ${events.size} events",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Failed to load telemetry track: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startPlayback() {
        if (isTracking.value) {
            // Stop service tracking before playing back a recorded track
            HikeRepository.setTracking(false)
        }
        _breadcrumbs.value = emptyList()
        HikeRepository.telemetryPlaybackEngine.play(viewModelScope)
    }

    fun pausePlayback() {
        HikeRepository.telemetryPlaybackEngine.pause()
    }

    fun stopPlayback() {
        HikeRepository.telemetryPlaybackEngine.stop()
    }

    fun stepForwardPlayback() {
        if (isTracking.value) {
            HikeRepository.setTracking(false)
        }
        viewModelScope.launch {
            HikeRepository.telemetryPlaybackEngine.stepForward()
        }
    }

    fun stepBackPlayback() {
        if (isTracking.value) {
            HikeRepository.setTracking(false)
        }
        viewModelScope.launch {
            HikeRepository.telemetryPlaybackEngine.stepBack()
        }
    }

    fun setPlaybackSpeed(multiplier: Double) {
        HikeRepository.telemetryPlaybackEngine.setSpeedMultiplier(multiplier)
    }

    fun setShow1kmGrid(show: Boolean) {
        HikeRepository.setShow1kmGrid(show)
    }

    fun setShow100mGrid(show: Boolean) {
        HikeRepository.setShow100mGrid(show)
    }

    fun setLegView(isLeg: Boolean) {
        HikeRepository.setLegView(isLeg)
    }

    fun setDriftCoolOffSec(sec: Long) {
        HikeRepository.setDriftCoolOffSec(sec)
    }

    fun setSteadyCoolOffSec(sec: Long) {
        HikeRepository.setSteadyCoolOffSec(sec)
    }

    fun startTracking(context: Context) {
        stopPlayback()
        _breadcrumbs.value = emptyList()
        val intent = Intent(context, DiagnosticForegroundService::class.java).apply {
            action = DiagnosticForegroundService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopTracking(context: Context) {
        val intent = Intent(context, DiagnosticForegroundService::class.java).apply {
            action = DiagnosticForegroundService.ACTION_STOP
        }
        context.startService(intent)

        val dir = context.getExternalFilesDir("telemetry") ?: context.filesDir
        val file = HikeRepository.telemetryRecorder.saveToTimestampedFile(dir)
        if (file != null) {
            val count = HikeRepository.telemetryRecorder.getEvents().size
            android.util.Log.i("DiagnosticViewModel", "Saved $count telemetry events to ${file.absolutePath}")
            Toast.makeText(
                context,
                "Saved $count telemetry events to:\n${file.name}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun setGpsPollingInterval(context: Context, intervalMs: Long) {
        HikeRepository.setGpsPollingInterval(intervalMs)
        val intent = Intent(context, DiagnosticForegroundService::class.java).apply {
            action = DiagnosticForegroundService.ACTION_UPDATE_INTERVAL
            putExtra(DiagnosticForegroundService.EXTRA_INTERVAL_MS, intervalMs)
        }
        context.startService(intent)
    }

    fun initTts(context: Context) {
        HikeRepository.initTts(context)
    }
}
