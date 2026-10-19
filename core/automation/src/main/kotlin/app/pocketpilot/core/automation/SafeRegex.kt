package app.pocketpilot.core.automation

/**
 * Runs a pattern from a rule on text from the phone with a budget. Java regular expressions can backtrack
 * for a very long time on some patterns (a shared rule can carry one), and a match cannot be interrupted, so
 * the text counts its own reads and the match stops once the budget is spent.
 */
object SafeRegex {
    /** Reads of the text allowed for one match; ordinary patterns on screen text use a few thousand. */
    private const val READ_BUDGET = 2_000_000

    class TooSlow : RuntimeException("That pattern takes too long on this text")

    private class Budgeted(private val text: String) : CharSequence {
        private var reads = 0

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++reads > READ_BUDGET) throw TooSlow()
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = text.substring(startIndex, endIndex)

        override fun toString(): String = text
    }

    /** The first match of [pattern] in [text], or null; throws [TooSlow] when the budget runs out. */
    fun find(
        pattern: String,
        text: String,
    ): MatchResult? = Regex(pattern).find(Budgeted(text))

    /** True when [pattern] matches somewhere in [text]; throws [TooSlow] when the budget runs out. */
    fun matches(
        pattern: String,
        text: String,
    ): Boolean = Regex(pattern).containsMatchIn(Budgeted(text))
}
