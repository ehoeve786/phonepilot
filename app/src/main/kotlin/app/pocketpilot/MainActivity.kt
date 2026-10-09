package app.pocketpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.ui.HomeScreen
import app.pocketpilot.ui.PocketPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var policyProfile: PolicyProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketPilotTheme {
                HomeScreen(policyProfile = policyProfile, versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
