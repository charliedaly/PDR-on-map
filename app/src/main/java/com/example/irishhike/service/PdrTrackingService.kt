package com.example.irishhike.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location as AndroidLocation
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.irishhike.ekf.PdrEkfEngine
import com.example.irishhike.model.Breadcrumb
import com.example.irishhike.model.Location
import com.example.irishhike.repository.HikeRepository
import com.example.irishhike.sensor.PdrStepDetector
import com.example.irishhike.util.GpsValidationResult
import com.example.irishhike.util.GpsValidationUtils
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

class PdrTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null

    private lateinit var ekfEngine: PdrEkfEngine
    private lateinit var stepDetector: PdrStepDetector
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager

    private var locationCallback: LocationCallback? = null
    private var nativeGpsListener: LocationListener? = null
    private var locationHandlerThread: HandlerThread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var breadcrumbIdSeq = 1L
    private var activeGpsIntervalMs = 1000L
    private var lastProcessedGpsTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        ekfEngine = PdrEkfEngine()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        HikeRepository.initTts(this)

        stepDetector = PdrStepDetector(this) { headingRawRad, timestampMs ->
            ekfEngine.onStepDetected(headingRawRad, timestampMs)
            if (HikeRepository.isTracking.value) {
                HikeRepository.telemetryRecorder.recordStep(
                    headingRawRad = headingRawRad,
                    timestampMs = timestampMs,
                    recordedEkfState = ekfEngine.getEkfState()
                )
            }
        }

        // Keep stepDetector mount in sync with settings
        HikeRepository.hikeConfig
            .onEach { config ->
                stepDetector.mount = config.phoneMount
            }
            .launchIn(serviceScope)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IrishHike::BackgroundGpsWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Dedicated background thread for location callbacks (prevents main-thread sleep throttle on Samsung S21)
        locationHandlerThread = HandlerThread("IrishHikeLocationThread").apply { start() }

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
        HikeRepository.telemetryRecorder.clear()
        ekfEngine.reset()
        breadcrumbIdSeq = 1L
        lastProcessedGpsTimeMs = 0L

        createNotificationChannel()
        val notification = buildNotification("PDR Tracking Active (1 Hz Background GPS)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Hold partial wake lock while tracking is active
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stepDetector.start()
        startLocationUpdates(activeGpsIntervalMs)
        start1HzPredictionTicker()

        HikeRepository.startHike(HikeRepository.activeRoute.value)
        HikeRepository.setTracking(true)
    }

    private fun stopTrackingService() {
        tickerJob?.cancel()
        stepDetector.stop()
        stopLocationUpdates()

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        HikeRepository.stopHike()
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

        val looper = locationHandlerThread?.looper ?: mainLooper

        // 1. Google Play Services Fused Location Provider with zero batching delay
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(200L))
            .setMaxUpdateDelayMillis(0) // Crucial: zero batching delay when screen is off
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processIncomingFix(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                looper
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Native Android GPS_PROVIDER: bypasses Google Play Services battery idling on Samsung One UI
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                nativeGpsListener = object : LocationListener {
                    override fun onLocationChanged(location: AndroidLocation) {
                        processIncomingFix(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    0f,
                    nativeGpsListener!!,
                    looper
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processIncomingFix(location: AndroidLocation) {
        val nowMs = System.currentTimeMillis()

        // Deduplicate fixes between FusedLocationProvider and native GPS_PROVIDER
        if (Math.abs(nowMs - lastProcessedGpsTimeMs) < (activeGpsIntervalMs / 3).coerceAtLeast(200L)) {
            return
        }
        lastProcessedGpsTimeMs = nowMs

        val validation = GpsValidationUtils.validateFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy,
            timestampMs = nowMs
        )

        if (validation != GpsValidationResult.VALID && validation != GpsValidationResult.WARN_HIGH_SPEED) {
            GpsValidationUtils.playBeep()
            return
        }

        if (validation == GpsValidationResult.WARN_HIGH_SPEED) {
            GpsValidationUtils.playBeep()
        }

        val ekfState = ekfEngine.onGpsFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy,
            activeIntervalMs = activeGpsIntervalMs
        )

        if (HikeRepository.isTracking.value) {
            HikeRepository.telemetryRecorder.recordGpsFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                activeIntervalMs = activeGpsIntervalMs,
                timestampMs = ekfState.timestampMs,
                recordedEkfState = ekfState
            )
        }

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
        nativeGpsListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            nativeGpsListener = null
        }
    }

    private fun start1HzPredictionTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (ekfEngine.isInitialized) {
                    val ekfState = ekfEngine.predict(1.0)
                    if (HikeRepository.isTracking.value) {
                        HikeRepository.telemetryRecorder.recordTick(
                            dtSec = 1.0,
                            timestampMs = ekfState.timestampMs,
                            recordedEkfState = ekfState
                        )
                    }
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
                "Irish Hike Tracking",
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
            .setContentTitle("Irish Hike Tracking Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "irish_hike_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.irishhike.action.START"
        const val ACTION_STOP = "com.example.irishhike.action.STOP"
        const val ACTION_UPDATE_INTERVAL = "com.example.irishhike.action.UPDATE_INTERVAL"
        const val EXTRA_INTERVAL_MS = "com.example.irishhike.extra.INTERVAL_MS"
    }
}