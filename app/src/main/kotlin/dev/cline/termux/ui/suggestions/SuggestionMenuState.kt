package dev.cline.termux.ui.suggestions

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.chat.ActiveMention
import dev.cline.termux.data.chat.ActiveSlash
import dev.cline.termux.data.chat.MentionProblem
import dev.cline.termux.data.chat.MentionResult
import dev.cline.termux.data.chat.MentionSearch
import dev.cline.termux.data.chat.MentionTokens
import dev.cline.termux.data.chat.SlashCatalog
import dev.cline.termux.data.chat.SlashCommand
import dev.cline.termux.data.chat.SlashCommandSource
import dev.cline.termux.data.chat.SlashCommands
import dev.cline.termux.data.chat.TextEdit
import java.text.Collator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The panel docked above the composer field (composer-approvals.md C5.6); null when hidden. */
@Immutable
sealed interface SuggestionMenuState {
    /** The hardware-keyboard highlight (Up/Down, Tab/Enter inserts). */
    val highlighted: Int

    /** Selectable rows (the `%d suggestions` field state, the collection semantics). */
    val rowCount: Int

    @Immutable
    data class Slash(
        val query: String,
        val items: List<SlashCommand>,
        /** `settings.list` in flight: "Loading commands..." when [items] is empty, else a trailing "Loading...". */
        val loading: Boolean,
        override val highlighted: Int,
    ) : SuggestionMenuState {
        override val rowCount: Int get() = items.size
    }

    @Immutable
    data class Mention(
        val query: String,
        val items: List<String>,
        /** First search for this token, nothing to show yet: "Searching files...". */
        val loading: Boolean,
        /** Previous results shown while a new search runs: trailing "Updating...". */
        val refreshing: Boolean,
        val problem: MentionProblem?,
        override val highlighted: Int,
    ) : SuggestionMenuState {
        override val rowCount: Int get() = if (problem == null || problem == MentionProblem.Error) items.size else 0
    }
}

/** What the menus need from the kernel (KernelGate only, D12). */
enum class SuggestionKernel {
    /** Hub and RUN_COMMAND usable. */
    Up,

    /** Starting, connecting, reconnecting, restarting, draining. */
    Busy,

    /** Hub down or a prerequisite missing. */
    Down,

    /** Hub up, RUN_COMMAND revoked or blocked. */
    NoCommands,
}

fun KernelGate.toSuggestionKernel(): SuggestionKernel = when (this) {
    KernelGate.Ready -> SuggestionKernel.Up
    KernelGate.DegradedNoCommands -> SuggestionKernel.NoCommands
    is KernelGate.Busy -> SuggestionKernel.Busy
    is KernelGate.Down -> SuggestionKernel.Down
    is KernelGate.Blocked -> when (focus) {
        CheckId.Permission, CheckId.ExternalApps -> SuggestionKernel.NoCommands
        else -> SuggestionKernel.Down
    }
}

/** The composer's surroundings: the session's workspace root (or Home's selection) and the kernel. */
@Immutable
data class SuggestionContext(val workspaceRoot: String?, val kernel: SuggestionKernel)

/**
 * Drives the slash (C5) and mention (C6) menus for one composer: tracks the active token
 * (`getActiveSlash` / `getActiveMention`), loads commands on each slash open, debounces
 * file searches by 120 ms, and turns a pick into the field's new text and cursor. Back
 * dismisses the menu for the current token only. Owned by `ChatViewModel`; the composer
 * calls [update] on every text or cursor change and [setContext] when the workspace or
 * kernel changes.
 */
class SuggestionMenuController(
    private val slashSource: SlashCommandSource,
    private val mentionSearch: MentionSearch,
    private val scope: CoroutineScope,
    private val collator: Collator = Collator.getInstance(),
) {
    private val _state = MutableStateFlow<SuggestionMenuState?>(null)
    val state: StateFlow<SuggestionMenuState?> = _state.asStateFlow()

    private data class TokenKey(val slash: Boolean, val start: Int, val query: String)

    private var text = ""
    private var cursor = 0
    private var context = SuggestionContext(null, SuggestionKernel.Up)
    private var dismissed: TokenKey? = null
    private var highlighted = 0

    private var activeSlash: ActiveSlash? = null
    private var slashLoading = false
    private var slashGeneration = 0

    private var activeMention: ActiveMention? = null
    private var mentionItems: List<String> = emptyList()
    private var mentionLoading = false
    private var mentionRefreshing = false
    private var mentionProblem: MentionProblem? = null
    private var mentionKey: Pair<String, String>? = null
    private var mentionJob: Job? = null

    init {
        scope.launch { slashSource.catalogs.collect { if (activeSlash != null) render() } }
        scope.launch {
            slashSource.invalidations.drop(1).collect { if (activeSlash != null) refreshSlash() }
        }
    }

    /** The field changed: [newText] with a collapsed cursor at [newCursor]. */
    fun update(newText: String, newCursor: Int) {
        text = newText
        cursor = newCursor.coerceIn(0, newText.length)
        evaluate()
    }

    fun setContext(newContext: SuggestionContext) {
        if (newContext == context) return
        val wasUp = context.kernel.canListCommands()
        val rootChanged = newContext.workspaceRoot != context.workspaceRoot
        context = newContext
        if (rootChanged) mentionKey = null
        if (mentionProblem != null && mentionProblem != MentionProblem.Error) mentionKey = null
        if (activeSlash != null && (rootChanged || (!wasUp && newContext.kernel.canListCommands()))) refreshSlash()
        if (activeMention != null) {
            // Re-run the mention for the new surroundings (a problem may have appeared or cleared).
            val mention = activeMention!!
            activeMention = null
            openMention(mention)
        } else {
            render()
        }
    }

    /** Back or Esc (C5.9, C6.7): hides the menu for this token. True when a menu was open. */
    fun dismiss(): Boolean {
        val key = currentKey() ?: return false
        dismissed = key
        close()
        return true
    }

    /** Hardware Up/Down: moves the highlight, wrapping (desktop). */
    fun moveHighlight(delta: Int) {
        val rows = _state.value?.rowCount ?: return
        if (rows == 0) return
        highlighted = Math.floorMod(highlighted + delta, rows)
        render()
    }

    /** Hardware Tab/Enter: inserts the highlighted row. */
    fun acceptHighlighted(): TextEdit? = select(highlighted)

    /** A tap on row [index]: the field's new text and cursor (`insertSlashCommandItem` / `insertMentionFile`). */
    fun select(index: Int): TextEdit? {
        val edit = when (val current = _state.value) {
            is SuggestionMenuState.Slash -> {
                val slash = activeSlash ?: return null
                val item = current.items.getOrNull(index) ?: return null
                SlashCommands.insertSlashCommandItem(text, cursor, slash, item.name)
            }
            is SuggestionMenuState.Mention -> {
                val mention = activeMention ?: return null
                if (current.rowCount == 0) return null
                val path = current.items.getOrNull(index) ?: return null
                MentionTokens.insertMentionFile(text, mention, path)
            }
            null -> return null
        }
        update(edit.text, edit.cursor)
        return edit
    }

    private fun currentKey(): TokenKey? {
        activeSlash?.let { return TokenKey(true, it.slashIndex, it.query) }
        activeMention?.let { return TokenKey(false, it.start, it.query) }
        return null
    }

    private fun evaluate() {
        val slash = SlashCommands.getActiveSlash(text, cursor)
        val mention = MentionTokens.getActiveMention(text, cursor)
        val useSlash = slash != null && (mention == null || slash.slashIndex > mention.start)
        val key = when {
            useSlash -> TokenKey(true, slash!!.slashIndex, slash.query)
            mention != null -> TokenKey(false, mention.start, mention.query)
            else -> null
        }
        if (key == null) {
            dismissed = null
            close()
            return
        }
        if (key == dismissed) {
            close()
            return
        }
        dismissed = null
        if (useSlash) openSlash(slash!!) else openMention(mention!!)
    }

    private fun openSlash(slash: ActiveSlash) {
        if (activeMention != null) closeMention()
        val wasOpen = activeSlash != null
        val queryChanged = activeSlash?.query != slash.query
        activeSlash = slash
        if (!wasOpen || queryChanged) highlighted = 0
        if (!wasOpen) refreshSlash()
        render()
    }

    /** `settings.list` for the panel (each open, and on `settings.changed`); only while the Hub is usable. */
    private fun refreshSlash() {
        val root = context.workspaceRoot
        if (root == null || !context.kernel.canListCommands()) {
            slashLoading = false
            render()
            return
        }
        val generation = ++slashGeneration
        slashLoading = true
        render()
        scope.launch {
            try {
                slashSource.refresh(root)
            } finally {
                if (generation == slashGeneration) {
                    slashLoading = false
                    render()
                }
            }
        }
    }

    private fun openMention(mention: ActiveMention) {
        if (activeSlash != null) activeSlash = null
        val queryChanged = activeMention?.query != mention.query
        activeMention = mention
        if (queryChanged) highlighted = 0
        val problem = staticProblem()
        if (problem != null) {
            mentionJob?.cancel()
            mentionKey = null
            mentionItems = emptyList()
            mentionLoading = false
            mentionRefreshing = false
            mentionProblem = problem
            render()
            return
        }
        val root = context.workspaceRoot!!
        val key = root to mention.query
        if (key == mentionKey) {
            render()
            return
        }
        mentionKey = key
        mentionJob?.cancel()
        val cached = mentionSearch.cached(root, mention.query)
        if (cached != null) {
            mentionItems = cached
            mentionLoading = false
            mentionRefreshing = false
            mentionProblem = null
            render()
            return
        }
        mentionProblem = null
        if (mentionItems.isEmpty()) mentionLoading = true else mentionRefreshing = true
        render()
        mentionJob = scope.launch {
            delay(MentionSearch.DEBOUNCE_MS)
            val result = mentionSearch.search(root, mention.query)
            when (result) {
                is MentionResult.Files -> {
                    mentionItems = result.paths
                    mentionProblem = null
                }
                is MentionResult.Failed -> {
                    mentionProblem = result.problem
                    if (result.problem != MentionProblem.Error) mentionItems = emptyList()
                    // A failed search is not cached: the same query searches again next time.
                    mentionKey = null
                }
            }
            mentionLoading = false
            mentionRefreshing = false
            highlighted = 0
            render()
        }
    }

    private fun staticProblem(): MentionProblem? = when {
        context.workspaceRoot.isNullOrBlank() -> MentionProblem.NoWorkspace
        context.kernel == SuggestionKernel.Down -> MentionProblem.KernelDown
        context.kernel == SuggestionKernel.NoCommands -> MentionProblem.PermissionMissing
        else -> null
    }

    private fun closeMention() {
        mentionJob?.cancel()
        mentionJob = null
        activeMention = null
        mentionItems = emptyList()
        mentionLoading = false
        mentionRefreshing = false
        mentionProblem = null
        mentionKey = null
    }

    private fun close() {
        activeSlash = null
        closeMention()
        highlighted = 0
        _state.value = null
    }

    private fun render() {
        val slash = activeSlash
        val mention = activeMention
        _state.value = when {
            slash != null -> {
                val catalog = slashSource.cached(context.workspaceRoot) ?: SlashCatalog.BUILTINS_ONLY
                val items = SlashCommands.filter(catalog.commands, slash.query, collator)
                SuggestionMenuState.Slash(
                    query = slash.query,
                    items = items,
                    loading = slashLoading && context.kernel.canListCommands(),
                    highlighted = highlighted.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                )
            }
            mention != null -> {
                val rows = if (mentionProblem == null || mentionProblem == MentionProblem.Error) mentionItems.size else 0
                SuggestionMenuState.Mention(
                    query = mention.query,
                    items = mentionItems,
                    loading = mentionLoading,
                    refreshing = mentionRefreshing,
                    problem = mentionProblem,
                    highlighted = highlighted.coerceIn(0, (rows - 1).coerceAtLeast(0)),
                )
            }
            else -> null
        }
    }

    private fun SuggestionKernel.canListCommands(): Boolean = this == SuggestionKernel.Up || this == SuggestionKernel.NoCommands

    /** One controller per composer; `ChatViewModel` passes its scope. */
    class Factory(private val slashSource: SlashCommandSource, private val mentionSearch: MentionSearch) {
        fun create(scope: CoroutineScope): SuggestionMenuController = SuggestionMenuController(slashSource, mentionSearch, scope)
    }
}
