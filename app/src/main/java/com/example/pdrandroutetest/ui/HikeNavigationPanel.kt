package com.example.pdrandroutetest.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pdrandroutetest.data.EkfState
import com.example.pdrandroutetest.model.Leg
import com.example.pdrandroutetest.model.Route
import java.util.Locale

@Composable
fun HikeNavigationPanel(
    activeRoute: Route?,
    activeLeg: Leg?,
    activeEkfState: EkfState,
    currentLegIndex: Int,
    onPrevLeg: () -> Unit,
    onNextLeg: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val totalLegs = activeRoute?.legs?.size ?: 0
    val canGoPrev = currentLegIndex > 0
    val canGoNext = activeLeg?.next != null || (currentLegIndex < totalLegs - 1 && totalLegs > 0)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Line 1: "Leg" label + 3 icon-only buttons further apart (Left Arrow, Right Arrow, Pencil)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Leg ${currentLegIndex + 1} of ${if (totalLegs > 0) totalLegs else 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Arrow Button (icons only, no text)
                    IconButton(
                        onClick = onPrevLeg,
                        enabled = canGoPrev,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "◀",
                            color = if (canGoPrev) Color.White else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Right Arrow Button (icons only, no text)
                    IconButton(
                        onClick = onNextLeg,
                        enabled = canGoNext,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "▶",
                            color = if (canGoNext) Color.White else Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Pencil Button (icons only, no text)
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Leg editing screen coming soon", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = "✎",
                            color = Color(0xFFFFB300),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Line 2: Current leg, start name, bearing to target, length to target, target name
            val startName = activeLeg?.start?.name?.ifBlank { "Start" } ?: "Start"
            val targetName = activeLeg?.target?.name?.ifBlank { "Target" } ?: "Target"
            val legBearing = if (activeLeg != null) activeLeg.start.bearingTo(activeLeg.target) else 0.0
            val legLength = if (activeLeg != null) activeLeg.start.distanceTo(activeLeg.target) else 0.0

            Text(
                text = "$startName ➔ %03.0f° • %.0fm ➔ $targetName".format(Locale.US, legBearing, legLength),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9FB3C8),
                fontWeight = FontWeight.SemiBold
            )

            // Line 3: Hiker: (000 000) 000° 10000m 0.0m/s
            val hikerLoc = activeEkfState.position.value
            val easting6 = ((hikerLoc.x.toInt() % 100000) / 100).coerceIn(0, 999)
            val northing6 = ((hikerLoc.y.toInt() % 100000) / 100).coerceIn(0, 999)
            val gridRefStr = String.format(Locale.US, "%03d %03d", easting6, northing6)

            val hikerBearingDeg = Math.toDegrees(activeEkfState.bearingRad.value)
            val normalizedBearing = (hikerBearingDeg % 360.0 + 360.0) % 360.0
            val distFromTarget = if (activeLeg != null) Math.hypot(activeLeg.target.x - hikerLoc.x, activeLeg.target.y - hikerLoc.y) else 0.0
            val speedMps = activeEkfState.speedMps.value

            Text(
                text = "Hiker: ($gridRefStr) %03.0f° %.0fm %.1fm/s".format(
                    Locale.US,
                    normalizedBearing,
                    distFromTarget,
                    speedMps
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF00E5FF),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
