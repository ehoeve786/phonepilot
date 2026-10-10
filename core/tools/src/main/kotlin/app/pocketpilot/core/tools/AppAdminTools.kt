package app.pocketpilot.core.tools

import app.pocketpilot.capability.api.apps.AppAdmin
import app.pocketpilot.capability.api.apps.AppAdminArgs
import app.pocketpilot.capability.api.apps.AppPermissions
import app.pocketpilot.core.model.Availability
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.core.model.Phase
import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.ToolAnnotations
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolResult
import app.pocketpilot.core.model.ToolSpec
import app.pocketpilot.core.orchestrator.ToolHandler
import kotlinx.serialization.json.JsonObject

private const val PACKAGE_PROP = """"package":{"type":"string","description":"Package name, for example com.example.app"}"""

private fun adminSpec(
    name: String,
    title: String,
    description: String,
    properties: String,
    required: String,
    tier: RiskTier,
    readOnly: Boolean = false,
) = ToolSpec(
    name = name,
    title = title,
    description = description,
    inputSchema = schema("""{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""),
    riskTier = tier,
    requiredScopes = setOf(if (readOnly) Scope.APPS_READ else Scope.APPS_CONTROL),
    requiredCapabilities = setOf(CapabilityId.RUN_SHELL),
    annotations = ToolAnnotations(readOnly = readOnly, destructive = tier == RiskTier.DESTRUCTIVE, idempotent = true),
    availability = Availability(Phase.P0),
)

private fun JsonObject.packageName(): String {
    val name = string("package") ?: invalid("Give package, for example com.example.app")
    if (!AppAdminArgs.PACKAGE.matches(name)) invalid("Not a package name: $name")
    return name
}

/** `app.stop`: force-stops an app (spec section 5). */
class AppStopTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.stop",
            "Force stop app",
            "Force-stops an app and its background work, like Force stop in App info.",
            PACKAGE_PROP,
            "\"package\"",
            RiskTier.SENSITIVE,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        admin.forceStop(pkg)
        return ToolResult.success("Stopped $pkg.")
    }
}

/** `app.permissions`: an app's runtime permissions and app ops. */
class AppPermissionsTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.permissions",
            "App permissions",
            "Lists an app's runtime permissions (granted or not) and its app ops that have a mode set, such as RUN_IN_BACKGROUND.",
            PACKAGE_PROP,
            "\"package\"",
            RiskTier.READ,
            readOnly = true,
        )

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult =
        ToolResult.success(compactJson.encodeToString(AppPermissions.serializer(), admin.permissions(call.arguments.packageName())))
}

/** `app.set_permission`: grants or revokes one runtime permission. */
class AppSetPermissionTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.set_permission",
            "Grant or revoke permission",
            "Grants or revokes a runtime permission (pm grant/revoke), for example android.permission.CAMERA. " +
                "Only runtime permissions listed by app.permissions can change.",
            """$PACKAGE_PROP,"permission":{"type":"string"},"granted":{"type":"boolean"}""",
            "\"package\",\"permission\",\"granted\"",
            RiskTier.SENSITIVE,
        )

    override fun confirmationDetail(call: ToolCall): String =
        "${if (call.arguments.boolean("granted") == true) "Grant" else "Revoke"} ${call.arguments.string("permission")} " +
            "for ${call.arguments.string("package")}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        val permission = call.arguments.string("permission") ?: invalid("Give permission")
        if (!AppAdminArgs.PERMISSION.matches(permission)) invalid("Not a permission name: $permission")
        val granted = call.arguments.boolean("granted") ?: invalid("Give granted: true or false")
        admin.setPermission(pkg, permission, granted)
        return ToolResult.success("${if (granted) "Granted" else "Revoked"} $permission for $pkg.")
    }
}

/** `app.set_appop`: sets one app op mode. */
class AppSetAppOpTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.set_appop",
            "Set app op",
            "Sets an app op (appops set), for example RUN_IN_BACKGROUND to ignore to stop background running, " +
                "or SYSTEM_ALERT_WINDOW to allow. Modes: ${AppAdminArgs.MODES.joinToString()}.",
            """$PACKAGE_PROP,"op":{"type":"string"},"mode":{"type":"string","enum":[${AppAdminArgs.MODES.joinToString(
                ",",
            ) { "\"$it\"" }}]}""",
            "\"package\",\"op\",\"mode\"",
            RiskTier.SENSITIVE,
        )

    override fun confirmationDetail(call: ToolCall): String =
        "Set ${call.arguments.string("op")} to ${call.arguments.string("mode")} for ${call.arguments.string("package")}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        val op = call.arguments.string("op") ?: invalid("Give op")
        if (!AppAdminArgs.OP.matches(op)) invalid("Not an app op name: $op")
        val mode = call.arguments.string("mode") ?: invalid("Give mode")
        admin.setAppOp(pkg, op, mode)
        return ToolResult.success("Set $op to $mode for $pkg.")
    }
}

/** `app.set_enabled`: disables or re-enables an app. */
class AppSetEnabledTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.set_enabled",
            "Disable or enable app",
            "Disables an app for this user (pm disable-user), hiding it and stopping it from running, or enables it again.",
            """$PACKAGE_PROP,"enabled":{"type":"boolean"}""",
            "\"package\",\"enabled\"",
            RiskTier.DESTRUCTIVE,
        )

    override fun confirmationDetail(call: ToolCall): String =
        "${if (call.arguments.boolean("enabled") == true) "Enable" else "Disable"} ${call.arguments.string("package")}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        val enabled = call.arguments.boolean("enabled") ?: invalid("Give enabled: true or false")
        admin.setEnabled(pkg, enabled)
        return ToolResult.success("${if (enabled) "Enabled" else "Disabled"} $pkg.")
    }
}

/** `app.clear_data`: wipes an app's data, like Clear storage. */
class AppClearDataTool(
    private val admin: AppAdmin,
) : ToolHandler {
    override val spec =
        adminSpec(
            "app.clear_data",
            "Clear app data",
            "Deletes all of an app's data, accounts and settings (pm clear), like Clear storage. This cannot be undone.",
            PACKAGE_PROP,
            "\"package\"",
            RiskTier.DESTRUCTIVE,
        )

    override fun confirmationDetail(call: ToolCall): String = "Delete all data of ${call.arguments.string("package")}"

    override suspend fun execute(
        call: ToolCall,
        session: Session,
    ): ToolResult {
        val pkg = call.arguments.packageName()
        admin.clearData(pkg)
        return ToolResult.success("Cleared all data of $pkg.")
    }
}
