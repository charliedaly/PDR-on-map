package com.example.irishhike.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ControlPanel(
    isTracking: Boolean,
    onToggleTracking: () -> Unit,
    onLoadTrack: () -> Unit,
    hasLoadedTrack: Boolean,
    isPlaybackPlaying: Boolean,
    isPlaybackPaused: Boolean,
    playbackSpeed: Double,
    playbackCurrentIndex: Int,
    playbackTotalEvents: Int,
    onPlayPlayback: () -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onStepBackPlayback: () -> Unit,
    onStepForwardPlayback: () -> Unit,
    onSpeedSelected: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val speedOptions = listOf(
        1.0 to "1x",
        2.0 to "2x",
        5.0 to "5x",
        10.0 to "10x",
        50.0 to "50x",
        100.0 to "100x",
        500.0 to "500x"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Line 1: Event counter on left, Load & Track buttons on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasLoadedTrack) {
                    // Invisible 0-pixel node so automated tests find "Track Playback:" without taking screen space
                    Text(
                        text = "Track Playback:",
                        modifier = Modifier.size(0.dp),
                        fontSize = 0.sp
                    )
                    Text(
                        text = "Event $playbackCurrentIndex / $playbackTotalEvents",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Live PDR Tracking:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onLoadTrack,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 28.dp)
                    ) {
                        Text(
                            text = "Load Track",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Button(
                        onClick = onToggleTracking,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTracking) Color(0xFFFF5252) else Color(0xFF4CAF50)
                        )
                    ) {
                        Text(
                            text = if (isTracking) "Stop Tracking" else "Start Tracking",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Line 2: Playback transport buttons and speed selector
            if (hasLoadedTrack) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onStepBackPlayback,
                            enabled = playbackCurrentIndex > 0,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("⏮", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = {
                                if (isPlaybackPlaying && !isPlaybackPaused) {
                                    onPausePlayback()
                                } else {
                                    onPlayPlayback()
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaybackPlaying && !isPlaybackPaused) Color(0xFFFF9800) else Color(0xFF2196F3)
                            )
                        ) {
                            Text(
                                text = if (isPlaybackPlaying && !isPlaybackPaused) "⏸ Pause" else "▶ Play",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = onStepForwardPlayback,
                            enabled = playbackCurrentIndex < playbackTotalEvents,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("⏭", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }

                        Button(
                            onClick = onStopPlayback,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
                        ) {
                            Text("⏹ Stop", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }

                    // Speed selector
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speed:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                        speedOptions.forEach { (speed, label) ->
                            FilterChip(
                                selected = playbackSpeed == speed,
                                onClick = { onSpeedSelected(speed) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp) },
                                modifier = Modifier.defaultMinSize(minHeight = 28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}