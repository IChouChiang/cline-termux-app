package dev.cline.termux

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cline.termux.TestAppGraph.awaitCommand
import dev.cline.termux.TestAppGraph.awaitDescription
import dev.cline.termux.TestAppGraph.awaitText
import dev.cline.termux.TestAppGraph.awaitNode
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.debug.KernelOverrides
import dev.cline.termux.ui.navigation.ChatSheet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five critical flows of docs/07 §10.2, end to end through the production UI, ViewModels,
 * stores and `HubClient`, against the debug fake kernel and recorded Hub traffic
 * ([FixtureHub]). Run on a device with `bash scripts/build.sh :app:connectedDebugAndroidTest`
 * (never on the S25U during a port release window, CLAUDE.md).
 */
@RunWith(AndroidJUnit4::class)
class CriticalFlowsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var activity: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        closeActivity()
        TestAppGraph.installHub(null)
    }

    // ---- 1. first run -> connected ---------------------------------------------------------------

    @Test
    fun firstRunReachesHomeConnected() {
        TestAppGraph.prepare(onboarded = false)
        TestAppGraph.installHub(TestAppGraph.fixtureHub("02-run-with-tool-approval.jsonl"))
        activity = TestAppGraph.launch(TestAppGraph.SCENARIO_AUTH_OK, resetOnboarding = true)

        // A0 Welcome.
        val getStarted = TestAppGraph.string(R.string.onboarding_welcome_cta)
        rule.awaitText(getStarted)
        rule.onNode(hasText(getStarted)).performClick()

        // A Kernel setup: the checks pass, row 5 waits for "Start kernel" on the first run.
        val start = TestAppGraph.string(R.string.kernel_start)
        rule.awaitText(start)
        rule.onAllNodes(hasText(start)).onFirst().performClick()
        val cont = TestAppGraph.string(R.string.onboarding_continue)
        rule.awaitText(TestAppGraph.string(R.string.hub_at_port, "25463"), timeoutMs = 90_000)
        rule.awaitText(cont)
        rule.onAllNodes(hasText(cont)).onFirst().performClick()

        // D Reliability (shown only when something verifiable is missing on this device).
        val skip = TestAppGraph.string(R.string.reliability_skip)
        val connectTitle = TestAppGraph.string(R.string.connect_api_title)
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasText(skip)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasContentDescription(connectTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText(skip)).fetchSemanticsNodes().isNotEmpty()) rule.onNode(hasText(skip)).performClick()

        // C Connect a model: the fallback `cline auth --provider … --apikey …` route.
        // Each option card is one labelled radio node (its texts are cleared into the label).
        rule.awaitDescription(connectTitle)
        rule.onNode(hasContentDescription(connectTitle)).performClick()
        // The provider field ignores taps until its options have loaded: tap until the menu opens.
        // Exact match: "Anthropic, OpenAI, OpenRouter, and more." on the key card is already on screen.
        val menuOpened = (1..20).any {
            rule.onNode(hasText(TestAppGraph.string(R.string.connect_provider_label)) and hasSetTextAction().not()).performClick()
            runCatching { rule.awaitNode(hasText("Anthropic"), timeoutMs = 3_000) }.isSuccess
        }
        assertTrue("the provider menu never opened", menuOpened)
        rule.onAllNodes(hasText("Anthropic")).onFirst().performClick()
        rule.onNode(hasSetTextAction() and hasText(TestAppGraph.string(R.string.connect_api_key_label), substring = true)).performTextInput("sk-ant-test-key")
        rule.onNode(hasText(TestAppGraph.string(R.string.connect_connect))).performClick()

        // C5 Done -> Home.
        val startBuilding = TestAppGraph.string(R.string.done_start_building)
        rule.awaitText(startBuilding, timeoutMs = 60_000)
        rule.onNode(hasText(startBuilding)).performClick()
        rule.awaitDescription(TestAppGraph.string(R.string.components_hero_a11y))

        // The kernel chip in the drawer header reads connected.
        rule.onNode(hasContentDescription(TestAppGraph.string(R.string.components_open_sessions))).performClick()
        rule.awaitText(TestAppGraph.string(R.string.components_kernel_connected, "25463"))
    }

    // ---- 2. send -> stream -> complete -----------------------------------------------------------

    @Test
    fun sendStreamsAndCompletes() {
        val hub = readyHome("02-run-with-tool-approval.jsonl")
        send("hello-from-mock")

        // The local bubble, then the recorded assistant text and the tool row.
        rule.awaitText("hello-from-mock")
        rule.awaitCommand(hub, HubCommands.SESSION_CREATE) { it.payload?.get("metadata")?.jsonObject?.str("source") == "android" }
        rule.awaitCommand(hub, HubCommands.SESSION_SEND_INPUT)
        rule.awaitText("I'll run the command now.")
        approve()
        rule.awaitText("The command ran successfully.", timeoutMs = 60_000)

        // The run completed: the composer offers Send again, and the session is in the drawer.
        rule.awaitDescription(TestAppGraph.string(R.string.composer_cd_send))
        val create = hub.received.first { it.command == HubCommands.SESSION_CREATE }.payload!!
        assertEquals(true, create["sessionConfig"]?.jsonObject?.get("checkpoint")?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull)
        assertFalse(create.toString().contains("apiKey"))
        rule.onNode(hasContentDescription(TestAppGraph.string(R.string.components_open_sessions))).performClick()
        rule.awaitText("hello-from-mock")
    }

    // ---- 3. approval sheet -----------------------------------------------------------------------

    @Test
    fun approvalSheetApprovesRejectsCollapsesAndStops() {
        // Approve.
        var hub = readyHome("02-run-with-tool-approval.jsonl")
        send("hello-from-mock")
        val approveLabel = TestAppGraph.string(R.string.approval_approve)
        rule.awaitText(approveLabel, timeoutMs = 60_000)
        approve()
        rule.awaitCommand(hub, HubCommands.APPROVAL_RESPOND) { it.payload?.get("approved")?.jsonPrimitive?.booleanOrNull == true }

        // The same recording again: Reject sends the documented reason and the turn continues.
        hub = replaceHub("02-run-with-tool-approval.jsonl")
        newSession()
        send("hello-from-mock")
        rule.awaitText(TestAppGraph.string(R.string.approval_reject), timeoutMs = 60_000)
        rule.onNode(hasText(TestAppGraph.string(R.string.approval_reject))).performClick()
        rule.awaitCommand(hub, HubCommands.APPROVAL_RESPOND) { env ->
            env.payload?.get("approved")?.jsonPrimitive?.booleanOrNull == false &&
                env.payload?.str("reason") == "This tool call was rejected by the user and not executed."
        }
        rule.awaitText("The command ran successfully.", timeoutMs = 60_000)

        // Collapse to the banner never rejects; "Stop agent" sends run.abort.
        hub = replaceHub("02-run-with-tool-approval.jsonl")
        newSession()
        send("hello-from-mock")
        rule.awaitText(approveLabel, timeoutMs = 60_000)
        backUntilGone(TestAppGraph.string(R.string.approval_description))
        // The banner exposes one button node (clearAndSetSemantics): its content description.
        val approvalBanner = TestAppGraph.string(R.string.approval_banner_cd_single)
        rule.awaitDescription(approvalBanner)
        assertTrue(hub.received.none { it.command == HubCommands.APPROVAL_RESPOND })
        rule.onNode(hasContentDescription(approvalBanner)).performSemanticsAction(SemanticsActions.OnClick)
        rule.awaitText(TestAppGraph.string(R.string.approval_stop))
        rule.onNode(hasText(TestAppGraph.string(R.string.approval_stop))).performClick()
        rule.awaitCommand(hub, HubCommands.RUN_ABORT)
    }

    // ---- 4. ask-question -------------------------------------------------------------------------

    @Test
    fun askQuestionAnswersByOptionAndCustomText() {
        val hub = readyHome("02-run-with-tool-approval.jsonl")
        send("hello-from-mock")
        rule.awaitCommand(hub, HubCommands.SESSION_SEND_INPUT)
        val sessionId = awaitSessionId(hub)
        val clientId = checkNotNull(hub.clientId)

        // Option B answers at once.
        hub.emit(HubEvents.CAPABILITY_REQUESTED, sessionId, FixtureHub.askQuestionPayload("q-1", clientId, "Which colour?", listOf("Red", "Green", "Blue")))
        rule.awaitText("Which colour?")
        rule.onNode(hasText("Green")).performClick()
        rule.awaitCommand(hub, HubCommands.CAPABILITY_RESPOND) { env ->
            env.payload?.str("requestId") == "q-1" && env.payload?.get("payload")?.jsonObject?.str("result") == "Green"
        }
        hub.emit(HubEvents.CAPABILITY_RESOLVED, sessionId, buildJsonObject { put("requestId", "q-1"); put("ok", true) })

        // A custom answer survives collapse -> banner -> reopen, then Submit sends it.
        hub.emit(HubEvents.CAPABILITY_REQUESTED, sessionId, FixtureHub.askQuestionPayload("q-2", clientId, "Anything else?", listOf("No")))
        rule.awaitText("Anything else?")
        rule.onNode(hasSetTextAction() and hasText(TestAppGraph.string(R.string.question_custom_placeholder), substring = true)).performTextInput("Use teal")
        backUntilGone("Anything else?")
        rule.awaitDescription(TestAppGraph.string(R.string.question_banner_cd))
        rule.onNode(hasContentDescription(TestAppGraph.string(R.string.question_banner_cd))).performSemanticsAction(SemanticsActions.OnClick)
        rule.awaitText("Use teal")
        rule.onNode(hasText(TestAppGraph.string(R.string.question_submit))).performClick()
        rule.awaitCommand(hub, HubCommands.CAPABILITY_RESPOND) { env ->
            env.payload?.str("requestId") == "q-2" && env.payload?.get("payload")?.jsonObject?.str("result") == "Use teal"
        }
    }

    // ---- 5. resume and interruption; notification deep link --------------------------------------

    @Test
    fun interruptedTurnResumesAndDeepLinkOpensApproval() {
        // Fixture 02 up to the approval (turn open), then fixture 05 (the Hub after its restart).
        val hub = readyHome("02-run-with-tool-approval.jsonl", "05-resume-after-restart.jsonl")
        send("hello-from-mock")
        rule.awaitText(TestAppGraph.string(R.string.approval_approve), timeoutMs = 60_000)
        val sessionId = awaitSessionId(hub)
        val runId = hub.recordedEvent(HubEvents.RUN_STARTED)?.payload?.str("runId") ?: "run_fixture"
        hub.emit(HubEvents.RUN_INTERRUPTED, sessionId, buildJsonObject { put("runId", runId); put("reason", "hub_restart") })
        rule.awaitText(TestAppGraph.string(R.string.run_interrupted))

        // Resend: send_input meets session_not_found (fixture 05), the app resumes with
        // session.create {sessionConfig.sessionId, initialMessages} and the new daemon's events render.
        rule.onAllNodes(hasText(TestAppGraph.string(R.string.run_resend))).onFirst().performClick()
        rule.awaitCommand(hub, HubCommands.SESSION_CREATE, timeoutMs = 60_000) { env ->
            env.payload?.get("sessionConfig")?.jsonObject?.str("sessionId") == sessionId && env.payload?.containsKey("initialMessages") == true
        }
        rule.waitUntil(60_000) {
            val resumed = hub.received.indexOfFirst { it.command == HubCommands.SESSION_CREATE && it.payload?.containsKey("initialMessages") == true }
            resumed >= 0 && hub.received.drop(resumed + 1).any { it.command == HubCommands.SESSION_SEND_INPUT }
        }

        // A notification tap (SESSION_ID + OPEN_SHEET=approval) cold-starts into the approval sheet.
        closeActivity()
        val deepHub = replaceHub("02-run-with-tool-approval.jsonl")
        val approval = deepHub.recordedEvent(HubEvents.APPROVAL_REQUESTED)!!
        deepHub.onSessionSubscribe = { id -> if (id == approval.sessionId) deepHub.reissue(approval) }
        activity = TestAppGraph.launch(TestAppGraph.SCENARIO_READY, deepLink = TestAppGraph.sessionLink(approval.sessionId!!, ChatSheet.Approval))
        rule.awaitText(TestAppGraph.string(R.string.approval_approve), timeoutMs = 60_000)
        // The deep-linked sheet answers the approval and closes.
        approve()
        rule.awaitCommand(deepHub, HubCommands.APPROVAL_RESPOND)
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(TestAppGraph.string(R.string.approval_description), substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Finishes the activity while it is still in front, then lets [ActivityScenario] confirm it.
     * `close()` alone first covers it with the runner's EmptyActivity; One UI's app freezer can
     * freeze the backgrounded app process right then (main thread in `do_freezer_trap`), and
     * `close()` waits forever for a `finish()` that never runs.
     */
    private fun closeActivity() {
        val scenario = activity ?: return
        activity = null
        runCatching { scenario.onActivity { it.finish() } }
        scenario.close()
    }

    /** Onboarded app on Home with the kernel connected to a hub replaying [fixtures]. */
    private fun readyHome(vararg fixtures: String): FixtureHub {
        TestAppGraph.prepare(onboarded = true)
        val hub = TestAppGraph.fixtureHub(*fixtures)
        TestAppGraph.installHub(hub)
        activity = TestAppGraph.launch(TestAppGraph.SCENARIO_READY)
        rule.awaitDescription(TestAppGraph.string(R.string.components_hero_a11y), timeoutMs = 60_000)
        rule.waitUntil(90_000) { hub.clientId != null }
        return hub
    }

    private fun replaceHub(vararg fixtures: String): FixtureHub {
        val hub = TestAppGraph.fixtureHub(*fixtures)
        TestAppGraph.installHub(hub)
        rule.waitUntil(60_000) { hub.received.any { it.command == HubCommands.CLIENT_REGISTER } }
        return hub
    }

    private fun newSession() {
        rule.onNode(hasContentDescription(TestAppGraph.string(R.string.components_open_sessions))).performClick()
        rule.awaitText(TestAppGraph.string(R.string.shell_new_session))
        rule.onAllNodes(hasText(TestAppGraph.string(R.string.shell_new_session))).onFirst().performClick()
        rule.awaitDescription(TestAppGraph.string(R.string.components_hero_a11y))
    }

    private fun send(prompt: String) {
        // The composer restores a persisted draft (an earlier test's unsent text); start empty.
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(prompt)
        // The composer's toolbar (with Send) shows once the provider catalog knows a configured
        // provider (the cold-start read of providers.json); without one Home shows the setup notice.
        val sendLabel = TestAppGraph.string(R.string.composer_cd_send)
        val ready = runCatching { rule.awaitDescription(sendLabel, timeoutMs = 30_000) }.isSuccess
        if (!ready) {
            val catalog = TestAppGraph.graph.providerCatalog.state.value
            throw AssertionError("no Send button: $catalog, presence=${catalog.presence?.configuredIds}, fake kernel=${KernelOverrides.fakeKernelTrace().takeLast(8)}")
        }
        rule.onNode(hasContentDescription(sendLabel)).performClick()
    }

    /**
     * A real system Back to the focused window. A sheet is its own window, so the activity's
     * `OnBackPressedDispatcher` would skip it and pop the chat instead; the key event reaches the
     * IME first, then the sheet, as on the device.
     */
    private fun systemBack() {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    /**
     * System Back until the sheet showing [marker] is gone (at most three presses): the first
     * press may only close the keyboard, or land before the sheet's window took focus.
     */
    private fun backUntilGone(marker: String) {
        repeat(3) {
            systemBack()
            val gone = runCatching {
                rule.waitUntil(4_000) { rule.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            }.isSuccess
            if (gone) return
        }
        throw AssertionError("the sheet showing \"$marker\" did not close on Back")
    }

    private fun approve() {
        val approve = TestAppGraph.string(R.string.approval_approve)
        rule.awaitText(approve, timeoutMs = 60_000)
        rule.onNode(hasText(approve)).performClick()
    }

    private fun awaitSessionId(hub: FixtureHub): String {
        // The session id travels on the envelope (`session.send_input` payload is `{prompt, mode}`).
        rule.waitUntil(30_000) { hub.received.any { it.command == HubCommands.SESSION_SEND_INPUT && it.sessionId != null } }
        return hub.received.first { it.command == HubCommands.SESSION_SEND_INPUT && it.sessionId != null }.sessionId!!
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
