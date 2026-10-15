package app.pocketpilot.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatsToDeleteTest {
    private val history =
        listOf(
            RunState("01C", "chat c", "fake", "m", 0, conversation = true),
            RunState("01B", "chat b", "fake", "m", 0, conversation = true),
            RunState("01T", "task", "fake", "m", 0, conversation = false),
            RunState("01A", "chat a", "fake", "m", 0, conversation = true),
        )

    @Test
    fun `picked chats are deleted and the chat on screen is kept even when picked`() {
        assertEquals(setOf("01B"), chatsToDelete(history, keep = "01C", picked = setOf("01B", "01C")))
    }

    @Test
    fun `delete all removes every earlier chat but never the chat on screen or a task run`() {
        assertEquals(setOf("01B", "01A"), chatsToDelete(history, keep = "01C", picked = null))
    }

    @Test
    fun `without a chat on screen every chat can go, and task runs still stay`() {
        assertEquals(setOf("01C", "01B", "01A"), chatsToDelete(history, keep = null, picked = null))
    }

    @Test
    fun `ids that are not chats in the history are ignored`() {
        assertEquals(emptySet(), chatsToDelete(history, keep = null, picked = setOf("01T", "99X")))
    }
}
