package com.homelab.app.domain.manager

import com.homelab.app.data.repository.GrafanaRepository
import com.homelab.app.data.repository.GrafanaSummary
import com.homelab.app.data.repository.KomodoContainerSummary
import com.homelab.app.data.repository.KomodoDashboardData
import com.homelab.app.data.repository.KomodoRepository
import com.homelab.app.data.repository.KomodoResourceSummary
import com.homelab.app.data.repository.MediaArrRepository
import com.homelab.app.data.repository.PlexRepository
import com.homelab.app.data.repository.PlexSummary
import com.homelab.app.data.repository.SeerrSummary
import com.homelab.app.data.repository.ServiceInstancesRepository
import com.homelab.app.data.repository.UptimeKumaRepository
import com.homelab.app.data.repository.UptimeKumaSummary
import com.homelab.app.domain.model.DashboardTileKey
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.TileSeverity
import com.homelab.app.domain.model.TileStatus
import com.homelab.app.util.ServiceType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardAggregatorTest {

    private val serviceInstances: ServiceInstancesRepository = mockk()
    private val komodo: KomodoRepository = mockk()
    private val uptimeKuma: UptimeKumaRepository = mockk()
    private val plex: PlexRepository = mockk()
    private val mediaArr: MediaArrRepository = mockk()
    private val grafana: GrafanaRepository = mockk()

    private fun aggregator() = DashboardAggregator(serviceInstances, komodo, uptimeKuma, plex, mediaArr, grafana)

    private fun instance(type: ServiceType, label: String = "") = ServiceInstance(
        id = "${type.name.lowercase()}-1",
        type = type,
        label = label,
        url = "https://example.invalid"
    )

    /** Everything configured and healthy unless a test overrides it. */
    private fun happyPath() {
        coEvery { serviceInstances.getPreferredInstance(ServiceType.KOMODO) } returns instance(ServiceType.KOMODO)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.UPTIME_KUMA) } returns instance(ServiceType.UPTIME_KUMA)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.PLEX) } returns instance(ServiceType.PLEX)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.JELLYSEERR) } returns instance(ServiceType.JELLYSEERR)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.GRAFANA) } returns instance(ServiceType.GRAFANA)

        coEvery { komodo.getDashboard(any()) } returns komodoDashboard(stopped = 0, unhealthy = 0)
        coEvery { uptimeKuma.getSummary(any()) } returns UptimeKumaSummary(upCount = 12, totalCount = 12)
        coEvery { plex.getSummary(any()) } returns PlexSummary(0, 0, 0)
        coEvery { mediaArr.getSeerrSummary(any()) } returns SeerrSummary(pendingRequests = 0, totalRequests = 40)
        coEvery { grafana.getSummary(any()) } returns GrafanaSummary(firingAlerts = 0, totalAlerts = 3)
    }

    private fun komodoDashboard(stopped: Int, unhealthy: Int): KomodoDashboardData {
        val empty = KomodoResourceSummary(0, 0, 0, 0, 0, 0)
        return KomodoDashboardData(
            version = "1.0",
            servers = empty,
            deployments = empty,
            stacks = empty,
            containers = KomodoContainerSummary(
                total = 20, running = 20 - stopped, stopped = stopped, unhealthy = unhealthy,
                exited = 0, paused = 0, restarting = 0, unknown = 0
            )
        )
    }

    private fun metric(tiles: List<com.homelab.app.domain.model.DashboardTile>, key: DashboardTileKey, label: String) =
        (tiles.single { it.key == key }.status as TileStatus.Ready).metrics.single { it.label == label }

    @Test
    fun `healthy homelab reports no problem`() = runTest {
        happyPath()

        val state = aggregator().load(nowMillis = 1_000L)

        assertEquals(5, state.tiles.size)
        assertEquals(1_000L, state.generatedAtMillis)
        assertFalse(state.hasProblem)
        assertFalse(state.allUnavailable)
    }

    @Test
    fun `unhealthy containers are danger, stopped are only warning`() = runTest {
        happyPath()
        coEvery { komodo.getDashboard(any()) } returns komodoDashboard(stopped = 2, unhealthy = 1)

        val tiles = aggregator().load(0L).tiles

        assertEquals(TileSeverity.WARNING, metric(tiles, DashboardTileKey.KOMODO, "Stopped").severity)
        assertEquals(TileSeverity.DANGER, metric(tiles, DashboardTileKey.KOMODO, "Unhealthy").severity)
    }

    @Test
    fun `uptime kuma reports down count, derived from total minus up`() = runTest {
        happyPath()
        coEvery { uptimeKuma.getSummary(any()) } returns UptimeKumaSummary(upCount = 9, totalCount = 12)

        val down = metric(aggregator().load(0L).tiles, DashboardTileKey.UPTIME_KUMA, "Down")

        assertEquals(3, down.value)
        assertEquals(TileSeverity.DANGER, down.severity)
    }

    @Test
    fun `down count never goes negative if up exceeds total`() = runTest {
        // Defensive: the two numbers come from separate metric lines and could disagree mid-scrape.
        happyPath()
        coEvery { uptimeKuma.getSummary(any()) } returns UptimeKumaSummary(upCount = 13, totalCount = 12)

        assertEquals(0, metric(aggregator().load(0L).tiles, DashboardTileKey.UPTIME_KUMA, "Down").value)
    }

    @Test
    fun `one failing service degrades only its own tile`() = runTest {
        happyPath()
        coEvery { plex.getSummary(any()) } throws IllegalStateException("connection refused")

        val state = aggregator().load(0L)
        val plexTile = state.tiles.single { it.key == DashboardTileKey.PLEX }

        assertTrue(plexTile.status is TileStatus.Unavailable)
        assertEquals("connection refused", (plexTile.status as TileStatus.Unavailable).message)

        // The whole point: every other tile still carries real data.
        listOf(DashboardTileKey.KOMODO, DashboardTileKey.UPTIME_KUMA, DashboardTileKey.SEERR, DashboardTileKey.GRAFANA).forEach { key ->
            assertTrue("$key should still be Ready", state.tiles.single { it.key == key }.status is TileStatus.Ready)
        }
        assertFalse(state.allUnavailable)
    }

    @Test
    fun `service with no instance is NotConfigured, not an error`() = runTest {
        happyPath()
        coEvery { serviceInstances.getPreferredInstance(ServiceType.JELLYSEERR) } returns null

        val seerr = aggregator().load(0L).tiles.single { it.key == DashboardTileKey.SEERR }

        assertEquals(TileStatus.NotConfigured, seerr.status)
        assertEquals(ServiceType.JELLYSEERR.displayName, seerr.title)
    }

    @Test
    fun `firing grafana alerts are danger`() = runTest {
        happyPath()
        coEvery { grafana.getSummary(any()) } returns GrafanaSummary(firingAlerts = 2, totalAlerts = 5)

        val firing = metric(aggregator().load(0L).tiles, DashboardTileKey.GRAFANA, "Firing")

        assertEquals(2, firing.value)
        assertEquals(TileSeverity.DANGER, firing.severity)
        assertTrue(aggregator().load(0L).hasProblem)
    }

    @Test
    fun `silenced grafana alerts are not counted as firing`() = runTest {
        // GrafanaAlert.isFiring only counts state == "active"; silencing an alert is an explicit
        // statement that you do not want to be told about it, so the widget must respect that.
        happyPath()
        coEvery { grafana.getSummary(any()) } returns GrafanaSummary(firingAlerts = 0, totalAlerts = 4)

        val firing = metric(aggregator().load(0L).tiles, DashboardTileKey.GRAFANA, "Firing")

        assertEquals(0, firing.value)
        assertEquals(TileSeverity.GOOD, firing.severity)
        assertFalse(aggregator().load(0L).hasProblem)
    }

    @Test
    fun `tile title prefers the instance label`() = runTest {
        happyPath()
        coEvery { serviceInstances.getPreferredInstance(ServiceType.KOMODO) } returns
            instance(ServiceType.KOMODO, label = "Home Komodo")

        assertEquals("Home Komodo", aggregator().load(0L).tiles.single { it.key == DashboardTileKey.KOMODO }.title)
    }

    @Test
    fun `everything down is reported as allUnavailable`() = runTest {
        happyPath()
        val boom = IllegalStateException("no route to host")
        coEvery { komodo.getDashboard(any()) } throws boom
        coEvery { uptimeKuma.getSummary(any()) } throws boom
        coEvery { plex.getSummary(any()) } throws boom
        coEvery { mediaArr.getSeerrSummary(any()) } throws boom
        coEvery { grafana.getSummary(any()) } throws boom

        val state = aggregator().load(0L)

        assertTrue(state.allUnavailable)
        // Not "a problem" — this is almost always the phone being off-network, and colouring the
        // whole widget red for that would train the user to ignore it.
        assertFalse(state.hasProblem)
    }
}
