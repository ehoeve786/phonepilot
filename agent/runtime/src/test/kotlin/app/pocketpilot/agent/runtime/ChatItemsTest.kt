package app.pocketpilot.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatItemsTest {
    private fun step(
        index: Int,
        text: String? = null,
        calls: List<StepCall> = emptyList(),
        note: String? = null,
        user: String? = null,
        reply: String? = null,
    ) = AgentStep(index, 0, text, calls, note = note, user = user, reply = reply)

    private val tap = StepCall("ui.tap", "{}", "tapped", isError = false)

    @Test
    fun `groups the work between messages and shows why a turn ended`() {
        val run =
            RunState(
                id = "r1",
                goal = "Hi",
                provider = "fake",
                model = "m",
                startedAtMillis = 0,
                status = RunStatus.WAITING,
                conversation = true,
                steps =
                    listOf(
                        step(0, reply = "Hello"),
                        step(1, user = "Tap it"),
                        step(2, text = "Tapping", calls = listOf(tap)),
                        step(3, note = "Model call failed (RATE_LIMIT), retrying"),
                        step(4, calls = listOf(tap)),
                        step(5, note = "Reached the limit of 2 steps"),
                    ),
            )
        val items = chatItems(run)
        assertEquals(
            listOf(
                ChatItem.Owner("Hi"),
                ChatItem.Agent("Hello"),
                ChatItem.Owner("Tap it"),
                ChatItem.Work(run.steps.subList(2, 5)),
                ChatItem.Note("Reached the limit of 2 steps"),
            ),
            items,
        )
    }

    @Test
    fun `a stopped chat ends with the reason`() {
        val run =
            RunState(
                id = "r1",
                goal = "Hi",
                provider = "fake",
                model = "m",
                startedAtMillis = 0,
                status = RunStatus.STOPPED,
                outcome = "Stopped by the owner",
                conversation = true,
                steps = listOf(step(0, calls = listOf(tap))),
            )
        assertEquals(ChatItem.Note("Stopped by the owner"), chatItems(run).last())
    }
}
