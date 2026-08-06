package com.homelab.app.widget

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
