package com.homelab.app.domain.manager

import com.homelab.app.data.repository.GrafanaRepository
import com.homelab.app.data.repository.KomodoRepository
import com.homelab.app.data.repository.MediaArrRepository
import com.homelab.app.data.repository.PlexRepository
import com.homelab.app.data.repository.ServiceInstancesRepository
import com.homelab.app.data.repository.UptimeKumaRepository
import com.homelab.app.domain.model.DashboardState
import com.homelab.app.domain.model.DashboardTile
import com.homelab.app.domain.model.DashboardTileKey
import com.homelab.app.domain.model.TileMetric
import com.homelab.app.domain.model.TileSeverity
import com.homelab.app.domain.model.TileStatus
import com.homelab.app.util.Logger
import com.homelab.app.util.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans in across the services that matter at a glance and reduces them to one [DashboardState].
 *
 * This is the piece upstream never had. Every `ui/<service>` screen owns its own state; nothing
 * combined them. The widget needs exactly one object, refreshed on a timer, so this is where that
 * happens.
 *
 * Three properties it has to hold, all of which follow from being on a refresh cycle rather than a
 * screen the user is looking at:
 *
 *  - **Cheap.** Every call here is a repository `getSummary()`-style path — one request each. The
 *    heavyweight `getDashboard()` calls exist for the detail screens and must never be used here.
 *  - **Failure-isolated.** One dead service degrades one tile. A widget that goes blank because
 *    Plex is restarting is worse than useless, because it hides the services that *are* fine.
 *  - **Bounded.** Every tile is under a timeout, so a black-holed host can't leave the widget
 *    spinning until OkHttp's own timeouts expire in series.
 */
@Singleton
class DashboardAggregator @Inject constructor(
    private val serviceInstances: ServiceInstancesRepository,
    private val komodo: KomodoRepository,
    private val uptimeKuma: UptimeKumaRepository,
    private val plex: PlexRepository,
    private val mediaArr: MediaArrRepository,
    private val grafana: GrafanaRepository
) {

    suspend fun load(nowMillis: Long): DashboardState = coroutineScope {
        // Parallel: the slowest service sets the refresh time, not the sum of all of them.
        val tiles = listOf(
            async { komodoTile() },
            async { uptimeKumaTile() },
            async { plexTile() },
            async { seerrTile() },
            async { grafanaTile() }
        ).map { it.await() }

        DashboardState(tiles = tiles, generatedAtMillis = nowMillis)
    }

    private suspend fun komodoTile(): DashboardTile =
        tile(DashboardTileKey.KOMODO, ServiceType.KOMODO) { instanceId ->
            // getDashboard(), not getSummary(): the lighter getSummary() omits `unhealthy`, which is
            // the single most important number on this widget.
            val containers = komodo.getDashboard(instanceId).containers
            listOf(
                TileMetric(
                    label = "Stopped",
                    value = containers.stopped,
                    severity = if (containers.stopped > 0) TileSeverity.WARNING else TileSeverity.GOOD
                ),
                TileMetric(
                    label = "Unhealthy",
                    value = containers.unhealthy,
                    severity = if (containers.unhealthy > 0) TileSeverity.DANGER else TileSeverity.GOOD
                )
            )
        }

    private suspend fun uptimeKumaTile(): DashboardTile =
        tile(DashboardTileKey.UPTIME_KUMA, ServiceType.UPTIME_KUMA) { instanceId ->
            val summary = uptimeKuma.getSummary(instanceId)
            // Reported as "down" rather than "up": the widget exists to surface problems, and a
            // count of 0 is the shape the eye should skip over.
            val down = (summary.totalCount - summary.upCount).coerceAtLeast(0)
            listOf(
                TileMetric(
                    label = "Down",
                    value = down,
                    severity = if (down > 0) TileSeverity.DANGER else TileSeverity.GOOD
                )
            )
        }

    private suspend fun plexTile(): DashboardTile =
        tile(DashboardTileKey.PLEX, ServiceType.PLEX) { instanceId ->
            val summary = plex.getSummary(instanceId)
            listOf(
                TileMetric("Streams", summary.activeStreams, TileSeverity.NEUTRAL),
                TileMetric("Transcoding", summary.transcodingStreams, TileSeverity.NEUTRAL)
            )
        }

    private suspend fun seerrTile(): DashboardTile =
        tile(DashboardTileKey.SEERR, ServiceType.JELLYSEERR) { instanceId ->
            val summary = mediaArr.getSeerrSummary(instanceId)
            listOf(
                TileMetric(
                    label = "Pending",
                    value = summary.pendingRequests,
                    // Something waiting on you, not something broken.
                    severity = if (summary.pendingRequests > 0) TileSeverity.WARNING else TileSeverity.GOOD
                )
            )
        }

    private suspend fun grafanaTile(): DashboardTile =
        tile(DashboardTileKey.GRAFANA, ServiceType.GRAFANA) { instanceId ->
            val summary = grafana.getSummary(instanceId)
            listOf(
                TileMetric(
                    label = "Firing",
                    value = summary.firingAlerts,
                    // A firing Grafana alert is something you configured to demand attention, so it
                    // gets the same weight as an unhealthy container rather than a softer warning.
                    severity = if (summary.firingAlerts > 0) TileSeverity.DANGER else TileSeverity.GOOD
                )
            )
        }

    /**
     * Resolves the preferred instance for [type], runs [fetch] against it under a timeout, and
     * converts any failure into a degraded tile rather than letting it escape.
     */
    private suspend fun tile(
        key: DashboardTileKey,
        type: ServiceType,
        fetch: suspend (instanceId: String) -> List<TileMetric>
    ): DashboardTile {
        val instance = try {
            serviceInstances.getPreferredInstance(type)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "Could not resolve ${type.displayName} instance: ${error.message}")
            null
        }

        if (instance == null) {
            return DashboardTile(key, type.displayName, TileStatus.NotConfigured)
        }

        val title = instance.label.ifBlank { type.displayName }

        return try {
            DashboardTile(key, title, TileStatus.Ready(withTimeout(PER_TILE_TIMEOUT_MS) { fetch(instance.id) }))
        } catch (error: TimeoutCancellationException) {
            // Caught BEFORE CancellationException on purpose — withTimeout signals by throwing a
            // subclass of it, so the usual "rethrow cancellation" rule would swallow our own timeout
            // and take the whole refresh down with it.
            Logger.w(TAG, "${type.displayName} timed out after ${PER_TILE_TIMEOUT_MS}ms")
            DashboardTile(key, title, TileStatus.Unavailable("Timed out"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "${type.displayName} unavailable: ${error.message}")
            DashboardTile(key, title, TileStatus.Unavailable(error.message))
        }
    }

    private companion object {
        const val TAG = "DashboardAggregator"

        /**
         * Below OkHttp's own 15s connect/read timeouts, so a black-holed host degrades one tile
         * quickly instead of stalling the refresh.
         */
        const val PER_TILE_TIMEOUT_MS = 8_000L
    }
}
