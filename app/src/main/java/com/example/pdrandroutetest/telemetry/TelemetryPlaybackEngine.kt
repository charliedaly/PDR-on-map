package com.example.pdrandroutetest.telemetry

import com.example.pdrandroutetest.ekf.DiagnosticEkf
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Location
import com.example.pdrandroutetest.repository.HikeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackProgress(
    val currentIndex: Int = 0,
    val totalEvents: Int = 0,
    val currentTimestampMs: Long = 0L
) {
    val fraction: Float
        get() = if (totalEvents > 0) currentIndex.toFloat() / totalEvents.toFloat() else 0f
}

class TelemetryPlaybackEngine {

    private val _events = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val events: StateFlow<List<TelemetryEvent>> = _events.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _speedMultiplier = MutableStateFlow(1.0)
    val speedMultiplier: StateFlow<Double> = _speedMultiplier.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var playbackJob: Job? = null
    private var ekfEngine = DiagnosticEkf()
    private var breadcrumbIdSeq = 1L
    private var currentIndex = 0

    @Synchronized
    fun loadEvents(eventList: List<TelemetryEvent>) {
        stop()
        _events.value = eventList.sortedBy { it.timestampMs }
        _progress.value = PlaybackProgress(0, _events.value.size, 0L)
    }

    @Synchronized
    fun play(scope: CoroutineScope) {
        val eventList = _events.value
        if (eventList.isEmpty()) return

        if (_isPaused.value && playbackJob?.isActive == true) {
            _isPaused.value = false
            _isPlaying.value = true
            return
        }

        stop()

        _isPlaying.value = true
        _isPaused.value = false
        ekfEngine.reset()
        HikeRepository.clearBreadcrumbs()
        breadcrumbIdSeq = 1L
        currentIndex = 0

        playbackJob = scope.launch(Dispatchers.Default) {
            var lastEventTimeMs = 0L

            for (i in eventList.indices) {
                currentIndex = i
                val event = eventList[i]

                while (_isPaused.value) {
                    delay(100L)
                }

                if (i > 0 && lastEventTimeMs > 0) {
                    val rawDtMs = (event.timestampMs - lastEventTimeMs).coerceAtLeast(0L)
                    val scaledDelayMs = (rawDtMs / _speedMultiplier.value).toLong().coerceIn(1L, 5000L)
                    delay(scaledDelayMs)
                }
                lastEventTimeMs = event.timestampMs

                _progress.value = PlaybackProgress(
                    currentIndex = i + 1,
                    totalEvents = eventList.size,
                    currentTimestampMs = event.timestampMs
                )

                when (event) {
                    is TelemetryEvent.GpsFixEvent -> {
                        val ekfState = ekfEngine.onGpsFix(
                            lat = event.latitude,
                            lon = event.longitude,
                            accuracyMeters = event.accuracyMeters,
                            activeIntervalMs = event.activeIntervalMs
                        )
                        HikeRepository.updateEkfState(ekfState)

                        val gpsBreadcrumb = Breadcrumb(
                            id = breadcrumbIdSeq++,
                            location = Location(ekfState.position.value.x, ekfState.position.value.y),
                            timestampMs = ekfState.timestampMs,
                            isGpsFix = true,
                            ekfState = ekfState
                        )
                        HikeRepository.emitBreadcrumb(gpsBreadcrumb)
                    }
                    is TelemetryEvent.StepDetectedEvent -> {
                        ekfEngine.onStepDetected(event.headingRawRad, event.timestampMs)
                    }
                    is TelemetryEvent.PredictionTickEvent -> {
                        if (ekfEngine.isInitialized) {
                            val ekfState = ekfEngine.predict(event.dtSec)
                            HikeRepository.updateEkfState(ekfState)

                            val pdrBreadcrumb = Breadcrumb(
                                id = breadcrumbIdSeq++,
                                location = Location(ekfState.position.value.x, ekfState.position.value.y),
                                timestampMs = ekfState.timestampMs,
                                isGpsFix = false,
                                ekfState = ekfState
                            )
                            HikeRepository.emitBreadcrumb(pdrBreadcrumb)
                        }
                    }
                }
            }

            _isPlaying.value = false
            _isPaused.value = false
        }
    }

    @Synchronized
    fun pause() {
        if (_isPlaying.value) {
            _isPaused.value = true
        }
    }

    @Synchronized
    fun resume() {
        if (_isPaused.value) {
            _isPaused.value = false
        }
    }

    @Synchronized
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
        _isPaused.value = false
        currentIndex = 0
        _progress.value = PlaybackProgress(0, _events.value.size, 0L)
    }

    fun setSpeedMultiplier(multiplier: Double) {
        _speedMultiplier.value = multiplier.coerceAtLeast(0.1)
    }
}
