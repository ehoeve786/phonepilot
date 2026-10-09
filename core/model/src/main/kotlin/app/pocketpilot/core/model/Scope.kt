package app.pocketpilot.core.model

import kotlinx.serialization.Serializable

/**
 * An OAuth scope in `namespace:level` form, for example `ui:interact` (spec section 8).
 * Unknown scopes are allowed so plugins can define their own; [KNOWN] lists the core set.
 */
@Serializable
@JvmInline
value class Scope(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Scope must look like namespace:level, got '$value'" }
    }

    val namespace: String get() = value.substringBefore(':')
    val level: String get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$")

        val SCREEN_READ = Scope("screen:read")
        val UI_INTERACT = Scope("ui:interact")
        val APPS_READ = Scope("apps:read")
        val APPS_CONTROL = Scope("apps:control")
        val DEVICE_READ = Scope("device:read")
        val CLIPBOARD_RW = Scope("clipboard:rw")
        val NOTIFY_READ = Scope("notify:read")
        val NOTIFY_ACT = Scope("notify:act")
        val SETTINGS_WRITE = Scope("settings:write")
        val FILES_READ = Scope("files:read")
        val FILES_WRITE = Scope("files:write")
        val TASK_RUN = Scope("task:run")
        val SHELL_EXEC = Scope("shell:exec")
        val TERMUX_RUN = Scope("termux:run")

        val KNOWN: Set<Scope> =
            setOf(
                SCREEN_READ,
                UI_INTERACT,
                APPS_READ,
                APPS_CONTROL,
                DEVICE_READ,
                CLIPBOARD_RW,
                NOTIFY_READ,
                NOTIFY_ACT,
                SETTINGS_WRITE,
                FILES_READ,
                FILES_WRITE,
                TASK_RUN,
                SHELL_EXEC,
                TERMUX_RUN,
            )

        /** Parses a space-separated OAuth scope string. */
        fun parseList(raw: String): Set<Scope> =
            raw
                .split(' ')
                .filter(String::isNotBlank)
                .map(::Scope)
                .toSet()
    }
}
