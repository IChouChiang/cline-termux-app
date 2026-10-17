package dev.cline.termux.data.customize

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSettingsTest {
    private fun json(text: String): JsonObject = CustomizeJson.parseToJsonElement(text).jsonObject

    @Test
    fun `parses the recorded empty snapshot (HF settings-list-003)`() {
        val snapshot = SettingsSnapshot.fromReply(json("""{"snapshot":{"workflows":[],"rules":[],"plugins":[],"skills":[],"tools":[],"mcp":[]}}"""))!!
        assertEquals(SettingsSnapshot.EMPTY, snapshot)
        assertEquals(CustomizeCounts(0, 0, 0, 0, 0, 0), customizeCounts(snapshot, emptyList(), emptyList()))
    }

    @Test
    fun `parses items leniently and drops unknown kinds`() {
        val snapshot = SettingsSnapshot.parse(
            json(
                """
                {"skills":[{"id":"s1","name":"review","path":"/h/.cline/skills/review/SKILL.md","kind":"skill","source":"global","enabled":false,"toggleable":true}],
                 "plugins":[{"id":"p1","name":"lint","path":"/w/.cline/plugins/lint","kind":"plugin","source":"workspace-plugin","agentPlugin":true,
                   "contributions":{"inspectionStatus":"failed","tools":["a"],"skills":[]},"loadError":"boom"}],
                 "tools":[{"id":"t","name":"lint_run","path":"/x","kind":"tool","source":"global-plugin","pluginName":"lint"},{"id":"bad","kind":"nope"}],
                 "mcp":[{"id":"m","name":"github","path":"","kind":"mcp","source":"global","enabled":true}]}
                """.trimIndent(),
            ),
        )
        assertEquals(1, snapshot.skills.size)
        assertFalse(snapshot.skills.single().isEnabled)
        assertEquals(SettingsType.Skills, snapshot.skills.single().kind.type)
        val plugin = snapshot.plugins.single()
        assertTrue(plugin.agentPlugin)
        assertEquals("failed", plugin.contributions!!.inspectionStatus)
        assertEquals(listOf("a"), plugin.contributions!!.tools)
        assertEquals("boom", plugin.loadError)
        assertEquals(1, snapshot.tools.size)
        assertEquals("lint", snapshot.tools.single().pluginName)
        assertTrue(snapshot.workflows.isEmpty())
    }

    @Test
    fun `built-in tools skip plugin entries and append the tasks card`() {
        val stdout = """
            [{"id":"read_files","description":"Read files","defaultEnabled":true,"headlessToolNames":["read_file","read_files"]},
             {"id":"web_search","description":"Search","defaultEnabled":false,"headlessToolNames":[]},
             {"name":"lint_run","type":"plugin","pluginName":"lint","path":"/x","source":"global","enabled":true}]
        """.trimIndent()
        val tools = CliCatalogs.parseBuiltinTools(stdout, tasksEnabled = false)
        assertEquals(listOf("read_files", "web_search", "tasks"), tools.map { it.id })
        assertFalse(tools[1].enabled)
        val tasks = tools.last()
        assertEquals(CliCatalogs.TASKS_TOOL_DESCRIPTION, tasks.description)
        assertFalse(tasks.enabled)
        assertEquals(listOf("tasks"), tasks.toggleNames)
        assertEquals(listOf("read_files", "read_file"), tools[0].toggleNames)
    }

    @Test
    fun `the tasks card is not duplicated when the catalog has it, and log lines before the array are skipped`() {
        val stdout = "warning: something\n[{\"id\":\"tasks\",\"defaultEnabled\":true,\"headlessToolNames\":[\"tasks\"]}]"
        assertEquals(listOf("tasks"), CliCatalogs.parseBuiltinTools(stdout, tasksEnabled = false).map { it.id })
    }

    @Test
    fun `hooks parse`() {
        val hooks = CliCatalogs.parseHooks("""[{"fileName":"PreToolUse.sh","hookEventName":"PreToolUse","path":"/w/.clinerules/hooks/PreToolUse.sh"},{"fileName":"x"}]""")
        assertEquals(1, hooks.size)
        assertEquals("PreToolUse", hooks.single().hookEventName)
    }

    @Test
    fun `scope rules follow extensions-view`() {
        val root = ScopeRules.normalizeRoot("/w/app/")
        assertEquals("/w/app", root)
        assertEquals(Scope.Project, ScopeRules.rule("/w/app/.clinerules/style.md", root))
        assertEquals(Scope.Project, ScopeRules.rule("/w/app/.cline/rules/a.md", root))
        assertEquals(Scope.Global, ScopeRules.rule("/h/Documents/Cline/Rules/a.md", root))
        assertEquals(Scope.Global, ScopeRules.rule("/w/app/docs/a.md", root))
        assertEquals(Scope.Project, ScopeRules.hook("/w/app/.clinerules/hooks/Pre.sh", root))
        assertEquals(Scope.Global, ScopeRules.hook("/h/Documents/Cline/Hooks/Pre.sh", root))
        val plugin = SettingsItem("p", "p", "/w/app/.agents/plugins/p", SettingsKind.Plugin, "global-plugin")
        assertEquals(Scope.Project, ScopeRules.plugin(plugin, root))
        assertEquals(Scope.Project, ScopeRules.plugin(plugin.copy(path = "/elsewhere", source = "workspace-plugin"), root))
        assertEquals(Scope.Global, ScopeRules.plugin(plugin.copy(path = "/h/.cline/plugins/p"), root))
        assertEquals(Scope.Project, ScopeRules.command("/w/app/.cline/skills/x/SKILL.md", root))
        assertEquals(Scope.Global, ScopeRules.command("/w/application/x", root))
        assertEquals(listOf("g1", "g2", "p1"), ScopeRules.globalFirst(listOf("p1", "g1", "g2")) { if (it.startsWith("g")) Scope.Global else Scope.Project })
    }

    @Test
    fun `ordering and counts`() {
        val snapshot = SettingsSnapshot(
            skills = listOf(SettingsItem("b", "beta", "/b", SettingsKind.Skill, "global")),
            workflows = listOf(SettingsItem("a", "alpha", "/a", SettingsKind.Workflow, "global")),
            tools = listOf(SettingsItem("t", "t", "/t", SettingsKind.Tool, "global-plugin")),
        )
        assertEquals(listOf("alpha", "beta"), CustomizeOrdering.commands(snapshot).map { it.name })
        assertEquals(listOf("alpha", "Beta", "gamma"), CustomizeOrdering.mcpNames(listOf("gamma", "Beta", "alpha")))
        val counts = customizeCounts(snapshot, listOf(BuiltinTool("x", null, true, emptyList())), listOf(HookFile("h", null, "/h")))
        assertEquals(CustomizeCounts(tools = 2, plugins = 0, skills = 2, rules = 0, mcp = 0, hooks = 1), counts)
    }

    @Test
    fun `marketplace match values port getLocalMarketplaceMatchValues`() {
        val values = localMarketplaceMatchValues("s1", "review", "/h/.cline/skills/review/SKILL.md")
        assertEquals(
            listOf(
                // ".cline" loses its whole name to the extension rule, exactly like the desktop regex.
                "s1", "review", "/h/.cline/skills/review/SKILL.md", "h", "", "skills", "SKILL",
                "h/.cline", ".cline/skills", "skills/review", "review/SKILL.md",
            ).toSet(),
            values.toSet(),
        )
        assertTrue(localMarketplaceMatchValues(null, " ").isEmpty())
        assertEquals("a\\b".replace('\\', '/'), localMarketplaceMatchValues("a\\b").first())
        assertNull(localMarketplaceMatchValues().firstOrNull())
    }
}
