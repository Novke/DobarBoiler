package rs.novica.dobarboiler.ui.dashboard

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.StateSource
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.HealthIssue
import rs.novica.dobarboiler.ui.common.SourceUi
import rs.novica.dobarboiler.ui.common.clampTemp
import rs.novica.dobarboiler.ui.common.computeHealthIssues
import rs.novica.dobarboiler.ui.common.nextWindow
import rs.novica.dobarboiler.ui.common.sourceUiFrom
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class DashboardUiState(
    val snapshot: DeviceSnapshot? = null,
    val lastSyncSecondsAgo: Long? = null,
    /** Seconds until manual refresh is allowed again (30 s etiquette floor). */
    val refreshCooldownSeconds: Long = 0,
    val activeSource: StateSource = StateSource.DEFAULT_OFF,
    val source: SourceUi = SourceUi.Off,
    /** Upcoming window (start, end) labels for "Sledeće: grejanje X–Y", when known. */
    val nextWindowLabels: Pair<String, String>? = null,
    val healthIssues: List<HealthIssue> = emptyList(),
    val isRefreshing: Boolean = false,
    val manualHold: Boolean = false,
    val manualHoldUntilLabel: String? = null,
    val boostActive: Boolean = false,
    /** Starting point for the quick temp-bump stepper. */
    val currentReqTemp: Int = 55,
    /** Upper clamp for user-selectable temperatures (maxSetpointGuard-aware). */
    val maxSelectableTemp: Int = 75,
)

class DashboardViewModel(app: Application, container: AppContainer) :
    ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state

    init {
        refresh()
        // Periodic ticker: keeps "Ažurirano pre X", refresh cooldown and health checks live.
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                recompute()
                delay(15_000)
            }
        }
        viewModelScope.launch { container.deviceRepository.snapshot.collect { recompute() } }
        viewModelScope.launch { container.scheduleRepository.inputsChanged().collect { recompute() } }
        viewModelScope.launch { container.settings.runtime.collect { recompute() } }
    }

    private suspend fun recompute() {
        try {
            val now = Instant.now(container.clock)
            val zone = container.zone
            val snapshot = container.deviceRepository.snapshot.value
            val runtime = container.settings.runtimeSnapshot()
            val inputs = container.scheduleRepository.scheduleInputs(now)
            val resolution = container.engine.resolve(now, inputs)
            val manualHoldUntil = runtime.manualHoldUntil?.takeIf { now < it }
            val sinceSync = snapshot?.let { Duration.between(it.at, now).seconds.coerceAtLeast(0) }
            val upcoming = nextWindow(now, inputs, zone)

            _state.update {
                it.copy(
                    snapshot = snapshot,
                    lastSyncSecondsAgo = sinceSync,
                    refreshCooldownSeconds = sinceSync?.let { s -> (30 - s).coerceAtLeast(0) } ?: 0,
                    activeSource = resolution.source,
                    source = sourceUiFrom(resolution, inputs, now, zone, container.engine),
                    nextWindowLabels = upcoming?.let { (s, e) ->
                        Fmt.time(s, zone) to Fmt.time(e, zone)
                    },
                    healthIssues = computeHealthIssues(
                        context = app,
                        canScheduleExact = container.alarmScheduler.canScheduleExact(),
                        lastCloudContactAt = runtime.lastCloudContactAt,
                        now = now,
                    ),
                    manualHold = manualHoldUntil != null,
                    manualHoldUntilLabel = manualHoldUntil?.let { u -> Fmt.time(u, zone) },
                    boostActive = inputs.boost != null,
                    currentReqTemp = snapshot?.reqTemp?.roundToInt()
                        ?: resolution.desired.targetTemp ?: 55,
                    maxSelectableTemp = minOf(75, inputs.config.maxSetpointGuard ?: 75),
                )
            }
        } catch (t: Throwable) {
            emitError(t)
        }
    }

    /** Manual poll; [rs.novica.dobarboiler.data.DeviceRepository.refresh] enforces the 30 s floor. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.deviceRepository.refresh()
            } catch (t: Throwable) {
                emitError(t)
            }
            _state.update { it.copy(isRefreshing = false) }
            recompute()
        }
    }

    /** "Vrati na plan": clears the manual hold, then converges with the USER trigger. */
    fun returnToPlan() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.settings.updateRuntime { it.copy(manualHoldUntil = null) }
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }

    /**
     * Quick "Temp" bump (SPEC §8): a HeatByTime with `eventTime = nextTransition.at` and
     * `holdMinutes = 0`, eco left as currently observed — a direct temp override valid until
     * the next scheduled transition.
     */
    fun tempBump(target: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = Instant.now(container.clock)
                val inputs = container.scheduleRepository.scheduleInputs(now)
                val resolution = container.engine.resolve(now, inputs)
                // No scheduled transition (empty world) → bound the bump to 2 h.
                val eventTime = resolution.nextTransition?.at ?: now.plus(Duration.ofHours(2))
                val eco = container.deviceRepository.snapshot.value?.eco ?: true
                container.scheduleRepository.addHeatByTime(
                    eventTime = eventTime,
                    targetTemp = clampTemp(target, inputs),
                    eco = eco,
                    holdMinutes = 0,
                )
            } catch (t: Throwable) {
                emitError(t)
                return@launch
            }
            convergeUser()
        }
    }
}
