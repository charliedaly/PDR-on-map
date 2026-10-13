package com.example.pdrandroutetest.repository

import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Route
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

    private val breadcrumbHistory = ConcurrentLinkedQueue<Breadcrumb>()

    fun updateEkfState(state: EkfState) {
        _ekfStateFlow.value = state
    }

    suspend fun emitBreadcrumb(breadcrumb: Breadcrumb) {
        breadcrumbHistory.add(breadcrumb)
        _breadcrumbsFlow.emit(breadcrumb)
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
    }

    fun getBreadcrumbHistory(): List<Breadcrumb> {
        return breadcrumbHistory.toList()
    }
}
