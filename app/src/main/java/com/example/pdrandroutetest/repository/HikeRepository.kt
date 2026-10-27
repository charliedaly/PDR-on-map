package com.example.pdrandroutetest.repository

import android.content.Context
import com.example.pdrandroutetest.config.AppPreferences
import com.example.pdrandroutetest.config.HikeConfig
import com.example.pdrandroutetest.config.MapConfig
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.hike.Hike
import com.example.pdrandroutetest.hike.LegState
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

data class TtsQueueItem(
    val state: LegState?,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

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

    private val _mapConfig = MutableStateFlow(MapConfig())
    val mapConfig: StateFlow<MapConfig> = _mapConfig.asStateFlow()

    private val _hikeConfig = MutableStateFlow(HikeConfig())
    val hikeConfig: StateFlow<HikeConfig> = _hikeConfig.asStateFlow()

    val show1kmGrid: StateFlow<Boolean> = _mapConfig.asStateFlow().let {
        MutableStateFlow(_mapConfig.value.show1kmGrid)
    }

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

    private val _currentLegIndex = MutableStateFlow(0)
    val currentLegIndex: StateFlow<Int> = _currentLegIndex.asStateFlow()

    private var currentHike: Hike? = null
    val activeHike: Hike?
        get() = currentHike

    private var appPreferences: AppPreferences? = null
    private var ttsManager: TtsManager? = null
    private var isTtsSpeaking = false
    private val pendingTtsQueue = mutableListOf<TtsQueueItem>()

    val telemetryRecorder = TelemetryRecorder()
    val telemetryPlaybackEngine = TelemetryPlaybackEngine()

    private val breadcrumbHistory = ConcurrentLinkedQueue<Breadcrumb>()

    fun initPreferences(context: Context) {
        if (appPreferences == null) {
            val prefs = AppPreferences(context.applicationContext)
            appPreferences = prefs

            val loadedMapConfig = prefs.loadMapConfig()
            val loadedHikeConfig = prefs.loadHikeConfig()

            _mapConfig.value = loadedMapConfig
            _hikeConfig.value = loadedHikeConfig

            val lastRoute = prefs.loadLastRoute()
            if (lastRoute != null && _activeRoute.value == null) {
                try {
                    val route = Route.parseFromCsvContent(lastRoute.first, lastRoute.second, sourceUri = lastRoute.third)
                    _activeRoute.value = route
                    _currentTargetWaypoint.value = route.legs.firstOrNull()?.target
                    _activeLeg.value = route.legs.firstOrNull()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        initTts(context)
    }

    fun initTts(context: Context) {
        if (ttsManager == null) {
            ttsManager = TtsManager(context.applicationContext, onSpeechDone = {
                onTtsSpeechFinished()
            })
        }
    }

    fun shutdownTts() {
        clearTtsQueue()
        ttsManager?.shutdown()
        ttsManager = null
    }

    @Synchronized
    fun enqueueTtsMessage(state: LegState?, message: String) {
        if (message.isBlank()) return

        if (state == LegState.OFFCOURSE) {
            pendingTtsQueue.removeAll { it.state == LegState.OFFCOURSE }
        }

        pendingTtsQueue.add(TtsQueueItem(state, message))
        processNextTtsItem()
    }

    @Synchronized
    private fun onTtsSpeechFinished() {
        isTtsSpeaking = false
        processNextTtsItem()
    }

    @Synchronized
    private fun processNextTtsItem() {
        if (isTtsSpeaking) return
        if (pendingTtsQueue.isEmpty()) return

        val nextItem = pendingTtsQueue.removeAt(0)
        isTtsSpeaking = true
        ttsManager?.speak(nextItem.message)
    }

    @Synchronized
    fun clearTtsQueue() {
        pendingTtsQueue.clear()
        isTtsSpeaking = false
        ttsManager?.stop()
    }

    fun updateEkfState(state: EkfState) {
        _ekfStateFlow.value = state
    }

    fun setCurrentLegIndex(index: Int) {
        currentHike?.setCurrentLegIndex(index)
        _currentLegIndex.value = currentHike?.currentLegIndex ?: 0
        _activeLeg.value = currentHike?.currentLeg
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: _activeRoute.value?.legs?.firstOrNull()?.target
    }

    suspend fun emitBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumbHistory.add(breadcrumb)
        _breadcrumbsFlow.emit(breadcrumb)
        currentHike?.let { hike ->
            val result = hike.update(
                location = breadcrumb.location,
                timestampMs = breadcrumb.timestampMs,
                isGpsFix = breadcrumb.isGpsFix,
                ekfState = breadcrumb.ekfState
            )
            val msg = result?.message
            val state = result?.state
            if (!msg.isNullOrBlank()) {
                _hikeMessage.value = msg
                _navigationMessages.value = _navigationMessages.value + NavigationMessage(
                    breadcrumbIndex = breadcrumbHistory.size,
                    text = msg,
                    timestampMs = breadcrumb.timestampMs
                )
                enqueueTtsMessage(state, msg)
            }
            _relativeBearingCorrection.value = hike.relativeBearingCorrection(breadcrumb.location, breadcrumb.ekfState)
            _currentLegIndex.value = hike.currentLegIndex
        }
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: _activeRoute.value?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: _activeRoute.value?.legs?.firstOrNull()
    }

    fun startHike(route: Route?) {
        currentHike = Hike(route, _hikeConfig.value)
        _hikeMessage.value = null
        _navigationMessages.value = emptyList()
        _relativeBearingCorrection.value = 0.0
        _currentLegIndex.value = currentHike?.currentLegIndex ?: 0
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: route?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: route?.legs?.firstOrNull()
        clearTtsQueue()
        clearBreadcrumbs()
    }

    fun stopHike() {
        currentHike?.stop()
        clearTtsQueue()
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
        if (route != null) {
            startHike(route)
        } else {
            currentHike = null
            _activeLeg.value = null
            _currentTargetWaypoint.value = null
            _currentLegIndex.value = 0
        }
    }

    fun updateRouteWithoutClearing(route: Route?) {
        _activeRoute.value = route
        currentHike?.updateRoute(route)
        _currentTargetWaypoint.value = currentHike?.currentLeg?.target ?: route?.legs?.firstOrNull()?.target
        _activeLeg.value = currentHike?.currentLeg ?: route?.legs?.firstOrNull()
        _currentLegIndex.value = currentHike?.currentLegIndex ?: 0
    }

    fun setActiveRouteWithContent(routeName: String, csvContent: String, route: Route, preserveTracking: Boolean = false) {
        if (preserveTracking) {
            updateRouteWithoutClearing(route)
        } else {
            setActiveRoute(route)
        }
        appPreferences?.saveLastRoute(routeName, csvContent, route.sourceUri?.toString())
    }

    fun updateMapConfig(transform: (MapConfig) -> MapConfig) {
        val newConfig = transform(_mapConfig.value)
        _mapConfig.value = newConfig
        appPreferences?.saveMapConfig(newConfig)
    }

    fun updateHikeConfig(transform: (HikeConfig) -> HikeConfig) {
        val newConfig = transform(_hikeConfig.value)
        _hikeConfig.value = newConfig
        appPreferences?.saveHikeConfig(newConfig)
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

    fun getBreadcrumbHistory(): List<Breadcrumb> {
        return breadcrumbHistory.toList()
    }
}
