package app.pocketpilot.agent.providers.api

/** Maps a provider's HTTP failure to a [ModelEvent.Error], the same way for every adapter. */
object ModelErrors {
    private val CONTEXT_HINTS =
        listOf("context length", "context_length", "too long", "maximum context", "too many tokens", "prompt is too long")

    fun fromHttp(
        status: Int,
        body: String,
        retryAfterSeconds: String? = null,
    ): ModelEvent.Error {
        val message = "HTTP $status: ${body.take(MAX_MESSAGE)}"
        val code =
            when {
                status == 401 || status == 403 -> ModelErrorCode.AUTH
                status == 429 -> ModelErrorCode.RATE_LIMIT
                status == 413 -> ModelErrorCode.CONTEXT_TOO_LONG
                status in 400..499 && CONTEXT_HINTS.any { body.contains(it, ignoreCase = true) } -> ModelErrorCode.CONTEXT_TOO_LONG
                status in 400..499 -> ModelErrorCode.INVALID_REQUEST
                else -> ModelErrorCode.SERVER
            }
        val retryAfterMs = retryAfterSeconds?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        return ModelEvent.Error(code, message, retryAfterMs.takeIf { code == ModelErrorCode.RATE_LIMIT })
    }

    fun network(e: Throwable): ModelEvent.Error =
        ModelEvent.Error(ModelErrorCode.NETWORK, e.message ?: e::class.simpleName ?: "network error")

    private const val MAX_MESSAGE = 500
}
