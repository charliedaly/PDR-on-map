package com.example.pdrandroutetest.config

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("hiking_tool_prefs", Context.MODE_PRIVATE)

    fun saveMapConfig(config: MapConfig) {
        prefs.edit()
            .putBoolean("is_leg_view", config.isLegView)
            .putBoolean("show_1km_grid", config.show1kmGrid)
            .putBoolean("show_100m_grid", config.show100mGrid)
            .putLong("gps_interval_ms", config.gpsPollingIntervalMs)
            .putBoolean("show_telemetry", config.showTelemetryViewport)
            .putBoolean("show_osm_bg", config.showOsmBackground)
            .apply()
    }

    fun loadMapConfig(): MapConfig {
        prefs.edit().putBoolean("show_osm_bg", false).apply()
        return MapConfig(
            isLegView = prefs.getBoolean("is_leg_view", false),
            show1kmGrid = prefs.getBoolean("show_1km_grid", false),
            show100mGrid = prefs.getBoolean("show_100m_grid", false),
            gpsPollingIntervalMs = prefs.getLong("gps_interval_ms", 1000L),
            showTelemetryViewport = prefs.getBoolean("show_telemetry", true),
            showOsmBackground = false // Force disabled on startup
        )
    }

    fun saveHikeConfig(config: HikeConfig) {
        prefs.edit()
            .putFloat("arrive_threshold", config.arriveThresholdMeters.toFloat())
            .putFloat("approach_threshold", config.approachThresholdMeters.toFloat())
            .putFloat("offcourse_dev_deg", config.offCourseHeadingDevDeg.toFloat())
            .putFloat("offcourse_min_speed", config.offCourseMinSpeedMps.toFloat())
            .putLong("drift_cooloff_sec", config.driftCoolOffSec)
            .putLong("steady_cooloff_sec", config.steadyCoolOffSec)
            .apply()
    }

    fun loadHikeConfig(): HikeConfig {
        return HikeConfig(
            arriveThresholdMeters = prefs.getFloat("arrive_threshold", 10.0f).toDouble(),
            approachThresholdMeters = prefs.getFloat("approach_threshold", 40.0f).toDouble(),
            offCourseHeadingDevDeg = prefs.getFloat("offcourse_dev_deg", 10.0f).toDouble(),
            offCourseMinSpeedMps = prefs.getFloat("offcourse_min_speed", 0.4f).toDouble(),
            driftCoolOffSec = prefs.getLong("drift_cooloff_sec", 4L),
            steadyCoolOffSec = prefs.getLong("steady_cooloff_sec", 6L)
        )
    }

    fun saveLastRoute(routeName: String, csvContent: String) {
        prefs.edit()
            .putString("last_route_name", routeName)
            .putString("last_route_csv", csvContent)
            .apply()
    }

    fun loadLastRoute(): Pair<String, String>? {
        val name = prefs.getString("last_route_name", null) ?: return null
        val csv = prefs.getString("last_route_csv", null) ?: return null
        return Pair(name, csv)
    }
}
