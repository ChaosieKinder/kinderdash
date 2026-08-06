package com.homelab.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.homelab.app.data.local.DashboardSnapshotStore
import com.homelab.app.domain.manager.DashboardAggregator
import com.homelab.app.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Refreshes the widget's snapshot off the widget's render path.
 *
 * The aggregator already isolates per-service failures, so a run that reaches nothing still writes
 * a valid snapshot — one where every tile says Unavailable. That is deliberate: the widget should
 * show honest "unavailable" tiles with a stale timestamp rather than silently keep displaying old
 * numbers as if they were current.
 */
@HiltWorker
class DashboardRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val aggregator: DashboardAggregator,
    private val snapshotStore: DashboardSnapshotStore
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val state = aggregator.load(System.currentTimeMillis())
            snapshotStore.save(state)
            KinderDashWidget().updateAll(applicationContext)
            Result.success()
        } catch (error: Exception) {
            // Individual services failing is already handled inside the aggregator, so reaching
            // here means something structural. Retry rather than leaving the widget frozen.
            Logger.e(TAG, "Dashboard refresh failed: ${error.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DashboardRefreshWorker"
        private const val PERIODIC_WORK = "kinderdash-dashboard-refresh"
        private const val ONE_SHOT_WORK = "kinderdash-dashboard-refresh-now"

        /**
         * 15 minutes is WorkManager's floor for periodic work; anything smaller is silently raised.
         * Fine for a homelab summary — and [refreshNow] covers the "I want to see it now" case.
         */
        private const val REFRESH_INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DashboardRefreshWorker>(
                REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(
                // No point waking up to fail: every tile needs the network.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                // KEEP, not UPDATE: re-enqueueing on every widget placement would otherwise reset
                // the interval each time and delay the next run indefinitely.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Immediate refresh — on first placement, and on tap. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DashboardRefreshWorker>().build()
            )
        }

        /** Called when the last widget is removed — nothing left to refresh for. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
