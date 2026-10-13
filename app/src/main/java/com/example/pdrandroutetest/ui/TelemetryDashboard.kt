package com.example.pdrandroutetest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pdrandroutetest.data.EkfState
import kotlin.math.sqrt

@Composable
fun TelemetryDashboard(
    ekfState: EkfState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "6-State EKF Telemetry",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (ekfState.isInitializedWithGps) "GPS Init: OK" else "GPS Init: Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ekfState.isInitializedWithGps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Position: 4 digits X, single space, 4 digits Y (no brackets, no comma, no 'm')
                val x4 = ((ekfState.position.value.x / 10.0).toInt() % 10000).let { if (it < 0) it + 10000 else it }
                val y4 = ((ekfState.position.value.y / 10.0).toInt() % 10000).let { if (it < 0) it + 10000 else it }
                TelemetryItem(
                    label = "Pos (X Y)",
                    value = "%04d %04d".format(x4, y4),
                    errorText = "±%.2f".format(sqrt(ekfState.position.variance))
                )

                // 2. Bearing
                TelemetryItem(
                    label = "Bearing",
                    value = "%.1f°".format(Math.toDegrees(ekfState.bearingRad.value)),
                    errorText = "±%.1f°".format(Math.toDegrees(sqrt(ekfState.bearingRad.variance)))
                )

                // 3. Speed
                TelemetryItem(
                    label = "Speed",
                    value = "%.2fm/s".format(ekfState.speedMps.value),
                    errorText = "±%.2f".format(sqrt(ekfState.speedMps.variance))
                )

                // 4. Step Length
                TelemetryItem(
                    label = "Step L",
                    value = "%.2fm".format(ekfState.strideLengthMeters.value),
                    errorText = "±%.3f".format(sqrt(ekfState.strideLengthMeters.variance))
                )

                // 5. Compass Bias
                TelemetryItem(
                    label = "Bias (b_θ)",
                    value = "%.1f°".format(Math.toDegrees(ekfState.compassBiasRad.value)),
                    errorText = "±%.1f°".format(Math.toDegrees(sqrt(ekfState.compassBiasRad.variance)))
                )
            }
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String,
    errorText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = errorText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
