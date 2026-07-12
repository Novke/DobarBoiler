package rs.novica.dobarboiler.ui.presets

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.HeatingWindow
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.LabeledSwitchRow
import rs.novica.dobarboiler.ui.common.TempStepper
import rs.novica.dobarboiler.ui.common.TimePickerField
import rs.novica.dobarboiler.ui.common.containerViewModel
import java.time.Instant
import java.time.LocalTime
import kotlin.math.min

enum class PresetValidationError { NAME_INVALID, IN_USE }

data class PresetEditUiState(
    val name: String = "",
    val temp: Int = 55,
    val eco: Boolean = true,
    val windowEnabled: Boolean = false,
    val windowStart: LocalTime = HeatingWindow.DEFAULT.start,
    val windowEnd: LocalTime = HeatingWindow.DEFAULT.end,
    val validationError: PresetValidationError? = null,
    val isExisting: Boolean = false,
    val isSaving: Boolean = false,
    val maxTemp: Int = 75,
)

class PresetEditViewModel(
    app: Application,
    container: AppContainer,
    private val presetId: Long?,
) : ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(PresetEditUiState())
    val state: StateFlow<PresetEditUiState> = _state

    private var otherNames: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            try {
                val now = Instant.now(container.clock)
                val inputs = container.scheduleRepository.scheduleInputs(now)
                val maxTemp = min(75, inputs.config.maxSetpointGuard ?: 75)
                val presets = container.scheduleRepository.presets().first()
                val existing = presetId?.let { id -> presets.firstOrNull { it.id == id } }
                otherNames = presets
                    .filter { it.id != presetId }
                    .map { it.name.trim().lowercase() }
                    .toSet()
                _state.update {
                    if (existing != null) {
                        it.copy(
                            name = existing.name,
                            temp = existing.targetTemp.coerceIn(40, maxTemp),
                            eco = existing.eco,
                            windowEnabled = existing.windowOverride != null,
                            windowStart = existing.windowOverride?.start ?: it.windowStart,
                            windowEnd = existing.windowOverride?.end ?: it.windowEnd,
                            isExisting = true,
                            maxTemp = maxTemp,
                        )
                    } else {
                        it.copy(maxTemp = maxTemp)
                    }
                }
            } catch (t: Throwable) {
                emitError(t)
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, validationError = null) }
    fun setTemp(temp: Int) = _state.update { it.copy(temp = temp.coerceIn(40, it.maxTemp)) }
    fun setEco(eco: Boolean) = _state.update { it.copy(eco = eco) }
    fun setWindowEnabled(enabled: Boolean) = _state.update { it.copy(windowEnabled = enabled) }
    fun setWindow(start: LocalTime, end: LocalTime) =
        _state.update { it.copy(windowStart = start, windowEnd = end) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        val trimmed = s.name.trim()
        if (trimmed.isBlank() || trimmed.lowercase() in otherNames) {
            _state.update { it.copy(validationError = PresetValidationError.NAME_INVALID) }
            return
        }
        if (s.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.scheduleRepository.upsertPreset(
                    Preset(
                        id = presetId ?: 0L,
                        name = trimmed,
                        targetTemp = s.temp.coerceIn(40, s.maxTemp),
                        eco = s.eco,
                        windowOverride = if (s.windowEnabled) {
                            HeatingWindow(s.windowStart, s.windowEnd)
                        } else {
                            null
                        },
                    ),
                )
            } catch (t: Throwable) {
                // Unique-name constraint or storage failure.
                _state.update {
                    it.copy(isSaving = false, validationError = PresetValidationError.NAME_INVALID)
                }
                emitError(t)
                return@launch
            }
            convergeUser()
            _state.update { it.copy(isSaving = false) }
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    /** Delete is blocked while the preset is referenced by the weekly plan (SPEC §4.5). */
    fun delete(onDeleted: () -> Unit) {
        val id = presetId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (container.scheduleRepository.presetInUse(id)) {
                    _state.update { it.copy(validationError = PresetValidationError.IN_USE) }
                    return@launch
                }
                val deleted = container.scheduleRepository.deletePreset(id)
                if (!deleted) {
                    _state.update { it.copy(validationError = PresetValidationError.IN_USE) }
                    return@launch
                }
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
            withContext(Dispatchers.Main) { onDeleted() }
        }
    }
}

/** Route `presets/edit/{id?}` (SPEC §8): name, temp, eco, optional per-preset window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(presetId: Long?, onBack: () -> Unit) {
    val vm = containerViewModel(key = "preset-edit-$presetId") { app, container ->
        PresetEditViewModel(app, container, presetId)
    }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isExisting) R.string.preset_title else R.string.preset_new,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.isExisting) {
                        IconButton(onClick = { vm.delete(onBack) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.preset_delete),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                label = { Text(stringResource(R.string.preset_name)) },
                singleLine = true,
                isError = state.validationError == PresetValidationError.NAME_INVALID,
                modifier = Modifier.fillMaxWidth(),
            )
            state.validationError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (error) {
                            PresetValidationError.NAME_INVALID -> R.string.preset_name_invalid
                            PresetValidationError.IN_USE -> R.string.preset_in_use_error
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.preset_temp), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TempStepper(
                value = state.temp,
                onValueChange = vm::setTemp,
                range = 40..state.maxTemp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            LabeledSwitchRow(
                label = stringResource(R.string.preset_eco),
                checked = state.eco,
                onCheckedChange = vm::setEco,
            )

            Spacer(Modifier.height(8.dp))
            LabeledSwitchRow(
                label = stringResource(R.string.preset_window_override),
                checked = state.windowEnabled,
                onCheckedChange = vm::setWindowEnabled,
            )
            if (state.windowEnabled) {
                Spacer(Modifier.height(8.dp))
                TimePickerField(
                    label = stringResource(R.string.set_window_start),
                    value = state.windowStart,
                    onValueChange = { vm.setWindow(it, state.windowEnd) },
                )
                Spacer(Modifier.height(8.dp))
                TimePickerField(
                    label = stringResource(R.string.set_window_end),
                    value = state.windowEnd,
                    onValueChange = { vm.setWindow(state.windowStart, it) },
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { vm.save(onBack) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.btn_save)) }
        }
    }
}
