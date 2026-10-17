package dev.cline.termux

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.WhatsNewTrigger
import dev.cline.termux.ui.asTrigger
import dev.cline.termux.ui.navigation.DeepLinks
import dev.cline.termux.ui.navigation.LocalSecureWindow
import dev.cline.termux.ui.navigation.LocalShortcutBus
import dev.cline.termux.ui.navigation.ProvideWindowClass
import dev.cline.termux.ui.navigation.RootNavHost
import dev.cline.termux.ui.navigation.RootViewModel
import dev.cline.termux.ui.navigation.SecureFlagHolder
import dev.cline.termux.ui.navigation.SecureWindow
import dev.cline.termux.ui.navigation.ShellShortcuts
import dev.cline.termux.ui.navigation.ShortcutBus
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.LocalIsDarkTheme

/**
 * The single activity (docs/07 §1.1, §6.4): splash kept only for the one DataStore read that
 * picks the start destination, edge to edge, the root NavHost, notification intent routing
 * (cold start and `onNewIntent`), the `FLAG_SECURE` owner, the app-wide snackbar host and the
 * shell's hardware-keyboard shortcuts.
 */
class MainActivity : ComponentActivity() {
    private val root: RootViewModel by viewModels { RootViewModel.factory(appGraph, whatsNew) }
    private val whatsNew: WhatsNewTrigger by lazy { appGraph.whatsNew.asTrigger() }

    /**
     * Screens that reveal a secret hold `FLAG_SECURE` through this (docs/07 §3.7, SP §0.6);
     * key-entry screens also turn off the recents screenshot (API 33+).
     */
    val secureWindow = SecureWindow(
        secure = SecureFlagHolder { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        },
        recentsScreenshot = SecureFlagHolder { blocked ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!blocked)
            // Keep typed keys away from password managers: no fill, no "save login?" prompt.
            window.decorView.importantForAutofill = if (blocked) {
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_AUTOFILL_AUTO
            }
        },
    )

    private val shortcuts = ShortcutBus()

    override fun onCreate(savedInstanceState: Bundle?) {
        dev.cline.termux.debug.KernelOverrides.applyLaunchIntent(this, intent)
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // KS §0.2 / DS §10.4: held for the start-destination read only, never for the kernel probe.
        splash.setKeepOnScreenCondition { !root.ready }
        enableEdgeToEdge()
        window.callback = ShortcutWindowCallback(window.callback, shortcuts)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No scrim behind gesture navigation (design-system.md §1.2 rule 5).
            window.isNavigationBarContrastEnforced = false
        }
        // CA §0.6: the cold-start path for a notification tapped while the app was not running.
        if (savedInstanceState == null) routeIntent(intent)
        setContent {
            val appearance by root.appearance.collectAsStateWithLifecycle()
            // The splash covers this frame (`ready` waits for the same value).
            val theme = appearance ?: return@setContent
            val snackbarHost = remember { SnackbarHostState() }
            ClineTheme(theme) {
                SystemBarIcons()
                CompositionLocalProvider(
                    LocalSnackbarHost provides snackbarHost,
                    LocalSecureWindow provides secureWindow,
                    LocalShortcutBus provides shortcuts,
                ) {
                    ProvideWindowClass {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            RootNavHost(root, whatsNew = whatsNew)
                        }
                    }
                }
            }
        }
    }

    /** CA §0.6: the warm path (`singleTask`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    /**
     * Reads `SESSION_ID` / `OPEN_SHEET`, removes them and stores the intent, so a rotation or
     * process recreation does not reopen the sheet; the root queues the link for the shell.
     */
    private fun routeIntent(intent: Intent?) {
        if (intent == null) return
        val link = DeepLinks.consume(intent)
        setIntent(intent)
        root.onDeepLink(link)
    }

    /**
     * Ctrl+K / Ctrl+P (search), Ctrl+N (new session), Ctrl+, (Settings) and Ctrl+B (drawer or
     * list pane) anywhere in the shell (DS §5.7, SS §2.8, [ShellShortcuts]),
     * seen before any view: the window's own callback is wrapped (public API) instead of
     * overriding `ComponentActivity.dispatchKeyEvent`, which androidx restricts to its library group.
     */
    private class ShortcutWindowCallback(
        private val delegate: Window.Callback,
        private val shortcuts: ShortcutBus,
    ) : Window.Callback by delegate {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val shortcut = ShellShortcuts.match(event)
            if (shortcut != null && shortcuts.offer(shortcut)) return true
            return delegate.dispatchKeyEvent(event)
        }
    }

    /** System-bar icon colour follows the in-app theme choice, not only the system setting. */
    @Composable
    private fun SystemBarIcons() {
        val dark = LocalIsDarkTheme.current
        SideEffect {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            )
        }
    }
}
