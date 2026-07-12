package rs.novica.dobarboiler.ui.overrides

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.scheduling.LogKinds
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.activeHbt
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.dayNameRes
import rs.novica.dobarboiler.ui.common.windowInstantsFor
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

sealed interface OverrideUi {
    val id: Long
    val isDayReplacement: Boolean

    data class Day(
        override val id: Long,
        val date: LocalDate,
        val presetName: String?, // null = skip night
    ) : OverrideUi {
        override val isDayReplacement: Boolean get() = true
    }

    data class Hbt(
        override val id: Long,
        val untilLabel: String,
        val eventLabel: String,
        val temp: Int,
        val eco: Boolean,
    ) : OverrideUi {
        override val isDayReplacement: Boolean get() = false
    }
}

data class OverridesUiState(
    val active: List<OverrideUi> = emptyList(),
    val upcoming: List<OverrideUi> = emptyList(),
    /** Recently expired overrides from the action log (time label, detail). */
    val expiredRecent: List<Pair<String, String>> = emptyList(),
)

class OverridesViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(OverridesUiState())
    val state: StateFlow<OverridesUiState> = _state

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { container.scheduleRepository.inputsChanged().collect { load() } }
        viewModelScope.launch {
            container.actionLog.recent(300).collect { entries ->
                val zone = container.zone
                _state.update { s ->
                    s.copy(
                        expiredRecent = entries
                            .filter { it.kind == LogKinds.OVERRIDE_EXPIRED }
                            .take(10)
                            .map { entry ->
                                Fmt.dateTime(entry.at, zone) to entry.detail
                            },
                    )
                }
            }
        }
    }

    private suspend fun load() {
        try {
            val now = Instant.now(container.clock)
            val zone = container.zone
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val currentHbt = activeHbt(now, inputs, container.engine)

            val active = mutableListOf<OverrideUi>()
            val upcoming = mutableListOf<OverrideUi>()

            inputs.overrides.forEach { override ->
                when (override) {
                    is OneTimeOverride.DayReplacement -> {
                        val item = OverrideUi.Day(
                            id = override.id,
                            date = override.date,
                            presetName = override.presetId?.let { inputs.presets[it]?.name },
                        )
                        val window = windowInstantsFor(override.date, inputs, zone)
                        val isActiveNow = window != null && now >= window.first && now < window.second
                        // A "skip" replacement is active during the window it suppresses too;
                        // compute that window from the planner preset instead.
                        val plannedWindow = windowInstantsFor(
                            override.date,
                            inputs.copy(overrides = inputs.overrides - override),
                            zone,
                        )
                        val skipActive = plannedWindow != null &&
                            now >= plannedWindow.first && now < plannedWindow.second
                        if (isActiveNow || skipActive) active += item else upcoming += item
                    }
                    is OneTimeOverride.HeatByTime -> {
                        val until = override.eventTime
                            .plus(Duration.ofMinutes(override.holdMinutes.toLong()))
                        val item = OverrideUi.Hbt(
                            id = override.id,
                            untilLabel = Fmt.time(until, zone),
                            eventLabel = Fmt.dateTime(override.eventTime, zone),
                            temp = override.targetTemp,
                            eco = override.eco,
                        )
                        if (currentHbt?.id == override.id) active += item else upcoming += item
                    }
                }
            }
            _state.update { it.copy(active = active, upcoming = upcoming) }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    fun cancel(item: OverrideUi) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.scheduleRepository.cancelOverride(item.id, day = item.isDayReplacement)
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }
}

/** Route `overrides` (SPEC §8): active / upcoming / recently expired with cancel actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverridesScreen() {
    val vm = containerViewModel { app, container -> OverridesViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.ot_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.active.isEmpty() && state.upcoming.isEmpty() && state.expiredRecent.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.ot_no_overrides),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.active.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ot_section_active)) }
                items(state.active, key = { "a-${it.isDayReplacement}-${it.id}" }) { item ->
                    OverrideRow(item = item, onCancel = { vm.cancel(item) })
                }
            }
            if (state.upcoming.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ot_section_upcoming)) }
                items(state.upcoming, key = { "u-${it.isDayReplacement}-${it.id}" }) { item ->
                    OverrideRow(item = item, onCancel = { vm.cancel(item) })
                }
            }
            if (state.expiredRecent.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ot_section_expired)) }
                items(state.expiredRecent) { (timeLabel, detail) ->
                    ListItem(
                        headlineContent = { Text(detail) },
                        supportingContent = { Text(timeLabel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun OverrideRow(item: OverrideUi, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        when (item) {
            is OverrideUi.Day -> ListItem(
                headlineContent = {
                    Text("${stringResource(dayNameRes(item.date.dayOfWeek))} ${Fmt.date(item.date)}")
                },
                supportingContent = {
                    Text(item.presetName ?: stringResource(R.string.ot_skip_night))
                },
                trailingContent = { CancelButton(onCancel) },
            )
            is OverrideUi.Hbt -> ListItem(
                headlineContent = {
                    Text(stringResource(R.string.source_override_until, item.untilLabel, item.temp))
                },
                supportingContent = {
                    val eco = if (item.eco) " • ${stringResource(R.string.dash_eco)}" else ""
                    Text("${stringResource(R.string.ot_hot_by)} ${item.eventLabel}$eco")
                },
                trailingContent = { CancelButton(onCancel) },
            )
        }
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    IconButton(onClick = onCancel) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.btn_cancel))
    }
}
