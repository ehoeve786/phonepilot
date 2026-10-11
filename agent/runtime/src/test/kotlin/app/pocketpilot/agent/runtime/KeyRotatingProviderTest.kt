package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelInfo
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.api.ModelRequest
import app.pocketpilot.agent.providers.api.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyRotatingProviderTest {
    private class Key(
        val name: String,
        var limited: Boolean,
    ) : ModelProvider {
        var calls = 0
        override val id = "gemini"
        override val displayName = "Gemini"

        override suspend fun listModels() = listOf(ModelInfo(name))

        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            calls++
            return if (limited) {
                flowOf(ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "429 from $name", retryAfterMs = 1_000))
            } else {
                flowOf(ModelEvent.TextDelta(name), ModelEvent.Stop(StopReason.END_TURN))
            }
        }
    }

    private val request = ModelRequest(model = "m", system = "s", messages = emptyList())

    @Test
    fun rateLimitedKeyHandsOverToTheNextAndStaysThere() =
        runTest {
            val a = Key("a", limited = true)
            val b = Key("b", limited = false)
            val provider = KeyRotatingProvider(listOf(a, b))

            val first = provider.stream(request).toList()
            assertEquals(listOf(ModelEvent.TextDelta("b"), ModelEvent.Stop(StopReason.END_TURN)), first)

            provider.stream(request).toList()
            assertEquals(1, a.calls)
            assertEquals(2, b.calls)
        }

    @Test
    fun whenEveryKeyIsLimitedTheErrorIsPassedOn() =
        runTest {
            val provider = KeyRotatingProvider(listOf(Key("a", true), Key("b", true)))

            val events = provider.stream(request).toList()

            assertEquals(1, events.size)
            assertEquals(ModelErrorCode.RATE_LIMIT, (events.single() as ModelEvent.Error).code)
        }

    @Test
    fun otherErrorsAreNotRetriedOnAnotherKey() =
        runTest {
            val broken =
                object : ModelProvider {
                    override val id = "gemini"
                    override val displayName = "Gemini"

                    override suspend fun listModels() = emptyList<ModelInfo>()

                    override fun stream(request: ModelRequest): Flow<ModelEvent> =
                        flowOf(ModelEvent.Error(ModelErrorCode.INVALID_REQUEST, "bad"))
                }
            val spare = Key("b", false)

            val events = KeyRotatingProvider(listOf(broken, spare)).stream(request).toList()

            assertEquals(ModelErrorCode.INVALID_REQUEST, (events.single() as ModelEvent.Error).code)
            assertEquals(0, spare.calls)
        }
}
