package app.pocketpilot.core.imaging

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ImageEncoder
import app.pocketpilot.capability.api.screen.ImageFormat
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/** How to turn a captured frame into an image for a client (spec section 6, Screen capture pipeline). */
data class CaptureRequest(
    val maxEdge: Int = DEFAULT_MAX_EDGE,
    val format: ImageFormat = ImageFormat.WEBP,
    val quality: Int = DEFAULT_QUALITY,
    /** Crop to this part of the screen first. */
    val region: Bounds? = null,
    /** Painted black before encoding, in screen pixels (password fields by default). */
    val redact: List<Bounds> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_EDGE = 1280
        const val MIN_MAX_EDGE = 512
        const val DEFAULT_QUALITY = 80
        const val MIN_QUALITY = 30
        const val MAX_QUALITY = 100
    }
}

/** Maps image pixels back to screen pixels: `screenX = offsetX + imageX * scaleX`. */
@Serializable
data class Transform(
    val scaleX: Double,
    val scaleY: Double,
    val offsetX: Int,
    val offsetY: Int,
    val rotation: Int = 0,
)

class EncodedImage(
    val bytes: ByteArray,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val transform: Transform,
    /** 64-bit difference hash, for change detection and audit. */
    val hash: Long,
)

/** Crop, redact, hash, resize and encode. Pure Kotlin apart from the [encoder]. */
class ImagePipeline(
    private val encoder: ImageEncoder,
) {
    fun process(
        frame: Frame,
        request: CaptureRequest = CaptureRequest(),
    ): EncodedImage {
        val region = request.region?.clampTo(frame) ?: Bounds(0, 0, frame.width, frame.height)
        require(!region.isEmpty) { "Region is outside the screen" }
        val cropped = crop(frame, region)
        val redacted = redact(cropped, request.redact.map { it.offset(-region.left, -region.top) })

        val longEdge = max(redacted.width, redacted.height)
        val target =
            request.maxEdge
                .coerceIn(
                    CaptureRequest.MIN_MAX_EDGE,
                    max(longEdge, CaptureRequest.MIN_MAX_EDGE),
                ).coerceAtMost(longEdge)
        val scale = target.toDouble() / longEdge
        val width = (redacted.width * scale).roundToInt().coerceAtLeast(1)
        val height = (redacted.height * scale).roundToInt().coerceAtLeast(1)
        val quality = request.quality.coerceIn(CaptureRequest.MIN_QUALITY, CaptureRequest.MAX_QUALITY)

        return EncodedImage(
            bytes = encoder.encode(redacted, width, height, request.format, quality),
            format = request.format,
            width = width,
            height = height,
            transform =
                Transform(
                    scaleX = redacted.width.toDouble() / width,
                    scaleY = redacted.height.toDouble() / height,
                    offsetX = region.left,
                    offsetY = region.top,
                ),
            hash = DHash.of(redacted),
        )
    }

    private fun crop(
        frame: Frame,
        region: Bounds,
    ): Frame {
        if (region.left == 0 && region.top == 0 && region.width == frame.width && region.height == frame.height) return frame
        val pixels = IntArray(region.width * region.height)
        for (row in 0 until region.height) {
            System.arraycopy(frame.pixels, (region.top + row) * frame.width + region.left, pixels, row * region.width, region.width)
        }
        return Frame(region.width, region.height, pixels)
    }

    private fun redact(
        frame: Frame,
        boxes: List<Bounds>,
    ): Frame {
        val visible = boxes.mapNotNull { it.clampTo(frame) }.filterNot(Bounds::isEmpty)
        if (visible.isEmpty()) return frame
        val pixels = frame.pixels.copyOf()
        for (box in visible) {
            for (y in box.top until box.bottom) {
                pixels.fill(OPAQUE_BLACK, y * frame.width + box.left, y * frame.width + box.right)
            }
        }
        return Frame(frame.width, frame.height, pixels)
    }

    private fun Bounds.clampTo(frame: Frame): Bounds? {
        val clamped =
            Bounds(
                left.coerceIn(0, frame.width),
                top.coerceIn(0, frame.height),
                right.coerceIn(0, frame.width),
                bottom.coerceIn(0, frame.height),
            )
        return clamped.takeUnless(Bounds::isEmpty)
    }

    private fun Bounds.offset(
        dx: Int,
        dy: Int,
    ) = Bounds(left + dx, top + dy, right + dx, bottom + dy)

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
