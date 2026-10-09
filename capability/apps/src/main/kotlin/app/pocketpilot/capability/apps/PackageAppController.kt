package app.pocketpilot.capability.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * LAUNCH_APPS through the package manager. Starting an activity from the background is only allowed
 * while PocketPilot's Accessibility service is on, which the error hint says.
 */
class PackageAppController(
    private val context: Context,
) : AppController {
    override val backendId: String = BackendIds.PACKAGE_MANAGER
    override val available: StateFlow<Boolean> = MutableStateFlow(true)

    private val packageManager: PackageManager get() = context.packageManager

    override suspend fun list(includeSystem: Boolean): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            queryActivities(launcher)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .map { info ->
                    AppInfo(
                        packageName = info.packageName,
                        label = info.loadLabel(packageManager).toString(),
                        versionName = versionOf(info.packageName),
                        system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }.filter { includeSystem || !it.system }
        }

    override suspend fun launch(packageName: String) {
        val intent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: throw ToolException(
                    ToolErrorCode.ELEMENT_NOT_FOUND,
                    "$packageName has no launcher entry",
                    "Use app.list to find launchable apps",
                )
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    override suspend fun openUrl(url: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, "No app can open that", e.message)
        } catch (e: SecurityException) {
            throw ToolException(
                ToolErrorCode.CAPABILITY_UNAVAILABLE,
                "Android blocked opening the app from the background",
                "Ask the phone's owner to turn on PocketPilot in Settings > Accessibility",
            )
        }
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

    private fun versionOf(packageName: String): String? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
}
