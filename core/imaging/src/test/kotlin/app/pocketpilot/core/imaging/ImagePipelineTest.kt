package app.pocketpilot.core.imaging

import app.pocketpilot.capability.api.screen.Bounds
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ImageEncoder
import app.pocketpilot.capability.api.screen.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImagePipelineTest {
    private class RecordingEncoder : ImageEncoder {
        var frame: Frame? = null
        var size: Pair<Int, Int>? = null

        override fun encode(
            frame: Frame,
            width: Int,
            height: Int,
            format: ImageFormat,
            quality: Int,
        ): ByteArray {
            this.frame = frame
            size = width to height
            return byteArrayOf(1, 2, 3)
        }
    }

    private fun gradient(
        width: Int,
        height: Int,
    ) = Frame(width, height, IntArray(width * height) { i -> 0xFF000000.toInt() or ((i % width) * 255 / width) * 0x010101 })

    @Test
    fun `scales the long edge to maxEdge and reports the transform back to screen pixels`() {
        val encoder = RecordingEncoder()
        val image = ImagePipeline(encoder).process(gradient(1080, 2400), CaptureRequest(maxEdge = 1200))
        assertEquals(540 to 1200, encoder.size)
        assertEquals(2.0, image.transform.scaleX)
        assertEquals(2.0, image.transform.scaleY)
        assertEquals(0, image.transform.offsetX)
    }

    @Test
    fun `never upscales and never goes below 512 unless the screen is smaller`() {
        val encoder = RecordingEncoder()
        ImagePipeline(encoder).process(gradient(400, 300), CaptureRequest(maxEdge = 2000))
        assertEquals(400 to 300, encoder.size)
        ImagePipeline(encoder).process(gradient(1000, 2000), CaptureRequest(maxEdge = 100))
        assertEquals(256 to 512, encoder.size)
    }

    @Test
    fun `crops to a region and offsets the transform`() {
        val encoder = RecordingEncoder()
        val image = ImagePipeline(encoder).process(gradient(1000, 1000), CaptureRequest(region = Bounds(100, 200, 700, 600)))
        assertEquals(600, encoder.frame!!.width)
        assertEquals(400, encoder.frame!!.height)
        assertEquals(100, image.transform.offsetX)
        assertEquals(200, image.transform.offsetY)
    }

    @Test
    fun `paints redacted boxes black`() {
        val encoder = RecordingEncoder()
        val frame = Frame(10, 10, IntArray(100) { 0xFFFFFFFF.toInt() })
        ImagePipeline(encoder).process(frame, CaptureRequest(redact = listOf(Bounds(2, 2, 4, 4))))
        val out = encoder.frame!!.pixels
        assertEquals(0xFF000000.toInt(), out[2 * 10 + 2])
        assertEquals(0xFF000000.toInt(), out[3 * 10 + 3])
        assertEquals(0xFFFFFFFF.toInt(), out[4 * 10 + 4])
        assertEquals(0xFFFFFFFF.toInt(), frame.pixels[2 * 10 + 2], "the captured frame itself is untouched")
    }

    @Test
    fun `dhash is stable for the same picture and differs for a different one`() {
        val a = DHash.of(gradient(300, 600))
        assertEquals(a, DHash.of(gradient(300, 600)))
        val flipped = Frame(300, 600, IntArray(300 * 600) { i -> 0xFF000000.toInt() or ((299 - i % 300) * 255 / 300) * 0x010101 })
        assertNotEquals(a, DHash.of(flipped))
        assertTrue(DHash.distance(a, DHash.of(flipped)) > 32)
    }
}
