package rs.novica.dobarboiler

import android.app.Application
import androidx.work.Configuration
import kotlinx.coroutines.launch
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.notifications.Channels
import rs.novica.dobarboiler.scheduling.LogKinds
import rs.novica.dobarboiler.scheduling.LogOutcomes
import rs.novica.dobarboiler.scheduling.Triggers

/**
 * Application entry point (SPEC §10.1).
 *
 * Owns the manual-DI [AppContainer]; workers, receivers and services reach it via
 * `(context.applicationContext as App).container`. Implements on-demand WorkManager
 * initialization (the default initializer is removed in the manifest).
 */
class App : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.ensure(this)
        container.enqueueWatchdog() // periodic 6 h, unique "watchdog", KEEP
        container.enqueueServerSync() // periodic 1 h, unique "server_sync_periodic", KEEP
        container.enqueueLinkCheck() // periodic 15 min, unique "link_check", KEEP
        logVersionChange()
    }

    /** One APP_UPDATED row per new APK, so the history shows which build was running when. */
    private fun logVersionChange() {
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        container.appScope.launch {
            runCatching {
                val (changed, previous) = container.settings.swapLoggedAppVersion(version)
                if (changed) {
                    container.actionLog.log(
                        kind = LogKinds.APP_UPDATED, trig = Triggers.BOOT,
                        detail = "Aplikacija $version" + (previous?.let { " (pre: $it)" } ?: " — prvo pokretanje"),
                        outcome = LogOutcomes.SUCCESS,
                    )
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
