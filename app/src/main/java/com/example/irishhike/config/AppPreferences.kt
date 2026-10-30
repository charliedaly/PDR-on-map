package com.example.irishhike.config

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("irish_hiker_prefs_v2", Context.MODE_PRIVATE)

    fun saveMapConfig(config: MapConfig) {
        prefs.edit()
            .putBoolean("is_leg_view", config.isLegView)
            .putBoolean("show_1km_grid", config.show1kmGrid)
            .putBoolean("show_100m_grid", config.show100mGrid)
            .putBoolean("show_wp_names", config.showWaypointNames)
            .putBoolean("show_wp_heights", config.showWaypointHeights)
            .putLong("gps_interval_ms", config.gpsPollingIntervalMs)
            .putBoolean("show_telemetry", config.showTelemetryViewport)
            .putBoolean("show_osm_bg", config.showOsmBackground)
            .apply()
    }

    fun loadMapConfig(): MapConfig {
        return MapConfig(
            isLegView = prefs.getBoolean("is_leg_view", false),
            show1kmGrid = prefs.getBoolean("show_1km_grid", false),
            show100mGrid = prefs.getBoolean("show_100m_grid", false),
            showWaypointNames = prefs.getBoolean("show_wp_names", true),
            showWaypointHeights = prefs.getBoolean("show_wp_heights", true),
            gpsPollingIntervalMs = prefs.getLong("gps_interval_ms", 1000L),
            showTelemetryViewport = prefs.getBoolean("show_telemetry", true),
            showOsmBackground = prefs.getBoolean("show_osm_bg", false)
        )
    }

    fun saveBreadcrumbConfig(config: BreadcrumbConfig) {
        prefs.edit()
            .putBoolean("bc_show", config.showBreadcrumbs)
            .putFloat("bc_diameter", config.dotDiameterPx)
            .putString("bc_color_mode", config.colorMode.name)
            .putLong("bc_speed_start_color", config.speedStartColor)
            .putLong("bc_speed_end_color", config.speedEndColor)
            .putLong("bc_stride_start_color", config.strideStartColor)
            .putLong("bc_stride_end_color", config.strideEndColor)
            .putBoolean("bc_fade_age", config.fadeWithAge)
            .putBoolean("bc_show_accuracy", config.showAccuracyCircles)
            .putBoolean("bc_show_stationary", config.showStationaryStops)
            .apply()
    }

    fun loadBreadcrumbConfig(): BreadcrumbConfig {
        val colorModeName = prefs.getString("bc_color_mode", BreadcrumbColorMode.GPS_PDR.name) ?: BreadcrumbColorMode.GPS_PDR.name
        val colorMode = try {
            val mode = BreadcrumbColorMode.valueOf(colorModeName)
            if (mode.name == "DEFAULT") BreadcrumbColorMode.GPS_PDR else mode
        } catch (_: Exception) {
            BreadcrumbColorMode.GPS_PDR
        }

        return BreadcrumbConfig(
            showBreadcrumbs = prefs.getBoolean("bc_show", true),
            dotDiameterPx = prefs.getFloat("bc_diameter", 3.0f),
            colorMode = colorMode,
            speedStartColor = prefs.getLong("bc_speed_start_color", 0xFF00FF00),
            speedEndColor = prefs.getLong("bc_speed_end_color", 0xFFFF0000),
            strideStartColor = prefs.getLong("bc_stride_start_color", 0xFF00E5FF),
            strideEndColor = prefs.getLong("bc_stride_end_color", 0xFFFFD700),
            fadeWithAge = prefs.getBoolean("bc_fade_age", false),
            showAccuracyCircles = prefs.getBoolean("bc_show_accuracy", true),
            showStationaryStops = prefs.getBoolean("bc_show_stationary", true)
        )
    }

    fun saveHikeConfig(config: HikeConfig) {
        prefs.edit()
            .putBoolean("audio_enabled", config.audioEnabled)
            .putFloat("arrive_threshold", config.arriveThresholdMeters.toFloat())
            .putFloat("approach_threshold", config.approachThresholdMeters.toFloat())
            .putFloat("offcourse_dev_deg", config.offCourseHeadingDevDeg.toFloat())
            .putFloat("offcourse_min_speed", config.offCourseMinSpeedMps.toFloat())
            .putLong("drift_cooloff_sec", config.driftCoolOffSec)
            .putLong("steady_cooloff_sec", config.steadyCoolOffSec)
            .putFloat("speech_rate", config.speechRate)
            .apply()
    }

    fun loadHikeConfig(): HikeConfig {
        return HikeConfig(
            audioEnabled = prefs.getBoolean("audio_enabled", true),
            arriveThresholdMeters = prefs.getFloat("arrive_threshold", 10.0f).toDouble(),
            approachThresholdMeters = prefs.getFloat("approach_threshold", 40.0f).toDouble(),
            offCourseHeadingDevDeg = prefs.getFloat("offcourse_dev_deg", 10.0f).toDouble(),
            offCourseMinSpeedMps = prefs.getFloat("offcourse_min_speed", 0.4f).toDouble(),
            driftCoolOffSec = prefs.getLong("drift_cooloff_sec", 4L),
            steadyCoolOffSec = prefs.getLong("steady_cooloff_sec", 6L),
            speechRate = prefs.getFloat("speech_rate", 1.0f)
        )
    }

    fun saveLastRoute(routeName: String, csvContent: String, sourceUriStr: String? = null) {
        prefs.edit()
            .putString("last_route_name", routeName)
            .putString("last_route_csv", csvContent)
            .putString("last_route_uri", sourceUriStr)
            .apply()
    }

    fun loadLastRoute(): Triple<String, String, android.net.Uri?>? {
        val name = prefs.getString("last_route_name", null) ?: return null
        val csv = prefs.getString("last_route_csv", null) ?: return null
        val uriStr = prefs.getString("last_route_uri", null)
        val uri = uriStr?.let { android.net.Uri.parse(it) }
        return Triple(name, csv, uri)
    }

    fun saveSettingsTab(index: Int) {
        prefs.edit().putInt("settings_selected_tab", index).apply()
    }

    fun loadSettingsTab(): Int {
        return prefs.getInt("settings_selected_tab", 0)
    }
}
