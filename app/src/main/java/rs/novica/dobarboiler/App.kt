package rs.novica.dobarboiler

import android.app.Application
import androidx.work.Configuration
import rs.novica.dobarboiler.di.AppContainer
import rs.novica.dobarboiler.notifications.Channels

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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
