package app.pocketpilot.core.imaging

import app.pocketpilot.capability.api.screen.Frame

/**
 * 64-bit difference hash: the frame shrunk to 9x8 grey cells, one bit per horizontal neighbour pair.
 * Similar screens give hashes a few bits apart.
 */
object DHash {
    private const val COLUMNS = 9
    private const val ROWS = 8

    /** Pixels sampled per cell edge; enough for a stable average without reading every pixel. */
    private const val SAMPLES = 6

    fun of(frame: Frame): Long {
        val cells = DoubleArray(COLUMNS * ROWS)
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                cells[row * COLUMNS + column] = cellLuminance(frame, column, row)
            }
        }
        var hash = 0L
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS - 1) {
                hash = hash shl 1
                if (cells[row * COLUMNS + column] < cells[row * COLUMNS + column + 1]) hash = hash or 1L
            }
        }
        return hash
    }

    fun distance(
        a: Long,
        b: Long,
    ): Int = java.lang.Long.bitCount(a xor b)

    private fun cellLuminance(
        frame: Frame,
        column: Int,
        row: Int,
    ): Double {
        val left = column * frame.width / COLUMNS
        val right = maxOf(left + 1, (column + 1) * frame.width / COLUMNS)
        val top = row * frame.height / ROWS
        val bottom = maxOf(top + 1, (row + 1) * frame.height / ROWS)
        var total = 0.0
        var count = 0
        for (sy in 0 until SAMPLES) {
            val y = (top + (bottom - top) * sy / SAMPLES).coerceAtMost(frame.height - 1)
            for (sx in 0 until SAMPLES) {
                val x = (left + (right - left) * sx / SAMPLES).coerceAtMost(frame.width - 1)
                total += luminance(frame.pixels[y * frame.width + x])
                count++
            }
        }
        return total / count
    }

    private fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
