package dev.shutchat.app.domain.expiry

import dev.shutchat.app.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Expiry is enforced locally by every device, so the arithmetic has to agree
 * across devices and timezones. These tests pin the boundary: a code is alive up
 * to the last millisecond of its day and dead from the first millisecond of the
 * next, in UTC, whatever the phone's locale.
 */
class ExpiryTest {

    private fun clockAt(millis: Long) = Clock { millis }

    @Test
    fun `a permanent code never expires`() {
        val clock = clockAt(4_000_000_000_000L)
        assertFalse(Expiry.isExpired(null, clock))
        assertNull(Expiry.expiresAtMillis(null))
    }

    @Test
    fun `the deadline is the end of the stored day in utc`() {
        val day = 500
        val deadline = Expiry.expiresAtMillis(day)!!
        assertEquals((day + Expiry.ORIGIN_DAY + 1).toLong() * TimeUnit.DAYS.toMillis(1), deadline)
    }

    @Test
    fun `a code is alive one millisecond before the deadline and dead after`() {
        val day = 800
        val deadline = Expiry.expiresAtMillis(day)!!

        assertFalse(Expiry.isExpired(day, clockAt(deadline - 1)))
        assertTrue(Expiry.isExpired(day, clockAt(deadline)))
    }

    @Test
    fun `day conversion is the inverse of the deadline calculation`() {
        val expiry = 1_800_000_000_000L
        val day = Expiry.dayFromExpiryMillis(expiry)
        assertEquals(Expiry.dayOf(expiry), day + Expiry.ORIGIN_DAY)
    }

    @Test
    fun `a preset always lands in the future`() {
        val now = 1_750_000_000_000L
        val clock = clockAt(now)
        Expiry.Preset.entries.filter { it.days != null }.forEach { preset ->
            val day = Expiry.presetExpiryDay(preset, clock)!!
            assertFalse("il preset ${preset.label} è già scaduto", Expiry.isExpired(day, clock))
        }
    }

    @Test
    fun `never preset maps to no expiry day`() {
        assertNull(Expiry.presetExpiryDay(Expiry.Preset.NEVER, clockAt(0)))
    }

    @Test
    fun `describeDeadline labels a permanent code as infinite`() {
        assertEquals("Mai (∞)", Expiry.describeDeadline(null, clockAt(0)))
    }
}
