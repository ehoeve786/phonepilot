package app.pocketpilot.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.pocketpilot.core.automation.TriggerEvent
import app.pocketpilot.core.designsystem.R as DsR

/** Runs the rule whose home screen icon was tapped. It shows nothing: the icon is the only UI. */
class HomeScreenIconActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_NAME)?.let { name ->
            automations().onEvent(TriggerEvent("shortcut", mapOf("name" to name)))
        }
        finish()
    }

    companion object {
        const val EXTRA_NAME = "app.pocketpilot.automation.ICON_NAME"
    }
}

/**
 * Asks the launcher to put an icon named [name] on the home screen. Tapping it fires the "Home screen
 * icon" trigger with that name. Returns false when the launcher cannot pin icons.
 */
fun Context.requestHomeScreenIcon(name: String): Boolean {
    val manager = getSystemService(ShortcutManager::class.java)
    if (!manager.isRequestPinShortcutSupported) return false
    val info =
        ShortcutInfo
            .Builder(this, "icon:$name")
            .setShortLabel(name)
            .setIcon(Icon.createWithResource(this, DsR.drawable.core_designsystem_ic_bolt))
            .setIntent(
                Intent(this, HomeScreenIconActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(HomeScreenIconActivity.EXTRA_NAME, name),
            ).build()
    return manager.requestPinShortcut(info, null)
}
