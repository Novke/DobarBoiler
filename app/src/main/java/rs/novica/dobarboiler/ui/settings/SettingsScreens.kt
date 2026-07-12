package rs.novica.dobarboiler.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.BandConfig
import rs.novica.dobarboiler.domain.EngineConfig
import rs.novica.dobarboiler.domain.HeatingWindow
import rs.novica.dobarboiler.domain.MaintainMode
import rs.novica.dobarboiler.domain.ManualChangePolicy
import rs.novica.dobarboiler.domain.OutsideWindowBehavior
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.HealthIssue
import rs.novica.dobarboiler.ui.common.IntStepperRow
import rs.novica.dobarboiler.ui.common.LabeledSwitchRow
import rs.novica.dobarboiler.ui.common.RadioRow
import rs.novica.dobarboiler.ui.common.SectionHeader
import rs.novica.dobarboiler.ui.common.SettingsNavItem
import rs.novica.dobarboiler.ui.common.TimePickerField
import rs.novica.dobarboiler.ui.common.computeHealthIssues
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.fixIntent
import rs.novica.dobarboiler.ui.common.labelRes
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.math.min
import kotlin.math.roundToInt

data class SettingsUiState(
    val windowStart: LocalTime = HeatingWindow.DEFAULT.start,
    val windowEnd: LocalTime = HeatingWindow.DEFAULT.end,
    val outsideWindow: OutsideWindowBehavior = OutsideWindowBehavior.OFF,
    val keepWarmTemp: Int = 40,
    val bandEnabled: Boolean = false,
    val stopAt: Int = 56,
    val restartAt: Int = 52,
    val bandPollMinutes: Int = 15,
    val heatRate: Int = 15,
    val learnedRate: Double? = null,
    val learnedEcoRate: Double? = null,
    val safetyMarginPercent: Int = 15,
    val boostMaxHours: Int = 4,
    val manualPolicy: ManualChangePolicy = ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION,
    val reassertMinutes: Int = 15,
    val midWindowCheck: Boolean = true,
    val maxSetpoint: Int = 75,
    val accountEmail: String = "",
    val deviceName: String? = null,
    val healthIssues: List<HealthIssue> = emptyList(),
    val lastCloudAgoLabel: String? = null,
    val zoneId: String = "Europe/Belgrade",
)

class SettingsViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                val cfg = settings.engineConfig
                val band = (cfg.maintainMode as? MaintainMode.Band)?.band
                _state.update {
                    it.copy(
                        windowStart = cfg.defaultWindow.start,
                        windowEnd = cfg.defaultWindow.end,
                        outsideWindow = cfg.outsideWindow,
                        keepWarmTemp = cfg.keepWarmTemp,
                        bandEnabled = band != null,
                        stopAt = band?.stopAt ?: it.stopAt,
                        restartAt = band?.restartAt ?: it.restartAt,
                        bandPollMinutes = band?.pollEvery?.toMinutes()?.toInt() ?: it.bandPollMinutes,
                        heatRate = cfg.heatingRate.ratePerHourC.roundToInt(),
                        learnedRate = cfg.heatingRate.learnedRatePerHourC,
                        learnedEcoRate = cfg.heatingRate.learnedEcoRatePerHourC,
                        safetyMarginPercent = cfg.heatingRate.safetyMarginPercent,
                        boostMaxHours = cfg.boostMaxDuration.toHours().toInt().coerceIn(1, 6),
                        manualPolicy = cfg.manualChangePolicy,
                        reassertMinutes = settings.reassertAfterMinutes,
                        midWindowCheck = cfg.midWindowCheck,
                        maxSetpoint = cfg.maxSetpointGuard ?: 75,
                        zoneId = settings.zoneId,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val email = container.credentialStore.credentials()?.first ?: ""
            val device = container.credentialStore.deviceName
            _state.update { it.copy(accountEmail = email, deviceName = device) }
        }
        refreshHealth()
    }

    fun refreshHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = Instant.now(container.clock)
                val runtime = container.settings.runtimeSnapshot()
                _state.update {
                    it.copy(
                        healthIssues = computeHealthIssues(
                            context = app,
                            canScheduleExact = container.alarmScheduler.canScheduleExact(),
                            lastCloudContactAt = runtime.lastCloudContactAt,
                            now = now,
                        ),
                        lastCloudAgoLabel = runtime.lastCloudContactAt?.let { at ->
                            Fmt.ago(Duration.between(at, now).seconds.coerceAtLeast(0))
                        },
                    )
                }
            } catch (t: Throwable) {
                emitError(t)
            }
        }
    }

    private fun updateConfig(transform: (EngineConfig) -> EngineConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.settings.update { it.copy(engineConfig = transform(it.engineConfig)) }
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }

    fun setWindow(start: LocalTime, end: LocalTime) =
        updateConfig { it.copy(defaultWindow = HeatingWindow(start, end)) }

    fun setOutsideWindow(behavior: OutsideWindowBehavior) =
        updateConfig { it.copy(outsideWindow = behavior) }

    fun setKeepWarmTemp(temp: Int) =
        updateConfig { it.copy(keepWarmTemp = temp.coerceIn(40, 75)) }

    fun setMidWindowCheck(enabled: Boolean) =
        updateConfig { it.copy(midWindowCheck = enabled) }

    /** Band-mode write keeping the `stopAt >= restartAt + 2` invariant (SPEC §3.3). */
    fun setBand(enabled: Boolean, stopAt: Int, restartAt: Int, pollMinutes: Int) {
        val stop = stopAt.coerceIn(44, 75)
        val restart = min(restartAt, stop - 2).coerceAtLeast(40)
        val poll = pollMinutes.coerceIn(10, 30)
        _state.update {
            it.copy(bandEnabled = enabled, stopAt = stop, restartAt = restart, bandPollMinutes = poll)
        }
        updateConfig {
            it.copy(
                maintainMode = if (enabled) {
                    MaintainMode.Band(BandConfig(stop, restart, Duration.ofMinutes(poll.toLong())))
                } else {
                    MaintainMode.Simple
                },
            )
        }
    }

    fun setHeatRate(perHour: Int) = updateConfig {
        it.copy(heatingRate = it.heatingRate.copy(ratePerHourC = perHour.coerceIn(1, 50).toDouble()))
    }

    fun setSafetyMargin(percent: Int) = updateConfig {
        it.copy(heatingRate = it.heatingRate.copy(safetyMarginPercent = percent.coerceIn(0, 50)))
    }

    fun setBoostMaxHours(hours: Int) = updateConfig {
        it.copy(boostMaxDuration = Duration.ofHours(hours.coerceIn(1, 6).toLong()))
    }

    fun setManualPolicy(policy: ManualChangePolicy) =
        updateConfig { it.copy(manualChangePolicy = policy) }

    fun setReassertMinutes(minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.settings.update { it.copy(reassertAfterMinutes = minutes.coerceIn(5, 120)) }
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }

    /** Writes the *device* plant setting (old/new pattern) and refreshes the local guard (R14). */
    fun applyMaxSetpoint(temp: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.deviceRepository.applyMaxSetpoint(temp.coerceIn(40, 75))
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.alarmScheduler.cancelTransition()
                container.alarmScheduler.cancelBandTick()
                container.alarmScheduler.cancelBoostTimeout()
                container.credentialStore.clear()
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}

// =================================================================================================
// Root ("Još")
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onOpenWindow: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenOverrides: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsNavItem(stringResource(R.string.set_window), onOpenWindow)
            SettingsNavItem(stringResource(R.string.set_advanced), onOpenAdvanced)
            SettingsNavItem(stringResource(R.string.ot_title), onOpenOverrides)
            SettingsNavItem(stringResource(R.string.history_title), onOpenHistory)
            SettingsNavItem(stringResource(R.string.set_health), onOpenHealth)
            SettingsNavItem(stringResource(R.string.set_account), onOpenAccount)
        }
    }
}

// =================================================================================================
// Window times (`settings/window`)
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWindowScreen() {
    val vm = containerViewModel { app, container -> SettingsViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.set_window)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
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

            SectionHeader(stringResource(R.string.set_outside_window))
            RadioRow(
                label = stringResource(R.string.set_outside_off),
                selected = state.outsideWindow == OutsideWindowBehavior.OFF,
                onClick = { vm.setOutsideWindow(OutsideWindowBehavior.OFF) },
            )
            RadioRow(
                label = stringResource(R.string.set_outside_keep, state.keepWarmTemp),
                selected = state.outsideWindow == OutsideWindowBehavior.KEEP_WARM,
                onClick = { vm.setOutsideWindow(OutsideWindowBehavior.KEEP_WARM) },
            )
            if (state.outsideWindow == OutsideWindowBehavior.KEEP_WARM) {
                IntStepperRow(
                    label = stringResource(R.string.preset_temp),
                    value = state.keepWarmTemp,
                    onValueChange = vm::setKeepWarmTemp,
                    range = 40..75,
                    valueLabel = "${state.keepWarmTemp} ${stringResource(R.string.unit_c)}",
                )
            }

            Spacer(Modifier.height(16.dp))
            LabeledSwitchRow(
                label = stringResource(R.string.set_midwindow_check),
                checked = state.midWindowCheck,
                onCheckedChange = vm::setMidWindowCheck,
            )
        }
    }
}

// =================================================================================================
// Advanced (`settings/advanced`)
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAdvancedScreen() {
    val vm = containerViewModel { app, container -> SettingsViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    var pendingMaxSetpoint by remember(state.maxSetpoint) { mutableIntStateOf(state.maxSetpoint) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.set_advanced)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ---- App-side hysteresis (band mode) ----
            LabeledSwitchRow(
                label = stringResource(R.string.set_hysteresis),
                supportingText = stringResource(R.string.set_hysteresis_note),
                checked = state.bandEnabled,
                onCheckedChange = { enabled ->
                    vm.setBand(enabled, state.stopAt, state.restartAt, state.bandPollMinutes)
                },
            )
            if (state.bandEnabled) {
                Spacer(Modifier.height(8.dp))
                IntStepperRow(
                    label = stringResource(R.string.set_stop_at, state.stopAt),
                    value = state.stopAt,
                    onValueChange = { vm.setBand(true, it, state.restartAt, state.bandPollMinutes) },
                    range = 44..75,
                    valueLabel = "${state.stopAt} ${stringResource(R.string.unit_c)}",
                )
                IntStepperRow(
                    label = stringResource(R.string.set_restart_at, state.restartAt),
                    value = state.restartAt,
                    onValueChange = { vm.setBand(true, state.stopAt, it, state.bandPollMinutes) },
                    range = 40..(state.stopAt - 2),
                    valueLabel = "${state.restartAt} ${stringResource(R.string.unit_c)}",
                )
                IntStepperRow(
                    label = stringResource(R.string.set_band_poll),
                    value = state.bandPollMinutes,
                    onValueChange = { vm.setBand(true, state.stopAt, state.restartAt, it) },
                    range = 10..30,
                    valueLabel = "${state.bandPollMinutes} min",
                    step = 5,
                )
            }

            // ---- Heating rate ----
            SectionHeader(stringResource(R.string.set_heat_rate))
            IntStepperRow(
                label = stringResource(R.string.set_heat_rate),
                value = state.heatRate,
                onValueChange = vm::setHeatRate,
                range = 1..50,
                valueLabel = stringResource(R.string.set_heat_rate_value, state.heatRate),
            )
            state.learnedRate?.let { learned ->
                Text(
                    stringResource(R.string.set_heat_rate_learned, learned.toFloat()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.learnedEcoRate?.let { learned ->
                Text(
                    stringResource(R.string.set_heat_rate_learned, learned.toFloat()) +
                        " (${stringResource(R.string.dash_eco)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IntStepperRow(
                label = stringResource(R.string.set_safety_margin),
                value = state.safetyMarginPercent,
                onValueChange = vm::setSafetyMargin,
                range = 0..50,
                valueLabel = "${state.safetyMarginPercent} %",
                step = 5,
            )

            // ---- Boost ----
            SectionHeader(stringResource(R.string.boost_title))
            IntStepperRow(
                label = stringResource(R.string.set_boost_timeout),
                value = state.boostMaxHours,
                onValueChange = vm::setBoostMaxHours,
                range = 1..6,
                valueLabel = stringResource(R.string.set_hours_value, state.boostMaxHours),
            )

            // ---- Manual button policy ----
            SectionHeader(stringResource(R.string.set_manual_policy))
            RadioRow(
                label = stringResource(R.string.set_manual_respect),
                selected = state.manualPolicy == ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION,
                onClick = { vm.setManualPolicy(ManualChangePolicy.RESPECT_UNTIL_NEXT_TRANSITION) },
            )
            RadioRow(
                label = stringResource(R.string.set_manual_reassert),
                selected = state.manualPolicy == ManualChangePolicy.REASSERT,
                onClick = { vm.setManualPolicy(ManualChangePolicy.REASSERT) },
            )
            if (state.manualPolicy == ManualChangePolicy.REASSERT) {
                IntStepperRow(
                    label = stringResource(R.string.set_reassert_minutes),
                    value = state.reassertMinutes,
                    onValueChange = vm::setReassertMinutes,
                    range = 5..120,
                    valueLabel = "${state.reassertMinutes} min",
                    step = 5,
                )
            }

            // ---- Device max setpoint (plant setting, old/new write) ----
            SectionHeader(stringResource(R.string.set_max_setpoint))
            IntStepperRow(
                label = stringResource(R.string.set_max_setpoint),
                value = pendingMaxSetpoint,
                onValueChange = { pendingMaxSetpoint = it },
                range = 40..75,
                valueLabel = "$pendingMaxSetpoint ${stringResource(R.string.unit_c)}",
            )
            if (pendingMaxSetpoint != state.maxSetpoint) {
                OutlinedButton(onClick = { vm.applyMaxSetpoint(pendingMaxSetpoint) }) {
                    Text(stringResource(R.string.btn_apply))
                }
            }

            // ---- Zone (informational) ----
            SectionHeader(stringResource(R.string.set_zone))
            Text(state.zoneId, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// =================================================================================================
// Account (`settings/account`)
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAccountScreen(onLoggedOut: () -> Unit) {
    val vm = containerViewModel { app, container -> SettingsViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.set_account)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.login_email)) },
                supportingContent = { Text(state.accountEmail) },
            )
            state.deviceName?.let { device ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.onb_pick_device)) },
                    supportingContent = { Text(device) },
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.logout(onLoggedOut) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.set_logout)) }
        }
    }
}

// =================================================================================================
// Reliability health (`settings/health`)
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHealthScreen() {
    val vm = containerViewModel { app, container -> SettingsViewModel(app, container) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    // Recheck when the user comes back from a system settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHealth() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.set_health)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.healthIssues.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.health_ok), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                state.healthIssues.forEach { issue ->
                    val label = if (issue == HealthIssue.CLOUD_STALE_24H) {
                        stringResource(R.string.health_stale, state.lastCloudAgoLabel ?: "24 h")
                    } else {
                        stringResource(issue.labelRes())
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        issue.fixIntent(context)?.let { intent ->
                            TextButton(onClick = { context.startActivity(intent) }) {
                                Text(stringResource(R.string.btn_fix))
                            }
                        }
                    }
                }
            }
        }
    }
}
