package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.apps.ActivityEntry

/** Reads `dumpsys package` and `appops get` output. */
internal object AppAdminParsers {
    /**
     * Shortens `dumpsys package <pkg>` to [max] characters. Big system apps such as Settings dump more than
     * that, mostly resolver tables, so the head (activities) and the "Packages:" section (the install
     * record and permissions) each keep up to half.
     */
    fun trimDump(
        dump: String,
        max: Int,
    ): String {
        if (dump.length <= max) return dump
        val packages = dump.indexOf("\nPackages:\n")
        if (packages < 0) return dump.take(max)
        val tail = dump.substring(packages + 1).take(max / 2)
        return dump.take(minOf(packages, max - tail.length - 1)) + "\n" + tail
    }

    private val GRANT = Regex("^\\s+([A-Za-z0-9_.]+): granted=(true|false)")
    private val OP = Regex("^\\s*(?:Uid mode: )?([A-Z][A-Z0-9_]+): ([a-z]+)")

    /** Runtime permissions from `dumpsys package <pkg>`, for the first user listed. */
    fun runtimePermissions(dump: String): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        var headerIndent = -1
        for (line in dump.lineSequence()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (headerIndent >= 0) {
                if (line.isBlank() || indent <= headerIndent) {
                    if (result.isNotEmpty()) break
                    headerIndent = -1
                } else {
                    GRANT.find(line)?.let { result.putIfAbsent(it.groupValues[1], it.groupValues[2] == "true") }
                    continue
                }
            }
            if (line.trim() == "runtime permissions:") headerIndent = indent
        }
        return result
    }

    /** App op modes from `appops get <pkg>`; package modes win over uid modes. */
    fun appOps(output: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in output.lineSequence()) {
            val match = OP.find(line) ?: continue
            val (op, mode) = match.destructured
            if (line.trimStart().startsWith("Uid mode:")) result.putIfAbsent(op, mode) else result[op] = mode
        }
        return result
    }

    private val COMPONENT = Regex("^\\s+[0-9a-f]+ ([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)")
    private val FILTER_LINE = Regex("^\\s+(Action|Category|Scheme): \"([^\"]+)\"")

    /** Activities with intent filters, from the "Activity Resolver Table" of `dumpsys package <pkg>`. */
    fun activities(
        dump: String,
        packageName: String,
    ): List<ActivityEntry> {
        val found = LinkedHashMap<String, Triple<MutableSet<String>, MutableSet<String>, MutableSet<String>>>()
        var inTable = false
        var current: String? = null
        for (line in dump.lineSequence()) {
            if (line.startsWith("Activity Resolver Table:")) {
                inTable = true
                continue
            }
            if (!inTable) continue
            if (line.isNotEmpty() && !line[0].isWhitespace()) break
            val component = COMPONENT.find(line)?.groupValues?.get(1)
            if (component != null) {
                current = component.takeIf { it.startsWith("$packageName/") }
                current?.let { found.getOrPut(it) { Triple(LinkedHashSet(), LinkedHashSet(), LinkedHashSet()) } }
                continue
            }
            val entry = current?.let(found::get) ?: continue
            val match = FILTER_LINE.find(line) ?: continue
            val (kind, value) = match.destructured
            when (kind) {
                "Action" -> entry.first += value
                "Category" -> entry.second += value
                else -> entry.third += value
            }
        }
        return found.map { (component, sets) -> ActivityEntry(component, sets.first.toList(), sets.second.toList(), sets.third.toList()) }
    }
}
