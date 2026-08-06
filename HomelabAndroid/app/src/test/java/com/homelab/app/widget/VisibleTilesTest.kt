package com.homelab.app.widget

import com.homelab.app.domain.model.DashboardState
import com.homelab.app.domain.model.DashboardTile
import com.homelab.app.domain.model.DashboardTileKey
import com.homelab.app.domain.model.TileMetric
import com.homelab.app.domain.model.TileSeverity
import com.homelab.app.domain.model.TileStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleTilesTest {

    private fun tile(key: DashboardTileKey, status: TileStatus) =
        DashboardTile(key = key, title = key.name, status = status)

    private val ready = TileStatus.Ready(listOf(TileMetric("Down", 0, TileSeverity.GOOD)))

    @Test
    fun `unconfigured services are hidden once anything is configured`() {
        val state = DashboardState(
            tiles = listOf(
                tile(DashboardTileKey.KOMODO, ready),
                tile(DashboardTileKey.NEXTCLOUD, TileStatus.NotConfigured),
                tile(DashboardTileKey.TRANSMISSION, TileStatus.NotConfigured)
            ),
            generatedAtMillis = 0L
        )

        assertEquals(listOf(DashboardTileKey.KOMODO), visibleTiles(state).map { it.key })
    }

    @Test
    fun `unreachable services stay visible`() {
        // Unavailable is not the same as absent: a service you set up and that has stopped
        // answering is exactly what the widget exists to tell you about.
        val state = DashboardState(
            tiles = listOf(
                tile(DashboardTileKey.KOMODO, ready),
                tile(DashboardTileKey.PLEX, TileStatus.Unavailable("connection refused")),
                tile(DashboardTileKey.NEXTCLOUD, TileStatus.NotConfigured)
            ),
            generatedAtMillis = 0L
        )

        assertEquals(
            listOf(DashboardTileKey.KOMODO, DashboardTileKey.PLEX),
            visibleTiles(state).map { it.key }
        )
    }

    @Test
    fun `nothing configured shows the full list instead of an empty widget`() {
        // First run. Showing every service is the most useful thing here — it tells the user what
        // the app can do, and an empty widget would just look broken.
        val state = DashboardState(
            tiles = listOf(
                tile(DashboardTileKey.KOMODO, TileStatus.NotConfigured),
                tile(DashboardTileKey.PLEX, TileStatus.NotConfigured)
            ),
            generatedAtMillis = 0L
        )

        assertEquals(2, visibleTiles(state).size)
    }
}
