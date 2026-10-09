package app.pocketpilot.core.common

import java.security.SecureRandom

/**
 * Generates ULIDs: 48-bit millisecond timestamp plus 80 random bits, Crockford base32, 26 chars.
 * Every persisted record has one (spec conventions). Lexicographic order follows creation time.
 */
class UlidGenerator(
    private val clock: Clock = Clock.System,
    private val random: java.util.Random = SecureRandom(),
) {
    fun next(): String {
        val time = clock.nowMillis()
        require(time in 0..MAX_TIME) { "Timestamp out of ULID range: $time" }
        val chars = CharArray(LENGTH)
        var t = time
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t shr 5
        }
        for (i in TIME_CHARS until LENGTH) {
            chars[i] = ALPHABET[random.nextInt(32)]
        }
        return String(chars)
    }

    companion object {
        const val LENGTH = 26
        private const val TIME_CHARS = 10
        private const val MAX_TIME = (1L shl 48) - 1
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Milliseconds since the epoch encoded in [ulid]. */
        fun timestampOf(ulid: String): Long {
            require(ulid.length == LENGTH) { "ULID must be $LENGTH characters" }
            return ulid.take(TIME_CHARS).fold(0L) { acc, c ->
                val digit = ALPHABET.indexOf(c)
                require(digit >= 0) { "Invalid ULID character '$c'" }
                (acc shl 5) or digit.toLong()
            }
        }
    }
}
