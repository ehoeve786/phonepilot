package app.pocketpilot.capability.screencapture

import android.graphics.Bitmap
import app.pocketpilot.capability.api.screen.Frame
import app.pocketpilot.capability.api.screen.ImageEncoder
import app.pocketpilot.capability.api.screen.ImageFormat
import java.io.ByteArrayOutputStream

/** Scales with bilinear filtering and encodes with the platform codecs. */
class BitmapImageEncoder : ImageEncoder {
    override fun encode(
        frame: Frame,
        width: Int,
        height: Int,
        format: ImageFormat,
        quality: Int,
    ): ByteArray {
        val source = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val scaled = if (width == frame.width && height == frame.height) source else Bitmap.createScaledBitmap(source, width, height, true)
        val out = ByteArrayOutputStream()
        val compressFormat =
            when (format) {
                ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
                ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            }
        check(scaled.compress(compressFormat, quality, out)) { "Encoding $format failed" }
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return out.toByteArray()
    }
}
