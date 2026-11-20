package cz.tapdrop.app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import cz.tapdrop.app.nfc.Nfc
import cz.tapdrop.app.nfc.NfcBus
import cz.tapdrop.app.nfc.WriteState
import cz.tapdrop.app.transfer.Haptics
import cz.tapdrop.app.transfer.TransferService
import cz.tapdrop.app.ui.AppRoot
import cz.tapdrop.app.ui.NavBus
import cz.tapdrop.app.ui.TapTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var nfc: NfcAdapter? = null
    private var lastTap = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcAdapter.getDefaultAdapter(this)

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())

        setContent { TapTheme { AppRoot() } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc ?: return
        if (!adapter.isEnabled) return
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            addDataScheme("tapdrop")
            addDataAuthority("p", null)
        }
        val filters = arrayOf(
            ndefFilter,
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        )
        val techs = arrayOf(arrayOf(Ndef::class.java.name), arrayOf(NdefFormatable::class.java.name))
        runCatching { adapter.enableForegroundDispatch(this, pi, filters, techs) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfc?.disableForegroundDispatch(this) }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("route")?.let { NavBus.route.value = it }

        val action = i.action
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED
        ) return

        val tag = Nfc.tagFrom(i)

        // --- režim zápisu tagu
        val writeUri = NfcBus.writeUri.value
        if (writeUri != null && tag != null) {
            Haptics.tap(this)
            NfcBus.writeState.value = WriteState.Waiting
            lifecycleScope.launch(Dispatchers.IO) {
                val err = Nfc.write(tag, writeUri)
                NfcBus.writeState.value = if (err == null) WriteState.Success else WriteState.Failed(err)
                if (err == null) Haptics.success(this@MainActivity) else Haptics.error(this@MainActivity)
            }
            return
        }

        // --- normální přiložení tagu
        val id = Nfc.readNotebookId(i)
        if (id == null) {
            Toast.makeText(this, "Tohle není TapDrop tag", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTap < 3000) return
        lastTap = now
        Haptics.tap(this)
        NavBus.route.value = "home"
        // Žádné potvrzování: služba rovnou odešle / stáhne, co je potřeba.
        TransferService.start(this, TransferService.ACTION_TAP, id)
    }
}
