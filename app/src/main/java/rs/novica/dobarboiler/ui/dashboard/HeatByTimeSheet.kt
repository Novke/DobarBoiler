package rs.novica.dobarboiler.ui.dashboard

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import kotlinx.coroutines.withContext
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.LabeledSwitchRow
import rs.novica.dobarboiler.ui.common.SheetLayout
import rs.novica.dobarboiler.ui.common.TempStepper
import rs.novica.dobarboiler.ui.common.TimePickerField
import rs.novica.dobarboiler.ui.common.containerViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.math.min
import kotlin.math.roundToInt

/** Live "grejanje počinje oko ..." preview parameters (ot_start_estimate). */
data class HbtEstimate(val startLabel: String, val ratePerHour: Int, val marginMinutes: Int)

data class HbtUiState(
    val targetTime: LocalTime = LocalTime.of(21, 0),
    val tomorrow: Boolean = false,
    val targetTemp: Int = 60,
    val eco: Boolean = false, // speed by default (SPEC)
    val holdMinutes: Int = 60,
    val estimate: HbtEstimate? = null,
    /** eventTime + hold label for "Posle %s vraćam se na planer." */
    val revertLabel: String? = null,
    val despiteVacation: Boolean = false,
    val isSaving: Boolean = false,
    val maxTemp: Int = 75,
)

class HeatByTimeViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(HbtUiState())
    val state: StateFlow<HbtUiState> = _state

    init {
        // Auto day toggle: if 21:00 already passed today, default to "Sutra".
        val now = Instant.now(container.clock).atZone(container.zone)
        if (!now.toLocalTime().isBefore(_state.value.targetTime)) {
            _state.update { it.copy(tomorrow = true) }
        }
        viewModelScope.launch { recompute() }
    }

    fun setTargetTime(time: LocalTime) {
        val now = Instant.now(container.clock).atZone(container.zone).toLocalTime()
        _state.update { it.copy(targetTime = time, tomorrow = if (!now.isBefore(time)) true else it.tomorrow) }
        viewModelScope.launch { recompute() }
    }

    fun setTomorrow(tomorrow: Boolean) {
        // "Danas" is not selectable once the time has passed.
        val now = Instant.now(container.clock).atZone(container.zone).toLocalTime()
        val effective = if (!tomorrow && !now.isBefore(_state.value.targetTime)) true else tomorrow
        _state.update { it.copy(tomorrow = effective) }
        viewModelScope.launch { recompute() }
    }

    fun setTargetTemp(temp: Int) {
        _state.update { it.copy(targetTemp = temp.coerceIn(40, it.maxTemp)) }
        viewModelScope.launch { recompute() }
    }

    fun setEco(eco: Boolean) {
        _state.update { it.copy(eco = eco) }
        viewModelScope.launch { recompute() }
    }

    private fun eventInstant(): Instant {
        val s = _state.value
        val zone = container.zone
        val today = Instant.now(container.clock).atZone(zone).toLocalDate()
        val date = if (s.tomorrow) today.plusDays(1) else today
        return date.atTime(s.targetTime).atZone(zone).toInstant()
    }

    private suspend fun recompute() {
        try {
            val now = Instant.now(container.clock)
            val zone = container.zone
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val maxTemp = min(75, inputs.config.maxSetpointGuard ?: 75)
            val s = _state.value
            val target = s.targetTemp.coerceIn(40, maxTemp)
            val eventTime = eventInstant()

            // Live preview via the engine's own lead math (SPEC §3.6).
            val preview = OneTimeOverride.HeatByTime(
                id = 0, createdAt = now, eventTime = eventTime,
                targetTemp = target, eco = s.eco, holdMinutes = s.holdMinutes,
            )
            val start = container.engine.computedStart(preview, inputs, now)

            // Margin shown in minutes (SPEC R9: percent-based, UI shows resulting minutes).
            val cfg = inputs.config.heatingRate
            val rate = cfg.effectiveRatePerHour(s.eco)
            val snapshot = inputs.lastSnapshot
            val estimateTemp = if (snapshot != null &&
                Duration.between(snapshot.at, now) < Duration.ofHours(2)
            ) {
                snapshot.temp
            } else {
                30.0
            }
            val deltaC = (target - estimateTemp).coerceAtLeast(0.0)
            val leadSeconds = if (rate > 0) deltaC / rate * 3600.0 else 0.0
            val marginMinutes = (leadSeconds * cfg.safetyMarginPercent / 100.0 / 60.0).roundToInt()

            val holdEnd = eventTime.plus(Duration.ofMinutes(s.holdMinutes.toLong()))
            val vacation = inputs.vacation
            val despiteVacation = vacation != null && vacation.startsAt <= eventTime &&
                (vacation.resumeAt == null || eventTime < vacation.resumeAt)

            _state.update {
                it.copy(
                    targetTemp = target,
                    maxTemp = maxTemp,
                    estimate = HbtEstimate(
                        startLabel = Fmt.time(start, zone),
                        ratePerHour = rate.roundToInt(),
                        marginMinutes = marginMinutes,
                    ),
                    revertLabel = Fmt.time(holdEnd, zone),
                    despiteVacation = despiteVacation,
                )
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    fun save(onSaved: () -> Unit) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = _state.value
                container.scheduleRepository.addHeatByTime(
                    eventTime = eventInstant(),
                    targetTemp = s.targetTemp,
                    eco = s.eco,
                    holdMinutes = s.holdMinutes,
                )
            } catch (t: Throwable) {
                emitError(t)
                _state.update { it.copy(isSaving = false) }
                return@launch
            }
            convergeUser()
            _state.update { it.copy(isSaving = false) }
            withContext(Dispatchers.Main) { onSaved() }
        }
    }
}

/** Sheet `override/time` — "Toplo u...": target time (danas/sutra), temp, live start estimate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatByTimeSheet(onDismiss: () -> Unit) {
    val vm = containerViewModel { app, container -> HeatByTimeViewModel(app, container) }
    val state by vm.state.collectAsState()

    SheetLayout(onDismiss = onDismiss, title = stringResource(R.string.ot_hot_by)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !state.tomorrow,
                onClick = { vm.setTomorrow(false) },
                label = { Text(stringResource(R.string.ot_today)) },
            )
            FilterChip(
                selected = state.tomorrow,
                onClick = { vm.setTomorrow(true) },
                label = { Text(stringResource(R.string.ot_tomorrow)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        TimePickerField(
            label = stringResource(R.string.ot_hot_by),
            value = state.targetTime,
            onValueChange = vm::setTargetTime,
        )

        Spacer(Modifier.height(16.dp))
        TempStepper(
            value = state.targetTemp,
            onValueChange = vm::setTargetTemp,
            range = 40..state.maxTemp,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        LabeledSwitchRow(
            label = stringResource(R.string.preset_eco),
            checked = state.eco,
            onCheckedChange = vm::setEco,
        )

        state.estimate?.let { estimate ->
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.ot_start_estimate,
                    estimate.startLabel,
                    estimate.ratePerHour,
                    estimate.marginMinutes,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.revertLabel?.let { revert ->
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ot_revert_note, revert),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.despiteVacation) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ot_despite_vacation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_cancel))
            }
            Button(
                onClick = { vm.save(onDismiss) },
                enabled = !state.isSaving,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.btn_schedule)) }
        }
    }
}
