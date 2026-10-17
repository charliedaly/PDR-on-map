package com.example.pdrandroutetest.hike

data class NavigationMessage(
    val breadcrumbIndex: Int,
    val text: String,
    val timestampMs: Long
)
