package app.pocketpilot.agent.providers.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelErrorsTest {
    @Test
    fun `429 is a rate limit and keeps the wait the server asked for`() {
        val error = ModelErrors.fromHttp(429, """{"error":{"message":"Quota exceeded"}}""", retryAfterSeconds = "2.5")
        assertEquals(ModelErrorCode.RATE_LIMIT, error.code)
        assertEquals(2_500L, error.retryAfterMs)
        assertTrue(error.retryable)
    }

    @Test
    fun `a rate limit named in the body counts whatever the status`() {
        for (status in listOf(400, 500, 503)) {
            for (body in listOf("Rate limit reached for model", "rate_limit_exceeded", "RESOURCE_EXHAUSTED", "Too Many Requests")) {
                assertEquals(ModelErrorCode.RATE_LIMIT, ModelErrors.fromHttp(status, body).code, "$status $body")
            }
        }
    }

    @Test
    fun `refused keys and exhausted credit are not mistaken for a rate limit`() {
        assertEquals(ModelErrorCode.AUTH, ModelErrors.fromHttp(401, "rate limit headers missing").code)
        assertEquals(ModelErrorCode.QUOTA, ModelErrors.fromHttp(402, "payment required").code)
        assertEquals(ModelErrorCode.INVALID_REQUEST, ModelErrors.fromHttp(400, "unknown parameter").code)
        assertEquals(ModelErrorCode.SERVER, ModelErrors.fromHttp(503, "model overloaded").code)
        assertNull(ModelErrors.fromHttp(500, "boom", retryAfterSeconds = "3").retryAfterMs)
    }

    @Test
    fun `a timeout or dropped connection is a network error`() {
        val error = ModelErrors.network(java.net.SocketTimeoutException("timeout"))
        assertEquals(ModelErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
    }
}
