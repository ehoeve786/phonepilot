package app.pocketpilot.core.policy

import app.pocketpilot.core.common.Clock

/** Sliding one-minute window of calls per key (spec section 8: 120 tool calls a minute per session). */
class RateLimiter(
    private val perMinute: Int,
    private val clock: Clock = Clock.System,
) {
    private val calls = HashMap<String, ArrayDeque<Long>>()

    /** Records a call for [key] and returns false when it is over the limit. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.nowMillis()
        val window = calls.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= WINDOW_MS) window.removeFirst()
        if (window.size >= perMinute) return false
        window.addLast(now)
        return true
    }

    @Synchronized
    fun forget(key: String) {
        calls.remove(key)
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
