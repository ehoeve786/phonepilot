package app.pocketpilot.capability.shizuku

import app.pocketpilot.capability.api.screen.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes raw `screencap` output: little-endian width, height and pixel format (plus a colour space
 * word on newer Android), then RGBA_8888 rows. Raw avoids a PNG encode and decode on every capture.
 */
internal object RawScreencap {
    private const val RGBA_8888 = 1
    private const val RGBX_8888 = 2

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size >= 12) { "screencap returned ${bytes.size} bytes" }
        val header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val format = header.int
        require(width > 0 && height > 0) { "screencap returned a ${width}x$height screen" }
        require(format == RGBA_8888 || format == RGBX_8888) { "Unsupported screencap pixel format $format" }
        val pixelBytes = width.toLong() * height * 4
        val offset = bytes.size - pixelBytes
        require(offset == 12L || offset == 16L) { "screencap returned ${bytes.size} bytes for ${width}x$height" }
        val start = offset.toInt()
        val opaque = format == RGBX_8888
        val pixels =
            IntArray(width * height) { i ->
                val p = start + i * 4
                val r = bytes[p].toInt() and 0xFF
                val g = bytes[p + 1].toInt() and 0xFF
                val b = bytes[p + 2].toInt() and 0xFF
                val a = if (opaque) 0xFF else bytes[p + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        return Frame(width, height, pixels)
    }
}
