package rs.novica.dobarboiler.ui.history

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.scheduling.LogKinds
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.dayNameRes
import java.time.Instant
import java.time.LocalDate

data class HistoryEntry(
    val id: Long,
    val timeLabel: String,
    val kind: String,
    val detail: String,
    val outcome: String,
    val attempt: Int,
)

data class HistoryDay(val date: LocalDate, val entries: List<HistoryEntry>)

class HistoryViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _days = MutableStateFlow<List<HistoryDay>>(emptyList())
    val days: StateFlow<List<HistoryDay>> = _days

    init {
        viewModelScope.launch {
            container.actionLog.recent(300).collect { entries ->
                val zone = container.zone
                _days.value = entries
                    .map { e ->
                        val at = e.at
                        Triple(at.atZone(zone).toLocalDate(), at, e)
                    }
                    .sortedByDescending { it.second }
                    .groupBy({ it.first }) { (_, at, e) ->
                        HistoryEntry(
                            id = e.id,
                            timeLabel = Fmt.time(at, zone),
                            kind = e.kind,
                            detail = e.detail,
                            outcome = e.outcome,
                            attempt = e.attempt,
                        )
                    }
                    .map { (date, dayEntries) -> HistoryDay(date, dayEntries) }
                    .sortedByDescending { it.date }
            }
        }
    }
}

/** Route `history` (SPEC §8): action log grouped by day with ✓/⚠/✕ outcome icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val vm = containerViewModel { app, container -> HistoryViewModel(app, container) }
    val days by vm.days.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (days.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.hist_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.date}") {
                    Text(
                        "${stringResource(dayNameRes(day.date.dayOfWeek))} ${Fmt.date(day.date)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(day.entries, key = { "e-${it.id}" }) { entry ->
                    HistoryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    val headline = when {
        entry.outcome == "FAILED" -> stringResource(R.string.hist_failed, entry.detail)
        entry.outcome == "RETRYING" -> stringResource(R.string.hist_retry, entry.detail)
        entry.kind == LogKinds.MANUAL_CHANGE -> stringResource(R.string.hist_manual_detected)
        entry.kind == LogKinds.WINDOW_END -> stringResource(R.string.hist_heat_off)
        else -> entry.detail
    }
    val supporting = if (headline != entry.detail && entry.detail.isNotBlank()) entry.detail else null

    ListItem(
        leadingContent = {
            when (entry.outcome) {
                "SUCCESS" -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                "FAILED" -> Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon( // RETRYING / SKIPPED
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        },
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Text(
                entry.timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
