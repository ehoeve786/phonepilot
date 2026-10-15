package app.pocketpilot.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the owner reads about each rule. Unknown types must say so rather than fail or print nothing. */
class DescribeTest {
    private fun check(type: String) = ConstraintNode.Check(ConstraintSpec(type))

    @Test
    fun `an unknown trigger, condition or action says it is unknown`() {
        assertEquals("Unknown trigger no.such", Describe.trigger(TriggerSpec("no.such")))
        assertEquals("Unknown condition no.such", Describe.constraint(ConstraintSpec("no.such")))
        assertEquals("Unknown action no.such", Describe.action(ActionStep("no.such")))
    }

    @Test
    fun `group modes join their conditions in plain words`() {
        val a = check("no.such.a")
        val b = check("no.such.b")

        fun group(mode: GroupMode) = Describe.group(ConstraintGroup(mode, listOf(a, b)))

        assertEquals("Unknown condition no.such.a and Unknown condition no.such.b", group(GroupMode.ALL))
        assertEquals("Unknown condition no.such.a or Unknown condition no.such.b", group(GroupMode.ANY))
        assertEquals("not Unknown condition no.such.a or Unknown condition no.such.b", group(GroupMode.NONE))
        assertEquals("exactly one of Unknown condition no.such.a, Unknown condition no.such.b", group(GroupMode.ONE))
    }

    @Test
    fun `nested groups are wrapped in brackets`() {
        val inner = ConstraintGroup(GroupMode.ANY, listOf(check("no.such.a"), check("no.such.b")))
        val outer = ConstraintGroup(GroupMode.ALL, listOf(ConstraintNode.Group(inner), check("no.such.c")))

        assertEquals(
            "(Unknown condition no.such.a or Unknown condition no.such.b) and Unknown condition no.such.c",
            Describe.group(outer),
        )
    }
}
