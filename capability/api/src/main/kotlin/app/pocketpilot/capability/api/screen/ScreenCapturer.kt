package app.pocketpilot.capability.api.screen

import kotlinx.coroutines.flow.StateFlow

/** A captured screen as ARGB pixels, row by row. */
class Frame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }
}

/** CAPTURE_SCREEN. Throws ToolException PROTECTED_SCREEN for secure windows. */
interface ScreenCapturer {
    val available: StateFlow<Boolean>

    suspend fun capture(): Frame
}

enum class ImageFormat(
    val mimeType: String,
) {
    WEBP("image/webp"),
    JPEG("image/jpeg"),
    PNG("image/png"),
}

/** Scales and encodes a frame. Android does this with Bitmap; tests use a fake. */
fun interface ImageEncoder {
    fun encode(
        frame: Frame,
        width: Int,
        height: Int,
        format: ImageFormat,
        quality: Int,
    ): ByteArray
}
