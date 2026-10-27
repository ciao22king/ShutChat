package dev.shutchat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.shutchat.app.core.Log
import dev.shutchat.app.ui.navigation.ShutChatNavHost
import dev.shutchat.app.ui.theme.ShutChatTheme
import dev.shutchat.app.ui.theme.ThemeMode

/**
 * The single activity.
 *
 * Permissions are requested as one batch on first launch rather than lazily per
 * feature: every permission ShutChat wants exists to make the mesh reachable, and
 * a user who denies them has an app that cannot do the one thing it promises, so
 * asking up front and explaining why is kinder than failing later.
 */
class MainActivity : ComponentActivity() {

    private var startChatId by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val denied = granted.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.i(TAG, "permessi negati: ${denied.joinToString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        startChatId = intent?.getStringExtra(EXTRA_CHAT_ID)

        val container = (application as ShutChatApp).container
        requestPermissionsIfNeeded()
        container.notifier.ensureChannels()

        // The foreground listener is what makes messages arrive with the UI closed.
        (application as ShutChatApp).startSessionService()

        setContent {
            val themeKey by container.settings.theme.collectAsState(initial = "system")
            val dynamic by container.settings.dynamicColor.collectAsState(initial = true)

            ShutChatTheme(
                themeMode = ThemeMode.fromKey(themeKey),
                dynamicColor = dynamic,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ShutChatNavHost(startConversation = startChatId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startChatId = intent.getStringExtra(EXTRA_CHAT_ID)
    }

    /**
     * Requests only what is missing. Android remembers a permanent denial, so the
     * launcher is skipped when there is nothing left to ask for.
     */
    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_CHAT_ID = "extra_chat_id"
    }
}
