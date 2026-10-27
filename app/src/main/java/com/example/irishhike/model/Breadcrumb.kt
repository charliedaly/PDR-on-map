package com.example.irishhike.model

import com.example.irishhike.data.EkfState

data class Breadcrumb(
    val id: Long,
    val location: Location,
    val timestampMs: Long,
    val isGpsFix: Boolean = false,
    val ekfState: EkfState? = null
)
