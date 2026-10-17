package dev.cline.termux.ui.chat.rows

import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.ui.chat.ThinkingIndicatorRow
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.LocalReducedMotion
import dev.cline.termux.ui.theme.Spacing
import java.time.Duration
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer

/**
 * A23 and A24 on real compositions: every tool row starts folded whatever its body holds and
 * opens only on its tap (staying open while it updates), and the tool, thinking, reasoning and
 * "Worked for…" rows are one [DisclosureRowTokens.Height] tall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1400dp-xxhdpi")
class DisclosureRowsTest {
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var activity: ComponentActivity
    private val callbacks = TranscriptCallbacks(onOpenToolDetail = { _, _ -> })

    private val collapsed get() = activity.getString(R.string.chat_row_collapsed)
    private val expanded get() = activity.getString(R.string.chat_row_expanded)
    private val viewDetails get() = activity.getString(R.string.chat_tool_view_details)

    @Before
    fun setUp() {
        // Animations off, as the system setting turns them off: Compose then ends the running
        // rows' spinners and shimmer at once instead of drawing frames for as long as they show,
        // which under Robolectric costs minutes per test. Frames at 60 Hz for what remains.
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()
    }

    /**
     * The activity goes with the test and the main looper is drained: a Compose dispatch left
     * posted there is dropped by the next test's looper reset, which leaves Compose's main
     * dispatcher waiting for good, and every later Compose test in this sandbox stops
     * recomposing (see `RouteNavigationTest.drainMainLooper`).
     */
    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- samples: one row per kind of body the desktop opened by itself ---------------------

    private fun tool(id: String, content: String, meta: Meta = Meta(), sessionId: String = "s1") = ChatMessage(
        id = "t:$id",
        sessionId = sessionId,
        role = Role.Tool,
        content = content,
        createdAt = 1,
        meta = meta.copy(toolCallId = id),
    )

    private val diff = tool("edit", """{"toolName":"editor","input":{"path":"a.ts","old_text":"x","new_text":"y"},"result":"ok"}""")
    private val media = tool(
        "shot",
        """{"toolName":"computer","input":{},"result":{"content":[{"type":"image","data":"aGVsbG8=","mimeType":"image/png"}]}}""",
    )
    private val running = tool(
        "run",
        """{"toolName":"run_commands","input":{"commands":["echo hi"]}}""",
        Meta(toolName = "run_commands", toolOutput = "hi\n", toolDetachable = true),
    )
    private val ran = tool("run", """{"toolName":"run_commands","input":{"commands":["echo hi"]},"result":[{"query":"echo hi","result":"hi\n","success":true}]}""")
    private val submitting = tool("submit", """{"toolName":"submit_and_exit","input":{"summary":"All done."}}""")
    private val submitted = tool("submit", """{"toolName":"submit_and_exit","input":{"summary":"All done."},"result":"ok"}""")

    /** The live run: the diff and media rows finished, the command and the submit still running. */
    private fun liveRows() = listOf(
        toolRowFacts(diff, isRunActive = true, showInputPreview = false),
        toolRowFacts(media, isRunActive = true, showInputPreview = false),
        toolRowFacts(running, isRunActive = true, showInputPreview = false),
        toolRowFacts(submitting, isRunActive = true, showInputPreview = false),
    )

    /** The same calls as a reopened session loads them from history: every one finished. */
    private fun historyRows() = listOf(diff, media, ran, submitted).map { toolRowFacts(it, isRunActive = false, showInputPreview = false) }

    // ---- harness --------------------------------------------------------------------------

    private fun show(content: @Composable () -> Unit) {
        activity.setContent {
            ClinePreviewTheme(dark = false) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Surface {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) { content() }
                    }
                }
            }
        }
        idle()
    }

    private fun idle() {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    private fun root(view: View = activity.window.decorView): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun nodes(): List<SemanticsNode> = root()!!.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)

    /** The disclosure headers that toggle, top to bottom. */
    private fun headers(): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.StateDescription) != null }.sortedBy { it.boundsInRoot.top }

    private fun states(): List<String> = headers().map { it.config[SemanticsProperties.StateDescription] }

    private fun tap(node: SemanticsNode) {
        node.config[SemanticsActions.OnClick].action!!.invoke()
        idle()
    }

    /** "View details" ends every open tool body, so it counts the open rows. */
    private fun openBodies(): Int = nodes().count { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == viewDetails } == true
    }

    private fun px(dp: androidx.compose.ui.unit.Dp): Int = (dp.value * activity.resources.displayMetrics.density).roundToInt()

    // ---- A23 --------------------------------------------------------------------------------

    @Test
    fun `rows with a diff, media, running output or submit text start folded`() {
        val rows = liveRows()
        rows.forEach { assertTrue(it.model.expandable) }
        show { rows.forEach { ToolCallRow(it, callbacks) } }
        assertEquals(List(4) { collapsed }, states())
        assertEquals(0, openBodies())
    }

    @Test
    fun `a tap opens only that row and the next tap folds it`() {
        val rows = liveRows()
        show { rows.forEach { ToolCallRow(it, callbacks) } }
        tap(headers()[2])
        assertEquals(listOf(collapsed, collapsed, expanded, collapsed), states())
        assertEquals(1, openBodies())
        tap(headers()[2])
        assertEquals(List(4) { collapsed }, states())
        assertEquals(0, openBodies())
    }

    @Test
    fun `an opened row stays open while its output grows and its run ends`() {
        var facts by mutableStateOf(toolRowFacts(running, isRunActive = true, showInputPreview = false))
        show { ToolCallRow(facts, callbacks) }
        tap(headers().single())
        assertEquals(listOf(expanded), states())
        facts = toolRowFacts(running.copy(meta = running.meta.copy(toolOutput = "hi\nmore\n")), isRunActive = true, showInputPreview = false)
        idle()
        assertEquals(listOf(expanded), states())
        facts = toolRowFacts(ran, isRunActive = false, showInputPreview = false)
        idle()
        assertEquals(listOf(expanded), states())
        assertEquals(1, openBodies())
    }

    @Test
    fun `a reopened session shows its rows folded, and one the user opened stays open`() {
        var session by mutableStateOf("s1")
        val rows = historyRows()
        show {
            // As ChatTranscript keeps it: the rows' saved state per session, within the process.
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(session) {
                if (session == "s1") rows.forEach { ToolCallRow(it, callbacks) }
            }
        }
        assertEquals(List(4) { collapsed }, states())
        tap(headers()[2])
        session = "s2"
        idle()
        assertTrue(headers().isEmpty())
        session = "s1"
        idle()
        assertEquals(listOf(collapsed, collapsed, expanded, collapsed), states())
        // A new screen (a new process holds no saved rows): everything folded again.
        show { rows.forEach { ToolCallRow(it, callbacks) } }
        assertEquals(List(4) { collapsed }, states())
    }

    // ---- A24 --------------------------------------------------------------------------------

    @Test
    fun `the compact metrics are the decided ones, declared once`() {
        assertEquals(40.dp, DisclosureRowTokens.Height)
        assertEquals(18.dp, DisclosureRowTokens.IconSize)
        assertEquals(4.dp, DisclosureRowTokens.BlockGap)
        assertEquals(DisclosureRowTokens.BlockGap, TranscriptGap.Block.height)
        assertEquals(DisclosureRowTokens.BlockGap + Spacing.l, TranscriptGap.Turn.height)
        assertEquals(0.dp, TranscriptGap.Tight.height)
        assertEquals(DisclosureRowTokens.IconSize / 2, DisclosureRowTokens.RailLineInset)
        assertEquals(DisclosureRowTokens.RailLineInset + DisclosureRowTokens.RailGap, DisclosureRowTokens.RailContentInset)
        assertTrue(DisclosureRowTokens.SpinnerSize < DisclosureRowTokens.IconSize)
    }

    @Test
    fun `the label styles are bodySmall and the small mono`() {
        var label: TextStyle? = null
        var code: TextStyle? = null
        var bodySmall: TextStyle? = null
        var monoSmall: TextStyle? = null
        show {
            label = DisclosureRowTokens.labelStyle
            code = DisclosureRowTokens.labelCodeStyle
            bodySmall = MaterialTheme.typography.bodySmall
            monoSmall = ClineMono.small
        }
        assertEquals(bodySmall, label)
        assertEquals(monoSmall, code)
        assertEquals(label!!.lineHeight, code!!.lineHeight)
    }

    @Test
    fun `tool, reasoning, work and thinking rows are one dense row tall`() {
        val rows = liveRows()
        show {
            rows.forEach { ToolCallRow(it, callbacks) }
            ReasoningBlock(ReasoningContent("Check the guard first.", false), streaming = false, durationMilliseconds = 4_000, stateKey = "r1")
            WorkBlock(id = "w1", durationMilliseconds = 72_000, toolCallCount = 6) {}
            ThinkingIndicatorRow()
        }
        val thinking = activity.getString(R.string.chat_thinking)
        val headers = headers() + nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(thinking) == true }
        assertEquals(7, headers.size)
        headers.forEach { assertEquals(px(DisclosureRowTokens.Height), it.boundsInRoot.height.roundToInt()) }
    }

    @Test
    fun `six folded tool calls take six row heights`() {
        val calls = (1..6).map { index ->
            toolRowFacts(
                tool("c$index", """{"toolName":"run_commands","input":{"commands":["echo $index"]},"result":[{"query":"echo $index","result":"$index\n","success":true}]}"""),
                isRunActive = false,
                showInputPreview = false,
            )
        }
        var height = 0
        show {
            Column(Modifier.onGloballyPositioned { height = it.size.height }) {
                calls.forEach { ToolCallRow(it, callbacks) }
            }
        }
        assertEquals(List(6) { collapsed }, states())
        assertEquals(px(DisclosureRowTokens.Height) * 6, height)
    }
}
