package dev.cline.termux.data.chat

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.GlobalSettingsView
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import kotlinx.coroutines.CancellationException

/**
 * The built-in tools the owner switched off: `global-settings.json` `disabledTools`, the list
 * the desktop's send-time checks read (`readGlobalSettings().disabledTools` in
 * `rewriteDesktopTeamPrompt` and `expandRuntimeSlashCommand`, sidecar chat-session.ts) and the
 * kernel's tool `settings.toggle` writes (Customize → Tools). C5.7 steps 2 and 4 ask it for
 * `teams` and `skills`.
 *
 * Not `settings.list`: the core's `snapshot.tools` holds plugin tools only (settings-service.ts
 * builds it from the plugin source), so a built-in `teams` or `skills` entry never appears there.
 */
fun interface DisabledToolsSource {
    /** The disabled built-in tool ids; empty when the file is missing or cannot be read. */
    suspend fun disabledTools(): Set<String>

    companion object {
        /** Nothing disabled (tests and previews). */
        val NONE = DisabledToolsSource { emptySet() }
    }
}

/**
 * Reads `global-settings.json` through RUN_COMMAND `cat` at each call, as the desktop reads the
 * file at each send. A missing or unparseable file, a failed `cat` or an unreachable Termux all
 * mean "nothing disabled", like the desktop's `readGlobalSettings()` returning `{}`: the send
 * then goes out and the kernel decides. Only the command label is ever logged.
 */
class GlobalSettingsDisabledTools(private val termux: TermuxExecutor) : DisabledToolsSource {
    override suspend fun disabledTools(): Set<String> = try {
        GlobalSettingsView.from(termux.run(ClineCommands.readGlobalSettings())).disabledTools
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptySet()
    }
}
