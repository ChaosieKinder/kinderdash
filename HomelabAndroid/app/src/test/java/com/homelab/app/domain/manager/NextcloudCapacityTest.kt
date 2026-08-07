package com.homelab.app.domain.manager

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Nextcloud storage bar is derived from a hand-entered capacity, because serverinfo reports
 * free space with no matching total. A wrong capacity produces a confidently wrong bar, so the
 * rejection rules matter more than the arithmetic.
 */
class NextcloudCapacityTest {

    private val aggregator = DashboardAggregator(
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
    )

    @Test
    fun `computes usage from a sane capacity`() {
        assertEquals(75, aggregator.usedPercentOrNull(freeGb = 250, capacityGb = 1000))
        assertEquals(0, aggregator.usedPercentOrNull(freeGb = 1000, capacityGb = 1000))
        assertEquals(100, aggregator.usedPercentOrNull(freeGb = 0, capacityGb = 1000))
    }

    @Test
    fun `unset capacity produces no bar`() {
        assertNull(aggregator.usedPercentOrNull(freeGb = 250, capacityGb = 0))
    }

    @Test
    fun `negative capacity produces no bar`() {
        // Can't be typed through the settings field, which filters to digits — but the preference
        // is an Int and this is the kind of thing that arrives via a restored backup.
        assertNull(aggregator.usedPercentOrNull(freeGb = 250, capacityGb = -500))
    }

    @Test
    fun `capacity smaller than free space is rejected as impossible`() {
        // The main misconfiguration this can actually catch: someone types 100 meaning 100 TB, or
        // the array grew since. A disk cannot have more free space than it has space.
        assertNull(aggregator.usedPercentOrNull(freeGb = 500, capacityGb = 100))
    }

    @Test
    fun `negative free space is rejected`() {
        assertNull(aggregator.usedPercentOrNull(freeGb = -1, capacityGb = 1000))
    }

    @Test
    fun `a capacity that is too large cannot be detected`() {
        // Documenting the known hole rather than pretending it is handled. 10x the real capacity
        // is arithmetically indistinguishable from a nearly-full disk, and reads as 99% used.
        // Nothing in the data can tell these apart; only an accurate value can.
        assertEquals(99, aggregator.usedPercentOrNull(freeGb = 100, capacityGb = 10_000))
    }
}
