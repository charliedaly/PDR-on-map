package com.example.pdrandroutetest.repository

import android.content.Context
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.hike.Hike
import com.example.pdrandroutetest.hike.NavigationMessage
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Leg
import com.example.pdrandroutetest.model.Route
import com.example.pdrandroutetest.model.Waypoint
import com.example.pdrandroutetest.telemetry.TelemetryPlaybackEngine
import com.example.pdrandroutetest.telemetry.TelemetryRecorder
import com.example.pdrandroutetest.util.TtsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

object HikeRepository {
    private val _ekfStateFlow = MutableStateFlow(EkfState())
    val ekfStateFlow: StateFlow<EkfState> = _ekfStateFlow.asStateFlow()

    private val _breadcrumbsFlow = MutableSharedFlow<Breadcrumb>(replay = 500)
    val breadcrumbsFlow: SharedFlow<Breadcrumb> = _breadcrumbsFlow.asSharedFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _gpsPollingIntervalMs = MutableStateFlow(1000L)
    val gpsPollingIntervalMs: StateFlow<Long> = _gpsPollingIntervalMs.asStateFlow()

    private val _activeRoute = MutableStateFlow<Route?>(null)
    val activeRoute: StateFlow<Route?> = _activeRoute.asStateFlow()

    private val _show1kmGrid = MutableStateFlow(false)
    val show1kmGrid: StateFlow<Boolean> = _show1kmGrid.asStateFlow()

    private val _show100mGrid = MutableStateFlow(false)
    val show100mGrid: StateFlow<Boolean> = _show100mGrid.asStateFlow()

    private val _isLegView = MutableStateFlow(false)
    val isLegView: StateFlow<Boolean> = _isLegView.asStateFlow()

    private val _driftCoolOffSec = MutableStateFlow(2L)
    val driftCoolOffSec: StateFlow<Long> = _driftCoolOffSec.asStateFlow()

    private val _steadyCoolOffSec = MutableStateFlow(15L)
    val steadyCoolOffSec: StateFlow<Long> = _steadyCoolOffSec.asStateFlow()

    private val _hikeMessage = MutableStateFlow<String?>(null)
    val hikeMessage: StateFlow<String?> = _hikeMessage.asStateFlow()

    private val _navigationMessages = MutableStateFlow<List<NavigationMessage>>(emptyList())
    val navigationMessages: StateFlow<List<NavigationMessage>> = _navigationMessages.asStateFlow()

    private val _relativeBearingCorrection = MutableStateFlow(0.0)
    val relativeBearingCorrection: StateFlow<Double> = _relativeBearingCorrection.asStateFlow()

    private val _currentTargetWaypoint = MutableStateFlow<Waypoint?>(null)
    val currentTargetWaypoint: StateFlow<Waypoint?> = _currentTargetWaypoint.asStateFlow()

    private val _activeLeg = MutableStateFlow<Leg?>(null)
    val activeLeg: StateFlow<Leg?> = _activeLeg.asStateFlow()

    private var currentHike: Hike? = null
    val activeHike: Hike?
        get() = currentHike

    private var ttsManager: TtsManager? = null

    val telemetryRecorder = TelemetryRecorder()
    val telemetryPlaybackEngine = TelemetryPlaybackEngine()

    private val breadcrumbHistory = ConcurrentLinkedQueue<Breadcrumb>()

    fun initTts(context: Context) {
        if (ttsManager == null) {
            ttsManager = TtsManager(context.applicationContext)
        }
    }

    fun shutdownTts() {
        ttsManager?.shutdown()
        ttsManager = null
    }

    fun updateEkfState(state: EkfState) {
        _ekfStateFlow.value = state
    }

    suspend fun emitBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumbHistory.add(breadcrumb)
        _breadcrumbsFlow.emit(breadcrumb)
        currentHike?.let { hike ->
            val msg = hike.update(
                location = breadcrumb.location,
                timestampMs = breadcrumb.timestampMs,
                isGpsFix = breadcrumb.isGpsFix,
                ekfState = breadcrumb.ekfState
            )
            if (!msg.isNullOrBlank()) {
                _hikeMessage.value = msg
                _navigationMessages.value = _navigationMessages.value + NavigationMessage(
                    breadcrumbIndex = breadcrumbHistory.size,
                    text = msg,
                    timestampMs = breadcrumb.timestampMs
                )
                ttsManager?.speak(msg)
            }
            _relativeBearingCorrection.value = hike.relativeBearingCorrection(breadcrumb.location, breadcrumb.ekfState)
        }
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: _activeRoute.value?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: _activeRoute.value?.legs?.firstOrNull()
    }

    fun startHike(route: Route?) {
        currentHike = Hike(route).apply {
            driftCoolOffSec = _driftCoolOffSec.value
            steadyCoolOffSec = _steadyCoolOffSec.value
        }
        _hikeMessage.value = null
        _navigationMessages.value = emptyList()
        _relativeBearingCorrection.value = 0.0
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: route?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: route?.legs?.firstOrNull()
        clearBreadcrumbs()
    }

    fun stopHike() {
        currentHike?.stop()
    }

    fun setTracking(tracking: Boolean) {
        _isTracking.value = tracking
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearBreadcrumbs() {
        breadcrumbHistory.clear()
        _breadcrumbsFlow.resetReplayCache()
    }

    fun setGpsPollingInterval(intervalMs: Long) {
        _gpsPollingIntervalMs.value = intervalMs
    }

    fun setActiveRoute(route: Route?) {
        _activeRoute.value = route
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: route?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: route?.legs?.firstOrNull()
    }

    fun setShow1kmGrid(show: Boolean) {
        _show1kmGrid.value = show
    }

    fun setShow100mGrid(show: Boolean) {
        _show100mGrid.value = show
    }

    fun setLegView(isLeg: Boolean) {
        _isLegView.value = isLeg
    }

    fun setDriftCoolOffSec(sec: Long) {
        _driftCoolOffSec.value = sec.coerceAtLeast(1L)
        currentHike?.driftCoolOffSec = sec.coerceAtLeast(1L)
    }

    fun setSteadyCoolOffSec(sec: Long) {
        _steadyCoolOffSec.value = sec.coerceAtLeast(1L)
        currentHike?.steadyCoolOffSec = sec.coerceAtLeast(1L)
    }

    fun getBreadcrumbHistory(): List<Breadcrumb> {
        return breadcrumbHistory.toList()
    }
}
