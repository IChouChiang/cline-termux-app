package dev.cline.termux.ui.about

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.cline.termux.AppGraph
import dev.cline.termux.ui.navigation.About
import dev.cline.termux.ui.navigation.AboutDiagnostics
import dev.cline.termux.ui.navigation.AboutLicenses
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.editor
import kotlinx.serialization.Serializable

/**
 * One licence text (C13), pushed from Open source licenses. A lane-local route: the shell's
 * sealed `ShellRoute` hierarchy lives in `Routes.kt`, which this lane does not own, and the
 * page needs no deep link of its own.
 */
@Serializable
data class AboutLicenseText(val file: String, val name: String)

/**
 * About, Export diagnostics and the licence pages, registered from `shellDestinations`
 * (`DebugKernelProbe` is registered there directly, debug builds only).
 */
fun NavGraphBuilder.aboutGraph(nav: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean) {
    composable<About> { AboutRoute(nav, graph) }
    composable<AboutLicenses> {
        LicensesRoute(onBack = { nav.back() }, onOpen = { entry -> nav.nav.navigate(AboutLicenseText(entry.file, entry.name)) })
    }
    composable<AboutLicenseText> { entry ->
        val route = entry.toRoute<AboutLicenseText>()
        LicenseTextRoute(route.name, route.file, onBack = { nav.back() })
    }
    editor<AboutDiagnostics>(editorsAsDialogs) { asDialog ->
        ExportDiagnosticsRoute(graph, asDialog, onClose = { nav.back() })
    }
}
