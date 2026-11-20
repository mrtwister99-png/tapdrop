package cz.tapdrop.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cz.tapdrop.app.TapApp
import cz.tapdrop.app.net.Discovery
import cz.tapdrop.app.net.ErrorKind
import cz.tapdrop.app.net.RemoteFile
import cz.tapdrop.app.net.TapError
import cz.tapdrop.app.net.czFiles
import cz.tapdrop.app.net.humanSize
import cz.tapdrop.app.transfer.FileMeta
import cz.tapdrop.app.transfer.PollBus
import cz.tapdrop.app.transfer.TransferBus
import cz.tapdrop.app.transfer.TransferService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================ DOMŮ
@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val prefs = TapApp.prefs
    val ui by TransferBus.ui.collectAsState()
    val result by TransferBus.result.collectAsState()
    val connected by PollBus.connected.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Hero(paired = prefs.paired, connected = connected, active = ui.active)

        if (ui.active) TransferCard(ui)
        result?.let { if (!ui.active) ResultCard(it, onDismiss = { TransferBus.setResult(null) }) }

        if (!prefs.paired) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Telefon ještě není spárovaný", style = MaterialTheme.typography.titleMedium)
                    Text("Spusť TapDrop na notebooku a naskenuj QR kód z jeho okna. Víc není potřeba.")
                    Button(onClick = { nav.navigate("pair") }, Modifier.fillMaxWidth()) { Text("Spárovat s notebookem") }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Spárováno", style = MaterialTheme.typography.titleMedium)
                    Text("Notebook ${prefs.notebookId} · ${prefs.host}:${prefs.port}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Soubory přetažené do okna TapDrop na notebooku se sem stáhnou samy. Odesílání je v záložce Odeslat.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { TransferService.start(ctx, TransferService.ACTION_DOWNLOAD) },
                    Modifier.weight(1f), enabled = !ui.active,
                ) { Text("Stáhnout teď") }
                OutlinedButton(onClick = { nav.navigate("send") }, Modifier.weight(1f)) { Text("Odeslat soubory") }
            }
        }
    }
}

@Composable
private fun Hero(paired: Boolean, connected: Boolean?, active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(
        1f, 1.55f, infiniteRepeatable(tween(if (active) 700 else 1800), RepeatMode.Restart), label = "s",
    )
    val a by t.animateFloat(
        0.45f, 0f, infiniteRepeatable(tween(if (active) 700 else 1800), RepeatMode.Restart), label = "a",
    )
    val ok = paired && connected == true
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        if (ok) Box(Modifier.size(110.dp).scale(s).alpha(a).clip(CircleShape).background(color))
        Box(Modifier.size(110.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(
                when { !paired -> Icons.Rounded.QrCodeScanner; ok -> Icons.Rounded.Wifi; else -> Icons.Rounded.WifiOff },
                null, Modifier.size(56.dp), tint = Color.White,
            )
        }
        Text(
            when {
                active -> "Přenáším…"
                !paired -> "Nespárováno"
                connected == true -> "Připojeno k notebooku"
                connected == false -> "Notebook není dostupný"
                else -> "Připojuji…"
            },
            Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

// ============================================================ ODESLAT
@Composable
fun SendScreen(nav: NavController) {
    val ctx = LocalContext.current
    val prefs = TapApp.prefs
    val ui by TransferBus.ui.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val s = u.toString()
            if (s !in prefs.queue) prefs.queue.add(s)
        }
        prefs.saveQueue()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        if (ui.active && ui.upload) TransferCard(ui)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f)) {
                Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.size(8.dp)); Text("Vybrat soubory")
            }
            if (prefs.queue.isNotEmpty()) {
                TextButton(onClick = { prefs.queue.clear(); prefs.saveQueue() }) { Text("Vyprázdnit") }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (prefs.queue.isEmpty()) {
            EmptyState(Icons.Rounded.Description, "Žádné soubory", "Vyber soubory (jakýkoli typ, i víc najednou) a stiskni „Odeslat do notebooku“.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prefs.queue.toList(), key = { it }) { s ->
                    val (name, size) = remember(s) { FileMeta.query(ctx, Uri.parse(s)) }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Description, null)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                Text(humanSize(size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { prefs.queue.remove(s); prefs.saveQueue() }) { Icon(Icons.Rounded.Close, "Odebrat") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!prefs.paired) {
            Button(onClick = { nav.navigate("pair") }, Modifier.fillMaxWidth()) { Text("Nejdřív spárovat s notebookem") }
        } else {
            Button(
                onClick = { TransferService.start(ctx, TransferService.ACTION_UPLOAD) },
                Modifier.fillMaxWidth().height(56.dp),
                enabled = prefs.queue.isNotEmpty() && !ui.active,
            ) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.size(8.dp)); Text("Odeslat do notebooku") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ============================================================ PŘIJATO
@Composable
fun ReceivedScreen(snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val ui by TransferBus.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf<TapError?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true; error = null
            try {
                val api = Discovery.connect(ctx, TapApp.prefs)
                pending = api.pending()
            } catch (e: TapError) { error = e; pending = null }
            catch (e: Exception) { error = TapError(ErrorKind.NOT_FOUND); pending = null }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(ui.active) { if (!ui.active) refresh() }

    val received = TapApp.history.items.filter { !it.sent }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        if (ui.active && !ui.upload) TransferCard(ui)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Čeká na notebooku", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Rounded.Refresh, "Obnovit") }
                }
                val e = error
                val p = pending
                when {
                    loading && p == null -> Text("Zjišťuji…")
                    e != null -> Text("${e.title}. ${TapError.hint(e.kind)}", color = WarnRed)
                    p != null && p.isEmpty() -> Text("Nic nečeká. Přetáhni soubory do okna TapDrop na notebooku.")
                    p != null -> {
                        Text("${czFiles(p.size)} (${humanSize(p.sumOf { it.size })})")
                        p.take(5).forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        if (p.size > 5) Text("… a další", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = { TransferService.start(ctx, TransferService.ACTION_DOWNLOAD) },
                    Modifier.fillMaxWidth(),
                    enabled = !ui.active && TapApp.prefs.paired,
                ) { Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.size(8.dp)); Text("Stáhnout teď") }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (received.isEmpty()) {
            EmptyState(Icons.Rounded.CloudDownload, "Zatím nic nepřišlo", "Soubory přetažené do okna TapDrop na notebooku se stáhnou samy.", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(received) { h -> HistoryRow(h) { openFile(ctx, h.uri, snack, scope) } }
            }
        }
    }
}

private fun openFile(ctx: android.content.Context, uri: String, snack: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    if (uri.isBlank()) return
    try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        scope.launch { snack.showSnackbar("Soubor nelze otevřít – najdeš ho ve složce Stažené/TapDrop.") }
    }
}

// ============================================================ HISTORIE
@Composable
fun HistoryScreen() {
    val items = TapApp.history.items
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (items.isEmpty()) {
            EmptyState(Icons.Rounded.History, "Historie je prázdná", "Tady uvidíš všechny odeslané i přijaté soubory.", Modifier.weight(1f))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { TapApp.history.clear() }) {
                    Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.size(6.dp)); Text("Vymazat historii")
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.toList()) { HistoryRow(it, null) }
            }
        }
    }
}

@Composable
fun HistoryRow(h: cz.tapdrop.app.data.HistoryItem, onClick: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth().then(if (onClick != null && h.ok) Modifier.clickable { onClick() } else Modifier),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (h.sent) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null,
                tint = if (h.ok) OkGreen else WarnRed,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(h.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (h.sent) "Odesláno" else "Přijato"} · ${humanSize(h.size)} · ${fmtDate(h.time)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!h.ok) Text("Selhalo: ${h.error}", style = MaterialTheme.typography.bodySmall, color = WarnRed)
            }
        }
    }
}
