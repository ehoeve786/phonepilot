package app.pocketpilot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.server.LocalTokenStore
import app.pocketpilot.server.PocketPilotService
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.ui.HomeScreen
import app.pocketpilot.ui.PocketPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var policyProfile: PolicyProfile

    @Inject lateinit var server: McpHttpServer

    @Inject lateinit var tokens: LocalTokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The foreground service notification needs this permission on Android 13+; the server
            // runs either way, the notification is just hidden without it.
            val notificationPermission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    PocketPilotService.start(this)
                }
            PocketPilotTheme {
                HomeScreen(
                    policyProfile = policyProfile,
                    versionName = BuildConfig.VERSION_NAME,
                    serverState = server.serverState.collectAsStateWithLifecycle().value,
                    token = tokens.token.collectAsStateWithLifecycle().value,
                    onStart = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            PocketPilotService.start(this)
                        }
                    },
                    onStop = { PocketPilotService.stop(this) },
                    onRotateToken = tokens::rotate,
                )
            }
        }
    }
}
