package app.pocketpilot.capability.api.apps

import kotlinx.serialization.Serializable

/** One extra on an intent: [type] is `string`, `int`, `long`, `bool` or `float`. */
data class IntentExtra(
    val key: String,
    val type: String,
    val value: String,
)

/** An activity an app exposes to other apps, with the intent filters it answers. */
@Serializable
data class ActivityEntry(
    /** `package/class`, ready for `component`. */
    val component: String,
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
)

/**
 * Arguments for `am start` and `am broadcast`, built by the tool and checked again by the privileged
 * service. Every intent names its app ([component] or package), so policy can refuse apps on the deny
 * list, and actions that place calls, install or remove apps or wipe the phone are refused.
 */
object IntentArgs {
    val COMPONENT = Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/([A-Za-z0-9_.$]+)$")
    private val NAME = Regex("^[A-Za-z0-9_.$:\\-]{1,200}$")
    private val EXTRA_FLAGS = mapOf("string" to "--es", "int" to "--ei", "long" to "--el", "bool" to "--ez", "float" to "--ef")
    private val MIME = Regex("^[A-Za-z0-9*.+\\-]+/[A-Za-z0-9*.+\\-]+$")
    private const val MAX_VALUE = 2_000

    /**
     * Schemes that would hand the target a file, start another component or run script. Other schemes,
     * such as an app's own links, stay allowed.
     */
    val DENIED_DATA_SCHEMES =
        setOf("file", "content", "intent", "android-app", "javascript", "data", "blob", "jar", "about")

    val REFUSED_ACTIONS =
        setOf(
            "android.intent.action.CALL",
            "android.intent.action.CALL_PRIVILEGED",
            "android.intent.action.CALL_EMERGENCY",
            "android.intent.action.DELETE",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.INSTALL_PACKAGE",
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.REBOOT",
            "android.intent.action.ACTION_SHUTDOWN",
            "com.android.internal.intent.action.REQUEST_SHUTDOWN",
            "android.app.action.ADD_DEVICE_ADMIN",
            "android.settings.ACTION_MANAGE_OVERLAY_PERMISSION",
        )

    /** Builds the arguments after `am start` or `am broadcast`. */
    fun build(
        component: String?,
        packageName: String?,
        action: String?,
        data: String?,
        mimeType: String?,
        categories: List<String>,
        extras: List<IntentExtra>,
    ): List<String> =
        buildList {
            component?.let {
                add("-n")
                add(it)
            }
            if (component == null) {
                packageName?.let {
                    add("-p")
                    add(it)
                }
            }
            action?.let {
                add("-a")
                add(it)
            }
            data?.let {
                add("-d")
                add(it)
            }
            mimeType?.let {
                add("-t")
                add(it)
            }
            categories.forEach {
                add("-c")
                add(it)
            }
            extras.forEach { extra ->
                add(EXTRA_FLAGS[extra.type] ?: throw IllegalArgumentException("Extra type must be one of ${EXTRA_FLAGS.keys}"))
                add(extra.key)
                add(extra.value)
            }
        }.also(::check)

    /** The app an intent built by [build] goes to. */
    fun targetPackage(args: List<String>): String? {
        val component = args.valueAfter("-n")
        return component?.substringBefore('/') ?: args.valueAfter("-p")
    }

    /** Throws [IllegalArgumentException] unless [args] is a safe intent built by [build]. */
    fun check(args: List<String>) {
        var i = 0
        while (i < args.size) {
            val flag = args[i]
            val value = args.getOrNull(i + 1) ?: throw IllegalArgumentException("$flag needs a value")
            when (flag) {
                "-n" -> {
                    require(COMPONENT.matches(value)) { "component must look like package/class: $value" }
                }

                "-p" -> {
                    AppAdminArgs.checkPackage(value)
                }

                "-a" -> {
                    require(NAME.matches(value)) { "Not an action name: $value" }
                    require(value !in REFUSED_ACTIONS) { "$value is not allowed" }
                }

                "-c" -> {
                    require(NAME.matches(value)) { "Not a category name: $value" }
                }

                "-t" -> {
                    require(MIME.matches(value)) { "Not a MIME type: $value" }
                }

                "-d" -> {
                    require(
                        value.length <= MAX_VALUE && value.none { it == '\u0000' || it == '\n' },
                    ) { "data is too long or has line breaks" }
                    val scheme = value.substringBefore(':').lowercase()
                    require(scheme !in DENIED_DATA_SCHEMES) { "$scheme: links are not opened, since they reach files or start components" }
                }

                in EXTRA_FLAGS.values -> {
                    require(NAME.matches(value)) { "Not an extra key: $value" }
                    val extra = args.getOrNull(i + 2) ?: throw IllegalArgumentException("Extra $value needs a value")
                    require(extra.length <= MAX_VALUE && '\u0000' !in extra) { "Extra $value is too long" }
                    i++
                }

                else -> {
                    throw IllegalArgumentException("Unexpected intent argument: $flag")
                }
            }
            i += 2
        }
        val pkg = targetPackage(args) ?: throw IllegalArgumentException("Name the app: give component or package")
        AppAdminArgs.checkPackage(pkg)
        AppAdminArgs.checkNotProtected(pkg)
    }

    private fun List<String>.valueAfter(flag: String): String? {
        var i = 0
        while (i < size - 1) {
            when (this[i]) {
                flag -> return this[i + 1]
                in EXTRA_FLAGS.values -> i += 3
                else -> i += 2
            }
        }
        return null
    }
}
