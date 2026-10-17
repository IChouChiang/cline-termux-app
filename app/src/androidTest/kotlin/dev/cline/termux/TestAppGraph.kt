package dev.cline.termux

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cline.termux.debug.KernelOverrides
import dev.cline.termux.ui.kernel.quantityResources
import dev.cline.termux.ui.navigation.ChatSheet
import dev.cline.termux.ui.navigation.DeepLink
import dev.cline.termux.ui.navigation.DeepLinks
import kotlinx.coroutines.runBlocking

/**
 * The app graph as the Compose UI flows of docs/07 §10.2 run it: the real [AppGraph] of the
 * instrumented process, with the debug fake kernel (KS §0.9, `FAKE_KERNEL` scenario) behind
 * Termux and the loopback probes, and a [FixtureHub] behind the Hub socket
 * (`KernelOverrides.testHubSockets`). Every repository, store and ViewModel above those edges is
 * the production code.
 */
object TestAppGraph {
    /** Termux present, port release current, Hub not running yet (the coordinator starts it), Anthropic configured. */
    const val SCENARIO_READY = "providers_anthropic"

    /** Termux present, no provider yet; the fallback `cline auth --provider … --apikey …` succeeds. */
    const val SCENARIO_AUTH_OK = "auth_ok"

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    val graph: AppGraph get() = (targetContext.applicationContext as ClineApp).graph

    @Volatile private var hub: FixtureHub? = null

    /**
     * A Hub replaying [fixtures] (asset names under `hub-fixtures/`, concatenated in order). The
     * `session.messages` histories come from the fixtures themselves and from
     * [HISTORY_FIXTURE], which recorded the transcript of fixture 02's session.
     */
    fun fixtureHub(vararg fixtures: String): FixtureHub {
        val lines = fixtures.flatMap(::fixtureLines)
        val histories = FixtureHub.historiesOf(fixtureLines(HISTORY_FIXTURE)) + FixtureHub.historiesOf(lines)
        return FixtureHub(lines, histories = histories)
    }

    private const val HISTORY_FIXTURE = "04-history-and-replay.jsonl"

    private fun fixtureLines(name: String) =
        FixtureHub.parse(InstrumentationRegistry.getInstrumentation().context.assets.open("hub-fixtures/$name").bufferedReader().use { it.readText() })

    /**
     * Installs [next] as the Hub: the previous fixture hub drops its sockets, so the client
     * reconnects to [next] with the same client id (KC reconnect policy).
     */
    fun installHub(next: FixtureHub?) {
        val previous = hub
        hub = next
        KernelOverrides.testHubSockets = next?.let { h -> { _, _ -> h.open() } }
        previous?.close()
    }

    /** Onboarding done (or not), What's new already seen, the kernel not stopped by the user. */
    fun prepare(onboarded: Boolean) = runBlocking {
        // A developer Hub token (scripts/dev-hub.sh reverse) would bypass the fake kernel.
        KernelOverrides.clearHubToken(targetContext)
        val prefs = graph.prefs
        prefs.setOnboardingCompleted(if (onboarded) System.currentTimeMillis() else null)
        prefs.setUserStopped(false)
        if (onboarded) {
            graph.whatsNew.markCurrentSeen()
            prefs.setOnboardingReliabilityShownAt()
        }
    }

    /** Starts `MainActivity` with the fake-kernel [scenario] and an optional notification [deepLink]. */
    fun launch(scenario: String, resetOnboarding: Boolean = false, deepLink: DeepLink? = null): ActivityScenario<MainActivity> {
        val intent = Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(KernelOverrides.EXTRA_FAKE_KERNEL, scenario)
            .putExtra(KernelOverrides.EXTRA_RESET_ONBOARDING, resetOnboarding)
        deepLink?.let { DeepLinks.fillIn(intent, it) }
        return ActivityScenario.launch(intent)
    }

    /** The CA §0.6 notification tap: `SESSION_ID` + `OPEN_SHEET`. */
    fun sessionLink(sessionId: String, sheet: ChatSheet?): DeepLink = DeepLink.Session(sessionId, sheet)

    fun string(id: Int, vararg args: Any): String = targetContext.getString(id, *args)

    /** A plural with the form chosen by the copy's language, as the app does ([quantityResources]). */
    fun quantity(id: Int, count: Int): String = quantityResources(targetContext.resources).getQuantityString(id, count, count)

    /** Waits until a node matching [matcher] exists (merged tree). */
    fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = 30_000) = awaitNode(hasText(text, substring = true), timeoutMs)

    fun ComposeTestRule.awaitDescription(description: String, timeoutMs: Long = 30_000) =
        awaitNode(hasContentDescription(description, substring = true), timeoutMs)

    /** Waits until [hub] received a command named [command] matching [predicate]. */
    fun ComposeTestRule.awaitCommand(hub: FixtureHub, command: String, timeoutMs: Long = 30_000, predicate: (dev.cline.termux.core.kernel.hub.HubCommandEnvelope) -> Boolean = { true }) {
        waitUntil(timeoutMs) { hub.received.any { it.command == command && predicate(it) } }
    }
}
