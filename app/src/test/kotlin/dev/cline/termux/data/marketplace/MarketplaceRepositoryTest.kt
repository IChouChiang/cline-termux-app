package dev.cline.termux.data.marketplace

import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.fakes.FakeHelperChannel
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.ok
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketplaceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var cacheDir: File

    private val catalogJson = """
        {"version":2,"generatedAt":"2026-10-01","counts":{"total":4,"skills":1,"mcps":1,"plugins":1},
         "tags":[{"id":"git","label":"Git","count":2},{"id":"bad"}],
         "entries":[
          {"id":"pr-review","type":"skill","name":"PR Review","tagline":"t","description":"d","tags":["git"],"featured":true,"verified":true,
           "author":{"name":"Cline","url":"https://cline.bot"},"install":{"command":"npx","args":["cline/skills","--skill","pr-review"]}},
          {"id":"github","type":"mcp","name":"GitHub","tagline":"t","description":"d","tags":["git"],
           "install":{"command":"cline mcp install","args":["github","--","npx","-y","gh-mcp"],"env":[{"name":"GITHUB_TOKEN","required":true,"url":"https://github.com/settings/tokens"},{"x":1}]}},
          {"id":"lint","type":"plugin","name":"Lint","tagline":"t","description":"d","install":{"command":"cline plugin install","args":["https://x/lint.git"]}},
          {"id":"noargs","type":"plugin","name":"No args","tagline":"t","description":"d","install":{"command":"x","args":[]}},
          {"id":"connector","type":"connector","name":"C","tagline":"t","description":"d","install":{"command":"x","args":["a"]}},
          {"id":"nodesc","type":"skill","name":"N","tagline":"t","install":{"command":"x","args":["a"]}}
         ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
        cacheDir = Files.createTempDirectory("marketplace-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        cacheDir.deleteRecursively()
    }

    private class Rig(val repo: MarketplaceRepository, val hub: FakeHubTransport, val termux: FakeTermuxExecutor, val helper: FakeHelperChannel, val clock: FakeClock)

    private suspend fun TestScope.rig(state: HelperState = HelperState.Ready, settingsReply: String = """{"snapshot":{"skills":[],"plugins":[],"mcp":[]}}"""): Rig {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            if (env.command == HubCommands.SETTINGS_LIST) env.ok(CustomizeJson.parseToJsonElement(settingsReply).jsonObject) else null
        }
        val termux = FakeTermuxExecutor()
        val helper = FakeHelperChannel(state)
        val clock = FakeClock()
        val repo = MarketplaceRepository(
            http = MarketplaceRepository.httpClient(cacheDir),
            hub = hub,
            termux = termux,
            helper = helper,
            clock = clock,
            catalogUrl = server.url("/marketplace/catalog.json").toString(),
            io = UnconfinedTestDispatcher(testScheduler),
        )
        return Rig(repo, transport, termux, helper, clock)
    }

    private fun catalogResponse(cacheControl: String = "max-age=0") =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").addHeader("Cache-Control", cacheControl).body(catalogJson).build()

    @Test
    fun `parses the catalog like fetchMarketplaceCatalog`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig()
        val loaded = r.repo.catalog() as MarketplaceRepository.CatalogLoad.Loaded
        assertFalse(loaded.stale)
        val catalog = loaded.catalog
        assertEquals(listOf("pr-review", "github", "lint"), catalog.entries.map { it.id })
        assertEquals(listOf("git"), catalog.tags.map { it.id })
        assertEquals(2, catalog.version)
        assertEquals(4, catalog.counts.total)
        val github = catalog.find(MarketplaceType.Mcp, "github")!!
        assertEquals(listOf("GITHUB_TOKEN"), github.install.env!!.map { it.name })
        assertEquals("mcp:github", github.key)
        assertTrue(catalog.entries.first().featured == true)
        assertEquals("application/json", server.takeRequest().headers["Accept"])
    }

    @Test
    fun `the catalog is held in memory for 10 minutes`() = runTest {
        server.enqueue(catalogResponse())
        server.enqueue(catalogResponse())
        val r = rig()
        r.repo.catalog()
        r.repo.catalog()
        assertEquals(1, server.requestCount)
        r.clock.advanceMillis(10 * 60_000L + 1)
        r.repo.catalog()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a network failure falls back to the disk cache as stale`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig()
        r.repo.catalog()
        server.close()
        val again = r.repo.catalog(force = true) as MarketplaceRepository.CatalogLoad.Loaded
        assertTrue(again.stale)
        assertEquals(3, again.catalog.entries.size)
    }

    @Test
    fun `an http error with nothing cached is the desktop message`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        val r = rig()
        val error = r.repo.catalog() as MarketplaceRepository.CatalogLoad.Error
        assertEquals(503, error.httpStatus)
        assertEquals("Failed to fetch marketplace: 503", error.message)
    }

    @Test
    fun `installed keys come from the helper, or approximately from settings list`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig(
            settingsReply = """{"snapshot":{"skills":[{"id":"s","name":"pr review","path":"/h/.cline/skills/pr-review/SKILL.md","kind":"skill","source":"global"},
                {"id":"s2","name":"PR-REVIEW","path":"/h/.cline/skills/x/SKILL.md","kind":"skill","source":"global"}],
                "mcp":[{"id":"m","name":"github","path":"","kind":"mcp","source":"global"}],
                "plugins":[{"id":"p","name":"other","path":"/h/.cline/plugins/lint","kind":"plugin","source":"global-plugin"}]}}""",
        )
        val catalog = (r.repo.catalog() as MarketplaceRepository.CatalogLoad.Loaded).catalog
        r.helper.client.respond(MarketplaceRepository.LIST_INSTALLED, CustomizeJson.parseToJsonElement("""{"installedKeys":["skill:pr-review"]}"""))
        val viaHelper = (r.repo.installedKeys(catalog, "/w") as Outcome.Ok).value
        assertEquals(MarketplaceRepository.Installed(setOf("skill:pr-review"), approximate = false), viaHelper)
        val sent = r.helper.client.callsFor(MarketplaceRepository.LIST_INSTALLED).single().args["entries"] as JsonArray
        assertEquals(3, sent.size)

        r.helper.unavailable()
        val approx = (r.repo.installedKeys(catalog, "/w") as Outcome.Ok).value
        assertTrue(approx.approximate)
        assertEquals(setOf("skill:pr-review", "mcp:github", "plugin:lint"), approx.keys)
        assertEquals("false", r.hub.current.command(HubCommands.SETTINGS_LIST)!!.payload!!["includePluginTools"]!!.jsonPrimitive.content)
    }

    @Test
    fun `install goes through the helper with the full entry and a 300 s timer`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig()
        val entry = (r.repo.catalog() as MarketplaceRepository.CatalogLoad.Loaded).catalog.entries.first()
        r.helper.client.respond(MarketplaceRepository.INSTALL, CustomizeJson.parseToJsonElement("""{"status":"installed","message":"Installed PR Review globally for Cline."}"""))
        assertEquals(
            MarketplaceRepository.ActionResult.Done(MarketplaceDone.Message("Installed PR Review globally for Cline.")),
            r.repo.install(entry, "/w"),
        )
        val call = r.helper.client.callsFor(MarketplaceRepository.INSTALL).single()
        assertEquals(300_000L, call.timeoutMs)
        assertEquals("pr-review", call.args["entry"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        r.helper.client.respond(MarketplaceRepository.UNINSTALL) { throw HelperUnavailable.TransportClosed() }
        assertEquals(MarketplaceRepository.ActionResult.Unknown, r.repo.uninstall(entry, "/w"))
    }

    @Test
    fun `the CLI fallback uses the dashboard commands and typed results`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig(state = HelperState.Idle, settingsReply = """{"snapshot":{"skills":[{"id":"s","name":"PR Review","path":"/h/.agents/skills/pr-review/SKILL.md","kind":"skill","source":"global"}]}}""")
        val catalog = (r.repo.catalog() as MarketplaceRepository.CatalogLoad.Loaded).catalog
        val skill = catalog.find(MarketplaceType.Skill, "pr-review")!!
        val mcp = catalog.find(MarketplaceType.Mcp, "github")!!
        val plugin = catalog.find(MarketplaceType.Plugin, "lint")!!

        assertEquals(MarketplaceRepository.ActionResult.Done(MarketplaceDone.Installed("PR Review", global = true)), r.repo.install(skill, "/w"))
        assertEquals(listOf("-y", "skills@latest", "add", "cline/skills", "--skill", "pr-review", "-g", "-a", "cline", "-y"), r.termux.commands.last().args)
        assertEquals(MarketplaceRepository.ActionResult.Done(MarketplaceDone.Installed("GitHub", global = false)), r.repo.install(mcp, "/w"))
        assertEquals(listOf("mcp", "install", "--yes", "--json", "github", "--", "npx", "-y", "gh-mcp"), r.termux.commands.last().args)

        r.termux.on(ClineCommands.Labels.PLUGIN_INSTALL, TermuxResult.failure(1, "clone failed token=abc123"))
        val failed = r.repo.install(plugin, "/w") as MarketplaceRepository.ActionResult.Failed
        val cli = failed.failure as MarketplaceFailure.Cli
        assertEquals(CliStep.PluginInstall, cli.step)
        assertEquals(1, cli.exitCode)
        assertTrue(cli.output.startsWith("clone failed"))
        assertFalse(cli.output.contains("abc123"))

        assertEquals(MarketplaceRepository.ActionResult.Done(MarketplaceDone.Uninstalled("PR Review")), r.repo.uninstall(skill, "/w"))
        assertEquals(listOf("skill", "remove", "pr-review", "-g", "-y"), r.termux.commands.last().args)
        assertEquals(MarketplaceRepository.ActionResult.Done(MarketplaceDone.Uninstalled("GitHub")), r.repo.uninstall(mcp, "/w"))
        assertEquals(listOf("mcp", "uninstall", "github", "--json"), r.termux.commands.last().args)

        r.termux.on(ClineCommands.Labels.MCP_UNINSTALL, TermuxResult.failure(2, ""))
        assertEquals(
            MarketplaceRepository.ActionResult.Failed(MarketplaceFailure.Cli(CliStep.McpUninstall, 2, "")),
            r.repo.uninstall(mcp, "/w"),
        )
    }

    @Test
    fun `the CLI fallback reports a missing skill and a multi-source plugin as typed results`() = runTest {
        server.enqueue(catalogResponse())
        val r = rig(state = HelperState.Idle, settingsReply = """{"snapshot":{"skills":[]}}""")
        val catalog = (r.repo.catalog() as MarketplaceRepository.CatalogLoad.Loaded).catalog
        val skill = catalog.find(MarketplaceType.Skill, "pr-review")!!
        val plugin = catalog.find(MarketplaceType.Plugin, "lint")!!

        assertEquals(MarketplaceRepository.ActionResult.Done(MarketplaceDone.NotInstalled("PR Review")), r.repo.uninstall(skill, "/w"))
        val twoSources = plugin.copy(install = plugin.install.copy(args = listOf("a", "b")))
        assertEquals(MarketplaceRepository.ActionResult.Failed(MarketplaceFailure.PluginArguments), r.repo.install(twoSources, "/w"))
    }
}
