package app.pocketpilot.core.automation

import app.pocketpilot.core.common.Clock
import app.pocketpilot.core.model.Scope
import app.pocketpilot.core.model.Session
import app.pocketpilot.core.model.SessionId
import app.pocketpilot.core.model.SessionKind
import app.pocketpilot.core.model.ToolCall
import app.pocketpilot.core.model.ToolErrorCode
import app.pocketpilot.core.model.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {
    private class MemoryPersistence : Persistence {
        var text: String? = null

        override fun load() = text

        override fun save(text: String) {
            this.text = text
        }
    }

    private class FakeHost : AutomationHost {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val state = mutableMapOf<String, String>()
        var settings = mutableMapOf("wifi" to "on")
        val opened = mutableListOf<Set<String>>()
        var closed = 0

        override suspend fun callTool(
            session: Session,
            tool: String,
            arguments: JsonObject,
        ): ToolResult {
            calls += tool to arguments
            return when (tool) {
                "settings.get" -> {
                    val key = (arguments["key"] as JsonPrimitive).content
                    ToolResult.success("$key: ${settings[key]}")
                }

                "settings.set" -> {
                    settings[(arguments["key"] as JsonPrimitive).content] = (arguments["value"] as JsonPrimitive).content
                    ToolResult.success("ok")
                }

                "screen.find" -> {
                    ToolResult.success("snapshot s7\ne4 button \"Start\" [0,0][10,10]")
                }

                "app.stop" -> {
                    ToolResult.error(ToolErrorCode.DENIED_BY_POLICY, "Not allowed")
                }

                else -> {
                    ToolResult.success("done")
                }
            }
        }

        override fun openSession(
            rule: Rule,
            tools: Set<String>,
        ): Session {
            opened += tools
            return Session(SessionId(ULID), SessionKind.AUTOMATION, "Automation: ${rule.name}", Scope.KNOWN, 0)
        }

        override fun closeSession(session: Session) {
            closed++
        }

        override suspend fun state(key: String) = state[key]

        override suspend fun notifications() = listOf(NotificationInfo("com.whatsapp", "Mom", "Call me"))

        override suspend fun native(
            type: String,
            params: Map<String, String>,
        ): String? {
            calls += type to JsonObject(params.mapValues { JsonPrimitive(it.value) })
            return null
        }

        override suspend fun askAgent(prompt: String) = AgentOutcome("Did it", 0.01)

        override val sideload = true
    }

    private companion object {
        const val ULID = "01JZ0000000000000000000000"
    }

    // Monday 2026-10-12 09:30 UTC.
    private val clock = Clock { LocalDateTime.of(2026, 10, 12, 9, 30).toInstant(ZoneOffset.UTC).toEpochMilli() }

    private fun engine(
        scope: TestScope,
        host: FakeHost,
        store: RuleStore = RuleStore(MemoryPersistence(), clock),
    ) = AutomationEngine(store, host, scope, clock) { ZoneOffset.UTC }

    private fun rule(
        actions: List<ActionStep>,
        constraints: ConstraintGroup = ConstraintGroup(),
        triggers: List<TriggerSpec> = listOf(TriggerSpec("manual")),
    ) = Rule(id = "r1", name = "Test", triggers = triggers, constraints = constraints, actions = actions)

    @Test
    fun `runs tool actions with defaults and placeholders`() =
        runTest {
            val host = FakeHost()
            val engine = engine(this, host)
            val record =
                engine.run(
                    rule(
                        listOf(
                            ActionStep("timer.set", mapOf("minutes" to "3", "label" to "{trigger.title}")),
                            ActionStep("notify.post", mapOf("title" to "Hi {rule.name}")),
                        ),
                    ),
                    "Test run",
                    mapOf("title" to "Tea"),
                )
            assertEquals(RunOutcome.OK, record.outcome)
            val (tool, args) = host.calls[0]
            assertEquals("app.start_activity", tool)
            assertEquals("android.intent.action.SET_TIMER", (args["action"] as JsonPrimitive).content)
            val extras = args["extras"] as JsonObject
            assertEquals(180, (extras["android.intent.extra.alarm.LENGTH"] as JsonPrimitive).content.toInt())
            assertEquals("Tea", (extras["android.intent.extra.alarm.MESSAGE"] as JsonPrimitive).content)
            assertEquals("Hi Test", (host.calls[1].second["title"] as JsonPrimitive).content)
            assertEquals(2, record.lines.size)
            assertEquals(1, host.closed)
            assertTrue("app.start_activity" in host.opened.single())
        }

    @Test
    fun `toggles a setting and stops on a failed action`() =
        runTest {
            val host = FakeHost()
            val record =
                engine(this, host).run(
                    rule(
                        listOf(
                            ActionStep("setting.wifi", mapOf("value" to "toggle")),
                            ActionStep("app.stop", mapOf("apps" to "com.example")),
                            ActionStep("notify.post", mapOf("title" to "never")),
                        ),
                    ),
                    "Test",
                )
            assertEquals("off", host.settings["wifi"])
            assertEquals(RunOutcome.FAILED, record.outcome)
            assertTrue(record.lines[1].contains("failed, Not allowed"))
            assertTrue(host.calls.none { it.first == "notify.post" })
        }

    @Test
    fun `continues past a failure when told to`() =
        runTest {
            val host = FakeHost()
            val record =
                engine(this, host).run(
                    rule(
                        listOf(
                            ActionStep("app.stop", mapOf("apps" to "com.example"), onError = OnError.CONTINUE),
                            ActionStep("notify.post", mapOf("title" to "after")),
                        ),
                    ),
                    "Test",
                )
            assertEquals(RunOutcome.OK, record.outcome)
            assertTrue(host.calls.any { it.first == "notify.post" })
        }

    @Test
    fun `flow, loops and variables`() =
        runTest {
            val host = FakeHost()
            host.state[StateKeys.BATTERY_LEVEL] = "15"
            val record =
                engine(this, host).run(
                    rule(
                        listOf(
                            ActionStep("var.set", mapOf("name" to "n", "value" to "1")),
                            ActionStep(
                                "flow.repeat",
                                mapOf("count" to "3"),
                                children = listOf(ActionStep("var.math", mapOf("name" to "n", "op" to "multiply", "value" to "2"))),
                            ),
                            ActionStep(
                                "flow.if",
                                constraints = ConstraintGroup.of(ConstraintSpec("battery.level", mapOf("max" to "20"))),
                                children = listOf(ActionStep("notify.post", mapOf("title" to "Low {n}"))),
                                elseChildren = listOf(ActionStep("notify.post", mapOf("title" to "Fine"))),
                            ),
                            ActionStep("flow.stop"),
                            ActionStep("notify.post", mapOf("title" to "never")),
                        ),
                    ),
                    "Test",
                )
            assertEquals(RunOutcome.OK, record.outcome)
            val titles = host.calls.filter { it.first == "notify.post" }.map { (it.second["title"] as JsonPrimitive).content }
            assertEquals(listOf("Low 8"), titles)
        }

    @Test
    fun `taps by text through screen find`() =
        runTest {
            val host = FakeHost()
            engine(this, host).run(rule(listOf(ActionStep("ui.tap_text", mapOf("text" to "Start")))), "Test")
            val tap = host.calls.single { it.first == "ui.tap" }.second
            assertEquals("e4", (tap["element"] as JsonPrimitive).content)
            assertEquals("s7", (tap["snapshotId"] as JsonPrimitive).content)
        }

    @Test
    fun `rule constraints skip the run`() =
        runTest {
            val host = FakeHost()
            host.state[StateKeys.PLUGGED] = "false"
            val record =
                engine(this, host).run(
                    rule(
                        listOf(ActionStep("notify.post", mapOf("title" to "x"))),
                        ConstraintGroup.of(ConstraintSpec("power.charging", mapOf("state" to "yes"))),
                    ),
                    "Test",
                )
            assertEquals(RunOutcome.SKIPPED, record.outcome)
            assertTrue(host.calls.isEmpty())
        }

    @Test
    fun `group modes combine checks`() =
        runTest {
            val host = FakeHost()
            host.state[StateKeys.WIFI_SSID] = "Home"
            val engine = engine(this, host)
            val onHome = ConstraintSpec("wifi.network", mapOf("ssid" to "Home"))
            val notification = ConstraintSpec("notification.present", mapOf("apps" to "com.whatsapp", "text" to "call"))
            val night = ConstraintSpec("time.window", mapOf("from" to "22:00", "to" to "06:00"))
            val r = rule(emptyList())
            assertTrue(
                engine.check(r, ConstraintGroup(GroupMode.ALL, listOf(ConstraintNode.Check(onHome), ConstraintNode.Check(notification)))),
            )
            assertTrue(engine.check(r, ConstraintGroup(GroupMode.ANY, listOf(ConstraintNode.Check(night), ConstraintNode.Check(onHome)))))
            assertFalse(engine.check(r, ConstraintGroup(GroupMode.NONE, listOf(ConstraintNode.Check(onHome)))))
            assertTrue(engine.check(r, ConstraintGroup(GroupMode.ONE, listOf(ConstraintNode.Check(night), ConstraintNode.Check(onHome)))))
            assertTrue(
                engine.check(
                    r,
                    ConstraintGroup(
                        GroupMode.ALL,
                        listOf(ConstraintNode.Group(ConstraintGroup(GroupMode.NONE, listOf(ConstraintNode.Check(night))))),
                    ),
                ),
            )
        }

    @Test
    fun `battery trigger fires only when the level crosses`() =
        runTest {
            val host = FakeHost()
            val store = RuleStore(MemoryPersistence(), clock)
            store.upsert(
                rule(
                    listOf(ActionStep("notify.post", mapOf("title" to "Low"))),
                    triggers = listOf(TriggerSpec("battery.level", mapOf("direction" to "below", "percent" to "20"))),
                ),
            )
            val engine = engine(this, host, store)
            engine.onEvent(TriggerEvent("battery.level", mapOf("level" to "25")))
            engine.onEvent(TriggerEvent("battery.level", mapOf("level" to "19")))
            advanceUntilIdle()
            engine.onEvent(TriggerEvent("battery.level", mapOf("level" to "18")))
            advanceUntilIdle()
            assertEquals(1, host.calls.count { it.first == "notify.post" })
            assertEquals(1, store.data.value.runs.size)
        }

    @Test
    fun `notification trigger matches app and text and fills variables`() =
        runTest {
            val host = FakeHost()
            val store = RuleStore(MemoryPersistence(), clock)
            store.upsert(
                rule(
                    listOf(ActionStep("speak", mapOf("text" to "{trigger.title} says {trigger.text}"))),
                    triggers = listOf(TriggerSpec("notification.posted", mapOf("apps" to "com.whatsapp", "text" to "home"))),
                ),
            )
            val engine = engine(this, host, store)
            engine.onEvent(TriggerEvent("notification.posted", mapOf("package" to "com.other", "title" to "A", "text" to "home")))
            engine.onEvent(TriggerEvent("notification.posted", mapOf("package" to "com.whatsapp", "title" to "Mom", "text" to "Come home")))
            advanceUntilIdle()
            val spoken = host.calls.single { it.first == "speak" }.second
            assertEquals("Mom says Come home", (spoken["text"] as JsonPrimitive).content)
        }

    @Test
    fun `pending proposals and disabled rules never run`() =
        runTest {
            val host = FakeHost()
            val store = RuleStore(MemoryPersistence(), clock)
            store.upsert(
                rule(
                    listOf(ActionStep("notify.post", mapOf("title" to "x"))),
                    triggers = listOf(TriggerSpec("screen.on")),
                ).copy(proposedBy = "Claude"),
            )
            val engine = engine(this, host, store)
            engine.onEvent(TriggerEvent("screen.on"))
            advanceUntilIdle()
            assertTrue(host.calls.isEmpty())
            store.accept("r1")
            assertEquals(null, store.rule("r1")?.proposedBy)
            engine.onEvent(TriggerEvent("screen.on"))
            advanceUntilIdle()
            assertEquals(1, host.calls.size)
        }

    @Test
    fun `agent runs stop at the monthly cap`() =
        runTest {
            val host = FakeHost()
            val store = RuleStore(MemoryPersistence(), clock)
            store.setSettings(AutomationSettings(agentMonthlyCapUsd = 0.015))
            val engine = engine(this, host, store)
            val r = rule(emptyList()).copy(runAs = RunMode.AGENT, agentPrompt = "Tidy up")
            assertEquals(RunOutcome.OK, engine.run(r, "Test").outcome)
            assertEquals(RunOutcome.OK, engine.run(r, "Test").outcome)
            val third = engine.run(r, "Test")
            assertEquals(RunOutcome.FAILED, third.outcome)
            assertTrue(third.lines.last().contains("monthly AI limit"))
        }

    @Test
    fun `schedule finds the next matching day`() {
        val zone = ZoneOffset.UTC
        val monday0930 = LocalDateTime.of(2026, 10, 12, 9, 30).toInstant(zone).toEpochMilli()
        val next = Schedule.next(TriggerSpec("time.at", mapOf("time" to "08:00", "days" to "mon,wed")), monday0930, null, zone)
        assertEquals(LocalDateTime.of(2026, 10, 14, 8, 0).toInstant(zone).toEpochMilli(), next)
        val later = Schedule.next(TriggerSpec("time.at", mapOf("time" to "10:00")), monday0930, null, zone)
        assertEquals(LocalDateTime.of(2026, 10, 12, 10, 0).toInstant(zone).toEpochMilli(), later)
        assertEquals(
            monday0930 + 15 * 60_000,
            Schedule.next(TriggerSpec("time.interval", mapOf("minutes" to "15")), monday0930, monday0930, zone),
        )
    }

    @Test
    fun `time windows wrap past midnight`() {
        assertTrue(Constraints.inWindow(LocalTime.of(23, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)))
        assertTrue(Constraints.inWindow(LocalTime.of(5, 59), LocalTime.of(22, 0), LocalTime.of(6, 0)))
        assertFalse(Constraints.inWindow(LocalTime.of(12, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)))
    }

    @Test
    fun `store round-trips nested constraints`() {
        val persistence = MemoryPersistence()
        val store = RuleStore(persistence, clock)
        val r =
            rule(
                listOf(
                    ActionStep(
                        "flow.if",
                        constraints = ConstraintGroup.of(ConstraintSpec("dnd.on")),
                        children = listOf(ActionStep("flow.stop")),
                    ),
                ),
                ConstraintGroup(GroupMode.ANY, listOf(ConstraintNode.Group(ConstraintGroup.of(ConstraintSpec("screen.state"))))),
            )
        store.upsert(r)
        val reloaded = RuleStore(persistence, clock).rule("r1")
        assertEquals(r.copy(createdAtMillis = clock.nowMillis()), reloaded)
    }

    @Test
    fun `proposals are validated and stored pending`() =
        runTest {
            val store = RuleStore(MemoryPersistence(), clock)
            var told: Rule? = null
            val tool = AutomationProposeTool(store, sideload = true) { told = it }
            val session = Session(SessionId(ULID), SessionKind.MCP_CLIENT, "Claude", Scope.KNOWN, 0)
            val good =
                buildJsonObject {
                    put("name", "Mute at work")
                    put(
                        "triggers",
                        AutomationJson.json.parseToJsonElement("""[{"type":"wifi.connected","params":{"ssid":"Office"}}]"""),
                    )
                    put("actions", AutomationJson.json.parseToJsonElement("""[{"type":"setting.dnd","params":{"value":"on"}}]"""))
                }
            val result = tool.execute(ToolCall("1", SessionId(ULID), "automation.propose", good), session)
            assertFalse(result.isError)
            val saved = store.rules.single()
            assertEquals("Claude", saved.proposedBy)
            assertFalse(saved.enabled)
            assertEquals(saved, told)

            val bad = buildJsonObject { put("name", "x") }
            val badCall =
                runCatching {
                    tool.execute(
                        ToolCall(
                            "2",
                            SessionId(ULID),
                            "automation.propose",
                            JsonObject(bad + ("triggers" to AutomationJson.json.parseToJsonElement("""[{"type":"nope"}]"""))),
                        ),
                        session,
                    )
                }
            assertTrue(badCall.exceptionOrNull()?.message?.contains("Unknown trigger nope") == true)
        }

    @Test
    fun `every catalog entry has a unique type and valid defaults`() {
        val types =
            AutomationCatalog.triggers.map { it.type } + AutomationCatalog.constraints.map { "c:" + it.type } +
                AutomationCatalog.actions.map { "a:" + it.type }
        assertEquals(types.size, types.toSet().size)
        AutomationCatalog.actions.forEach { def ->
            def.fields.filter { it.kind == FieldKind.CHOICE }.forEach { field ->
                assertTrue(field.default == null || field.choices.any { it.value == field.default }, "${def.type}.${field.key}")
            }
        }
    }

    @Test
    fun `every template is a valid rule`() {
        Templates.all.forEach { assertEquals(emptyList(), RuleValidator.problems(it), it.name) }
    }
}
