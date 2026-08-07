package com.homelab.app.domain.manager

import com.homelab.app.data.repository.CalendarEpisode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Covers the timezone handling in the calendar tile, which is the part most likely to be quietly
 * wrong: Sonarr reports `airDateUtc`, but the widget shows days in the viewer's local time.
 */
class CalendarBucketingTest {

    private val aggregator = DashboardAggregator(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
    )

    /** US Eastern: far enough behind UTC that late-evening broadcasts land on the next UTC day. */
    private val chicago: ZoneId = ZoneId.of("America/Chicago")
    private val today: LocalDate = LocalDate.of(2026, 8, 6)

    private fun episodeAt(local: ZonedDateTime, hasFile: Boolean = false) = CalendarEpisode(
        airsAtMillis = local.toInstant().toEpochMilli(),
        hasFile = hasFile,
        seriesTitle = "Some Series",
        episodeTitle = "Some Episode"
    )

    @Test
    fun `produces seven days, yesterday through five ahead`() {
        val days = aggregator.bucketCalendar(emptyList(), chicago, today)

        assertEquals(7, days.size)
        assertEquals("Yest", days.first().label)
        assertEquals("Today", days[1].label)
        assertTrue(days[1].isToday)
        assertEquals(1, days.count { it.isToday })
    }

    @Test
    fun `a late evening broadcast stays on its local day`() {
        // 9pm Chicago on the 6th is 02:00 UTC on the 7th. Bucketing on the UTC date would put this
        // on tomorrow's column — the single most likely bug in this feature.
        val ninePmLocal = ZonedDateTime.of(2026, 8, 6, 21, 0, 0, 0, chicago)

        val days = aggregator.bucketCalendar(listOf(episodeAt(ninePmLocal)), chicago, today)

        assertEquals(1, days.single { it.isToday }.total)
        assertEquals(0, days.filterNot { it.isToday }.sumOf { it.total })
    }

    @Test
    fun `counts downloaded separately from total`() {
        val tonight = ZonedDateTime.of(2026, 8, 6, 20, 0, 0, 0, chicago)
        val episodes = listOf(
            episodeAt(tonight, hasFile = true),
            episodeAt(tonight, hasFile = false),
            episodeAt(tonight, hasFile = true)
        )

        val todayColumn = aggregator.bucketCalendar(episodes, chicago, today).single { it.isToday }

        assertEquals(3, todayColumn.total)
        assertEquals(2, todayColumn.downloaded)
    }

    @Test
    fun `yesterday is included so last night's episode is still visible`() {
        val lastNight = ZonedDateTime.of(2026, 8, 5, 22, 0, 0, 0, chicago)

        val days = aggregator.bucketCalendar(listOf(episodeAt(lastNight, hasFile = true)), chicago, today)

        assertEquals(1, days.first().total)
        assertEquals(1, days.first().downloaded)
    }

    @Test
    fun `episodes outside the window are dropped, not folded into the edges`() {
        // The fetch deliberately over-fetches by a day at each end to survive boundary effects;
        // those extras must not pile up on the first and last columns.
        val tooEarly = ZonedDateTime.of(2026, 8, 3, 20, 0, 0, 0, chicago)
        val tooLate = ZonedDateTime.of(2026, 8, 20, 20, 0, 0, 0, chicago)

        val days = aggregator.bucketCalendar(listOf(episodeAt(tooEarly), episodeAt(tooLate)), chicago, today)

        assertEquals(0, days.sumOf { it.total })
    }
}
