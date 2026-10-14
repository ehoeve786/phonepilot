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

    /** Activities the app exposes to other apps, with their intent filters. */
    suspend fun activities(packageName: String): List<ActivityEntry>

    /**
     * Starts an activity (`am start -W`) or sends a broadcast (`am broadcast`) with [args] from
     * [IntentArgs.build]. Returns what `am` printed.
     */
    suspend fun startIntent(
        args: List<String>,
        broadcast: Boolean,
    ): String

    /** Battery optimization on or off (`cmd deviceidle whitelist`); off lets the app run in Doze. */
    suspend fun setBatteryOptimized(
        packageName: String,
        optimized: Boolean,
    )

    /** `am set-standby-bucket`; see [AppAdminArgs.STANDBY_BUCKETS]. */
    suspend fun setStandbyBucket(
        packageName: String,
        bucket: String,
    )
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

    /** App standby buckets, from most to least allowed to run in the background. */
    val STANDBY_BUCKETS = listOf("active", "working_set", "frequent", "rare", "restricted")

    fun checkStandbyBucket(bucket: String) =
        require(bucket in STANDBY_BUCKETS) { "Bucket must be one of ${STANDBY_BUCKETS.joinToString()}" }

    /**
     * Android's runtime ("dangerous") permissions, the only ones that may be granted or revoked, so a
     * client cannot hand an app shell-level permissions such as WRITE_SECURE_SETTINGS.
     */
    val RUNTIME_PERMISSIONS =
        setOf(
            "ACCEPT_HANDOVER",
            "ACCESS_BACKGROUND_LOCATION",
            "ACCESS_COARSE_LOCATION",
            "ACCESS_FINE_LOCATION",
            "ACCESS_MEDIA_LOCATION",
            "ACTIVITY_RECOGNITION",
            "ADD_VOICEMAIL",
            "ANSWER_PHONE_CALLS",
            "BLUETOOTH_ADVERTISE",
            "BLUETOOTH_CONNECT",
            "BLUETOOTH_SCAN",
            "BODY_SENSORS",
            "BODY_SENSORS_BACKGROUND",
            "CALL_PHONE",
            "CAMERA",
            "GET_ACCOUNTS",
            "NEARBY_WIFI_DEVICES",
            "POST_NOTIFICATIONS",
            "READ_CALENDAR",
            "READ_CALL_LOG",
            "READ_CONTACTS",
            "READ_EXTERNAL_STORAGE",
            "READ_MEDIA_AUDIO",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED",
            "READ_PHONE_NUMBERS",
            "READ_PHONE_STATE",
            "READ_SMS",
            "RECEIVE_MMS",
            "RECEIVE_SMS",
            "RECEIVE_WAP_PUSH",
            "RECORD_AUDIO",
            "SEND_SMS",
            "USE_SIP",
            "UWB_RANGING",
            "WRITE_CALENDAR",
            "WRITE_CALL_LOG",
            "WRITE_CONTACTS",
            "WRITE_EXTERNAL_STORAGE",
        ).mapTo(HashSet()) { "android.permission.$it" }

    /**
     * App ops that may be set to allow or foreground. Any op may be restricted (ignore, deny, default),
     * but ops that let an app draw over others, record the screen, install apps or change settings
     * cannot be opened up remotely.
     */
    val GRANTABLE_OPS =
        setOf(
            "ACTIVITY_RECOGNITION",
            "CAMERA",
            "COARSE_LOCATION",
            "FINE_LOCATION",
            "PICTURE_IN_PICTURE",
            "POST_NOTIFICATION",
            "READ_CLIPBOARD",
            "RECORD_AUDIO",
            "RUN_ANY_IN_BACKGROUND",
            "RUN_IN_BACKGROUND",
            "SCHEDULE_EXACT_ALARM",
            "START_FOREGROUND",
            "TURN_SCREEN_ON",
            "VIBRATE",
            "WAKE_LOCK",
        )

    fun checkRuntimePermission(name: String) {
        checkPermission(name)
        require(name in RUNTIME_PERMISSIONS) { "$name is not a runtime permission; only those can be granted or revoked" }
    }

    fun checkOpChange(
        op: String,
        mode: String,
    ) {
        checkOp(op)
        checkMode(mode)
        require(mode !in OPENING_MODES || op.removePrefix("android:").uppercase() in GRANTABLE_OPS) {
            "$op can only be restricted (ignore, deny or default), not set to $mode"
        }
    }

    private val OPENING_MODES = setOf("allow", "foreground")

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
