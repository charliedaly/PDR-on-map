package com.example.pdrandroutetest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

@Composable
fun ControlPanel(
    isTracking: Boolean,
    currentIntervalMs: Long,
    onToggleTracking: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
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
    onSpeedSelected: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val intervalOptions = listOf(
        1000L to "1s (1Hz)",
        10000L to "10s",
        20000L to "20s",
        100000L to "100s"
    )

    val speedOptions = listOf(
        1.0 to "1x",
        2.0 to "2x",
        5.0 to "5x",
        10.0 to "10x"
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
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasLoadedTrack) "Track Playback:" else "GPS Polling Interval:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onLoadTrack,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Load Track", style = MaterialTheme.typography.labelSmall)
                    }

                    Button(
                        onClick = onToggleTracking,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTracking) Color(0xFFFF5252) else Color(0xFF4CAF50)
                        )
                    ) {
                        Text(
                            if (isTracking) "Stop Tracking" else "Start Tracking",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (hasLoadedTrack) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Event $playbackCurrentIndex / $playbackTotalEvents",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                if (isPlaybackPlaying && !isPlaybackPaused) {
                                    onPausePlayback()
                                } else {
                                    onPlayPlayback()
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaybackPlaying && !isPlaybackPaused) Color(0xFFFF9800) else Color(0xFF2196F3)
                            )
                        ) {
                            Text(
                                if (isPlaybackPlaying && !isPlaybackPaused) "⏸ Pause" else "▶ Play",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Button(
                            onClick = onStopPlayback,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
                        ) {
                            Text("⏹ Stop", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speed:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    speedOptions.forEach { (speed, label) ->
                        FilterChip(
                            selected = playbackSpeed == speed,
                            onClick = { onSpeedSelected(speed) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    intervalOptions.forEach { (ms, label) ->
                        FilterChip(
                            selected = currentIntervalMs == ms,
                            onClick = { onIntervalSelected(ms) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}
