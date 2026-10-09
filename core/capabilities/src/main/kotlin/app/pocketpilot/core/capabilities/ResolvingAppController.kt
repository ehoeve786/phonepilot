package app.pocketpilot.core.capabilities

import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * LAUNCH_APPS: package manager intents first, Shizuku `am start` second (spec section 6). Android only
 * lets PocketPilot start activities from the background while its Accessibility service is on, so
 * without it launches go through Shizuku when that is available.
 */
class ResolvingAppController(
    private val packageManager: AppController,
    private val privileged: AppController?,
    private val backgroundStartsAllowed: StateFlow<Boolean>,
) : AppController {
    override val backendId: String = "resolver"
    override val available: StateFlow<Boolean> = packageManager.available

    override suspend fun list(includeSystem: Boolean): List<AppInfo> = packageManager.list(includeSystem)

    override suspend fun launch(packageName: String) = launcher().launch(packageName)

    override suspend fun openUrl(url: String) = launcher().openUrl(url)

    private fun launcher(): AppController =
        if (!backgroundStartsAllowed.value && privileged?.available?.value == true) privileged else packageManager
}
