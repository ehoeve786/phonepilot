package app.pocketpilot.server.oauth

import app.pocketpilot.core.common.Clock

/**
 * Caps how often one address may call an endpoint that needs no token, such as registration and
 * authorize, so nobody on the internet can flood the owner with sign-in prompts.
 */
class RequestLimit(
    private val maxRequests: Int,
    private val windowMs: Long,
    private val clock: Clock = Clock.System,
) {
    private val recent = HashMap<String, ArrayDeque<Long>>()

    /** Counts one request from [address]; false when it is over the limit. */
    @Synchronized
    fun tryAcquire(address: String): Boolean {
        val now = clock.nowMillis()
        recent.values.forEach { times -> while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst() }
        recent.values.removeAll { it.isEmpty() }
        val times = recent.getOrPut(address) { ArrayDeque() }
        if (times.size >= maxRequests) return false
        times.addLast(now)
        return true
    }
}
