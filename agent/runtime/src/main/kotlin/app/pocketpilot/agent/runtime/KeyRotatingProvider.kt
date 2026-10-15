package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.core.common.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * One provider with several API keys, one [ModelProvider] per key. When a key hits its rate limit,
 * runs out of credit or is refused, the request goes to the next key, and later requests start from that key. Only when
 * every key fails is the error passed on, so the runner's own retry and wait apply then.
 *
 * A key that failed is left alone for a while: a rate limit cools down for the server's `Retry-After` (or a minute) and
 * doubles on each limit in a row, up to ten minutes; a spent quota waits an hour; a refused key is not tried again for
 * the life of this provider. When every key is cooling down, the request still goes out, so this never fails a run by itself.
 *
 * With [roundRobin], every request starts on the next key instead of the one that worked last, which spreads
 * load so no single free-tier key reaches its per-minute limit first.
 */
class KeyRotatingProvider(
    private val keys: List<ModelProvider>,
    private val roundRobin: Boolean = false,
    private val clock: Clock = Clock.System,
) : ModelProvider {
    init {
        require(keys.isNotEmpty()) { "At least one key is needed" }
    }

    private val current = AtomicInteger(0)

    /** When each key may be tried again, by index; [REFUSED] for a refused key. */
    private val cooldownUntil = ConcurrentHashMap<Int, Long>()

    /** Rate limits in a row per key, which double its cooldown. */
    private val limitStreak = ConcurrentHashMap<Int, Int>()

    override val id: String get() = keys.first().id

    override val displayName: String get() = keys.first().displayName

    override suspend fun listModels(): List<ModelInfo> = keys[current.get()].listModels()

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            var last: ModelEvent.Error? = null
            val first = if (roundRobin) Math.floorMod(current.getAndIncrement(), keys.size) else current.get()
            val order = keys.indices.map { (first + it) % keys.size }
            val now = clock.nowMillis()
            val usable = order.filter { (cooldownUntil[it] ?: 0L) <= now }.ifEmpty { order }
            for (index in usable) {
                last = null
                // A provider emits either a full reply or a single error, so passing events on at once is safe.
                keys[index].stream(request).collect { event ->
                    if (event is ModelEvent.Error && event.code in SWITCH_ON) last = event else emit(event)
                }
                val failure = last
                if (failure == null) {
                    cooldownUntil.remove(index)
                    limitStreak.remove(index)
                    return@flow
                }
                cool(index, failure)
                if (!roundRobin) current.compareAndSet(index, (index + 1) % keys.size)
            }
            last?.let { emit(it) }
        }

    /** Leaves [index] alone for as long as its error calls for. */
    private fun cool(
        index: Int,
        failure: ModelEvent.Error,
    ) {
        val now = clock.nowMillis()
        cooldownUntil[index] =
            when (failure.code) {
                ModelErrorCode.RATE_LIMIT -> {
                    val streak = limitStreak.merge(index, 1) { a, b -> a + b } ?: 1
                    val base = failure.retryAfterMs ?: DEFAULT_COOLDOWN_MS
                    now + min(base shl min(streak - 1, MAX_DOUBLINGS), MAX_COOLDOWN_MS)
                }

                ModelErrorCode.QUOTA -> {
                    now + QUOTA_COOLDOWN_MS
                }

                else -> {
                    REFUSED
                }
            }
    }

    private companion object {
        val SWITCH_ON = setOf(ModelErrorCode.RATE_LIMIT, ModelErrorCode.QUOTA, ModelErrorCode.AUTH)
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 10 * 60_000L
        const val QUOTA_COOLDOWN_MS = 60 * 60_000L
        const val MAX_DOUBLINGS = 10
        const val REFUSED = Long.MAX_VALUE
    }
}
