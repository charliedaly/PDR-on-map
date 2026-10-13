package com.example.pdrandroutetest.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.pdrandroutetest.ekf.DiagnosticEkf
import com.example.pdrandroutetest.model.Breadcrumb
import com.example.pdrandroutetest.model.Location
import com.example.pdrandroutetest.repository.HikeRepository
import com.example.pdrandroutetest.sensor.PdrStepDetector
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DiagnosticForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null

    private lateinit var ekfEngine: DiagnosticEkf
    private lateinit var stepDetector: PdrStepDetector
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var breadcrumbIdSeq = 1L
    private var activeGpsIntervalMs = 1000L

    override fun onCreate() {
        super.onCreate()
        ekfEngine = DiagnosticEkf()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        stepDetector = PdrStepDetector(this) { headingRawRad, timestampMs ->
            ekfEngine.onStepDetected(headingRawRad, timestampMs)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PDRWithEKFTest::WakeLock")

        observeGpsPollingInterval()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrackingService()
            ACTION_STOP -> stopTrackingService()
            ACTION_UPDATE_INTERVAL -> {
                val newInterval = intent.getLongExtra(EXTRA_INTERVAL_MS, 1000L)
                updateLocationRequestInterval(newInterval)
            }
        }
        return START_STICKY
    }

    private fun startTrackingService() {
        HikeRepository.clearBreadcrumbs()
        ekfEngine.reset()
        breadcrumbIdSeq = 1L

        createNotificationChannel()
        val notification = buildNotification("PDR Tracking Active (1 Hz EKF)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes timeout */)
        stepDetector.start()
        startLocationUpdates(activeGpsIntervalMs)
        start1HzPredictionTicker()
        HikeRepository.setTracking(true)
    }

    private fun stopTrackingService() {
        tickerJob?.cancel()
        stepDetector.stop()
        stopLocationUpdates()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        HikeRepository.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeGpsPollingInterval() {
        HikeRepository.gpsPollingIntervalMs
            .onEach { intervalMs ->
                if (intervalMs != activeGpsIntervalMs) {
                    activeGpsIntervalMs = intervalMs
                    updateLocationRequestInterval(intervalMs)
                }
            }
            .launchIn(serviceScope)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(intervalMs: Long) {
        stopLocationUpdates()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(200L))
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    val ekfState = ekfEngine.onGpsFix(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyMeters = location.accuracy,
                        activeIntervalMs = activeGpsIntervalMs
                    )
                    HikeRepository.updateEkfState(ekfState)

                    serviceScope.launch {
                        val gpsBreadcrumb = Breadcrumb(
                            id = breadcrumbIdSeq++,
                            location = Location(ekfState.position.value.x, ekfState.position.value.y),
                            timestampMs = ekfState.timestampMs,
                            isGpsFix = true,
                            ekfState = ekfState
                        )
                        HikeRepository.emitBreadcrumb(gpsBreadcrumb)
                    }
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                mainLooper
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLocationRequestInterval(intervalMs: Long) {
        if (HikeRepository.isTracking.value) {
            startLocationUpdates(intervalMs)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun start1HzPredictionTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (ekfEngine.isInitialized) {
                    val ekfState = ekfEngine.predict(1.0)
                    HikeRepository.updateEkfState(ekfState)

                    val breadcrumb = Breadcrumb(
                        id = breadcrumbIdSeq++,
                        location = Location(ekfState.position.value.x, ekfState.position.value.y),
                        timestampMs = ekfState.timestampMs,
                        isGpsFix = false,
                        ekfState = ekfState
                    )
                    HikeRepository.emitBreadcrumb(breadcrumb)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PDR Diagnostic Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs 1 Hz EKF prediction loop and handles sensor fusion."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDR Diagnostic Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "pdr_diagnostic_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.pdrandroutetest.action.START"
        const val ACTION_STOP = "com.example.pdrandroutetest.action.STOP"
        const val ACTION_UPDATE_INTERVAL = "com.example.pdrandroutetest.action.UPDATE_INTERVAL"
        const val EXTRA_INTERVAL_MS = "com.example.pdrandroutetest.extra.INTERVAL_MS"
    }
}
