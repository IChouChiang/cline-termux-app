package dev.cline.termux.core.kernel.hub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPayloadsTest {
    private val spec = NewSessionSpec(workspaceRoot = "/w", providerId = "anthropic", modelId = "claude", autoApprove = false)

    @Test
    fun `session create carries the documented shape, checkpoints on, and never an apiKey`() {
        val p = HubPayloads.sessionCreate(spec)
        val config = p["sessionConfig"]!!.jsonObject
        assertEquals("anthropic", config["providerId"]!!.jsonPrimitive.content)
        assertEquals("claude", config["modelId"]!!.jsonPrimitive.content)
        assertEquals("act", config["mode"]!!.jsonPrimitive.content)
        assertEquals("true", config["enableTools"]!!.jsonPrimitive.content)
        assertEquals("/w", config["workspaceRoot"]!!.jsonPrimitive.content)
        assertEquals("/w", config["cwd"]!!.jsonPrimitive.content)
        assertEquals("true", config["checkpoint"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertNull(config["sessionId"])
        assertNull(p["configExtensions"])
        assertNull(p["initialMessages"])
        assertEquals("/w", p["workspaceRoot"]!!.jsonPrimitive.content)
        assertEquals("android", p["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals("true", p["metadata"]!!.jsonObject["interactive"]!!.jsonPrimitive.content)
        assertEquals("false", p["toolPolicies"]!!.jsonObject["*"]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content)
        val contributions = p["runtimeOptions"]!!.jsonObject["clientContributions"]!!.jsonArray
        assertEquals("an act session: askQuestion and mistakeLimit, no switch_to_act_mode", 2, contributions.size)
        val contribution = contributions[0].jsonObject
        assertEquals("toolExecutor", contribution["kind"]!!.jsonPrimitive.content)
        assertEquals("askQuestion", contribution["executor"]!!.jsonPrimitive.content)
        assertEquals(HubCapabilities.ASK_QUESTION, contribution["capabilityName"]!!.jsonPrimitive.content)
        assertFalse("no credential ever travels in a session payload", p.toString().contains("apiKey"))
        assertNull(HubPayloads.sessionCreate(spec.copy(contributeAskQuestion = false))["runtimeOptions"])
    }

    @Test
    fun `Ask each time keeps the terminal's seven read-only tools auto-approved and asks for the rest`() {
        val policies = HubPayloads.toolPolicies(autoApprove = false)
        val expected = setOf(
            "*",
            "ask_followup_question",
            "ask_question",
            "fetch_web_content",
            "read_files",
            "search_codebase",
            "skills",
            "submit_and_exit",
        )
        assertEquals(expected, policies.keys)
        assertEquals("false", policies["*"]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content)
        (expected - "*").forEach { name -> assertEquals(name, "true", policies[name]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content) }
        // The create payload carries exactly these.
        assertEquals(policies, HubPayloads.sessionCreate(spec)["toolPolicies"])
    }

    @Test
    fun `Auto-approve all is only the wildcard`() {
        val policies = HubPayloads.toolPolicies(autoApprove = true)
        assertEquals(setOf("*"), policies.keys)
        assertEquals("true", policies["*"]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content)
        assertEquals(policies, HubPayloads.sessionCreate(spec.copy(autoApprove = true))["toolPolicies"])
    }

    @Test
    fun `a restart's compaction state goes out as initialCompactionState, otherwise nothing is sent`() {
        assertNull(HubPayloads.sessionCreate(spec)["initialCompactionState"])
        val state = HubJson.parseToJsonElement("""{"version":1,"source_message_count":2,"messages":[]}""").jsonObject
        assertEquals(state, HubPayloads.sessionCreate(spec.copy(initialCompactionState = state))["initialCompactionState"])
    }

    @Test
    fun `resume adds the old session id and the history, keeping the persisted source`() {
        val history = HubJson.parseToJsonElement("""[{"id":"m1","role":"user","content":[{"type":"text","text":"hi"}],"ts":1}]""")
        val p = HubPayloads.sessionCreate(spec.copy(resumeSessionId = "old", initialMessages = history.jsonArrayOrNull(), source = "cli"))
        assertEquals("old", p["sessionConfig"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertEquals(history, p["initialMessages"])
        assertEquals("cli", p["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        val team = HubPayloads.sessionCreate(spec.copy(team = TeamMembership("t1", "member")))["team"]!!.jsonObject
        assertEquals("t1", team["teamId"]!!.jsonPrimitive.content)
        assertEquals("member", team["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session update spreads the current metadata before setting pinned`() {
        val current = buildJsonObject {
            put("title", JsonPrimitive("Old"))
            put("usage", buildJsonObject { put("inputTokens", JsonPrimitive(42)) })
            put("pinned", JsonPrimitive(false))
        }
        val p = HubPayloads.sessionUpdate("s1", current, pinned = true)
        val metadata = p["metadata"]!!.jsonObject
        assertEquals("true", metadata["pinned"]!!.jsonPrimitive.content)
        assertEquals("Old", metadata["title"]!!.jsonPrimitive.content)
        assertEquals("42", metadata["usage"]!!.jsonObject["inputTokens"]!!.jsonPrimitive.content)
        // A rename alone does not touch metadata.
        assertNull(HubPayloads.sessionUpdate("s1", current, title = "New")["metadata"])
    }

    @Test
    fun `send input attaches images as data urls and files as Termux paths only when present`() {
        assertNull(HubPayloads.sendInput("hi")["attachments"])
        val p = HubPayloads.sendInput("look", mode = "plan", imageDataUrls = listOf("data:image/png;base64,AAAA"), userFiles = listOf("/data/data/com.termux/files/home/.cline/attachments/a.pdf"))
        assertEquals("plan", p["mode"]!!.jsonPrimitive.content)
        val attachments = p["attachments"]!!.jsonObject
        assertEquals("data:image/png;base64,AAAA", attachments["userImages"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("/data/data/com.termux/files/home/.cline/attachments/a.pdf", attachments["userFiles"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("queue", HubPayloads.sendInput("later", delivery = "queue")["delivery"]!!.jsonPrimitive.content)
    }

    @Test
    fun `approval, abort and pending prompt payloads match the recorded ones`() {
        assertEquals("""{"approvalId":"a1","approved":true}""", HubPayloads.approvalRespond("a1", true).toString())
        assertEquals(
            """{"approvalId":"a1","approved":false,"reason":"This tool call was rejected by the user and not executed."}""",
            HubPayloads.approvalRespond("a1", false, "This tool call was rejected by the user and not executed.").toString(),
        )
        assertEquals("""{"sessionId":"s1","reason":"User pressed Stop"}""", HubPayloads.runAbort("s1").toString())
        assertEquals("""{"sessionId":"s1"}""", HubPayloads.steerFirstPendingPrompt("s1").toString())
        assertEquals("""{"sessionId":"s1","promptId":"p1"}""", HubPayloads.removePendingPrompt("s1", "p1").toString())
        assertEquals("""{"sessionId":"s1","promptId":"p1","prompt":"new text"}""", HubPayloads.updatePendingPrompt("s1", "p1", prompt = "new text").toString())
        val capability = HubPayloads.capabilityRespond("r1", ok = true, result = buildJsonObject { put("answer", JsonPrimitive("yes")) })
        assertEquals("r1", capability["requestId"]!!.jsonPrimitive.content)
        // Upstream `capability.respond`: the executor reply rides in `payload.result` (hub-client-contributions.ts `response?.result`).
        assertEquals("yes", capability["payload"]!!.jsonObject["result"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `restore metadata drops the non-inherited keys, re-stamps the client and records the fork`() {
        val source = buildJsonObject {
            put("title", JsonPrimitive("Parent"))
            put("source", JsonPrimitive("cli"))
            put("usage", buildJsonObject { put("inputTokens", JsonPrimitive(9)) })
            put("totalCost", JsonPrimitive(0.5))
            put("pid", JsonPrimitive(1))
            put("checkpoint", buildJsonObject {
                put("latest", checkpoint(3))
                put("history", buildJsonArray { add(checkpoint(1)); add(checkpoint(2)); add(checkpoint(3)) })
            })
        }
        val forked = HubPayloads.restoreMetadata(source, "parent", "anthropic", "claude", runCount = 3, nowIso = "2026-10-03T12:00:00Z")
        assertEquals("Parent", forked["title"]!!.jsonPrimitive.content)
        assertNull(forked["usage"]); assertNull(forked["totalCost"]); assertNull(forked["pid"]); assertNull(forked["checkpoint"])
        assertEquals("android", forked["source"]!!.jsonPrimitive.content)
        assertEquals("anthropic", forked["provider"]!!.jsonPrimitive.content)
        val fork = forked["fork"]!!.jsonObject
        assertEquals("parent", fork["forkedFromSessionId"]!!.jsonPrimitive.content)
        assertEquals("cli", fork["source"]!!.jsonPrimitive.content)
        assertEquals("3", fork["beforeRunCount"]!!.jsonPrimitive.content)
        assertEquals(3, fork["checkpoints"]!!.jsonObject["history"]!!.jsonArray.size)
        // The whole-session fork (A16) has no run: no `beforeRunCount`, like the terminal's and the desktop's.
        val whole = HubPayloads.restoreMetadata(source, "parent", "anthropic", "claude", runCount = null, nowIso = "now")
        assertNull(whole["fork"]!!.jsonObject["beforeRunCount"])
        assertEquals("parent", whole["fork"]!!.jsonObject["forkedFromSessionId"]!!.jsonPrimitive.content)

        val restored = HubPayloads.restoreMetadata(source, "parent", "anthropic", "claude", runCount = 3, nowIso = "now", checkpointRunCount = 2)
        assertEquals("parent", restored["restoredFromSessionId"]!!.jsonPrimitive.content)
        assertEquals("2", restored["restoredCheckpointRunCount"]!!.jsonPrimitive.content)
        val checkpoint = restored["checkpoint"]!!.jsonObject
        assertEquals("2", checkpoint["latest"]!!.jsonObject["runCount"]!!.jsonPrimitive.content)
        assertEquals(listOf("1", "2"), checkpoint["history"]!!.jsonArray.map { it.jsonObject["runCount"]!!.jsonPrimitive.content })
    }

    @Test
    fun `session restore nests the restore flags and mints no session id`() {
        val metadata = HubPayloads.revertMetadata("anthropic", "claude")
        val p = HubPayloads.sessionRestore("src", checkpointRunCount = 2, restoreWorkspace = true, omitCheckpointMessage = true, spec = spec.copy(resumeSessionId = "ignored"), metadata = metadata)
        assertEquals("src", p["sessionId"]!!.jsonPrimitive.content)
        assertEquals("2", p["checkpointRunCount"]!!.jsonPrimitive.content)
        val restore = p["restore"]!!.jsonObject
        assertEquals("true", restore["messages"]!!.jsonPrimitive.content)
        assertEquals("true", restore["workspace"]!!.jsonPrimitive.content)
        assertEquals("true", restore["omitCheckpointMessageFromSession"]!!.jsonPrimitive.content)
        assertNull(p["sessionConfig"]!!.jsonObject["sessionId"])
        assertEquals(metadata, p["metadata"])
        val filesOnly = HubPayloads.sessionRestoreWorkspaceOnly("src", 2)["restore"]!!.jsonObject
        assertEquals("false", filesOnly["messages"]!!.jsonPrimitive.content)
        assertEquals("true", filesOnly["workspace"]!!.jsonPrimitive.content)
        val seeded = HubPayloads.sessionCreateRestored(spec.copy(resumeSessionId = "x"), JsonArray(emptyList()), metadata)
        assertNull(seeded["sessionConfig"]!!.jsonObject["sessionId"])
        assertEquals(JsonArray(emptyList()), seeded["initialMessages"])
    }

    @Test
    fun `every create path carries sessionConfig compaction, enabled by default (G1)`() {
        val enabled = buildJsonObject { put("enabled", JsonPrimitive(true)) }
        val off = buildJsonObject { put("enabled", JsonPrimitive(false)) }
        val basic = buildJsonObject {
            put("enabled", JsonPrimitive(true))
            put("strategy", JsonPrimitive("basic"))
        }
        val metadata = HubPayloads.revertMetadata("anthropic", "claude")
        fun configs(s: NewSessionSpec) = listOf(
            HubPayloads.sessionCreate(s),
            HubPayloads.sessionCreate(s.copy(resumeSessionId = "old", initialMessages = JsonArray(emptyList()))),
            HubPayloads.sessionCreateRestored(s, JsonArray(emptyList()), metadata),
            HubPayloads.sessionRestore("src", checkpointRunCount = 1, restoreWorkspace = false, omitCheckpointMessage = false, spec = s, metadata = metadata),
        ).map { it["sessionConfig"]!!.jsonObject["compaction"] }
        assertEquals(List(4) { enabled }, configs(spec))
        assertEquals(List(4) { off }, configs(spec.copy(compaction = off)))
        assertEquals(List(4) { basic }, configs(spec.copy(compaction = basic)))
    }

    @Test
    fun `the debug config line names the command, its compaction and only the session id`() {
        assertEquals("""create compaction={"enabled":true}""", HubSessionApi.configLine("create", spec, null))
        val off = spec.copy(compaction = buildJsonObject { put("enabled", JsonPrimitive(false)) }, resumeSessionId = "s1")
        assertEquals("""resume compaction={"enabled":false} session=s1""", HubSessionApi.configLine("resume", off, "s1"))
    }

    @Test
    fun `a restarted session's compaction state goes out as top-level initialCompactionState`() {
        val state = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("source_message_count", JsonPrimitive(2))
            put("messages", JsonArray(emptyList()))
        }
        val restarted = spec.copy(resumeSessionId = "s1", initialMessages = JsonArray(emptyList()), initialCompactionState = state)
        val payload = HubPayloads.sessionCreate(restarted)
        assertEquals(state, payload["initialCompactionState"])
        assertNull("not part of sessionConfig", payload["sessionConfig"]!!.jsonObject["initialCompactionState"])
        assertFalse("absent without a state", "initialCompactionState" in HubPayloads.sessionCreate(restarted.copy(initialCompactionState = null)))
    }

    @Test
    fun `the mistake limit contribution names its capability and nothing else`() {
        for (mode in listOf("act", "plan")) {
            val contributions = HubPayloads.runtimeOptions(mode)["clientContributions"]!!.jsonArray
            assertEquals(
                mode,
                """{"kind":"mistakeLimit","capabilityName":"mistake_limit.decide"}""",
                contributions[1].jsonObject.toString(),
            )
        }
    }

    @Test
    fun `switch_to_act_mode is contributed to plan sessions only, as the CLI registers it`() {
        fun names(payload: JsonObject) = payload["runtimeOptions"]!!.jsonObject["clientContributions"]!!.jsonArray
            .map { it.jsonObject["capabilityName"]!!.jsonPrimitive.content }
        val act = listOf(HubCapabilities.ASK_QUESTION, HubCapabilities.MISTAKE_LIMIT)
        val plan = act + HubCapabilities.SWITCH_TO_ACT_MODE
        assertEquals(act, names(HubPayloads.sessionCreate(spec)))
        assertEquals(plan, names(HubPayloads.sessionCreate(spec.copy(mode = "plan"))))
        // The rebuild into act (same id, the history) drops it; a rebuild back into plan adds it.
        val history = JsonArray(emptyList())
        assertEquals(act, names(HubPayloads.sessionCreate(spec.copy(mode = "act", resumeSessionId = "s1", initialMessages = history))))
        assertEquals(plan, names(HubPayloads.sessionCreate(spec.copy(mode = "plan", resumeSessionId = "s1", initialMessages = history))))
        assertEquals(act, names(HubPayloads.sessionCreate(spec.copy(mode = "yolo"))))
        val metadata = HubPayloads.revertMetadata("anthropic", "claude")
        assertEquals(plan, names(HubPayloads.sessionCreateRestored(spec.copy(mode = "plan"), history, metadata)))
        assertEquals(plan, names(HubPayloads.sessionRestore("src", 2, restoreWorkspace = true, omitCheckpointMessage = false, spec = spec.copy(mode = "plan"), metadata = metadata)))
        assertEquals(act, names(HubPayloads.sessionRestore("src", 2, restoreWorkspace = true, omitCheckpointMessage = false, spec = spec, metadata = metadata)))
    }

    @Test
    fun `switch_to_act_mode is the CLI tool verbatim, with an empty schema and completesRun`() {
        val tool = HubPayloads.runtimeOptions("plan")["clientContributions"]!!.jsonArray[2].jsonObject
        assertEquals("tool", tool["kind"]!!.jsonPrimitive.content)
        assertEquals("custom_tool.switch_to_act_mode", tool["capabilityName"]!!.jsonPrimitive.content)
        assertEquals("switch_to_act_mode", tool["name"]!!.jsonPrimitive.content)
        // apps/cli/src/runtime/interactive/mode.ts, verbatim.
        assertEquals(
            "Switch from plan mode to act mode. Switching to act mode immediately starts executing the plan, so only call " +
                "this after the user has explicitly approved the plan in a message sent AFTER you presented it (e.g. 'looks " +
                "good', 'go ahead', 'switch to act mode'). Never call this in the same turn you present a plan, never call it " +
                "proactively, and never treat the original task request as approval.",
            tool["description"]!!.jsonPrimitive.content,
        )
        assertEquals("""{"type":"object","properties":{}}""", tool["inputSchema"].toString())
        assertEquals("""{"completesRun":true}""", tool["lifecycle"].toString())
        assertEquals(setOf("kind", "capabilityName", "name", "description", "inputSchema", "lifecycle"), tool.keys)
    }

    @Test
    fun `no create path ever sends configExtensions or a hook contribution (brief F1)`() {
        val history = JsonArray(emptyList())
        val metadata = HubPayloads.revertMetadata("anthropic", "claude")
        val creates = listOf("act", "plan").flatMap { mode ->
            val spec = spec.copy(mode = mode)
            listOf(
                "$mode new" to HubPayloads.sessionCreate(spec),
                "$mode resume" to HubPayloads.sessionCreate(spec.copy(resumeSessionId = "old", initialMessages = history, source = "cli")),
                "$mode team" to HubPayloads.sessionCreate(spec.copy(team = TeamMembership("t1", "member"))),
                "$mode fork/edit" to HubPayloads.sessionCreateRestored(spec, history, metadata),
                "$mode restore" to HubPayloads.sessionRestore("src", 2, restoreWorkspace = true, omitCheckpointMessage = false, spec = spec, metadata = metadata),
            )
        }
        for ((path, payload) in creates) {
            assertNull("$path: configExtensions", payload["configExtensions"])
            assertNull("$path: sessionConfig.configExtensions", payload["sessionConfig"]!!.jsonObject["configExtensions"])
            val runtime = payload["runtimeOptions"]!!.jsonObject
            assertNull("$path: runtimeOptions.configExtensions", runtime["configExtensions"])
            val kinds = runtime["clientContributions"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
            val expected = if (path.startsWith("plan")) listOf("toolExecutor", "mistakeLimit", "tool") else listOf("toolExecutor", "mistakeLimit")
            assertEquals("$path: the same contributions on every create of that mode", expected, kinds)
            assertFalse("$path: no hook contribution", kinds.any { it == "hook" })
            assertFalse("$path: no hook text anywhere", payload.toString().contains("\"hook"))
        }
    }

    @Test
    fun `settings and schedule payloads span all workspaces where the Hub expects it`() {
        val list = HubPayloads.settingsList("/w")
        assertEquals("true", list["includePluginTools"]!!.jsonPrimitive.content)
        assertEquals("/w", list["workspaceRoot"]!!.jsonPrimitive.content)
        assertEquals("true", HubPayloads.scheduleActive()["allWorkspaces"]!!.jsonPrimitive.content)
        assertEquals("true", HubPayloads.scheduleList()["allWorkspaces"]!!.jsonPrimitive.content)
        assertEquals("s", HubPayloads.scheduleGet("s")["scheduleId"]!!.jsonPrimitive.content)
        assertTrue(HubPayloads.sessionList(limit = 500, rootOnly = false).toString().contains("\"limit\":500"))
    }

    private fun checkpoint(run: Int) = buildJsonObject {
        put("ref", JsonPrimitive("ref$run"))
        put("createdAt", JsonPrimitive(1_791_000_000_000L + run)) // epoch ms, as core writes it
        put("runCount", JsonPrimitive(run))
        put("kind", JsonPrimitive("stash"))
    }

    private fun JsonElement.jsonArrayOrNull() = this as? JsonArray
}
