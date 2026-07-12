@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package rs.novica.dobarboiler.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.Flow
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.domain.OneTimeOverride
import rs.novica.dobarboiler.domain.Preset
import rs.novica.dobarboiler.domain.Resolution
import rs.novica.dobarboiler.domain.ScheduleInputs
import rs.novica.dobarboiler.domain.SchedulerEngine
import rs.novica.dobarboiler.domain.StateSource
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

// =================================================================================================
// Formatting helpers (numbers/times only — not translatable text)
// =================================================================================================

object Fmt {
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DM: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.")
    private val DMHM: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M. HH:mm")

    fun time(t: LocalTime): String = HM.format(t)
    fun time(i: Instant, zone: ZoneId): String = HM.format(i.atZone(zone))
    fun date(d: LocalDate): String = DM.format(d)
    fun dateTime(i: Instant, zone: ZoneId): String = DMHM.format(i.atZone(zone))

    /** "42 s" / "12 min" / "3 h" — for "Ažurirano pre %s" and staleness labels. */
    fun ago(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h"
    }

    /** "HH:mm" remaining-time label for rmTm. */
    fun hoursMinutes(d: Duration): String =
        "%02d:%02d".format(d.toHours(), d.toMinutes() % 60)

    /** "52 min" / "1 h 20 min" / "2 h". */
    fun minutesLabel(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

@StringRes
fun dayNameRes(dow: DayOfWeek): Int = when (dow) {
    DayOfWeek.MONDAY -> R.string.day_mon
    DayOfWeek.TUESDAY -> R.string.day_tue
    DayOfWeek.WEDNESDAY -> R.string.day_wed
    DayOfWeek.THURSDAY -> R.string.day_thu
    DayOfWeek.FRIDAY -> R.string.day_fri
    DayOfWeek.SATURDAY -> R.string.day_sat
    DayOfWeek.SUNDAY -> R.string.day_sun
}

// =================================================================================================
// Schedule helpers shared by dashboard / planner / overrides / vacation ViewModels
// =================================================================================================

/** Effective assignment for a window-start date: day override (if any) beats the weekly plan. */
data class DayAssignment(val presetId: Long?, val overridden: Boolean)

fun dayAssignment(date: LocalDate, inputs: ScheduleInputs): DayAssignment {
    val replacement = inputs.overrides
        .filterIsInstance<OneTimeOverride.DayReplacement>()
        .firstOrNull { it.date == date }
    return if (replacement != null) {
        DayAssignment(replacement.presetId, overridden = true)
    } else {
        DayAssignment(inputs.weeklyPlan.assignments[date.dayOfWeek], overridden = false)
    }
}

/** Concrete [start, end) instants of date's heating window, or null when that night is skipped. */
fun windowInstantsFor(date: LocalDate, inputs: ScheduleInputs, zone: ZoneId): Pair<Instant, Instant>? {
    val assignment = dayAssignment(date, inputs)
    val presetId = assignment.presetId ?: return null
    val preset = inputs.presets[presetId] ?: inputs.config.fallbackPreset
    val window = preset.windowOverride ?: inputs.config.defaultWindow
    val start = date.atTime(window.start).atZone(zone).toInstant()
    val endDate = if (window.crossesMidnight) date.plusDays(1) else date
    val end = endDate.atTime(window.end).atZone(zone).toInstant()
    return start to end
}

/** First upcoming window start strictly after [now] within the next 9 days, or null. */
fun nextWindowStart(now: Instant, inputs: ScheduleInputs, zone: ZoneId): Instant? {
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0..8L) {
        val (start, _) = windowInstantsFor(today.plusDays(offset), inputs, zone) ?: continue
        if (start > now) return start
    }
    return null
}

/** First upcoming window (start, end) strictly after [now]; used for "Sledeće: grejanje X–Y". */
fun nextWindow(now: Instant, inputs: ScheduleInputs, zone: ZoneId): Pair<Instant, Instant>? {
    val today = now.atZone(zone).toLocalDate()
    for (offset in 0..8L) {
        val w = windowInstantsFor(today.plusDays(offset), inputs, zone) ?: continue
        if (w.first > now) return w
    }
    return null
}

/** The HBT override currently in effect (highest target wins; tie → later eventTime), or null. */
fun activeHbt(now: Instant, inputs: ScheduleInputs, engine: SchedulerEngine): OneTimeOverride.HeatByTime? =
    inputs.overrides
        .filterIsInstance<OneTimeOverride.HeatByTime>()
        .filter { hbt ->
            val end = hbt.eventTime.plus(Duration.ofMinutes(hbt.holdMinutes.toLong()))
            engine.computedStart(hbt, inputs, now) <= now && now < end
        }
        .maxWithOrNull(compareBy({ it.targetTemp }, { it.eventTime }))

fun clampTemp(t: Int, inputs: ScheduleInputs): Int =
    t.coerceIn(40, min(75, inputs.config.maxSetpointGuard ?: 75))

// =================================================================================================
// Active-source description (dashboard card + boost "revert to" line)
// =================================================================================================

sealed interface SourceUi {
    data class Planner(val dow: DayOfWeek, val presetName: String, val temp: Int) : SourceUi
    data class OverrideUntil(val untilLabel: String, val temp: Int) : SourceUi
    data class Vacation(val untilLabel: String?) : SourceUi
    data class Boost(val temp: Int) : SourceUi
    data class KeepWarm(val temp: Int) : SourceUi
    data object Off : SourceUi
}

/** Maps a resolver result to a UI description of *why* the boiler is in its current state. */
fun sourceUiFrom(
    resolution: Resolution,
    inputs: ScheduleInputs,
    now: Instant,
    zone: ZoneId,
    engine: SchedulerEngine,
): SourceUi = when (resolution.source) {
    StateSource.BOOST -> SourceUi.Boost(inputs.boost?.targetTemp ?: resolution.desired.targetTemp ?: 0)

    StateSource.HEAT_BY_TIME -> {
        val hbt = activeHbt(now, inputs, engine)
        if (hbt != null) {
            val until = hbt.eventTime.plus(Duration.ofMinutes(hbt.holdMinutes.toLong()))
            SourceUi.OverrideUntil(Fmt.time(until, zone), clampTemp(hbt.targetTemp, inputs))
        } else {
            SourceUi.OverrideUntil("--:--", resolution.desired.targetTemp ?: 0)
        }
    }

    StateSource.VACATION ->
        SourceUi.Vacation(inputs.vacation?.resumeAt?.let { Fmt.dateTime(it, zone) })

    StateSource.DAY_OVERRIDE, StateSource.WEEKLY_PLAN -> {
        // The active window started either today or yesterday (midnight-crossing windows).
        val today = now.atZone(zone).toLocalDate()
        val date = listOf(today, today.minusDays(1)).firstOrNull { d ->
            windowInstantsFor(d, inputs, zone)?.let { (s, e) -> now >= s && now < e } == true
        } ?: today
        val presetId = dayAssignment(date, inputs).presetId
        val preset = presetId?.let { inputs.presets[it] } ?: inputs.config.fallbackPreset
        SourceUi.Planner(date.dayOfWeek, preset.name, clampTemp(preset.targetTemp, inputs))
    }

    StateSource.KEEP_WARM -> SourceUi.KeepWarm(inputs.config.keepWarmTemp)

    StateSource.DEFAULT_OFF -> SourceUi.Off
}

@Composable
fun sourceText(source: SourceUi): String = when (source) {
    is SourceUi.Planner -> stringResource(
        R.string.source_planner,
        stringResource(dayNameRes(source.dow)),
        source.presetName,
        source.temp,
    )
    is SourceUi.OverrideUntil -> stringResource(R.string.source_override_until, source.untilLabel, source.temp)
    is SourceUi.Vacation ->
        if (source.untilLabel != null) stringResource(R.string.source_vacation, source.untilLabel)
        else stringResource(R.string.source_vacation_manual)
    is SourceUi.Boost -> stringResource(R.string.source_boost, source.temp)
    is SourceUi.KeepWarm -> stringResource(R.string.source_keep_warm, source.temp)
    SourceUi.Off -> stringResource(R.string.source_off)
}

// =================================================================================================
// Reliability health (ui-local per SPEC §8)
// =================================================================================================

enum class HealthIssue { EXACT_ALARMS_OFF, BATTERY_OPTIMIZED, NOTIFICATIONS_BLOCKED, CLOUD_STALE_24H }

fun computeHealthIssues(
    context: Context,
    canScheduleExact: Boolean,
    lastCloudContactAt: Instant?,
    now: Instant,
): List<HealthIssue> {
    val issues = mutableListOf<HealthIssue>()
    if (!canScheduleExact) issues += HealthIssue.EXACT_ALARMS_OFF
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
        issues += HealthIssue.BATTERY_OPTIMIZED
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        issues += HealthIssue.NOTIFICATIONS_BLOCKED
    }
    if (lastCloudContactAt != null &&
        Duration.between(lastCloudContactAt, now) > Duration.ofHours(24)
    ) {
        issues += HealthIssue.CLOUD_STALE_24H
    }
    return issues
}

@StringRes
fun HealthIssue.labelRes(): Int = when (this) {
    HealthIssue.EXACT_ALARMS_OFF -> R.string.health_exact_alarms
    HealthIssue.BATTERY_OPTIMIZED -> R.string.health_battery
    HealthIssue.NOTIFICATIONS_BLOCKED -> R.string.health_notifications
    HealthIssue.CLOUD_STALE_24H -> R.string.health_stale // formatted with staleness duration
}

/** "Popravi" intent for the issue; null when there is nothing to launch (stale cloud). */
fun HealthIssue.fixIntent(context: Context): Intent? = when (this) {
    HealthIssue.EXACT_ALARMS_OFF ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            null
        }
    HealthIssue.BATTERY_OPTIMIZED -> Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    HealthIssue.NOTIFICATIONS_BLOCKED -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    HealthIssue.CLOUD_STALE_24H -> null
}

// =================================================================================================
// Layout building blocks
// =================================================================================================

/**
 * Bottom-sheet-styled container for navigation `dialog()` destinations (SPEC §8: sheets are
 * destinations layered over Dashboard). Tap on the scrim area dismisses.
 */
@Composable
fun SheetLayout(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 24.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 32.dp, height = 4.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                }
                Spacer(Modifier.height(16.dp))
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun LabeledSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Navigation row used by the "Još" root screen. */
@Composable
fun SettingsNavItem(title: String, onClick: () -> Unit, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

// =================================================================================================
// Value pickers
// =================================================================================================

/** Temperature stepper: "- 55 °C +", clamped to [range]. */
@Composable
fun TempStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange = 40..75,
    modifier: Modifier = Modifier,
    step: Int = 1,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        FilledIconButton(
            onClick = { onValueChange((value - step).coerceIn(range)) },
            enabled = value - step >= range.first,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        Text(
            text = "$value ${stringResource(R.string.unit_c)}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        FilledIconButton(
            onClick = { onValueChange((value + step).coerceIn(range)) },
            enabled = value + step <= range.last,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

/** Generic labeled integer stepper for settings values (minutes, percents, hours...). */
@Composable
fun IntStepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    valueLabel: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledIconButton(
            onClick = { onValueChange((value - step).coerceIn(range)) },
            enabled = value - step >= range.first,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(
            valueLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        FilledIconButton(
            onClick = { onValueChange((value + step).coerceIn(range)) },
            enabled = value + step <= range.last,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** Button showing a LocalTime; opens an M3 TimePicker dialog on tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    value: LocalTime,
    onValueChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { showDialog = true }) { Text(Fmt.time(value)) }
    }
    if (showDialog) {
        val state = rememberTimePickerState(
            initialHour = value.hour,
            initialMinute = value.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(LocalTime.of(state.hour, state.minute))
                    showDialog = false
                }) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}

/** M3 date picker dialog returning a LocalDate in [zone]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePickerDialog(
    initial: LocalDate,
    zone: ZoneId,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    // DatePicker works in UTC-midnight millis by convention.
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate())
                }
                onDismiss()
            }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** Simple preset chooser dialog; [allowNone] adds a "Bez grejanja" entry (returns null id). */
@Composable
fun PresetPickerDialog(
    title: String,
    presets: List<Preset>,
    allowNone: Boolean,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
        title = { Text(title) },
        text = {
            Column {
                presets.forEach { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = {
                            Text("${preset.targetTemp} ${stringResource(R.string.unit_c)}")
                        },
                        modifier = Modifier.clickable { onPick(preset.id) },
                    )
                }
                if (allowNone) {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.planner_no_heating)) },
                        modifier = Modifier.clickable { onPick(null) },
                    )
                }
            }
        },
    )
}

// =================================================================================================
// Error snackbars
// =================================================================================================

/** Collects a ViewModel's error stream into a snackbar host, formatted via `common_error`. */
@Composable
fun ErrorSnackbars(errors: Flow<String>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(errors) {
        errors.collect { detail ->
            snackbarHostState.showSnackbar(context.getString(R.string.common_error, detail))
        }
    }
}
