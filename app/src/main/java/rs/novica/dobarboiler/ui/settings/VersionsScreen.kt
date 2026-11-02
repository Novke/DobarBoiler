package rs.novica.dobarboiler.ui.settings

import android.app.Application
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.BuildConfig
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.api.ReleaseDto
import rs.novica.dobarboiler.data.UpdateState
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.containerViewModel

/** Shared by the dashboard banner, the settings row and Podešavanja → Verzije (docs/release.md). */
class UpdateViewModel(app: Application, container: AppContainer) : ContainerViewModel(app, container) {

    val state: StateFlow<UpdateState> = container.updater.state
    val history: StateFlow<List<ReleaseDto>> = container.updater.history

    init {
        viewModelScope.launch { container.updater.check() }
    }

    fun check() {
        viewModelScope.launch { container.updater.check(force = true) }
    }

    fun loadHistory() {
        viewModelScope.launch { container.updater.refreshHistory() }
    }

    fun isInstalled(release: ReleaseDto) = container.updater.isInstalled(release)

    /** "Ažuriraj": logged, then downloaded + handed to the system installer (outlives the screen). */
    fun update(release: ReleaseDto) {
        userAction("Ažuriraj na ${release.version} (sa ${BuildConfig.VERSION_NAME})")
        container.appScope.launch { container.updater.downloadAndInstall(release) }
    }
}

/** The release an update action can act on right now, or null when there is nothing to offer. */
fun UpdateState.offeredRelease(): ReleaseDto? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.NeedsPermission -> release
    is UpdateState.Installing -> release
    is UpdateState.Failed -> release
    else -> null
}

/** Dashboard card: appears only when a newer version is published. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val vm = containerViewModel(key = "update") { app, container -> UpdateViewModel(app, container) }
    val state by vm.state.collectAsState()
    val release = state.offeredRelease() ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.upd_title, release.version), style = MaterialTheme.typography.titleMedium)
            release.changelog.forEach { change ->
                Text("• ${change.text}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            UpdateAction(state, release, onUpdate = { vm.update(release) })
        }
    }
}

/** Status line + button for an offered release (progress while downloading). */
@Composable
private fun UpdateAction(state: UpdateState, release: ReleaseDto, onUpdate: () -> Unit) {
    when (state) {
        is UpdateState.Downloading -> {
            Text(stringResource(R.string.upd_downloading, (state.progress * 100).toInt()))
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        else -> {
            when (state) {
                is UpdateState.NeedsPermission -> Text(stringResource(R.string.upd_needs_permission))
                is UpdateState.Installing -> Text(stringResource(R.string.upd_installing))
                is UpdateState.Failed -> Text(stringResource(R.string.upd_failed, state.message))
                else -> Unit
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onUpdate) { Text(stringResource(R.string.upd_button) + " ${release.version}") }
        }
    }
}

/** Route `settings/versions`: installed version, check/update, and the published history. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsVersionsScreen() {
    val vm = containerViewModel(key = "update") { app, container -> UpdateViewModel(app, container) }
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadHistory() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ver_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.ver_installed_now, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            val offered = state.offeredRelease()
            if (offered != null) {
                UpdateBanner()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (val s = state) {
                            UpdateState.Checking -> stringResource(R.string.upd_checking)
                            is UpdateState.UpToDate -> stringResource(R.string.upd_up_to_date)
                            is UpdateState.Failed -> stringResource(R.string.upd_check_failed, s.message)
                            else -> ""
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.check(); vm.loadHistory() }) { Text(stringResource(R.string.upd_check)) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.ver_history), style = MaterialTheme.typography.titleMedium)
            if (history.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ver_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            history.forEach { release ->
                Spacer(Modifier.height(12.dp))
                val badges = listOfNotNull(
                    release.date,
                    stringResource(R.string.ver_badge_installed).takeIf { vm.isInstalled(release) },
                    stringResource(R.string.ver_badge_test).takeIf { release.test },
                ).joinToString(" · ")
                Text(release.version, style = MaterialTheme.typography.titleSmall)
                if (badges.isNotEmpty()) {
                    Text(badges, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                release.changelog.forEach { change ->
                    Text("• ${change.text}", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
            }
        }
    }
}
