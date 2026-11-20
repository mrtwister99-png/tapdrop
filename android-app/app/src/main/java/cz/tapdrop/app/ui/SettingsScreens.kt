package cz.tapdrop.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cz.tapdrop.app.TapApp
import cz.tapdrop.app.net.Discovery
import cz.tapdrop.app.net.TapError
import cz.tapdrop.app.transfer.PollService
import kotlinx.coroutines.launch

@Composable
private fun SettingRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

// ============================================================ NASTAVENÍ
@Composable
fun SettingsScreen(nav: NavController, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val prefs = TapApp.prefs
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var portText by remember(prefs.port) { mutableStateOf(prefs.port.toString()) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.downloadTree = uri.toString()
            prefs.downloadTreeName = DocumentFile.fromTreeUri(ctx, uri)?.name ?: uri.lastPathSegment.orEmpty()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingRow(
                    "Automaticky stahovat bez potvrzení",
                    "Po přiložení tagu se soubory z notebooku stáhnou samy, bez dialogu.",
                    prefs.autoDownload,
                ) { prefs.autoDownload = it }
                HorizontalDivider()
                SettingRow("Zůstat připojený na pozadí", "Telefon hlídá notebook a stahuje soubory hned, jak je přetáhneš do okna.", prefs.keepAlive) {
                    prefs.keepAlive = it
                    if (it) PollService.start(ctx) else PollService.stop(ctx)
                }
                HorizontalDivider()
                SettingRow("Notifikace", "Oznámení „Odesláno“ / „Staženo“.", prefs.notifications) { prefs.notifications = it }
                HorizontalDivider()
                SettingRow("Zvuk po přenosu", "Přehraje zvuk oznámení po úspěšném přenosu.", prefs.sound) { prefs.sound = it }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Složka pro stažené soubory", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (prefs.downloadTree.isBlank()) "Stažené / TapDrop (výchozí)" else prefs.downloadTreeName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Vybrat složku") }
                    if (prefs.downloadTree.isNotBlank()) {
                        TextButton(onClick = { prefs.downloadTree = ""; prefs.downloadTreeName = "" }) { Text("Výchozí") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Spojení s notebookem", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    prefs.host, { prefs.host = it.trim() }, Modifier.fillMaxWidth(),
                    label = { Text("IP adresa notebooku") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    portText,
                    { v -> portText = v.filter { it.isDigit() }.take(5); portText.toIntOrNull()?.let { if (it in 1..65535) prefs.port = it } },
                    Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(prefs.token, { prefs.token = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
                OutlinedTextField(prefs.notebookId, { prefs.notebookId = it.trim().take(12) }, Modifier.fillMaxWidth(), label = { Text("NOTEBOOK_ID") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("pair") }) { Text("Spárovat znovu") }
                    Button(
                        enabled = !testing,
                        onClick = {
                            scope.launch {
                                testing = true
                                val msg = try {
                                    val api = Discovery.connect(ctx, prefs)
                                    val i = api.discover()
                                    "Spojení v pořádku: ${i.name} (${api.ep.host})"
                                } catch (e: TapError) {
                                    "${e.title}. ${TapError.hint(e.kind)}"
                                } catch (e: Exception) {
                                    "Notebook nenalezen"
                                }
                                testing = false
                                snack.showSnackbar(msg)
                            }
                        },
                    ) { Text(if (testing) "Zkouším…" else "Test spojení") }
                }
            }
        }

        Text(
            "TapDrop 1.0.0 · komunikace probíhá jen v lokální síti",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

// ============================================================ SPÁROVAT
private sealed class PairState {
    object Idle : PairState()
    object Checking : PairState()
    data class Ok(val name: String) : PairState()
    data class Fail(val text: String) : PairState()
}

@Composable
fun PairScreen(nav: NavController) {
    val ctx = LocalContext.current
    val prefs = TapApp.prefs
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PairState>(PairState.Idle) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { res ->
        val text = res.contents ?: return@rememberLauncherForActivityResult
        val u = runCatching { Uri.parse(text) }.getOrNull()
        val ip = u?.getQueryParameter("ip")
        val port = u?.getQueryParameter("port")?.toIntOrNull() ?: 8765
        val id = u?.getQueryParameter("id")
        val token = u?.getQueryParameter("token")
        if (u == null || u.scheme != "tapdrop" || u.host != "pair" || ip.isNullOrBlank() || id.isNullOrBlank() || token.isNullOrBlank()) {
            state = PairState.Fail("Tohle není QR kód z TapDrop. Použij kód z okna programu na notebooku.")
        } else {
            prefs.savePairing(ip, port, id, token)
            state = PairState.Checking
            scope.launch {
                state = try {
                    val api = Discovery.connect(ctx, prefs)
                    run { PollService.start(ctx); PairState.Ok(api.discover().name) }
                } catch (e: TapError) {
                    PairState.Fail("${e.title}. ${TapError.hint(e.kind)} (uloženo: $ip:$port)")
                } catch (e: Exception) {
                    PairState.Fail("Notebook nenalezen. (uloženo: $ip:$port)")
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Na notebooku spusť TapDrop – v okně uvidíš QR kód. Naskenuj ho a hotovo, víc nepotřebuješ.", textAlign = TextAlign.Center)
        Button(
            onClick = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Namiř na QR kód v okně TapDrop")
                        .setBeepEnabled(false).setOrientationLocked(false)
                )
            },
            Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Naskenovat QR kód") }

        AnimatedContent(state, label = "pair") { s ->
            when (s) {
                PairState.Idle -> if (prefs.paired) Text("Aktuálně spárováno s ${prefs.notebookId} (${prefs.host})", textAlign = TextAlign.Center) else Spacer(Modifier.height(1.dp))
                PairState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("Ověřuji spojení…")
                }
                is PairState.Ok -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = OkGreen, modifier = Modifier.size(48.dp))
                    Text("Spárováno s notebookem ${s.name}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Button(onClick = { nav.popBackStack() }) { Text("Hotovo") }
                }
                is PairState.Fail -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = WarnRed, modifier = Modifier.size(48.dp))
                    Text(s.text, color = WarnRed, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

