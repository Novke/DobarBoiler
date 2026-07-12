package rs.novica.dobarboiler.ui.login

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.ui.common.HealthIssue
import rs.novica.dobarboiler.ui.common.TimePickerField
import rs.novica.dobarboiler.ui.common.fixIntent

/**
 * Onboarding steps after successful login (SPEC §8): device pick → heating window →
 * permissions walkthrough (notifications, exact alarms, battery exemption).
 */
@Composable
fun OnboardingScreen(state: LoginUiState, vm: LoginViewModel, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        when (state.step) {
            OnboardingStep.DEVICE -> DeviceStep(state, vm)
            OnboardingStep.WINDOW -> WindowStep(state, vm)
            OnboardingStep.PERMISSIONS -> PermissionsStep(
                onFinish = {
                    vm.finish()
                    onDone()
                },
            )
            OnboardingStep.CREDENTIALS -> Unit // handled by LoginScreen
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Step: device pick
// -------------------------------------------------------------------------------------------------

@Composable
private fun DeviceStep(state: LoginUiState, vm: LoginViewModel) {
    Text(stringResource(R.string.onb_pick_device), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Card {
        Column {
            state.devices.forEachIndexed { index, plant ->
                ListItem(
                    headlineContent = { Text(plant.name) },
                    supportingContent = {
                        val parts = buildList {
                            plant.sn?.let { add(it) }
                            if (!plant.online) add(stringResource(R.string.device_offline))
                        }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" — "))
                    },
                    trailingContent = {
                        TextButton(
                            onClick = { vm.selectDevice(plant) },
                            enabled = !state.isLoading,
                        ) { Text(stringResource(R.string.btn_apply)) }
                    },
                )
                if (index < state.devices.lastIndex) HorizontalDivider()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Step: heating window
// -------------------------------------------------------------------------------------------------

@Composable
private fun WindowStep(state: LoginUiState, vm: LoginViewModel) {
    Text(stringResource(R.string.onb_window_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.set_window),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    TimePickerField(
        label = stringResource(R.string.set_window_start),
        value = state.windowStart,
        onValueChange = { vm.setWindow(it, state.windowEnd) },
    )
    Spacer(Modifier.height(12.dp))
    TimePickerField(
        label = stringResource(R.string.set_window_end),
        value = state.windowEnd,
        onValueChange = { vm.setWindow(state.windowStart, it) },
    )

    Spacer(Modifier.height(32.dp))
    Button(onClick = vm::confirmWindow, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_continue))
    }
}

// -------------------------------------------------------------------------------------------------
// Step: permissions walkthrough
// -------------------------------------------------------------------------------------------------

private fun notificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

private fun exactAlarmsGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(AlarmManager::class.java) ?: return false
    return am.canScheduleExactAlarms()
}

private fun batteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun PermissionsStep(onFinish: () -> Unit) {
    val context = LocalContext.current

    // Re-evaluate permission state each time the user returns from a system settings screen.
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

    val notifGranted = remember(refreshTick) { notificationsGranted(context) }
    val alarmsGranted = remember(refreshTick) { exactAlarmsGranted(context) }
    val batteryOk = remember(refreshTick) { batteryExempt(context) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshTick++ }

    Text(
        stringResource(R.string.onb_permissions_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(16.dp))

    PermissionRow(
        label = stringResource(R.string.health_notifications),
        granted = notifGranted,
        onRequest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                HealthIssue.NOTIFICATIONS_BLOCKED.fixIntent(context)?.let(context::startActivity)
            }
        },
    )
    PermissionRow(
        label = stringResource(R.string.health_exact_alarms),
        granted = alarmsGranted,
        onRequest = {
            HealthIssue.EXACT_ALARMS_OFF.fixIntent(context)?.let(context::startActivity)
        },
    )
    PermissionRow(
        label = stringResource(R.string.health_battery),
        granted = batteryOk,
        onRequest = {
            HealthIssue.BATTERY_OPTIMIZED.fixIntent(context)?.let(context::startActivity)
        },
    )

    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.onb_battery_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onb_oem_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(32.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_finish))
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (granted) R.string.perm_granted else R.string.perm_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!granted) {
            TextButton(onClick = onRequest) { Text(stringResource(R.string.perm_request)) }
        }
    }
}
