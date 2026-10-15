package app.pocketpilot.network.wireguard

/**
 * The host of the first `Endpoint` line in a WireGuard config, without the port, or null when there is none.
 * An IPv6 endpoint is written in brackets, as `[v6]:port`.
 */
internal fun serverHostOf(config: String): String? {
    val endpoint =
        config
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?: return null
    return if (endpoint.startsWith("[")) {
        endpoint.substringAfter('[').substringBefore(']')
    } else {
        endpoint.substringBeforeLast(':')
    }
}
