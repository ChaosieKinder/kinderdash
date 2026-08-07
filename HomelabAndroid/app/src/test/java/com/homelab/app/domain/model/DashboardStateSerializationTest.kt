package com.homelab.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DashboardState is persisted as JSON between the refresh worker and the widget, so a broken
 * serializer means the widget silently keeps rendering the previous snapshot forever —
 * DashboardSnapshotStore.save() catches its own failure, so the worker still reports success and
 * nothing anywhere says a word.
 *
 * That happened. These tests exist so it can't happen quietly again.
 */
class DashboardStateSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private fun roundTrip(state: DashboardState): DashboardState =
        json.decodeFromString(DashboardState.serializer(), json.encodeToString(DashboardState.serializer(), state))

    @Test
    fun `metric tiles survive a round trip`() {
        val state = DashboardState(
            tiles = listOf(
                DashboardTile(
                    key = DashboardTileKey.KOMODO,
                    title = "Komodo",
                    status = TileStatus.Ready(listOf(TileMetric("Unhealthy", 2, TileSeverity.DANGER)))
                )
            ),
            generatedAtMillis = 1_000L
        )

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `calendar tiles survive a round trip`() {
        val state = DashboardState(
            tiles = listOf(
                DashboardTile(
                    key = DashboardTileKey.CALENDAR,
                    title = "Sonarr",
                    status = TileStatus.Calendar(
                        listOf(
                            CalendarDay(
                                label = "Thu",
                                total = 3,
                                downloaded = 2,
                                isToday = true,
                                entries = listOf(CalendarEntry("Foundation", 3, 2))
                            )
                        )
                    )
                )
            ),
            generatedAtMillis = 2_000L
        )

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `every tile status variant survives a round trip`() {
        // The sealed hierarchy is the fragile part: adding an intermediate interface, or a variant
        // that isn't annotated, breaks encoding for the WHOLE state, not just that one tile.
        val state = DashboardState(
            tiles = listOf(
                DashboardTile(DashboardTileKey.PLEX, "Plex", TileStatus.Ready(emptyList())),
                DashboardTile(DashboardTileKey.SEERR, "Seerr", TileStatus.NotConfigured),
                DashboardTile(DashboardTileKey.GRAFANA, "Grafana", TileStatus.Unavailable("timed out")),
                DashboardTile(DashboardTileKey.CALENDAR, "Sonarr", TileStatus.Calendar(emptyList()))
            ),
            generatedAtMillis = 3_000L
        )

        assertEquals(state, roundTrip(state))
    }
}
