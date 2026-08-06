package com.homelab.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters

/**
 * Manifest entry point for the widget. Also owns the refresh schedule's lifecycle, so the periodic
 * work only exists while at least one widget is actually placed.
 */
class KinderDashWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = KinderDashWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DashboardRefreshWorker.schedule(context)
        // Periodic work does not run immediately on enqueue, so without this the first widget would
        // sit empty for up to 15 minutes.
        DashboardRefreshWorker.refreshNow(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)

        // Self-healing, and not merely belt-and-braces: onEnabled fires only for the FIRST widget
        // ever placed. An app update, a force-stop, or anything that drops the WorkManager schedule
        // would otherwise leave an already-placed widget permanently stale with no way back short of
        // removing and re-adding it. schedule() uses KEEP, so this is idempotent.
        DashboardRefreshWorker.schedule(context)

        // Safe to refresh here because updatePeriodMillis is 0 — the system doesn't drive onUpdate
        // on a timer, so this only runs on explicit update requests and restores.
        DashboardRefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DashboardRefreshWorker.cancel(context)
    }
}

/** Tap anywhere on the widget to force a refresh. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        DashboardRefreshWorker.refreshNow(context)
    }
}
