package dev.cline.termux.core.kernel.termux

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.expectThrows
import dev.cline.termux.failedWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The intent plumbing of docs/07 §3.3 against Robolectric's package manager and service shadows. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxRunnerTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun installTermux(enabled: Boolean = true) {
        val info = PackageInfo().apply {
            packageName = TermuxPaths.PACKAGE
            versionName = "0.118.3"
            longVersionCode = 1183
            applicationInfo = ApplicationInfo().apply {
                packageName = TermuxPaths.PACKAGE
                this.enabled = enabled
            }
        }
        shadowOf(app.packageManager).installPackage(info)
    }

    private fun grant() = shadowOf(app).grantPermissions(TermuxRunner.PERMISSION)

    private fun ready(): TermuxRunner {
        installTermux()
        grant()
        return TermuxRunner(app) { 0L }
    }

    private fun nextServiceIntent(): Intent = requireNotNull(shadowOf(app).nextStartedService) { "no RUN_COMMAND intent was started" }

    private fun executionIdOf(intent: Intent): Int {
        @Suppress("DEPRECATION")
        val pending = intent.getParcelableExtra<PendingIntent>(TermuxRunner.EXTRA_PENDING_INTENT)!!
        return shadowOf(pending).savedIntent.getIntExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, -1)
    }

    private fun deliver(executionId: Int, stdout: String = "", stderr: String = "", exitCode: Int = 0, err: Int = -1, errmsg: String? = null) {
        val bundle = Bundle().apply {
            putString(TermuxRunner.RESULT_STDOUT, stdout)
            putString(TermuxRunner.RESULT_STDERR, stderr)
            putInt(TermuxRunner.RESULT_EXIT_CODE, exitCode)
            putInt(TermuxRunner.RESULT_ERR, err)
            errmsg?.let { putString(TermuxRunner.RESULT_ERRMSG, it) }
            putString(TermuxRunner.RESULT_STDOUT_ORIGINAL_LENGTH, stdout.length.toString())
            putString(TermuxRunner.RESULT_STDERR_ORIGINAL_LENGTH, stderr.length.toString())
        }
        val intent = Intent(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, executionId)
            .putExtra(TermuxRunner.RESULT_BUNDLE, bundle)
        TermuxResultReceiver().onReceive(app, intent)
    }

    @Test
    fun `presence reports missing, disabled and installed`() {
        val runner = TermuxRunner(app)
        assertEquals(TermuxPresence.Missing, runner.isTermuxInstalled())
        installTermux(enabled = false)
        assertEquals(TermuxPresence.Disabled, runner.isTermuxInstalled())
        installTermux(enabled = true)
        assertEquals(TermuxPresence.Installed(1183, "0.118.3"), runner.isTermuxInstalled())
        assertFalse(runner.hasPermission())
        grant()
        assertTrue(runner.hasPermission())
    }

    @Test
    fun `run refuses before dispatch when Termux is missing or the permission is not granted`() = runTest {
        val runner = TermuxRunner(app)
        expectThrows<TermuxUnavailable.NotInstalled> { runner.run(ClineCommands.cliVersion()) }
        installTermux()
        expectThrows<TermuxUnavailable.PermissionMissing> { runner.run(ClineCommands.cliVersion()) }
        expectThrows<TermuxUnavailable.PermissionMissing> { runner.runDetached(ClineCommands.setupStorage()) }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `run builds the RUN_COMMAND intent, routes the result and strips ANSI`() = runTest {
        val runner = ready()
        val command = ClineCommands.authProvider("anthropic", "sk-1", "claude-sonnet-4-6")
        val pending = async { runner.run(command) }
        runCurrent()

        val intent = nextServiceIntent()
        assertEquals(TermuxRunner.ACTION_RUN_COMMAND, intent.action)
        assertEquals(TermuxRunner.RUN_COMMAND_SERVICE, intent.component!!.className)
        assertEquals(TermuxPaths.PACKAGE, intent.component!!.packageName)
        assertEquals(TermuxPaths.CLINE, intent.getStringExtra(TermuxRunner.EXTRA_COMMAND_PATH))
        assertArrayEquals(command.args.toTypedArray(), intent.getStringArrayExtra(TermuxRunner.EXTRA_ARGUMENTS))
        assertEquals(TermuxPaths.HOME, intent.getStringExtra(TermuxRunner.EXTRA_WORKDIR))
        assertTrue(intent.getBooleanExtra(TermuxRunner.EXTRA_BACKGROUND, false))
        assertEquals("cline auth anthropic", intent.getStringExtra(TermuxRunner.EXTRA_COMMAND_LABEL))
        assertFalse(intent.hasExtra(TermuxRunner.EXTRA_STDIN))
        assertFalse(intent.hasExtra(TermuxRunner.EXTRA_SESSION_ACTION))

        deliver(executionIdOf(intent), stdout = "\u001B[32mProvider configured:\u001B[0m \u001B[36manthropic\u001B[0m (claude-sonnet-4-6)\n")
        val result = pending.await()
        assertTrue(result.ok)
        assertEquals("Provider configured: anthropic (claude-sonnet-4-6)\n", result.stdout)
        assertNull(result.errmsg)
    }

    @Test
    fun `stdin, session action and workdir travel in their extras`() = runTest {
        val runner = ready()
        val write = ClineCommands.attachmentWriteCommands("/d", "a.txt", "body").first()
        val signIn = ClineCommands.authInteractive()
        val tools = ClineCommands.configToolsJson("/ws")
        val first = async { runner.run(write) }
        runCurrent()
        val writeIntent = nextServiceIntent()
        assertEquals("body", writeIntent.getStringExtra(TermuxRunner.EXTRA_STDIN))
        deliver(executionIdOf(writeIntent))
        first.await()

        val handle = runner.runDetached(signIn)
        val signInIntent = nextServiceIntent()
        assertFalse(signInIntent.getBooleanExtra(TermuxRunner.EXTRA_BACKGROUND, true))
        assertEquals("0", signInIntent.getStringExtra(TermuxRunner.EXTRA_SESSION_ACTION))
        assertEquals("Sign in to Cline", signInIntent.getStringExtra(TermuxRunner.EXTRA_COMMAND_LABEL))
        handle.cancel()

        val third = async { runner.run(tools) }
        runCurrent()
        val toolsIntent = nextServiceIntent()
        assertEquals("/ws", toolsIntent.getStringExtra(TermuxRunner.EXTRA_WORKDIR))
        deliver(executionIdOf(toolsIntent), stdout = "[]")
        assertEquals("[]", third.await().stdout)
    }

    @Test
    fun `a timeout surfaces the label and forgets the execution`() = runTest {
        val runner = ready()
        val pending = async { runCatching { runner.run(ClineCommands.hubStart()) } }
        runCurrent()
        val intent = nextServiceIntent()
        advanceTimeBy(60_001)
        runCurrent()
        val error = pending.await().failedWith<TermuxUnavailable.TimedOut>()
        assertEquals("cline hub start", error.label)
        // A late result finds nobody waiting and is dropped.
        deliver(executionIdOf(intent), stdout = "late")
    }

    @Test
    fun `an allow-external-apps refusal becomes ExternalAppsBlocked`() = runTest {
        val runner = ready()
        val pending = async { runCatching { runner.run(ClineCommands.probeVersion()) } }
        runCurrent()
        deliver(
            executionIdOf(nextServiceIntent()),
            exitCode = -1,
            err = 1,
            errmsg = "RUN_COMMAND Intent is not allowed. Set the allow-external-apps property to true in ~/.termux/termux.properties.",
        )
        val error = pending.await().failedWith<TermuxUnavailable.ExternalAppsBlocked>()
        assertTrue(error.errmsg.contains("allow-external-apps"))
    }

    @Test
    fun `other delivery errors are returned as undelivered results`() = runTest {
        val runner = ready()
        val pending = async { runner.run(ClineCommands.probeVersion()) }
        runCurrent()
        deliver(executionIdOf(nextServiceIntent()), exitCode = -1, err = 150, errmsg = "No such file or directory")
        val result = pending.await()
        assertTrue(result.internalError && result.executableFailed)
        assertEquals("No such file or directory", result.errmsg)
    }

    @Test
    fun `detached commands report only an early exit and can be forgotten`() = runTest {
        val runner = ready()
        val handle = runner.runDetached(ClineCommands.dashboardStart("0123456789abcdef0123456789abcdef"))
        val intent = nextServiceIntent()
        assertEquals("start settings helper", intent.getStringExtra(TermuxRunner.EXTRA_COMMAND_LABEL))
        assertFalse(handle.earlyExit.isCompleted)
        advanceTimeBy(600_000)
        assertFalse(handle.earlyExit.isCompleted)

        deliver(executionIdOf(intent), stderr = "\u001B[31mError: listen EADDRINUSE\u001B[0m\n", exitCode = 1)
        val exit = handle.earlyExit.await()
        assertEquals(1, exit.exitCode)
        assertEquals("Error: listen EADDRINUSE", exit.lastStderrLine)

        val second = runner.runDetached(ClineCommands.setupStorage())
        val secondIntent = nextServiceIntent()
        second.cancel()
        deliver(executionIdOf(secondIntent))
        assertTrue(second.earlyExit.isCancelled)
    }

    @Test
    fun `a detached delivery refusal surfaces as ExternalAppsBlocked on the handle`() = runTest {
        val runner = ready()
        val handle = runner.runDetached(ClineCommands.authInteractive())
        deliver(executionIdOf(nextServiceIntent()), exitCode = -1, err = 1, errmsg = "allow-external-apps is false")
        assertTrue(handle.earlyExit.isCompleted)
        runCatching { handle.earlyExit.await() }.failedWith<TermuxUnavailable.ExternalAppsBlocked>()
    }

    @Test
    fun `the Termux activity intent targets TermuxActivity in a new task`() {
        val intent = TermuxRunner.termuxActivityIntent()
        assertEquals(TermuxPaths.PACKAGE, intent.component!!.packageName)
        assertEquals(TermuxPaths.ACTIVITY, intent.component!!.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
