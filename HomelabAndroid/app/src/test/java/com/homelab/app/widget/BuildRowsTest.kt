package com.homelab.app.widget

import com.homelab.app.domain.model.CalendarDay
import com.homelab.app.domain.model.DashboardTile
import com.homelab.app.domain.model.DashboardTileKey
import com.homelab.app.domain.model.TileMetric
import com.homelab.app.domain.model.TileSeverity
import com.homelab.app.domain.model.TileStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildRowsTest {

    private fun metric(key: DashboardTileKey) = DashboardTile(
        key = key,
        title = key.name,
        status = TileStatus.Ready(listOf(TileMetric("Down", 0, TileSeverity.GOOD)))
    )

    private val calendar = DashboardTile(
        key = DashboardTileKey.CALENDAR,
        title = "Sonarr",
        status = TileStatus.Calendar(listOf(CalendarDay("Today", 2, 1, isToday = true)))
    )

    @Test
    fun `single column puts every tile on its own row`() {
        val tiles = listOf(metric(DashboardTileKey.KOMODO), calendar, metric(DashboardTileKey.PLEX))

        assertEquals(3, buildRows(tiles, twoColumn = false).size)
    }

    @Test
    fun `metric tiles pair up`() {
        val tiles = listOf(
            metric(DashboardTileKey.KOMODO),
            metric(DashboardTileKey.PLEX),
            metric(DashboardTileKey.SEERR),
            metric(DashboardTileKey.GRAFANA)
        )

        val rows = buildRows(tiles, twoColumn = true)

        assertEquals(2, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals(2, rows[1].size)
    }

    @Test
    fun `calendar always gets a row to itself`() {
        val tiles = listOf(metric(DashboardTileKey.KOMODO), calendar, metric(DashboardTileKey.PLEX))

        val rows = buildRows(tiles, twoColumn = true)

        // Komodo can't pair with the calendar, so it flushes alone; the calendar takes its own row.
        assertEquals(listOf(1, 1, 1), rows.map { it.size })
        assertEquals(DashboardTileKey.CALENDAR, rows[1].single().key)
    }

    @Test
    fun `pairing resumes after the calendar`() {
        val tiles = listOf(
            calendar,
            metric(DashboardTileKey.KOMODO),
            metric(DashboardTileKey.PLEX),
            metric(DashboardTileKey.SEERR)
        )

        val rows = buildRows(tiles, twoColumn = true)

        assertEquals(listOf(1, 2, 1), rows.map { it.size })
        assertEquals(DashboardTileKey.CALENDAR, rows[0].single().key)
    }

    @Test
    fun `no tile is ever dropped`() {
        // The pending/flush logic is exactly where a tile could go missing, and a vanished tile in
        // a widget is silent — no error, just an absent service.
        val tiles = listOf(
            metric(DashboardTileKey.KOMODO),
            calendar,
            metric(DashboardTileKey.PLEX),
            metric(DashboardTileKey.SEERR),
            metric(DashboardTileKey.NEXTCLOUD)
        )

        val flattened = buildRows(tiles, twoColumn = true).flatten()

        assertEquals(tiles.size, flattened.size)
        assertEquals(tiles.map { it.key }, flattened.map { it.key })
    }
}
