package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppController
import app.pocketpilot.capability.api.apps.AppInfo
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolException
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.tools.SchemaParts.RETURN_SNAPSHOT
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI

/** `app.list`: launchable apps. */
class AppListTool(
    private val apps: AppController,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "app.list",
            title = "List apps",
            description =
                "Lists apps that have a launcher icon: package, label, version and whether it is a system app. " +
                    "filter matches label or package by substring.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"filter":{"type":"string"},"includeSystem":{"type":"boolean",
                    "description":"Include preinstalled system apps (default true)"}},"additionalProperties":false}""",
                ),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.APPS_READ),
            requiredCapabilities = setOf(CapabilityId.LAUNCH_APPS),
            annotations = ToolAnnotations(readOnly = true, idempotent = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val filter = call.arguments.string("filter")
        val list =
            apps
                .list(includeSystem = call.arguments.boolean("includeSystem") ?: true)
                .filter { filter == null || it.label.contains(filter, true) || it.packageName.contains(filter, true) }
                .sortedBy { it.label.lowercase() }
        return ToolResult.success(compactJson.encodeToString(ListSerializer(AppInfo.serializer()), list))
    }
}

/** `app.current`: the foreground app. */
class AppCurrentTool(
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "app.current",
            title = "Current app",
            description = "Returns the package and activity of the app in front.",
            inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
            riskTier = RiskTier.READ,
            requiredScopes = setOf(Scope.APPS_READ),
            requiredCapabilities = setOf(CapabilityId.READ_UI),
            annotations = ToolAnnotations(readOnly = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val app = reader.foreground() ?: return ToolResult.success("""{"package":null}""")
        return ToolResult.success(compactJson.encodeToString(Current.serializer(), Current(app.packageName, app.className)))
    }

    @Serializable
    private data class Current(
        @SerialName("package") val packageName: String,
        val activity: String?,
    )
}

/** `app.launch`: by package name or label. */
class AppLaunchTool(
    private val apps: AppController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "app.launch",
            title = "Launch app",
            description =
                "Opens an app by package name (com.android.settings) or by its label (Settings). Returns the screen " +
                    "once the app is in front.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"package":{"type":"string"},"label":{"type":"string"},
                    $RETURN_SNAPSHOT},"additionalProperties":false}""",
                ),
            riskTier = RiskTier.INTERACT,
            requiredScopes = setOf(Scope.APPS_CONTROL),
            requiredCapabilities = setOf(CapabilityId.LAUNCH_APPS),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val app = resolve(call.arguments.string("package"), call.arguments.string("label"))
        apps.launch(app.packageName)
        if (reader.available.value) {
            withTimeoutOrNull(LAUNCH_WAIT_MS) {
                while (reader.foreground()?.packageName != app.packageName) reader.awaitChange(LAUNCH_WAIT_MS)
            }
        }
        return afterAction(reader, "Launched ${app.label} (${app.packageName}).", call.arguments.boolean("returnSnapshot") ?: true)
    }

    private suspend fun resolve(
        packageName: String?,
        label: String?,
    ): AppInfo {
        if ((packageName == null) == (label == null)) invalid("Give either package or label")
        val all = apps.list(includeSystem = true)
        if (packageName != null) {
            return all.firstOrNull { it.packageName == packageName } ?: notFound("No launchable app has package $packageName")
        }
        val exact = all.filter { it.label.equals(label, ignoreCase = true) }
        val partial = all.filter { it.label.contains(label!!, ignoreCase = true) }
        return exact.singleOrNull() ?: exact.firstOrNull() ?: partial.singleOrNull()
            ?: if (partial.isEmpty()) {
                notFound("No launchable app is called \"$label\"")
            } else {
                notFound("Several apps match \"$label\": ${partial.joinToString { "${it.label} (${it.packageName})" }}")
            }
    }

    private fun notFound(message: String): Nothing =
        throw ToolException(ToolErrorCode.ELEMENT_NOT_FOUND, message, "Use app.list to find the package name, then pass package")

    private companion object {
        const val LAUNCH_WAIT_MS = 5_000L
    }
}

/** `app.open_url`. */
class AppOpenUrlTool(
    private val apps: AppController,
    private val reader: ScreenReader,
) : ToolHandler {
    override val spec =
        ToolSpec(
            name = "app.open_url",
            title = "Open link",
            description = "Opens an http or https link in the app that handles it, usually the browser.",
            inputSchema =
                schema(
                    """{"type":"object","properties":{"url":{"type":"string"},$RETURN_SNAPSHOT},"required":["url"],
                    "additionalProperties":false}""",
                ),
            riskTier = RiskTier.INTERACT,
            requiredScopes = setOf(Scope.APPS_CONTROL),
            requiredCapabilities = setOf(CapabilityId.LAUNCH_APPS),
            annotations = ToolAnnotations(openWorld = true),
            availability = Availability(Phase.P0),
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val url = call.arguments.string("url") ?: invalid("url is required")
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") invalid("Only http and https links can be opened")
        apps.openUrl(url)
        if (reader.available.value) reader.awaitChange(OPEN_WAIT_MS)
        return afterAction(reader, "Opened $url.", call.arguments.boolean("returnSnapshot") ?: true)
    }

    private companion object {
        const val OPEN_WAIT_MS = 3_000L
    }
}
