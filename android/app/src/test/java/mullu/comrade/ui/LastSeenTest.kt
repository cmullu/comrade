package mullu.comrade.ui

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "last seen" vocabulary — the Telegram shape: relative while it is
 * fresh, a wall clock once it isn't, a date beyond that.
 *
 * Everything is pinned against a fixed zone/locale/clock so these pass in any
 * CI timezone. Time fragments are compared through [norm] because the exact
 * AM/PM casing and the space before it are CLDR details that have changed
 * between JDK versions (CLDR 42 switched en-US to a narrow no-break space) —
 * those are not what this test is about.
 */
class LastSeenTest {

    private val utc = ZoneId.of("UTC")
    private val us = Locale.US

    /** 2026-08-01 12:00:00Z — a Saturday, so the weekday cases are unambiguous. */
    private val now = 1785585600L

    private fun at(secondsAgo: Long, use24Hour: Boolean = false) =
        lastSeenOf(now - secondsAgo, now, use24Hour, utc, us)

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)

    @Test
    fun withinTheMinuteIsJustNow() {
        assertEquals(LastSeen.JustNow, at(0))
        assertEquals(LastSeen.JustNow, at(59))
    }

    @Test
    fun aFutureTimestampReadsAsJustNowRatherThanNegative() {
        // Two phones' clocks disagreeing by seconds is ordinary; "last seen
        // -1 minutes ago" is not something a user should ever see.
        assertEquals(LastSeen.JustNow, lastSeenOf(now + 30, now, false, utc, us))
    }

    @Test
    fun withinTheHourCountsMinutes() {
        assertEquals(LastSeen.MinutesAgo(1), at(60))
        assertEquals(LastSeen.MinutesAgo(5), at(5 * 60))
        assertEquals(LastSeen.MinutesAgo(59), at(59 * 60 + 59))
    }

    @Test
    fun earlierTodayIsAWallClock() {
        // 12:00Z minus 5h30m = 06:30 — still today in UTC.
        val seen = at(5 * 3600 + 30 * 60)
        assertTrue("expected a same-day time, got $seen", seen is LastSeen.TodayAt)
        assertEquals("6:30 AM", norm((seen as LastSeen.TodayAt).time))
    }

    @Test
    fun theDeviceClockSettingDecidesTwelveOrTwentyFourHour() {
        val seen = at(5 * 3600 + 30 * 60, use24Hour = true)
        assertEquals(LastSeen.TodayAt("06:30"), seen)
    }

    @Test
    fun yesterdayIsNamedRatherThanCounted() {
        // 18:30 the previous calendar day (2026-07-31).
        val seen = at(17 * 3600 + 30 * 60)
        assertTrue("expected yesterday, got $seen", seen is LastSeen.YesterdayAt)
        assertEquals("6:30 PM", norm((seen as LastSeen.YesterdayAt).time))
    }

    @Test
    fun earlierThisWeekUsesTheWeekday() {
        // Three days back from Saturday is Wednesday.
        val seen = at(3 * 24 * 3600)
        assertTrue("expected a weekday, got $seen", seen is LastSeen.WeekdayAt)
        assertEquals("Wednesday", (seen as LastSeen.WeekdayAt).weekday)
        assertEquals("12:00 PM", norm(seen.time))
    }

    @Test
    fun beyondAWeekTheWeekdayStopsBeingUnambiguousSoADateIsUsed() {
        // Exactly 7 days back is already "which Saturday?" territory.
        assertEquals(LastSeen.OnDate("25 Jul"), at(7 * 24 * 3600))
    }

    @Test
    fun anEarlierYearCarriesTheYear() {
        assertEquals(LastSeen.OnDate("27 Jun 2025"), at(400L * 24 * 3600))
    }

    @Test
    fun presenceLabelPrecedenceDecidesWhetherWeEvenAsk() {
        // lastSeenOf is only reached through PresenceLabel.LastSeen — a peer
        // with no timestamp never gets a fabricated one.
        assertEquals(
            PresenceLabel.LastSeen,
            presenceLabelOf(online = false, lastSeenAt = now - 60, peerMarkedUs = true),
        )
        assertEquals(
            PresenceLabel.NeverSeen,
            presenceLabelOf(online = false, lastSeenAt = 0L, peerMarkedUs = true),
        )
    }
}
