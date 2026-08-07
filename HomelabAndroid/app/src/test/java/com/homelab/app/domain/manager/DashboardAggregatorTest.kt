package com.homelab.app.domain.manager

import com.homelab.app.data.repository.GrafanaRepository
import com.homelab.app.data.repository.HomeAssistantRepository
import com.homelab.app.data.repository.HomeAssistantSummary
import com.homelab.app.data.repository.NextcloudRepository
import com.homelab.app.data.repository.NextcloudSummary
import com.homelab.app.data.repository.TransmissionRepository
import com.homelab.app.data.repository.TransmissionSummary
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
    private val homeAssistant: HomeAssistantRepository = mockk()
    private val nextcloud: NextcloudRepository = mockk()
    private val transmission: TransmissionRepository = mockk()
    private val localPreferences: com.homelab.app.data.repository.LocalPreferencesRepository = mockk()

    private fun aggregator() = DashboardAggregator(serviceInstances, komodo, uptimeKuma, plex, mediaArr, grafana,
        homeAssistant, nextcloud, transmission, localPreferences)

    private fun instance(type: ServiceType, label: String = "") = ServiceInstance(
        id = "${type.name.lowercase()}-1",
        type = type,
        label = label,
        url = "https://example.invalid"
    )

    /** Everything configured and healthy unless a test overrides it. */
    private fun happyPath() {
        io.mockk.every { localPreferences.nextcloudCapacityGb } returns kotlinx.coroutines.flow.flowOf(0)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.KOMODO) } returns instance(ServiceType.KOMODO)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.UPTIME_KUMA) } returns instance(ServiceType.UPTIME_KUMA)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.PLEX) } returns instance(ServiceType.PLEX)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.JELLYSEERR) } returns instance(ServiceType.JELLYSEERR)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.GRAFANA) } returns instance(ServiceType.GRAFANA)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.HOME_ASSISTANT) } returns instance(ServiceType.HOME_ASSISTANT)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.NEXTCLOUD) } returns instance(ServiceType.NEXTCLOUD)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.TRANSMISSION) } returns instance(ServiceType.TRANSMISSION)
        coEvery { serviceInstances.getPreferredInstance(ServiceType.SONARR) } returns instance(ServiceType.SONARR)

        coEvery { komodo.getDashboard(any()) } returns komodoDashboard(stopped = 0, unhealthy = 0)
        coEvery { uptimeKuma.getSummary(any()) } returns UptimeKumaSummary(upCount = 12, totalCount = 12)
        coEvery { plex.getSummary(any()) } returns PlexSummary(0, 0, 0)
        coEvery { mediaArr.getSeerrSummary(any()) } returns SeerrSummary(pendingRequests = 0, totalRequests = 40)
        coEvery { grafana.getSummary(any()) } returns GrafanaSummary(firingAlerts = 0, totalAlerts = 3)
        coEvery { homeAssistant.getSummary(any()) } returns HomeAssistantSummary(lightsOn = 2, unavailableEntities = 0, totalEntities = 300)
        coEvery { nextcloud.getSummary(any()) } returns NextcloudSummary(freeSpaceBytes = 500L * 1_073_741_824L, activeUsers24h = 1, numFiles = 90_000,
            memTotalKb = 16_000_000L, memFreeKb = 8_000_000L)
        coEvery { transmission.getSummary(any()) } returns TransmissionSummary(activeTorrents = 3, erroredTorrents = 0, totalTorrents = 10)
        coEvery { mediaArr.getCalendar(any(), any(), any()) } returns emptyList()
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

        assertEquals(6, state.tiles.size)
        assertEquals(1_000L, state.generatedAtMillis)
        assertFalse(state.hasProblem)
        assertFalse(state.allUnavailable)
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
        listOf(DashboardTileKey.UPTIME_KUMA, DashboardTileKey.HOME_ASSISTANT,
            DashboardTileKey.NEXTCLOUD, DashboardTileKey.TRANSMISSION).forEach { key ->
            assertTrue("$key should still be Ready", state.tiles.single { it.key == key }.status is TileStatus.Ready)
        }
        assertFalse(state.allUnavailable)
    }

    @Test
    fun `service with no instance is NotConfigured, not an error`() = runTest {
        happyPath()
        coEvery { serviceInstances.getPreferredInstance(ServiceType.PLEX) } returns null

        val plex = aggregator().load(0L).tiles.single { it.key == DashboardTileKey.PLEX }

        assertEquals(TileStatus.NotConfigured, plex.status)
        assertEquals(ServiceType.PLEX.displayName, plex.title)
    }

    @Test
    fun `errored torrents are danger, active ones are neutral`() = runTest {
        happyPath()
        coEvery { transmission.getSummary(any()) } returns
            TransmissionSummary(activeTorrents = 4, erroredTorrents = 1, totalTorrents = 12)

        val tiles = aggregator().load(0L).tiles

        assertEquals(TileSeverity.NEUTRAL, metric(tiles, DashboardTileKey.TRANSMISSION, "Active").severity)
        assertEquals(TileSeverity.DANGER, metric(tiles, DashboardTileKey.TRANSMISSION, "Errors").severity)
    }

    @Test
    fun `nextcloud omits the memory bar when totals are not reported`() = runTest {
        // memTotalKb = 0 means the instance reported nothing to divide by; a bar would be invented.
        happyPath()
        coEvery { nextcloud.getSummary(any()) } returns
            NextcloudSummary(freeSpaceBytes = 1L, activeUsers24h = 0, numFiles = 1, memTotalKb = 0L, memFreeKb = 0L)

        val metrics = (aggregator().load(0L).tiles.single { it.key == DashboardTileKey.NEXTCLOUD }
            .status as TileStatus.Ready).metrics

        assertEquals(emptyList<String>(), metrics.filter { it.label == "Memory" }.map { it.label })
        assertEquals(null, metrics.single { it.label == "Free" }.percent)
    }

    @Test
    fun `nextcloud free space is reported in whole GB`() = runTest {
        happyPath()
        coEvery { nextcloud.getSummary(any()) } returns
            NextcloudSummary(freeSpaceBytes = 3L * 1_073_741_824L, activeUsers24h = 0, numFiles = 1,
                memTotalKb = 0L, memFreeKb = 0L)

        assertEquals(3, metric(aggregator().load(0L).tiles, DashboardTileKey.NEXTCLOUD, "Free").value)
    }

    @Test
    fun `unavailable home assistant entities warn but do not alarm`() = runTest {
        // A sleeping device or a rebooting hub is routine — real, but not worth colouring red.
        happyPath()
        coEvery { homeAssistant.getSummary(any()) } returns
            HomeAssistantSummary(lightsOn = 0, unavailableEntities = 5, totalEntities = 300)

        val state = aggregator().load(0L)

        assertEquals(TileSeverity.WARNING, metric(state.tiles, DashboardTileKey.HOME_ASSISTANT, "Unavailable").severity)
        assertFalse(state.hasProblem)
    }

    @Test
    fun `tile title prefers the instance label`() = runTest {
        happyPath()
        coEvery { serviceInstances.getPreferredInstance(ServiceType.PLEX) } returns
            instance(ServiceType.PLEX, label = "Basement Plex")

        assertEquals("Basement Plex", aggregator().load(0L).tiles.single { it.key == DashboardTileKey.PLEX }.title)
    }

    @Test
    fun `everything down is reported as allUnavailable`() = runTest {
        happyPath()
        val boom = IllegalStateException("no route to host")
        coEvery { uptimeKuma.getSummary(any()) } throws boom
        coEvery { plex.getSummary(any()) } throws boom
        coEvery { mediaArr.getSeerrSummary(any()) } throws boom
        coEvery { grafana.getSummary(any()) } throws boom
        coEvery { homeAssistant.getSummary(any()) } throws boom
        coEvery { nextcloud.getSummary(any()) } throws boom
        coEvery { transmission.getSummary(any()) } throws boom
        coEvery { mediaArr.getCalendar(any(), any(), any()) } throws boom

        val state = aggregator().load(0L)

        assertTrue(state.allUnavailable)
        // Not "a problem" — this is almost always the phone being off-network, and colouring the
        // whole widget red for that would train the user to ignore it.
        assertFalse(state.hasProblem)
    }
}
