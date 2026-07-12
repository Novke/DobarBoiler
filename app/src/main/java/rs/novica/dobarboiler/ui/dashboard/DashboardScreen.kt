@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package rs.novica.dobarboiler.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.domain.DeviceSnapshot
import rs.novica.dobarboiler.domain.StateSource
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.Fmt
import rs.novica.dobarboiler.ui.common.HealthIssue
import rs.novica.dobarboiler.ui.common.SourceUi
import rs.novica.dobarboiler.ui.common.TempStepper
import rs.novica.dobarboiler.ui.common.containerViewModel
import rs.novica.dobarboiler.ui.common.labelRes
import rs.novica.dobarboiler.ui.common.sourceText
import kotlin.math.roundToInt

/**
 * Route `dashboard` (SPEC §8): PermissionsHealthCard, TempDialSection, chips row,
 * ActiveSourceCard, QuickActionsRow, one-time-override entry. Pull-to-refresh honors the
 * 30 s minimum poll interval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenBoost: () -> Unit,
    onOpenVacation: () -> Unit,
    onOpenDayOverride: () -> Unit,
    onOpenHeatByTime: () -> Unit,
    onOpenPlanner: () -> Unit,
    onOpenOverrides: () -> Unit,
    onOpenHealth: () -> Unit,
) {
    val vm = containerViewModel { app, container -> DashboardViewModel(app, container) }
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)

    var showOneTimeChooser by remember { mutableStateOf(false) }
    var showTempBump by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.healthIssues.isNotEmpty()) {
                    PermissionsHealthCard(issues = state.healthIssues, onFix = onOpenHealth)
                    Spacer(Modifier.height(16.dp))
                }

                TempDialSection(snapshot = state.snapshot, boostActive = state.boostActive)

                StatusChipsRow(snapshot = state.snapshot)
                Spacer(Modifier.height(4.dp))
                UpdatedAgoRow(
                    secondsAgo = state.lastSyncSecondsAgo,
                    cooldownSeconds = state.refreshCooldownSeconds,
                    isRefreshing = state.isRefreshing,
                    onRefresh = vm::refresh,
                )
                Spacer(Modifier.height(12.dp))

                ActiveSourceCard(
                    state = state,
                    onReturnToPlan = vm::returnToPlan,
                    onClick = {
                        when (state.activeSource) {
                            StateSource.BOOST -> onOpenBoost()
                            StateSource.HEAT_BY_TIME -> onOpenOverrides()
                            StateSource.VACATION -> onOpenVacation()
                            else -> onOpenPlanner()
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))

                QuickActionsRow(
                    onBoost = onOpenBoost,
                    onPowerOff = onOpenVacation, // "Ugasi" NEVER blindly off — opens vacation dialog
                    onTempBump = { showTempBump = !showTempBump },
                    onOneTime = { showOneTimeChooser = true },
                )

                if (showTempBump) {
                    Spacer(Modifier.height(16.dp))
                    TempBumpCard(
                        initial = state.currentReqTemp,
                        maxTemp = state.maxSelectableTemp,
                        onApply = { temp ->
                            vm.tempBump(temp)
                            showTempBump = false
                        },
                    )
                }
            }
        }
    }

    if (showOneTimeChooser) {
        ModalBottomSheet(onDismissRequest = { showOneTimeChooser = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.ot_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ot_replace_days)) },
                    supportingContent = { Text(stringResource(R.string.ot_replace_days_sub)) },
                    leadingContent = { Icon(Icons.Filled.MoreTime, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showOneTimeChooser = false
                        onOpenDayOverride()
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.ot_hot_by)) },
                    supportingContent = { Text(stringResource(R.string.ot_hot_by_sub)) },
                    leadingContent = { Icon(Icons.Filled.Thermostat, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showOneTimeChooser = false
                        onOpenHeatByTime()
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TempDialSection
// -------------------------------------------------------------------------------------------------

@Composable
fun TempDialSection(snapshot: DeviceSnapshot?, boostActive: Boolean, modifier: Modifier = Modifier) {
    val ringColor = if (boostActive) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val statusRes = when {
        snapshot == null -> R.string.dash_off
        snapshot.on && snapshot.heatReq -> R.string.dash_heating
        snapshot.on -> R.string.dash_maintaining
        else -> R.string.dash_off
    }
    val statusColor = when {
        snapshot?.on == true && snapshot.heatReq -> ringColor
        snapshot?.on == true -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = modifier.size(260.dp), contentAlignment = Alignment.Center) {
        DialRing(
            fraction = snapshot?.let { ((it.temp - 40.0) / 35.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f,
            ringColor = ringColor,
            trackColor = trackColor,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = snapshot?.temp?.roundToInt()?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = snapshot?.let {
                    stringResource(R.string.dash_target, it.reqTemp.roundToInt())
                } ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.titleMedium,
                color = statusColor,
            )
        }
    }
}

@Composable
private fun DialRing(fraction: Float, ringColor: Color, trackColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val strokeWidth = 18.dp.toPx()
        val inset = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = style,
        )
        if (fraction > 0f) {
            drawArc(
                color = ringColor,
                startAngle = 135f,
                sweepAngle = 270f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = style,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Chips + sync row
// -------------------------------------------------------------------------------------------------

@Composable
private fun StatusChipsRow(snapshot: DeviceSnapshot?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot?.availableShowers?.let { showers ->
            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.dash_showers, showers)) },
            )
        }
        snapshot?.remainingHeatTime?.takeIf { !it.isZero && !it.isNegative }?.let { remaining ->
            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.dash_time_left, Fmt.hoursMinutes(remaining))) },
            )
        }
        if (snapshot?.eco == true) {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.dash_eco)) },
            )
        }
    }
}

@Composable
private fun UpdatedAgoRow(
    secondsAgo: Long?,
    cooldownSeconds: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = secondsAgo?.let { stringResource(R.string.dash_updated_ago, Fmt.ago(it)) } ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onRefresh,
            enabled = !isRefreshing && cooldownSeconds <= 0,
        ) {
            if (cooldownSeconds > 0) {
                Text(
                    "$cooldownSeconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// ActiveSourceCard
// -------------------------------------------------------------------------------------------------

@Composable
fun ActiveSourceCard(
    state: DashboardUiState,
    onReturnToPlan: () -> Unit,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (state.manualHold) {
                Text(
                    stringResource(R.string.source_manual, state.manualHoldUntilLabel ?: "--:--"),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onReturnToPlan) {
                    Text(stringResource(R.string.action_return_to_plan))
                }
            } else {
                Text(sourceText(state.source), style = MaterialTheme.typography.titleMedium)
            }
            val next = state.nextWindowLabels
            val showNext = next != null && state.source !is SourceUi.Boost
            if (showNext && next != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.source_next_window, next.first, next.second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// PermissionsHealthCard
// -------------------------------------------------------------------------------------------------

@Composable
fun PermissionsHealthCard(issues: List<HealthIssue>, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.health_bad_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            issues.forEach { issue ->
                val label = if (issue == HealthIssue.CLOUD_STALE_24H) {
                    stringResource(R.string.health_stale, "24 h")
                } else {
                    stringResource(issue.labelRes())
                }
                Text("• $label", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onFix) { Text(stringResource(R.string.btn_fix)) }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// QuickActionsRow + temp bump
// -------------------------------------------------------------------------------------------------

@Composable
fun QuickActionsRow(
    onBoost: () -> Unit,
    onPowerOff: () -> Unit,
    onTempBump: () -> Unit,
    onOneTime: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        QuickAction(Icons.Filled.Bolt, stringResource(R.string.action_boost), onBoost)
        QuickAction(Icons.Filled.PowerSettingsNew, stringResource(R.string.action_power_off), onPowerOff)
        QuickAction(Icons.Filled.Thermostat, stringResource(R.string.action_temp_bump), onTempBump)
        QuickAction(Icons.Filled.MoreTime, stringResource(R.string.action_one_time), onOneTime)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TempBumpCard(initial: Int, maxTemp: Int, onApply: (Int) -> Unit) {
    var temp by remember { mutableIntStateOf(initial.coerceIn(40, maxTemp)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TempStepper(value = temp, onValueChange = { temp = it }, range = 40..maxTemp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onApply(temp) }) {
                    Text(stringResource(R.string.btn_apply))
                }
            }
        }
    }
}
