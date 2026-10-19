@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.api.HistoryApiClient
import rs.novica.dobarboiler.data.SyncState
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.scheduling.ServerSyncWorker
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.ErrorSnackbars
import rs.novica.dobarboiler.ui.common.containerViewModel
import java.time.Duration
import java.time.Instant

const val DEFAULT_HISTORY_URL = "http://novica-dev/boiler/"

/** One line describing the upload state; shared by the settings root, health screen and this screen. */
sealed interface ServerStatus {
    data object NotConfigured : ServerStatus
    data object NeverSent : ServerStatus
    data class Ok(val ago: Duration) : ServerStatus
    data class Error(val message: String) : ServerStatus
}

fun serverStatus(configured: Boolean, sync: SyncState, now: Instant): ServerStatus = when {
    !configured -> ServerStatus.NotConfigured
    sync.lastSyncError != null -> ServerStatus.Error(sync.lastSyncError)
    sync.lastSyncOkAt == null -> ServerStatus.NeverSent
    else -> ServerStatus.Ok(Duration.between(sync.lastSyncOkAt, now).coerceAtLeast(Duration.ZERO))
}

@Composable
fun agoLabel(d: Duration): String = when {
    d.toMinutes() < 60 -> stringResource(R.string.srv_ago_min, d.toMinutes().coerceAtLeast(1))
    d.toHours() < 48 -> stringResource(R.string.srv_ago_h, d.toHours())
    else -> stringResource(R.string.srv_ago_d, d.toDays())
}

@Composable
fun serverStatusLabel(status: ServerStatus): String = when (status) {
    ServerStatus.NotConfigured -> stringResource(R.string.srv_status_none)
    ServerStatus.NeverSent -> stringResource(R.string.srv_status_never)
    is ServerStatus.Ok -> stringResource(R.string.srv_status_ok, agoLabel(status.ago))
    is ServerStatus.Error -> stringResource(R.string.srv_status_error, status.message)
}

sealed interface TestResult {
    data class Ok(val lastStateAgo: Duration?) : TestResult
    data class Failed(val message: String) : TestResult
}

class ServerSettingsViewModel(app: Application, container: AppContainer) : ContainerViewModel(app, container) {

    val status: StateFlow<ServerStatus> = container.settings.sync
        .map { serverStatus(isConfigured(), it, container.clock.instant()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerStatus.NotConfigured)

    var testResult by mutableStateOf<TestResult?>(null)
        private set
    var testing by mutableStateOf(false)
        private set

    fun initialUrl(): String = container.credentialStore.historyServerUrl ?: DEFAULT_HISTORY_URL
    fun initialToken(): String = container.credentialStore.historyServerToken.orEmpty()

    private fun isConfigured() = container.credentialStore.historyServerUrl != null &&
        container.credentialStore.historyServerToken != null

    fun save(url: String, token: String) {
        container.credentialStore.historyServerUrl = url
        container.credentialStore.historyServerToken = token
        ServerSyncWorker.enqueueSoon(app)
    }

    fun test(url: String, token: String) {
        testing = true
        viewModelScope.launch {
            testResult = try {
                val health = HistoryApiClient(url, token.trim(), Json { ignoreUnknownKeys = true }).health()
                val ago = health.lastStateAt?.let {
                    runCatching { Duration.between(java.time.OffsetDateTime.parse(it).toInstant(), container.clock.instant()) }
                        .getOrNull()
                }
                TestResult.Ok(ago)
            } catch (e: Exception) {
                TestResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            testing = false
        }
    }
}

@Composable
fun SettingsServerScreen() {
    val vm = containerViewModel { app, container -> ServerSettingsViewModel(app, container) }
    val status by vm.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    ErrorSnackbars(vm.errors, snackbarHostState)
    var url by remember { mutableStateOf(vm.initialUrl()) }
    var token by remember { mutableStateOf(vm.initialToken()) }
    var showToken by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.srv_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(serverStatusLabel(status), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; saved = false },
                label = { Text(stringResource(R.string.srv_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; saved = false },
                label = { Text(stringResource(R.string.srv_token)) },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(if (showToken) R.string.srv_hide else R.string.srv_show),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = { vm.save(url, token); saved = true },
                    enabled = url.isNotBlank() && token.isNotBlank(),
                ) { Text(stringResource(if (saved) R.string.srv_saved else R.string.srv_save)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.test(url, token) },
                    enabled = !vm.testing && url.isNotBlank() && token.isNotBlank(),
                ) { Text(stringResource(R.string.srv_test)) }
            }
            vm.testResult?.let { r ->
                Spacer(Modifier.height(12.dp))
                Text(
                    when (r) {
                        is TestResult.Ok -> r.lastStateAgo?.let { stringResource(R.string.srv_test_ok, agoLabel(it)) }
                            ?: stringResource(R.string.srv_test_ok_nostate)
                        is TestResult.Failed -> stringResource(R.string.srv_test_fail, r.message)
                    },
                    color = if (r is TestResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.srv_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
