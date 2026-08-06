package com.homelab.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Implements [Configuration.Provider] so WorkManager constructs workers through Hilt.
 *
 * Without it, `DashboardRefreshWorker` — which takes constructor dependencies — cannot be
 * instantiated, and the widget silently never refreshes. The default WorkManager startup
 * initializer is removed in AndroidManifest.xml so this configuration is the one that applies.
 */
@HiltAndroidApp
class MiaApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
