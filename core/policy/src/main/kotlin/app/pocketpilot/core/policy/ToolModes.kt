package app.pocketpilot.core.policy

import app.pocketpilot.core.model.RiskTier
import app.pocketpilot.core.model.ToolSpec

/** The owner's per-tool setting on the Tools screen. */
enum class ToolMode {
    /** Runs without asking; not offered for DESTRUCTIVE tools or shell.exec. */
    ALLOW,

    /** Asks once per session for each exact change; DESTRUCTIVE tools still ask on every call. */
    ASK,

    /** Hidden from tools/list and refused for every client. */
    BLOCK,
}

/** The owner's per-tool modes; null means the tool keeps its tier's default. */
fun interface ToolModes {
    fun modeFor(tool: String): ToolMode?

    companion object {
        /** Tools that never run without asking, whatever their tier. */
        val NEVER_ALLOW: Set<String> = setOf("shell.exec")

        /**
         * The modes the owner may choose for [spec]. The tier caps the choice, so a setting can never
         * weaken a DESTRUCTIVE tool or the raw shell.
         */
        fun allowedModes(spec: ToolSpec): Set<ToolMode> =
            if (spec.riskTier == RiskTier.DESTRUCTIVE || spec.name in NEVER_ALLOW) {
                setOf(ToolMode.ASK, ToolMode.BLOCK)
            } else {
                ToolMode.entries.toSet()
            }

        /** What [spec] does when the owner has not chosen: READ and INTERACT run, the rest ask. */
        fun defaultMode(spec: ToolSpec): ToolMode = if (spec.riskTier <= RiskTier.INTERACT) ToolMode.ALLOW else ToolMode.ASK

        /** The owner's mode for [spec] in [modes] if the tier permits it, else the tier default. */
        fun effectiveMode(
            modes: ToolModes?,
            spec: ToolSpec,
        ): ToolMode =
            modes
                ?.modeFor(spec.name)
                ?.takeIf { it in allowedModes(spec) }
                ?: defaultMode(spec)
    }
}
