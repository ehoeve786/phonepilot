package app.pocketpilot.capability.api.apps

import app.pocketpilot.capability.api.CapabilityBackend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** LAUNCH_APPS: installed apps and launching them. */
interface AppController : CapabilityBackend {
    /** Apps with a launcher entry. */
    suspend fun list(includeSystem: Boolean): List<AppInfo>

    /** Throws ToolException ELEMENT_NOT_FOUND when nothing matches [packageName]. */
    suspend fun launch(packageName: String)

    /** Opens an http or https link in [packageName], or in the default handler when it is null. */
    suspend fun openUrl(
        url: String,
        packageName: String? = null,
    )
}

@Serializable
data class AppInfo(
    @SerialName("package") val packageName: String,
    val label: String,
    val versionName: String? = null,
    val system: Boolean = false,
)
