package dev.cline.termux.ui.about

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Where [LicensesViewModel] reads the licence index and texts; the bundled assets in the app. */
interface LicenseSource {
    /** The licence index; throws when it cannot be read or parsed. */
    suspend fun index(): List<LicenseEntry>

    /** One licence text; null (or a throw) when it is not bundled or unreadable. */
    suspend fun text(file: String): String?
}

/** [LicenseSource] over `assets/licenses/` ([Licenses] reads on `Dispatchers.IO`). */
class AssetLicenseSource(context: Context) : LicenseSource {
    private val app = context.applicationContext

    override suspend fun index(): List<LicenseEntry> = Licenses.load(app)

    override suspend fun text(file: String): String? = Licenses.text(app, file)
}

/**
 * C13 (customize-and-rest.md §10): reads the bundled licence index, or one licence text, off the
 * main thread so the screens only render state. Null while loading; an unreadable index is an
 * empty list, an unreadable text the empty string.
 */
class LicensesViewModel(source: LicenseSource, file: String?) : ViewModel() {

    val entries: StateFlow<List<LicenseEntry>?> =
        flow { emit(if (file == null) readOr(emptyList()) { source.index() } else emptyList()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val text: StateFlow<String?> =
        flow { emit(file?.let { readOr(null) { source.text(it) } }.orEmpty()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        /** The list ([file] null) or one licence text. */
        fun factory(context: Context, file: String? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { LicensesViewModel(AssetLicenseSource(context), file) }
        }

        private suspend fun <T> readOr(fallback: T, read: suspend () -> T): T = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }
    }
}
