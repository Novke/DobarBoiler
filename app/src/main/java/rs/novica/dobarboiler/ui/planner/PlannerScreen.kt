package rs.novica.dobarboiler.ui.planner

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.PresetPickerDialog
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.dayNameRes
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

data class DayAssignmentUi(
    val dow: DayOfWeek,
    val presetName: String?,           // null = no heating that night
    /** "Jednokratno: %s" badge when a DayReplacement exists for this day in the next week. */
    val overrideBadge: String?,
)

data class PlannerUiState(
    val days: List<DayAssignmentUi> = emptyList(),
    val presets: List<Preset> = emptyList(),
)

class PlannerViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { container.scheduleRepository.inputsChanged().collect { load() } }
    }

    private suspend fun load() {
        try {
            val now = Instant.now(container.clock)
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val today = now.atZone(container.zone).toLocalDate()

            // Window-start dates of the next 7 nights, keyed by day of week.
            val upcomingDates: Map<DayOfWeek, LocalDate> =
                (0..6L).associate { offset ->
                    val d = today.plusDays(offset)
                    d.dayOfWeek to d
                }
            val replacements = inputs.overrides
                .filterIsInstance<rs.novica.dobarboiler.domain.OneTimeOverride.DayReplacement>()
                .associateBy { it.date }

            val days = DayOfWeek.entries.map { dow ->
                val presetId = inputs.weeklyPlan.assignments[dow]
                val replacement = upcomingDates[dow]?.let { replacements[it] }
                DayAssignmentUi(
                    dow = dow,
                    presetName = presetId?.let { inputs.presets[it]?.name },
                    overrideBadge = replacement?.let { r ->
                        r.presetId?.let { inputs.presets[it]?.name } ?: ""
                    },
                )
            }
            _state.update {
                it.copy(days = days, presets = inputs.presets.values.sortedBy(Preset::name))
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    fun assign(dow: DayOfWeek, presetId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.scheduleRepository.assign(dow, presetId)
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }

    /** "Primeni na radne dane / vikend" bulk assignment. */
    fun assignGroup(days: List<DayOfWeek>, presetId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                days.forEach { container.scheduleRepository.assign(it, presetId) }
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }
}

/** Route `planner` (SPEC §8): week view with per-day preset assignment + bulk apply buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(onEditPresets: () -> Unit) {
    val vm = containerViewModel { app, container -> PlannerViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    // null = closed; NonNull holds the pick target (single day or group).
    var pickTarget by remember { mutableStateOf<List<DayOfWeek>?>(null) }
    var pickTitle by remember { mutableStateOf("") }

    val weekdays = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    val weekend = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.planner_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val weekdaysLabel = stringResource(R.string.planner_weekdays)
        val weekendLabel = stringResource(R.string.planner_weekend)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.days, key = { it.dow }) { day ->
                val dayName = stringResource(dayNameRes(day.dow))
                DayRow(
                    day = day,
                    onClick = {
                        pickTitle = dayName
                        pickTarget = listOf(day.dow)
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickTitle = weekdaysLabel
                            pickTarget = weekdays
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.planner_apply_weekdays)) }
                    OutlinedButton(
                        onClick = {
                            pickTitle = weekendLabel
                            pickTarget = weekend
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.planner_apply_weekend)) }
                }
            }
            item {
                OutlinedButton(onClick = onEditPresets, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.preset_title))
                }
            }
        }
    }

    pickTarget?.let { target ->
        PresetPickerDialog(
            title = pickTitle,
            presets = state.presets,
            allowNone = true,
            onPick = { presetId ->
                if (target.size == 1) vm.assign(target.first(), presetId)
                else vm.assignGroup(target, presetId)
                pickTarget = null
            },
            onDismiss = { pickTarget = null },
        )
    }
}

@Composable
private fun DayRow(day: DayAssignmentUi, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(dayNameRes(day.dow)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    day.presetName ?: stringResource(R.string.planner_no_heating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            day.overrideBadge?.let { badge ->
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            stringResource(
                                R.string.planner_override_badge,
                                badge.ifEmpty { stringResource(R.string.planner_no_heating) },
                            ),
                        )
                    },
                )
            }
        }
    }
}
