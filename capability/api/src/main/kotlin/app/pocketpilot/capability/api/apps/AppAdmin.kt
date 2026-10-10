package app.pocketpilot.capability.api.apps

import app.pocketpilot.capability.api.CapabilityBackend
import kotlinx.serialization.Serializable

/**
 * Managing installed apps as the shell user (`am`, `pm`, `appops`): force stop, runtime permissions,
 * app ops, disable and clear data. Arguments are checked against [AppAdminArgs] on both sides.
 */
interface AppAdmin : CapabilityBackend {
    suspend fun forceStop(packageName: String)

    suspend fun permissions(packageName: String): AppPermissions

    suspend fun setPermission(
        packageName: String,
        permission: String,
        granted: Boolean,
    )

    suspend fun setAppOp(
        packageName: String,
        op: String,
        mode: String,
    )

    suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    )

    suspend fun clearData(packageName: String)
}

@Serializable
data class AppPermissions(
    /** Runtime permissions the app requests, with whether each is granted; only these can change. */
    val runtime: Map<String, Boolean>,
    /** App ops with a mode set, such as `RUN_IN_BACKGROUND` to `ignore`. */
    val appOps: Map<String, String>,
)

/** What app-management arguments may look like, so neither side passes anything else to a command. */
object AppAdminArgs {
    val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    val OP = Regex("^(android:)?[A-Za-z][A-Za-z0-9_]*$")
    val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    fun checkPackage(name: String) = require(PACKAGE.matches(name)) { "Not a package name: $name" }

    fun checkPermission(name: String) = require(PERMISSION.matches(name)) { "Not a permission name: $name" }

    fun checkOp(op: String) = require(OP.matches(op)) { "Not an app op name: $op" }

    fun checkMode(mode: String) = require(mode in MODES) { "Mode must be one of ${MODES.joinToString()}" }

    /** Apps nothing may stop or change: PocketPilot itself and Shizuku, which it runs on. */
    fun checkNotProtected(name: String) =
        require(!(name == OWN_PACKAGE || name.startsWith("$OWN_PACKAGE.") || name in SHIZUKU)) {
            "$name cannot be changed: PocketPilot and Shizuku need it"
        }

    /** System parts the phone cannot run without; they may not be disabled or wiped. */
    fun checkNotCritical(name: String) = require(name !in CRITICAL) { "$name is part of Android and cannot be disabled or cleared" }

    private const val OWN_PACKAGE = "app.pocketpilot"
    private val SHIZUKU = setOf("moe.shizuku.privileged.api")
    private val CRITICAL =
        setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings",
            "com.android.shell",
            "com.android.providers.settings",
        )
}
