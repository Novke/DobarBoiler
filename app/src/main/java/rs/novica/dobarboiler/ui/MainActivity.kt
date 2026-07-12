package rs.novica.dobarboiler.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableSharedFlow
import rs.novica.dobarboiler.App
import rs.novica.dobarboiler.notifications.Notifier
import rs.novica.dobarboiler.ui.theme.DobarBoilerTheme

/**
 * Single-activity Compose host (SPEC §8).
 *
 * Start destination: `login` when not onboarded (no credentials or no selected device),
 * otherwise `dashboard`. Error notifications carry [Notifier.EXTRA_OPEN_HISTORY] — both the
 * launch intent and subsequent [onNewIntent] deliveries route the UI to the history tab.
 */
class MainActivity : ComponentActivity() {

    /** Buffered so a notification tap arriving before composition still lands on history. */
    private val openHistoryEvents = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as App).container
        val onboarded = container.credentialStore.isLoggedIn() &&
            container.credentialStore.gwId != null
        val startDestination = if (onboarded) Routes.DASHBOARD else Routes.LOGIN

        maybeEmitOpenHistory(intent)

        setContent {
            DobarBoilerTheme {
                AppRoot(
                    startDestination = startDestination,
                    openHistoryEvents = openHistoryEvents,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeEmitOpenHistory(intent)
    }

    private fun maybeEmitOpenHistory(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifier.EXTRA_OPEN_HISTORY, false) == true) {
            openHistoryEvents.tryEmit(Unit)
        }
    }
}
