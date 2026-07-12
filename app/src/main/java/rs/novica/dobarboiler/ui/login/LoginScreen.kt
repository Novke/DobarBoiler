package rs.novica.dobarboiler.ui.login

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.R
import rs.novica.dobarboiler.api.VelisPlant
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.domain.HeatingWindow
import rs.novica.dobarboiler.ui.common.ContainerViewModel
import rs.novica.dobarboiler.ui.common.containerViewModel
import java.time.LocalTime

/** Onboarding progression (SPEC §8 login row): credentials → device → window → permissions. */
enum class OnboardingStep { CREDENTIALS, DEVICE, WINDOW, PERMISSIONS }

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: Boolean = false,
    val noDevices: Boolean = false,
    val devices: List<VelisPlant> = emptyList(),
    val step: OnboardingStep = OnboardingStep.CREDENTIALS,
    val windowStart: LocalTime = HeatingWindow.DEFAULT.start,
    val windowEnd: LocalTime = HeatingWindow.DEFAULT.end,
)

class LoginViewModel(app: Application, container: AppContainer) : ContainerViewModel(app, container) {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun setEmail(value: String) = _state.update { it.copy(email = value, error = false) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = false) }

    fun login() {
        val email = _state.value.email.trim()
        val password = _state.value.password
        if (email.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = true) }
            return
        }
        _state.update { it.copy(isLoading = true, error = false, noDevices = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.apiClient.login(email, password)
                container.credentialStore.storeCredentials(email, password)
                val devices = container.apiClient.listVelisPlants().filter { it.sys == 4 }
                when {
                    devices.isEmpty() ->
                        _state.update { it.copy(isLoading = false, noDevices = true) }
                    devices.size == 1 -> selectDeviceInternal(devices.first())
                    else -> _state.update {
                        it.copy(isLoading = false, devices = devices, step = OnboardingStep.DEVICE)
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false, error = true) }
            }
        }
    }

    fun selectDevice(plant: VelisPlant) {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) { selectDeviceInternal(plant) }
    }

    private suspend fun selectDeviceInternal(plant: VelisPlant) {
        container.credentialStore.gwId = plant.gw
        container.credentialStore.deviceName = plant.name
        // Best-effort: read live plantSettings so maxSetpointGuard is seeded (SPEC R14).
        runCatching { container.deviceRepository.plantSettings() }
        _state.update { it.copy(isLoading = false, step = OnboardingStep.WINDOW) }
    }

    fun setWindow(start: LocalTime, end: LocalTime) =
        _state.update { it.copy(windowStart = start, windowEnd = end) }

    fun confirmWindow() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            container.settings.update { settings ->
                settings.copy(
                    engineConfig = settings.engineConfig.copy(
                        defaultWindow = HeatingWindow(s.windowStart, s.windowEnd),
                    ),
                )
            }
            _state.update { it.copy(step = OnboardingStep.PERMISSIONS) }
        }
    }

    /** Final onboarding step done → first convergence (arms the transition alarm). */
    fun finish() {
        convergeUser()
    }
}

/**
 * Route `login`: hosts the whole login + onboarding flow; [onDone] navigates to the dashboard
 * once permissions walkthrough is finished.
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm = containerViewModel { app, container -> LoginViewModel(app, container) }
    val state by vm.state.collectAsState()

    when (state.step) {
        OnboardingStep.CREDENTIALS -> CredentialsStep(state, vm)
        else -> OnboardingScreen(state = state, vm = vm, onDone = onDone)
    }
}

@Composable
private fun CredentialsStep(state: LoginUiState, vm: LoginViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = vm::setEmail,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = vm::setPassword,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading,
        )

        if (state.error) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.login_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.noDevices) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onb_no_devices),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = vm::login,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.login_submit))
            }
        }
    }
}
