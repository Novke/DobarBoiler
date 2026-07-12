package rs.novica.dobarboiler.ui.presets

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.containerViewModel

class PresetsViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets

    init {
        viewModelScope.launch {
            container.scheduleRepository.presets().collect { list ->
                _presets.value = list.sortedBy(Preset::name)
            }
        }
    }
}

/** Route `presets` (SPEC §8): preset list + FAB for a new preset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(onEdit: (Long) -> Unit, onNew: () -> Unit) {
    val vm = containerViewModel { app, container -> PresetsViewModel(app, container) }
    val presets by vm.presets.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.preset_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.preset_new))
            }
        },
    ) { padding ->
        if (presets.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.preset_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presets, key = { it.id }) { preset ->
                    PresetRow(preset = preset, onClick = { onEdit(preset.id) })
                }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleMedium)
                val windowLabel = preset.windowOverride?.let { w ->
                    "${Fmt.time(w.start)}–${Fmt.time(w.end)}"
                }
                Text(
                    listOfNotNull(
                        "${preset.targetTemp} ${stringResource(R.string.unit_c)}",
                        windowLabel,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preset.eco) {
                AssistChip(onClick = onClick, label = { Text(stringResource(R.string.dash_eco)) })
            }
        }
    }
}
