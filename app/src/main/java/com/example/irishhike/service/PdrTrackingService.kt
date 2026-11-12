package com.example.irishhike.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.example.irishhike.config.PhoneMount
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
    private var locationCallback: LocationCallback? = null
    private var gpsHandlerThread: HandlerThread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var silentAudioTrack: AudioTrack? = null
    @Volatile
    private var isSilentAudioRunning = false

    private var breadcrumbIdSeq = 1L
    private var activeGpsIntervalMs = 1000L

    override fun onCreate() {
        super.onCreate()
        ekfEngine = PdrEkfEngine()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

        // Connect the chest-mount orientation from settings
        stepDetector.mount = HikeRepository.hikeConfig.value.phoneMount

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IrishHike::WakeLock")

        initMediaSession()
        observeConfigurationChanges()
    }

    private fun initMediaSession() {
        val mediaButtonReceiverComponent = ComponentName(this, HikeMediaButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = mediaButtonReceiverComponent
        }
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        mediaSession = MediaSession(this, "IrishHikeMediaSession").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setMediaButtonReceiver(mediaButtonPendingIntent)
            }

            val state = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            setPlaybackState(state)

            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                HikeRepository.announceBearingAndDistance()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                HikeRepository.advanceToNextLeg()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                HikeRepository.goToPreviousLeg()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    HikeRepository.announceBearingAndDistance()
                }

                override fun onPause() {
                    HikeRepository.announceBearingAndDistance()
                }

                override fun onSkipToNext() {
                    HikeRepository.advanceToNextLeg()
                }

                override fun onSkipToPrevious() {
                    HikeRepository.goToPreviousLeg()
                }
            })
        }
    }

    private fun startSilentAudioStream() {
        if (isSilentAudioRunning) return
        isSilentAudioRunning = true

        Thread {
            try {
                val sampleRate = 44100
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                silentAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                val silentBuffer = ByteArray(minBufferSize)
                silentAudioTrack?.play()

                while (isSilentAudioRunning) {
                    silentAudioTrack?.write(silentBuffer, 0, silentBuffer.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopSilentAudioStream() {
        isSilentAudioRunning = false
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (_: Exception) {}
        silentAudioTrack = null
    }

    private fun requestHeadsetAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .build()
            audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonHeadsetAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> HikeRepository.announceBearingAndDistance()
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> HikeRepository.advanceToNextLeg()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> HikeRepository.goToPreviousLeg()
                }
            }
        }

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

        wakeLock?.acquire()

        requestHeadsetAudioFocus()
        startSilentAudioStream()
        mediaSession?.isActive = true

        gpsHandlerThread = HandlerThread("GpsLocationThread").apply { start() }

        // Ensure mount is loaded before starting sensor listener
        stepDetector.mount = HikeRepository.hikeConfig.value.phoneMount
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

        stopSilentAudioStream()
        abandonHeadsetAudioFocus()
        mediaSession?.isActive = false

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        gpsHandlerThread?.quitSafely()
        gpsHandlerThread = null

        HikeRepository.stopHike()
        HikeRepository.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeConfigurationChanges() {
        HikeRepository.gpsPollingIntervalMs
            .onEach { intervalMs ->
                if (intervalMs != activeGpsIntervalMs) {
                    activeGpsIntervalMs = intervalMs
                    updateLocationRequestInterval(intervalMs)
                }
            }
            .launchIn(serviceScope)

        HikeRepository.hikeConfig
            .onEach { config ->
                stepDetector.mount = config.phoneMount
            }
            .launchIn(serviceScope)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(intervalMs: Long) {
        stopLocationUpdates()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(200L))
            .setMaxUpdateDelayMillis(0)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processIncomingLocation(location)
                }
            }
        }

        val looper = gpsHandlerThread?.looper ?: mainLooper
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                looper
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processIncomingLocation(location: android.location.Location) {
        val nowMs = System.currentTimeMillis()
        val validation = GpsValidationUtils.validateFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy,
            timestampMs = nowMs
        )

        if (validation != GpsValidationResult.VALID && validation != GpsValidationResult.WARN_HIGH_SPEED) {
            return
        }

        // Calibrate chest compass bias against GPS trajectory when walking forward
        if (location.hasSpeed() && location.speed > 0.8f && location.hasBearing()) {
            ekfEngine.onGpsHeadingUpdate(Math.toRadians(location.bearing.toDouble()))
        }

        val ekfState = ekfEngine.onGpsFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy,
            activeIntervalMs = activeGpsIntervalMs,
            timestampMs = nowMs
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
        stopSilentAudioStream()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "irish_hike_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.irishhike.action.START"
        const val ACTION_STOP = "com.example.irishhike.action.STOP"
        const val ACTION_UPDATE_INTERVAL = "com.example.irishhike.action.UPDATE_INTERVAL"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
    }
}