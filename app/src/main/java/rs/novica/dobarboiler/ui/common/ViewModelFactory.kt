package rs.novica.dobarboiler.ui.common

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.scheduling.ConvergeOutcome
import rs.novica.dobarboiler.scheduling.Triggers

/**
 * Plain-ViewModel construction per SPEC §8: no Hilt — every ViewModel is built via
 * [androidx.lifecycle.viewmodel.viewModelFactory] reading
 * `(LocalContext.current.applicationContext as App).container`.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    noinline create: (app: Application, container: AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as App
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(app, app.container) } },
    )
}

/**
 * Common base for all screen ViewModels: holds the [AppContainer], an error event stream
 * (surfaced as snackbars by screens) and the SPEC §8 rule that every schedule mutation
 * ends with `converger.converge(Triggers.USER)` — fire-and-forget on IO, snackbar on failure.
 */
abstract class ContainerViewModel(
    protected val app: Application,
    protected val container: AppContainer,
) : ViewModel() {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Human-readable failure details; screens format them with `common_error`. */
    val errors: SharedFlow<String> = _errors

    protected fun emitError(t: Throwable) {
        _errors.tryEmit(t.message ?: t.javaClass.simpleName)
    }

    protected fun emitError(detail: String) {
        _errors.tryEmit(detail)
    }

    /**
     * Runs the whole-app convergence heartbeat after a user-driven schedule mutation.
     * Fire-and-forget: failures surface as a snackbar (the Converger itself already
     * logs and notifies), never as a crash.
     */
    protected fun convergeUser(onDone: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val outcome = container.converger.converge(Triggers.USER)) {
                    is ConvergeOutcome.Failed -> emitError(
                        outcome.error.message ?: outcome.error.javaClass.simpleName,
                    )
                    else -> Unit
                }
            } catch (t: Throwable) {
                emitError(t)
            }
            onDone?.invoke()
        }
    }
}
