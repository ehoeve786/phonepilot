package app.pocketpilot.capability.shizuku

/** Reads `dumpsys package` and `appops get` output. */
internal object AppAdminParsers {
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
}
