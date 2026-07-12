package rs.novica.dobarboiler.ui.dashboard

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import rs.novica.dobarboiler.ui.common.AppDatePickerDialog
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.TimePickerField
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.nextWindowStart
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

enum class ReturnMode { PLANNER, DATETIME, MANUAL }

data class VacationUiState(
    val returnMode: ReturnMode = ReturnMode.PLANNER,
    val returnDateTime: LocalDateTime = LocalDateTime.now().plusDays(7).withHour(18).withMinute(0),
    val antiLegOnReturn: Boolean = true,
    val isSaving: Boolean = false,
    val vacationActive: Boolean = false,
    /** Label of the next planner window start ("Po planeru" resume point), when known. */
    val plannerResumeLabel: String? = null,
    /** Whether the currently selected return point is more than 7 days away (hygiene hint). */
    val spanOverSevenDays: Boolean = false,
)

class VacationViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(
        VacationUiState(
            returnDateTime = LocalDateTime.now(container.clock).plusDays(7)
                .withHour(18).withMinute(0).withSecond(0).withNano(0),
        ),
    )
    val state: StateFlow<VacationUiState> = _state

    init {
        viewModelScope.launch {
            container.scheduleRepository.vacation().collect { hold ->
                _state.update { it.copy(vacationActive = hold != null) }
            }
        }
        viewModelScope.launch { recomputeDerived() }
    }

    fun setMode(mode: ReturnMode) {
        _state.update { it.copy(returnMode = mode) }
        viewModelScope.launch { recomputeDerived() }
    }

    fun setReturnDateTime(dateTime: LocalDateTime) {
        _state.update { it.copy(returnDateTime = dateTime) }
        viewModelScope.launch { recomputeDerived() }
    }

    fun setAntiLeg(enabled: Boolean) = _state.update { it.copy(antiLegOnReturn = enabled) }

    private suspend fun recomputeDerived() {
        try {
            val now = Instant.now(container.clock)
            val zone = container.zone
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val resumeAt = when (_state.value.returnMode) {
                ReturnMode.PLANNER -> nextWindowStart(now, inputs, zone)
                ReturnMode.DATETIME -> _state.value.returnDateTime.atZone(zone).toInstant()
                ReturnMode.MANUAL -> null
            }
            _state.update {
                it.copy(
                    plannerResumeLabel = nextWindowStart(now, inputs, zone)
                        ?.let { at -> Fmt.dateTime(at, zone) },
                    spanOverSevenDays = resumeAt == null ||
                        Duration.between(now, resumeAt) > Duration.ofDays(7),
                )
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    /** "Isključi" — persists the VacationHold (survives reboot), then converges (USER). */
    fun save(onSaved: () -> Unit) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = Instant.now(container.clock)
                val zone = container.zone
                val inputs = container.scheduleRepository.scheduleInputs(now)
                val resumeAt = when (_state.value.returnMode) {
                    ReturnMode.PLANNER -> nextWindowStart(now, inputs, zone)
                    ReturnMode.DATETIME -> _state.value.returnDateTime.atZone(zone).toInstant()
                    ReturnMode.MANUAL -> null
                }
                // A past resume point would persist an "active" hold the resolver ignores —
                // the boiler would keep heating while the UI shows a vacation.
                if (_state.value.returnMode == ReturnMode.DATETIME &&
                    resumeAt != null && !resumeAt.isAfter(now)
                ) {
                    emitError(app.getString(R.string.vac_past_datetime))
                    _state.update { it.copy(isSaving = false) }
                    return@launch
                }
                container.scheduleRepository.startVacation(resumeAt, _state.value.antiLegOnReturn)
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

    /** "Prekini odmor" — cancels the hold and reasserts the planner state. */
    fun cancelVacation(onSaved: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.scheduleRepository.cancelVacation()
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
            withContext(Dispatchers.Main) { onSaved() }
        }
    }
}

/**
 * Dialog `vacation` (SPEC §8): "Ugasi" is NEVER a blind off — the user always picks how the
 * boiler comes back (planner / date-time / manual), with an anti-legionella hint for holds
 * longer than 7 days.
 */
@Composable
fun VacationDialog(onDismiss: () -> Unit) {
    val vm = containerViewModel { app, container -> VacationViewModel(app, container) }
    val state by vm.state.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.padding(16.dp),
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.vac_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.vac_when_back), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            ReturnModeOption(
                label = state.plannerResumeLabel
                    ?.let { "${stringResource(R.string.vac_by_planner)} — $it" }
                    ?: stringResource(R.string.vac_by_planner),
                selected = state.returnMode == ReturnMode.PLANNER,
                onClick = { vm.setMode(ReturnMode.PLANNER) },
            )
            ReturnModeOption(
                label = stringResource(R.string.vac_pick_datetime),
                selected = state.returnMode == ReturnMode.DATETIME,
                onClick = { vm.setMode(ReturnMode.DATETIME) },
            )
            if (state.returnMode == ReturnMode.DATETIME) {
                Column(Modifier.padding(start = 16.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text(Fmt.date(state.returnDateTime.toLocalDate()))
                    }
                    TimePickerField(
                        label = "",
                        value = state.returnDateTime.toLocalTime(),
                        onValueChange = { time: LocalTime ->
                            vm.setReturnDateTime(state.returnDateTime.with(time))
                        },
                    )
                }
            }
            ReturnModeOption(
                label = stringResource(R.string.vac_manual_only),
                selected = state.returnMode == ReturnMode.MANUAL,
                onClick = { vm.setMode(ReturnMode.MANUAL) },
            )

            if (state.spanOverSevenDays) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.antiLegOnReturn, onCheckedChange = vm::setAntiLeg)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.vac_antileg),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vac_reboot_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            if (state.vacationActive) {
                TextButton(onClick = { vm.cancelVacation(onDismiss) }) {
                    Text(stringResource(R.string.action_cancel_vacation))
                }
                Spacer(Modifier.height(8.dp))
            }
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
                ) { Text(stringResource(R.string.vac_do_off)) }
            }
        }
    }

    if (showDatePicker) {
        AppDatePickerDialog(
            initial = state.returnDateTime.toLocalDate(),
            zone = java.time.ZoneId.systemDefault(),
            onConfirm = { date ->
                vm.setReturnDateTime(state.returnDateTime.with(date))
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun ReturnModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
