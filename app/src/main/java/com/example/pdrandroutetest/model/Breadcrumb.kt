package com.example.pdrandroutetest.model

import com.example.pdrandroutetest.data.EkfState

data class Breadcrumb(
    val id: Long,
    val location: Location,
    val timestampMs: Long,
    val isGpsFix: Boolean = false,
    val ekfState: EkfState? = null
)
