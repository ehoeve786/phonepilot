package app.pocketpilot.capability.api.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IntentArgsTest {
    @Test
    fun `builds am arguments for a component with extras`() {
        val args =
            IntentArgs.build(
                component = "com.example.clock/.StopwatchActivity",
                packageName = null,
                action = "com.example.action.STOPWATCH",
                data = null,
                mimeType = null,
                categories = listOf("android.intent.category.DEFAULT"),
                extras = listOf(IntentExtra("start", "bool", "true"), IntentExtra("label", "string", "Run 5 k")),
            )
        assertEquals(
            listOf(
                "-n",
                "com.example.clock/.StopwatchActivity",
                "-a",
                "com.example.action.STOPWATCH",
                "-c",
                "android.intent.category.DEFAULT",
                "--ez",
                "start",
                "true",
                "--es",
                "label",
                "Run 5 k",
            ),
            args,
        )
        assertEquals("com.example.clock", IntentArgs.targetPackage(args))
    }

    @Test
    fun `an extra value cannot smuggle in another target`() {
        val args = IntentArgs.build(null, "com.example.app", null, null, null, emptyList(), listOf(IntentExtra("x", "string", "-n")))
        assertEquals("com.example.app", IntentArgs.targetPackage(args))
    }

    @Test
    fun `intents without an app, to protected apps or with dangerous actions are refused`() {
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-a", "android.intent.action.VIEW", "-d", "https://x")) }
        assertFailsWith<IllegalArgumentException> {
            IntentArgs.check(
                listOf("-p", "com.android.phone", "-a", "android.intent.action.CALL"),
            )
        }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "moe.shizuku.privileged.api")) }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "com.example.app", "--user", "10")) }
        assertFailsWith<IllegalArgumentException> { IntentArgs.check(listOf("-p", "com.example.app", "-t", "not a type")) }
    }
}
