package dev.cline.termux.ui.sessions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.data.session.str
import dev.cline.termux.data.sessions.formatSessionSearchPreview
import dev.cline.termux.data.sessions.formatSessionSearchTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One `session.search` hit, formatted (sessions.md §4.6). [snippet] keeps the Hub's `[`/`]` match markers. */
@Immutable
data class SearchHit(
    val sessionId: String,
    val documentId: String,
    val role: String,
    val title: String,
    val snippet: String,
    val workspaceRoot: String,
)

/** §4.5 phases. */
enum class SearchPhase { Idle, Searching, Results, NoResults, Failed, KernelDown }

/** A snippet with its bracketed matches removed: [text] and the ranges to set in SemiBold. */
data class SnippetSpans(val text: String, val bold: List<IntRange>)

object SessionSearchModel {
    /** `session-command-bar.tsx`: 180 ms debounce, `limit: 50`, batches of 15. */
    const val DEBOUNCE_MS = 180L
    const val LIMIT = 50
    const val BATCH = 15

    /** `{hits[...]}` in the Hub's order (score, then `startedAt` desc); never re-ranked. */
    fun parseHits(reply: JsonObject): List<SearchHit> {
        val hits = reply["hits"] as? JsonArray ?: return emptyList()
        return hits.mapNotNull { element ->
            val hit = element as? JsonObject ?: return@mapNotNull null
            val sessionId = hit.str("sessionId")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val role = hit.str("role").orEmpty()
            SearchHit(
                sessionId = sessionId,
                documentId = hit.str("documentId")?.ifEmpty { null } ?: (sessionId + ":" + (hit["ordinal"]?.toString() ?: "")),
                role = capitalizeRole(role),
                title = formatSessionSearchTitle(hit.str("title")),
                snippet = formatSessionSearchPreview(role, hit.str("snippet")),
                workspaceRoot = hit.str("workspaceRoot").orEmpty(),
            )
        }.distinctBy { it.documentId }
    }

    /** "user" → "User" (display only). */
    fun capitalizeRole(role: String): String = role.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** §4.3: matches are bracketed `[...]` by the Hub; the app drops the brackets and bolds the match. */
    fun snippetSpans(snippet: String): SnippetSpans {
        val out = StringBuilder()
        val bold = mutableListOf<IntRange>()
        var i = 0
        while (i < snippet.length) {
            val c = snippet[i]
            if (c == '[') {
                val close = snippet.indexOf(']', i + 1)
                if (close > i + 1) {
                    val start = out.length
                    out.append(snippet, i + 1, close)
                    bold += start until out.length
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i += 1
        }
        return SnippetSpans(out.toString(), bold)
    }

    /** §4.4: reveal 15 more when the list is within 120dp of its end. */
    fun reveal(visible: Int, total: Int): Int = minOf(total, visible + BATCH)
}

@Immutable
data class SessionSearchUiState(
    val query: String = "",
    val phase: SearchPhase = SearchPhase.Idle,
    val hits: List<SearchHit> = emptyList(),
    val visibleCount: Int = SessionSearchModel.BATCH,
    /** Hardware keyboard: the hit Up/Down moved to (Enter opens it); -1 none. */
    val highlighted: Int = -1,
) {
    val visibleHits: List<SearchHit> get() = hits.take(visibleCount)
    val hasMore: Boolean get() = visibleCount < hits.size
}

/**
 * S3 (design/screens/sessions.md §4): debounced `session.search` with the newest query
 * winning (`transformLatest` drops an older result), the 3 s timeout, kernel-down handling
 * (the query runs once the socket is back), batches of 15, and keyboard highlight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchViewModel(val port: SessionsPort) : ViewModel() {
    private data class Request(val query: String, val tick: Int, val immediate: Boolean)

    private val request = MutableStateFlow(Request("", 0, false))
    private val _state = MutableStateFlow(SessionSearchUiState())
    val uiState: StateFlow<SessionSearchUiState> = _state.asStateFlow()

    val actions = SessionRowActions(port, viewModelScope)
    val events = actions.events

    private val connected: StateFlow<Boolean> = port.snapshot.map { it.connected }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        combine(request, connected) { r, up -> r to up }
            .transformLatest { (r, up) ->
                val query = r.query.trim()
                when {
                    query.isEmpty() -> emit(Outcome(SearchPhase.Idle, emptyList()))
                    !up -> emit(Outcome(SearchPhase.KernelDown, emptyList()))
                    else -> {
                        emit(Outcome(SearchPhase.Searching, emptyList()))
                        if (!r.immediate) delay(SessionSearchModel.DEBOUNCE_MS)
                        val hits = try {
                            SessionSearchModel.parseHits(port.search(query, SessionSearchModel.LIMIT))
                        } catch (e: CancellationException) {
                            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
                        } catch (_: Exception) {
                            null
                        }
                        emit(
                            when {
                                hits == null -> Outcome(SearchPhase.Failed, emptyList())
                                hits.isEmpty() -> Outcome(SearchPhase.NoResults, emptyList())
                                else -> Outcome(SearchPhase.Results, hits)
                            },
                        )
                    }
                }
            }
            .onEach { outcome ->
                _state.update { it.copy(phase = outcome.phase, hits = outcome.hits, visibleCount = SessionSearchModel.BATCH, highlighted = -1) }
            }
            .launchIn(viewModelScope)
    }

    private data class Outcome(val phase: SearchPhase, val hits: List<SearchHit>)

    /** Typing: each change clears the hits; the search runs 180 ms after the last change. */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        request.update { Request(query, it.tick + 1, immediate = false) }
    }

    /** IME Search: run now (skip the debounce). */
    fun submit() {
        request.update { Request(_state.value.query, it.tick + 1, immediate = true) }
    }

    /** "Retry" after a failure. */
    fun retry() = submit()

    /** The query starts empty each time search opens (desktop resets on close). */
    fun reset() {
        _state.value = SessionSearchUiState()
        request.update { Request("", it.tick + 1, immediate = false) }
    }

    fun revealMore() {
        _state.update { it.copy(visibleCount = SessionSearchModel.reveal(it.visibleCount, it.hits.size)) }
    }

    /** Up/Down on a hardware keyboard. */
    fun moveHighlight(delta: Int) {
        _state.update { s ->
            val count = s.visibleHits.size
            if (count == 0) return@update s
            val next = (s.highlighted + delta).coerceIn(0, count - 1)
            val visible = if (next >= s.visibleCount - 1) SessionSearchModel.reveal(s.visibleCount, s.hits.size) else s.visibleCount
            s.copy(highlighted = next, visibleCount = visible)
        }
    }

    /** Enter: the highlighted hit (or the first). */
    fun highlightedHit(): SearchHit? = _state.value.let { s -> s.visibleHits.getOrNull(s.highlighted.coerceAtLeast(0)) }

    /** Select a hit: F1 open (marks it seen). */
    fun open(hit: SearchHit) = actions.open(hit.sessionId)

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionSearchViewModel(SessionsDrawerViewModel.graphPort(graph)) }
        }
    }
}
