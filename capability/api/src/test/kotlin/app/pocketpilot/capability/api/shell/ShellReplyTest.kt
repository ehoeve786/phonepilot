package app.pocketpilot.capability.api.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellReplyTest {
    @Test
    fun `output with NUL characters comes back whole`() {
        val stdout = "a\u0000b\u0000c\n"
        val stderr = "err\u0000"
        val result = ShellReply.decode(ShellReply.encode(0, stdout, stderr))

        assertEquals(ShellResult(exitCode = 0, stdout = stdout, stderr = stderr, timedOut = false), result)
    }

    @Test
    fun `a timed out command reports exit -1 as a timeout`() {
        assertEquals(true, ShellReply.decode(ShellReply.encode(-1, "", "")).timedOut)
    }

    @Test
    fun `an unreadable reply is a failed command`() {
        val result = ShellReply.decode("nonsense")

        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
    }

    @Test
    fun `a reply that claims more stdout than it holds is a failed command`() {
        assertEquals(-1, ShellReply.decode("0\n99\nshort").exitCode)
    }
}
