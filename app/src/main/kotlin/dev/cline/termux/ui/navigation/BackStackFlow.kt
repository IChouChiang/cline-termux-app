package dev.cline.termux.ui.navigation

import android.annotation.SuppressLint
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.flow.StateFlow

/**
 * The controller's whole back stack, bottom first: the drawer/rail area (`Routes.areaOf`), the
 * chat root's selection handle, the editor-as-dialog latch and the workspace picker's
 * "browser on top" check all need more than the top entry.
 *
 * navigation 2.10 publishes the full stack only as `NavController.currentBackStack`, which is
 * restricted to the androidx.navigation group (navigation-compose's own `NavHost` reads it);
 * `visibleEntries` holds only the visible top and its parents. This is the app's one read of
 * it, so a future public replacement changes a single line.
 */
@SuppressLint("RestrictedApi")
fun NavController.backStackFlow(): StateFlow<List<NavBackStackEntry>> = currentBackStack
