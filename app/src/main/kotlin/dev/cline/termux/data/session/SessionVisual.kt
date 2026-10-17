package dev.cline.termux.data.session

import dev.cline.termux.data.chat.ChatStatus

/*
 * The one status mapping (design-system.md §1.6; desktop `lib/session-status.ts`:
 * "Keeping the mapping in one place means a session cannot look green in one view and
 * grey in another"). No other file may choose a status colour, icon or label: the UI
 * resolves a [SessionVisual] through `SessionStatusDot.kt` and nothing else.
 *
 * Pure Kotlin on purpose: colours are named by [DotTone] and resolved against the theme in
 * `ui/components`; labels are string resources keyed by the visual.
 */

/** Which theme colour an 8dp status dot takes (resolved in `ui/components/SessionStatusDot.kt`). */
enum class DotTone { Error, Warning, Success, Info, Outline }

/** The glyph shown where a status icon is drawn (sessions screen supporting line, details, roster). */
enum class StatusGlyph { Failed, ApprovalRequired, QuestionAsked, Pending, Running, Completed, Cancelled }

/** Our own rename / fork / delete on a row in flight (SS §1.10). */
enum class PendingKind { Rename, Fork, Delete }

/** The app-derived flags that join the kernel status (DS §1.6 "Derived from" column). */
data class SessionVisualFlags(
    /** A menu action we issued is still running; wins over everything. */
    val pendingAction: PendingKind? = null,
    /** An `approval.requested` without its `approval.resolved`. */
    val approvalRequired: Boolean = false,
    /** An open `capability.requested` with `capabilityName == "tool_executor.askQuestion"`. */
    val questionAsked: Boolean = false,
    /** Our `session.create` (new, fork, restore) is in flight, or the kernel is still connecting. */
    val starting: Boolean = false,
    /** New events since the session was last shown; applies to idle-ish sessions only. */
    val unread: Boolean = false,
)

sealed interface SessionVisual {
    /** Dot colour, or null when the desktop hides the neutral dot. */
    val dot: DotTone?

    /** Only Starting pulses (DS §6); running is steady. */
    val pulsing: Boolean get() = false

    /** Icon where one is shown; null for Starting, Unread and Idle. */
    val glyph: StatusGlyph?

    /** Rename / fork / delete in flight: static warning dot, label = the menu's pending label. */
    data class ActionPending(val kind: PendingKind) : SessionVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val glyph: StatusGlyph? get() = null
    }

    data object Failed : SessionVisual {
        override val dot: DotTone get() = DotTone.Error
        override val glyph: StatusGlyph get() = StatusGlyph.Failed
    }

    data object ApprovalRequired : SessionVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val glyph: StatusGlyph get() = StatusGlyph.ApprovalRequired
    }

    data object QuestionAsked : SessionVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val glyph: StatusGlyph get() = StatusGlyph.QuestionAsked
    }

    data object Pending : SessionVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val glyph: StatusGlyph get() = StatusGlyph.Pending
    }

    data object Starting : SessionVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val pulsing: Boolean get() = true
        override val glyph: StatusGlyph? get() = null
    }

    data object Running : SessionVisual {
        override val dot: DotTone get() = DotTone.Success
        override val glyph: StatusGlyph get() = StatusGlyph.Running
    }

    data object Unread : SessionVisual {
        override val dot: DotTone get() = DotTone.Info
        override val glyph: StatusGlyph? get() = null
    }

    data object Completed : SessionVisual {
        override val dot: DotTone? get() = null
        override val glyph: StatusGlyph get() = StatusGlyph.Completed
    }

    data object Cancelled : SessionVisual {
        override val dot: DotTone? get() = null
        override val glyph: StatusGlyph get() = StatusGlyph.Cancelled
    }

    data object Idle : SessionVisual {
        override val dot: DotTone? get() = null
        override val glyph: StatusGlyph? get() = null
    }

    companion object {
        /**
         * Precedence, highest first (DS §1.6 + SS §1.3): action pending > Failed > Approval
         * required > Question asked > Pending > Starting > Running > Unread > Completed /
         * Cancelled / Idle. [status] is the kernel's word (`session.status`, `snapshot.status`,
         * a `session.list` row): `idle | starting | provisioning | running | stopping | pending |
         * completed | cancelled | aborted | failed | error`; unknown or null reads as idle.
         */
        fun of(status: String?, flags: SessionVisualFlags = SessionVisualFlags()): SessionVisual {
            flags.pendingAction?.let { return ActionPending(it) }
            val normalized = status?.trim()?.lowercase()
            if (normalized == "failed" || normalized == "error") return Failed
            if (flags.approvalRequired) return ApprovalRequired
            if (flags.questionAsked) return QuestionAsked
            if (normalized == "pending") return Pending
            if (flags.starting || normalized == "starting" || normalized == "provisioning") return Starting
            if (normalized == "running") return Running
            if (flags.unread && isIdleish(normalized)) return Unread
            return when (normalized) {
                "completed" -> Completed
                "aborted", "cancelled" -> Cancelled
                else -> Idle
            }
        }

        /** The chat transcript's own status enum (HC §1.4). */
        fun of(status: ChatStatus, flags: SessionVisualFlags = SessionVisualFlags()): SessionVisual =
            of(status.kernelWord, flags)

        /** Unread applies to idle-ish sessions only (desktop `AgentSessionRow`: "unread is idle-only"). */
        private fun isIdleish(status: String?): Boolean =
            status == null || status == "idle" || status == "completed" || status == "aborted" || status == "cancelled" || status == "unknown"
    }
}

/** `ChatStatus` → the kernel's word, so both overloads share one rule table. */
val ChatStatus.kernelWord: String
    get() = when (this) {
        ChatStatus.Idle -> "idle"
        ChatStatus.Starting -> "starting"
        ChatStatus.Running -> "running"
        ChatStatus.Stopping -> "stopping"
        ChatStatus.Completed -> "completed"
        ChatStatus.Cancelled -> "cancelled"
        ChatStatus.Failed -> "failed"
    }

/**
 * Kernel connection (DS §1.6 second table: desktop `desktop-client.ts` states plus the
 * Termux case). Rendered with the same dot component; lines are string resources.
 */
sealed interface KernelVisual {
    val dot: DotTone
    val pulsing: Boolean get() = false

    /** WebSocket open and `client.register` replied; [port] from `/health.url`. */
    data class Connected(val port: Int) : KernelVisual {
        override val dot: DotTone get() = DotTone.Success
    }

    /** First connect, or `cline hub start` running. */
    data object Connecting : KernelVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val pulsing: Boolean get() = true
    }

    /** Socket dropped, backoff loop. */
    data object Reconnecting : KernelVisual {
        override val dot: DotTone get() = DotTone.Warning
        override val pulsing: Boolean get() = true
    }

    /** `/health` refused after `hub start`. */
    data object Unavailable : KernelVisual {
        override val dot: DotTone get() = DotTone.Outline
    }

    /** `/health` fails while Termux is resolvable. */
    data object TermuxAsleep : KernelVisual {
        override val dot: DotTone get() = DotTone.Outline
    }
}
