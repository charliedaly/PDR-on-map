package com.example.pdrandroutetest.util

object LoggingUtils {
    val debugLogs = mutableListOf<String>()

    fun logError(message: String) {
        debugLogs.add(message)
    }

    fun clearLogs() {
        debugLogs.clear()
    }
}
