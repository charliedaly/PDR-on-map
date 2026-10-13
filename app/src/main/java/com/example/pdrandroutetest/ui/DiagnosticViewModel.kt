package com.example.pdrandroutetest.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Route
import com.example.pdrandroutetest.repository.HikeRepository
import com.example.pdrandroutetest.service.DiagnosticForegroundService
import com.example.pdrandroutetest.util.RouteParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class DiagnosticViewModel : ViewModel() {

    val ekfState: StateFlow<EkfState> = HikeRepository.ekfStateFlow
    val isTracking: StateFlow<Boolean> = HikeRepository.isTracking
    val gpsPollingIntervalMs: StateFlow<Long> = HikeRepository.gpsPollingIntervalMs
    val activeRoute: StateFlow<Route?> = HikeRepository.activeRoute

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

    fun startTracking(context: Context) {
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
    }

    fun setGpsPollingInterval(context: Context, intervalMs: Long) {
        HikeRepository.setGpsPollingInterval(intervalMs)
        val intent = Intent(context, DiagnosticForegroundService::class.java).apply {
            action = DiagnosticForegroundService.ACTION_UPDATE_INTERVAL
            putExtra(DiagnosticForegroundService.EXTRA_INTERVAL_MS, intervalMs)
        }
        context.startService(intent)
    }
}
