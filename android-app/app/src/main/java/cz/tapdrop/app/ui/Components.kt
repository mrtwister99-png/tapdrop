package cz.tapdrop.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.tapdrop.app.net.humanSize
import cz.tapdrop.app.transfer.TransferResult
import cz.tapdrop.app.transfer.TransferUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtDate(t: Long): String = SimpleDateFormat("d. M. yyyy HH:mm", Locale("cs")).format(Date(t))

fun fmtEta(s: Long): String = when {
    s < 0 -> "…"
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TransferCard(u: TransferUi, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (u.fileCount > 0) "${u.phase} (${u.fileIndex}/${u.fileCount})" else u.phase,
                style = MaterialTheme.typography.titleMedium,
            )
            if (u.fileName.isNotEmpty()) {
                Text(u.fileName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            if (u.total > 0) {
                LinearProgressIndicator(progress = { u.fraction }, Modifier.fillMaxWidth().height(8.dp))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(u.fraction * 100).toInt()} %", fontWeight = FontWeight.Bold)
                    Text("${humanSize(u.speed)}/s")
                    Text("zbývá ${fmtEta(u.etaSec)}")
                }
                Text(
                    "${humanSize(u.done)} / ${humanSize(u.total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
            }
        }
    }
}

@Composable
fun ResultCard(r: TransferResult, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val color = if (r.ok) OkGreen else WarnRed
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = color, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, color = if (r.ok) Color.Unspecified else color)
                if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.bodyMedium)
                Text(fmtDate(r.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDismiss != null) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Skrýt") }
            }
        }
    }
}
