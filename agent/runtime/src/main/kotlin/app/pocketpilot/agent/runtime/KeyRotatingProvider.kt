package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

/**
 * One provider with several API keys, one [ModelProvider] per key. When a key hits its rate limit,
 * runs out of credit or is refused, the request goes to the next key, and later requests start from that key. Only when
 * every key fails is the error passed on, so the runner's own retry and wait apply then.
 *
 * With [roundRobin], every request starts on the next key instead of the one that worked last, which spreads
 * load so no single free-tier key reaches its per-minute limit first.
 */
class KeyRotatingProvider(
    private val keys: List<ModelProvider>,
    private val roundRobin: Boolean = false,
) : ModelProvider {
    init {
        require(keys.isNotEmpty()) { "At least one key is needed" }
    }

    private val current = AtomicInteger(0)

    override val id: String get() = keys.first().id

    override val displayName: String get() = keys.first().displayName

    override suspend fun listModels(): List<ModelInfo> = keys[current.get()].listModels()

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            var last: ModelEvent.Error? = null
            val first = if (roundRobin) Math.floorMod(current.getAndIncrement(), keys.size) else current.get()
            for (tried in keys.indices) {
                val index = (first + tried) % keys.size
                last = null
                // A provider emits either a full reply or a single error, so passing events on at once is safe.
                keys[index].stream(request).collect { event ->
                    if (event is ModelEvent.Error && event.code in SWITCH_ON) last = event else emit(event)
                }
                if (last == null) return@flow
                if (!roundRobin) current.compareAndSet(index, (index + 1) % keys.size)
            }
            last?.let { emit(it) }
        }

    private companion object {
        val SWITCH_ON = setOf(ModelErrorCode.RATE_LIMIT, ModelErrorCode.QUOTA, ModelErrorCode.AUTH)
    }
}
