package com.example.irishhike.repository

import android.content.Context
import com.example.irishhike.config.AppPreferences
import com.example.irishhike.config.BreadcrumbConfig
import com.example.irishhike.config.HikeConfig
import com.example.irishhike.config.MapConfig
import com.example.irishhike.data.EkfState
import com.example.irishhike.hike.Hike
import com.example.irishhike.hike.LegState
import com.example.irishhike.hike.NavigationMessage
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Leg
import com.example.irishhike.model.Location
import com.example.irishhike.model.Route
import com.example.irishhike.model.Waypoint
import com.example.irishhike.telemetry.TelemetryPlaybackEngine
import com.example.irishhike.telemetry.TelemetryRecorder
import com.example.irishhike.util.TtsManager
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

    private val _breadcrumbConfig = MutableStateFlow(BreadcrumbConfig())
    val breadcrumbConfig: StateFlow<BreadcrumbConfig> = _breadcrumbConfig.asStateFlow()

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

    private val breadcrumbHistory = ConcurrentLinkedQueue<Breadcrumb>()

    private var currentHike: Hike? = null
    val activeHike: Hike? get() = currentHike

    private var appPreferences: AppPreferences? = null
    private var ttsManager: TtsManager? = null
    private var isTtsSpeaking = false
    private val pendingTtsQueue = mutableListOf<TtsQueueItem>()

    val telemetryRecorder = TelemetryRecorder()
    val trackPlaybackEngine = com.example.irishhike.playback.TrackPlaybackEngine()

    fun initPreferences(context: Context) {
        if (appPreferences == null) {
            appPreferences = AppPreferences(context.applicationContext)
            _mapConfig.value = appPreferences!!.loadMapConfig()
            _breadcrumbConfig.value = appPreferences!!.loadBreadcrumbConfig()
            _hikeConfig.value = appPreferences!!.loadHikeConfig()
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
        if (!_hikeConfig.value.audioEnabled) return

        // Always remove old off-course messages so they never pile up
        if (state == LegState.OFFCOURSE) {
            pendingTtsQueue.removeAll { it.state == LegState.OFFCOURSE }
        }

        // Keep queue capped at 2 items max so old messages can never cause a backlog
        while (pendingTtsQueue.size >= 2) {
            pendingTtsQueue.removeAt(0)
        }

        pendingTtsQueue.add(TtsQueueItem(state, message))
        processNextTtsMessage()
    }

    @Synchronized
    private fun onTtsSpeechFinished() {
        isTtsSpeaking = false
        processNextTtsMessage()
    }

    @Synchronized
    private fun processNextTtsMessage() {
        if (isTtsSpeaking) return
        if (pendingTtsQueue.isEmpty()) return

        // Discard any message that has been waiting for more than 6 seconds (stale navigation)
        val now = System.currentTimeMillis()
        while (pendingTtsQueue.isNotEmpty()) {
            val candidate = pendingTtsQueue.first()
            if (now - candidate.timestampMs > 6000L) {
                pendingTtsQueue.removeAt(0)
            } else {
                break
            }
        }

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

    /**
     * Handset Control 1: Advance to next leg on button press
     */
    fun advanceToNextLeg() {
        val hike = currentHike ?: return
        val routeLegs = _activeRoute.value?.legs ?: return
        val newIndex = hike.currentLegIndex + 1
        if (newIndex < routeLegs.size) {
            setCurrentLegIndex(newIndex)
            val leg = routeLegs[newIndex]
            val targetName = leg.target.name.ifBlank { "Waypoint ${newIndex + 1}" }
            val msg = "Advancing to leg ${newIndex + 1}. Target is $targetName."
            _hikeMessage.value = msg
            enqueueTtsMessage(LegState.LEG_START, msg)
        } else {
            val msg = "Already on final leg of route."
            _hikeMessage.value = msg
            enqueueTtsMessage(LegState.HIKE_ENDED, msg)
        }
    }

    /**
     * Handset Control: Return to previous leg
     */
    fun goToPreviousLeg() {
        val hike = currentHike ?: return
        val routeLegs = _activeRoute.value?.legs ?: return
        val newIndex = (hike.currentLegIndex - 1).coerceAtLeast(0)
        setCurrentLegIndex(newIndex)
        val leg = routeLegs[newIndex]
        val targetName = leg.target.name.ifBlank { "Waypoint ${newIndex + 1}" }
        val msg = "Returning to leg ${newIndex + 1}. Target is $targetName."
        _hikeMessage.value = msg
        enqueueTtsMessage(LegState.LEG_START, msg)
    }

    /**
     * Handset Control 2: Speak current bearing, distance, and turn direction on button press
     */
    fun announceBearingAndDistance() {
        val hike = currentHike ?: return
        val leg = hike.currentLeg ?: return
        val ekfState = _ekfStateFlow.value
        val hikerLoc = Location(ekfState.position.value.x, ekfState.position.value.y)
        val target = leg.target
        val distMeters = hikerLoc.distanceTo(target)
        val targetBearing = hikerLoc.bearingTo(target)
        val correctionStr = hike.bearingCorrectionString(hikerLoc, ekfState)
        val targetName = target.name.ifBlank { "Target" }

        val roundedDist = Math.round(distMeters).toInt()
        val roundedBearing = ((Math.round(targetBearing).toInt() % 360) + 360) % 360

        val msg = "To $targetName: $roundedDist meters, bearing $roundedBearing degrees. Turn $correctionStr."
        _hikeMessage.value = msg
        enqueueTtsMessage(LegState.OK, msg)
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
        updateMapConfig { it.copy(gpsPollingIntervalMs = intervalMs) }
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

    fun updateBreadcrumbConfig(transform: (BreadcrumbConfig) -> BreadcrumbConfig) {
        val newConfig = transform(_breadcrumbConfig.value)
        _breadcrumbConfig.value = newConfig
        appPreferences?.saveBreadcrumbConfig(newConfig)
    }

    fun saveSettingsTab(index: Int) {
        appPreferences?.saveSettingsTab(index)
    }

    fun loadSettingsTab(): Int {
        return appPreferences?.loadSettingsTab() ?: 0
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