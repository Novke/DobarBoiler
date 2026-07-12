package rs.novica.dobarboiler.ui.dashboard

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.scheduling.BoostService
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.SheetLayout
import rs.novica.dobarboiler.ui.common.SourceUi
import rs.novica.dobarboiler.ui.common.TempStepper
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.sourceText
import rs.novica.dobarboiler.ui.common.sourceUiFrom
import java.time.Instant
import kotlin.math.min
import kotlin.math.roundToInt

data class BoostUiState(
    val targetTemp: Int = 60,
    val currentTemp: Double? = null,
    val etaMinutes: Long? = null,
    /** What the schedule reverts to when boost completes. */
    val revert: SourceUi = SourceUi.Off,
    val boostActive: Boolean = false,
    val maxTemp: Int = 75,
)

class BoostViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(BoostUiState())
    val state: StateFlow<BoostUiState> = _state

    init {
        viewModelScope.launch {
            container.scheduleRepository.activeBoost().collect { boost ->
                _state.update {
                    it.copy(
                        boostActive = boost != null,
                        targetTemp = boost?.targetTemp ?: it.targetTemp,
                    )
                }
                recompute()
            }
        }
        viewModelScope.launch { container.deviceRepository.snapshot.collect { recompute() } }
    }

    fun setTarget(temp: Int) {
        _state.update { it.copy(targetTemp = temp.coerceIn(40, it.maxTemp)) }
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        try {
            val now = Instant.now(container.clock)
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val snapshot = container.deviceRepository.snapshot.value
            val maxTemp = min(75, inputs.config.maxSetpointGuard ?: 75)
            val target = _state.value.targetTemp.coerceIn(40, maxTemp)

            // Boost forces eco off (max power) — ETA uses the non-eco rate.
            val rate = inputs.config.heatingRate.effectiveRatePerHour(eco = false)
            val eta = snapshot?.temp
                ?.takeIf { it < target && rate > 0.0 }
                ?.let { ((target - it) / rate * 60.0).roundToInt().toLong() }

            // Revert target: the planner state that would hold if there were no boost.
            val noBoostInputs = inputs.copy(boost = null)
            val revertResolution = container.engine.resolve(now, noBoostInputs)
            val revert = sourceUiFrom(revertResolution, noBoostInputs, now, container.zone, container.engine)

            _state.update {
                it.copy(
                    targetTemp = target,
                    currentTemp = snapshot?.temp,
                    etaMinutes = eta,
                    revert = revert,
                    maxTemp = maxTemp,
                )
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    /** "Pokreni" — starts the foreground BoostService (allowed: we are foreground UI). */
    fun start() {
        BoostService.start(app, _state.value.targetTemp)
    }

    /** "Prekini boost" — cancels via the service's revert path. */
    fun cancel() {
        BoostService.cancel(app)
        // Belt-and-braces: converge back onto the planner state right away.
        viewModelScope.launch(Dispatchers.IO) { convergeUser() }
    }
}

/** Sheet `boost` (SPEC §8): target stepper, ETA estimate, revert note; Pokreni / Prekini boost. */
@Composable
fun BoostSheet(onDismiss: () -> Unit) {
    val vm = containerViewModel { app, container -> BoostViewModel(app, container) }
    val state by vm.state.collectAsState()

    SheetLayout(onDismiss = onDismiss, title = stringResource(R.string.boost_title)) {
        TempStepper(
            value = state.targetTemp,
            onValueChange = vm::setTarget,
            range = 40..state.maxTemp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.boost_eco_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.etaMinutes?.let { eta ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.boost_eta, Fmt.minutesLabel(eta)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.boost_revert_to, sourceText(state.revert)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_cancel))
            }
            if (state.boostActive) {
                Button(
                    onClick = {
                        vm.cancel()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_cancel_boost)) }
            } else {
                Button(
                    onClick = {
                        vm.start()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.btn_start)) }
            }
        }
    }
}
