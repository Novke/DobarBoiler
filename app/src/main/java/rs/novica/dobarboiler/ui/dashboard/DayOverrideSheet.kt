package rs.novica.dobarboiler.ui.dashboard

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.SheetLayout
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.dayAssignment
import rs.novica.dobarboiler.ui.common.dayNameRes
import java.time.Instant
import java.time.LocalDate

/** Replacement choice: a preset, or an explicit "skip this night". */
sealed interface ReplacementChoice {
    data object Skip : ReplacementChoice
    data class WithPreset(val presetId: Long) : ReplacementChoice
}

data class DayItem(
    val date: LocalDate,
    /** Name of the preset currently planned for that night, null = no heating. */
    val plannerPresetName: String?,
    /** True when a one-time replacement already exists for the date. */
    val alreadyOverridden: Boolean,
    val selected: Boolean,
)

data class DayOverrideUiState(
    val days: List<DayItem> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val replacement: ReplacementChoice? = null,
    val isSaving: Boolean = false,
)

class DayOverrideViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(DayOverrideUiState())
    val state: StateFlow<DayOverrideUiState> = _state

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            container.scheduleRepository.inputsChanged().collect { load() }
        }
    }

    private suspend fun load() {
        try {
            val now = Instant.now(container.clock)
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val today = now.atZone(container.zone).toLocalDate()
            val selectedDates = _state.value.days.filter { it.selected }.map { it.date }.toSet()
            val days = (0..6L).map { offset ->
                val date = today.plusDays(offset)
                val assignment = dayAssignment(date, inputs)
                DayItem(
                    date = date,
                    plannerPresetName = assignment.presetId?.let { id -> inputs.presets[id]?.name },
                    alreadyOverridden = assignment.overridden,
                    selected = date in selectedDates,
                )
            }
            _state.update {
                it.copy(days = days, presets = inputs.presets.values.sortedBy(Preset::name))
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    fun toggleDay(date: LocalDate) = _state.update { s ->
        s.copy(days = s.days.map { if (it.date == date) it.copy(selected = !it.selected) else it })
    }

    fun chooseReplacement(choice: ReplacementChoice) =
        _state.update { it.copy(replacement = choice) }

    val canSave: Boolean
        get() = _state.value.replacement != null && _state.value.days.any { it.selected }

    fun save(onSaved: () -> Unit) {
        val snapshot = _state.value
        val replacement = snapshot.replacement ?: return
        val dates = snapshot.days.filter { it.selected }.map { it.date }
        if (dates.isEmpty() || snapshot.isSaving) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val presetId = (replacement as? ReplacementChoice.WithPreset)?.presetId
                dates.forEach { date ->
                    container.scheduleRepository.upsertDayOverride(date, presetId)
                }
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

/** Sheet `override/day` (SPEC §8): pick day(s) in the next week + a replacement preset or skip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayOverrideSheet(onDismiss: () -> Unit) {
    val vm = containerViewModel { app, container -> DayOverrideViewModel(app, container) }
    val state by vm.state.collectAsState()

    SheetLayout(onDismiss = onDismiss, title = stringResource(R.string.ot_replace_days)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.days.forEach { day ->
                FilterChip(
                    selected = day.selected,
                    onClick = { vm.toggleDay(day.date) },
                    label = {
                        val dayName = stringResource(dayNameRes(day.date.dayOfWeek))
                        Text("$dayName ${Fmt.date(day.date)}")
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        state.days.firstOrNull { it.selected }?.let { first ->
            Text(
                first.plannerPresetName ?: stringResource(R.string.planner_no_heating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.ot_instead_of_planner),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.presets.forEach { preset ->
                FilterChip(
                    selected = (state.replacement as? ReplacementChoice.WithPreset)?.presetId == preset.id,
                    onClick = { vm.chooseReplacement(ReplacementChoice.WithPreset(preset.id)) },
                    label = { Text("${preset.name} ${preset.targetTemp}${stringResource(R.string.unit_c)}") },
                )
            }
            FilterChip(
                selected = state.replacement is ReplacementChoice.Skip,
                onClick = { vm.chooseReplacement(ReplacementChoice.Skip) },
                label = { Text(stringResource(R.string.ot_skip_night)) },
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ot_auto_resume_note),
            style = MaterialTheme.typography.bodySmall,
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
            Button(
                onClick = { vm.save(onDismiss) },
                enabled = vm.canSave && !state.isSaving,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.btn_save)) }
        }
    }
}
