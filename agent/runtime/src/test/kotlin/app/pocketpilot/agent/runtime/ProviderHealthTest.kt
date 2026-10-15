package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.ModelErrorCode
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.core.common.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderHealthTest {
    private var now = 1_000L
    private val health = ProviderHealth { now }

    @Test
    fun `a rate limit waits as long as the server said, or a minute`() {
        health.failed("Gemini", ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "429", retryAfterMs = 12_000))
        health.failed("Groq", ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "429"))

        assertEquals(
            13_000L,
            health.status.value
                .getValue("Gemini")
                .retryAtMillis,
        )
        assertEquals(
            61_000L,
            health.status.value
                .getValue("Groq")
                .retryAtMillis,
        )
        assertEquals(
            ProviderState.LIMITED,
            health.status.value
                .getValue("Groq")
                .state,
        )
    }

    @Test
    fun `used up credit is limited without a promised time, other errors mean down`() {
        health.failed("OpenRouter", ModelEvent.Error(ModelErrorCode.QUOTA, "402"))
        health.failed("Mistral", ModelEvent.Error(ModelErrorCode.NETWORK, "timeout"))

        assertEquals(
            ProviderState.LIMITED,
            health.status.value
                .getValue("OpenRouter")
                .state,
        )
        assertNull(
            health.status.value
                .getValue("OpenRouter")
                .retryAtMillis,
        )
        assertEquals(
            ProviderState.DOWN,
            health.status.value
                .getValue("Mistral")
                .state,
        )
        assertEquals(
            ModelErrorCode.NETWORK,
            health.status.value
                .getValue("Mistral")
                .error,
        )
    }

    @Test
    fun `an answer clears an earlier failure`() {
        health.failed("Gemini", ModelEvent.Error(ModelErrorCode.RATE_LIMIT, "429"))
        now = 70_000L
        health.succeeded("Gemini")

        val status = health.status.value.getValue("Gemini")
        assertEquals(ProviderState.READY, status.state)
        assertEquals(70_000L, status.atMillis)
        assertNull(status.retryAtMillis)
    }
}
