package cz.tapdrop.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import cz.tapdrop.app.transfer.PollService
import cz.tapdrop.app.ui.AppRoot
import cz.tapdrop.app.ui.NavBus
import cz.tapdrop.app.ui.TapTheme

class MainActivity : ComponentActivity() {
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { PollService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())

        setContent { TapTheme { AppRoot() } }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PollService.start(this) // po spárování běží na pozadí a hlídá notebook
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("route")?.let { NavBus.route.value = it }
    }
}
