package com.homelab.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.homelab.app.domain.model.DashboardState
import com.homelab.app.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last-known-good storage for the widget's [DashboardState].
 *
 * The widget must never do network work while rendering: `provideGlance` runs when the launcher
 * asks for a view, and blocking that on four HTTP calls gives a widget that is blank or stale for
 * seconds at a time. So the refresh worker writes here and the widget only ever reads.
 *
 * That split also buys the offline behaviour for free — off the network, the widget keeps showing
 * the last real numbers with an honest "as of" timestamp instead of an empty box.
 */
@Singleton
class DashboardSnapshotStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) {

    suspend fun save(state: DashboardState) {
        try {
            val encoded = json.encodeToString(DashboardState.serializer(), state)
            dataStore.edit { it[KEY] = encoded }
        } catch (error: Exception) {
            // A snapshot we can't store just means the widget shows the previous one for longer.
            Logger.e(TAG, "Could not save dashboard snapshot: ${error.message}")
        }
    }

    /** Returns null when nothing has been stored yet, or when the stored value can't be read. */
    suspend fun load(): DashboardState? {
        return try {
            val encoded = dataStore.data.first()[KEY] ?: return null
            json.decodeFromString(DashboardState.serializer(), encoded)
        } catch (error: Exception) {
            // Most likely a shape change across an app update. Treat as "no snapshot" rather than
            // crashing the launcher's widget host, which is a far worse failure.
            Logger.w(TAG, "Discarding unreadable dashboard snapshot: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DashboardSnapshotStore"
        val KEY = stringPreferencesKey("dashboard_snapshot_v1")
    }
}
