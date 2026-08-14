package mullu.comrade.ride

import mullu.comrade.ride.RideDecisions.Role
import mullu.comrade.ride.RideDecisions.Sig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the ride glance rules — no Android imports, so the
 * sandbox `kotlinc` lane and `./gradlew test` both pin them. The wire-name
 * tables here (`slow_down`, `left`, …) are the serde names asserted by
 * `comrade_core::ride`'s `the_wire_names_and_the_string_names_are_the_same_names`,
 * so a rename on either side is a red test, not a blank card in a helmet.
 */
class RideDecisionsTest {

    private fun quick(phrase: String, atMs: Long, urgency: String = "urgent") = Sig(
        kind = "quick", phrase = phrase, maneuver = null, distanceM = null,
        note = null, urgency = urgency, peerLabel = "Ana", atMs = atMs,
    )

    private fun route(
        maneuver: String,
        distanceM: Long?,
        atMs: Long,
        note: String? = null,
    ) = Sig(
        kind = "route", phrase = null, maneuver = maneuver, distanceM = distanceM,
        note = note, urgency = "notice", peerLabel = "Ana", atMs = atMs,
    )

    // ── One card ─────────────────────────────────────────────────────────────

    @Test
    fun aFreshQuickPhraseOwnsTheCardOverAnOlderRoute() {
        val card = RideDecisions.card(
            nowMs = 10_000,
            quick = quick("pull_over", atMs = 9_000),
            route = route("left", 400, atMs = 5_000),
        )!!
        assertEquals("Pull over", card.headline)
        assertEquals("Ana", card.detail)
        assertNull("a phrase has no arrow", card.glyph)
        assertEquals("urgent", card.urgency)
    }

    @Test
    fun theRouteComesBackOnceTheInterruptionHasHadItsEightSeconds() {
        val card = RideDecisions.card(
            nowMs = 9_000 + RideDecisions.QUICK_SHOW_MS,
            quick = quick("all_good", atMs = 9_000, urgency = "info"),
            route = route("left", 400, atMs = 5_000, note = "after the petrol pump"),
        )!!
        assertEquals("Left in 400 m", card.headline)
        assertEquals("after the petrol pump", card.detail)
        assertEquals("←", card.glyph)
    }

    @Test
    fun aStaleRouteIsGoneNotDimmed() {
        // "left in 400 m" from three minutes ago is not old news, it is false.
        assertNull(
            RideDecisions.card(
                nowMs = 200_000,
                quick = null,
                route = route("left", 400, atMs = 200_000 - RideDecisions.CARD_STALE_MS),
            ),
        )
        // One millisecond inside the window still shows.
        val live = RideDecisions.card(
            nowMs = 200_000,
            quick = null,
            route = route("left", 400, atMs = 200_000 - RideDecisions.CARD_STALE_MS + 1),
        )
        assertEquals("Left in 400 m", live!!.headline)
    }

    @Test
    fun anUnknownWireNameIsSkippedNeverBlankCarded() {
        // Release skew: a newer build's phrase must not paint an empty card —
        // and must not mask a perfectly good route suggestion behind it.
        val card = RideDecisions.card(
            nowMs = 10_000,
            quick = quick("warp_speed", atMs = 10_000),
            route = route("right", 1_200, atMs = 9_000),
        )!!
        assertEquals("Right in 1.2 km", card.headline)
        assertNull(
            RideDecisions.card(
                nowMs = 10_000,
                quick = quick("warp_speed", atMs = 10_000),
                route = route("ascend", null, atMs = 10_000),
            ),
        )
    }

    @Test
    fun noSignalsMeansNoCard() {
        assertNull(RideDecisions.card(nowMs = 0, quick = null, route = null))
    }

    // ── Distance words ───────────────────────────────────────────────────────

    @Test
    fun distancesReadAsTheShortestTruth() {
        assertEquals("100 m", RideDecisions.distanceText(100))
        assertEquals("999 m", RideDecisions.distanceText(999))
        assertEquals("1 km", RideDecisions.distanceText(1_000))
        assertEquals("1.2 km", RideDecisions.distanceText(1_200))
        // Rounds to the nearest tenth rather than truncating.
        assertEquals("1.3 km", RideDecisions.distanceText(1_250))
        assertEquals("9.9 km", RideDecisions.distanceText(9_949))
        // Past ten kilometres a decimal stops meaning anything.
        assertEquals("10 km", RideDecisions.distanceText(10_000))
        assertEquals("50 km", RideDecisions.distanceText(50_000))
    }

    @Test
    fun aRouteWithNoDistanceIsJustTheManeuver() {
        assertEquals("Left", RideDecisions.routeHeadline("left", null))
        assertEquals("U-turn in 400 m", RideDecisions.routeHeadline("u_turn", 400))
        assertNull(RideDecisions.routeHeadline("ascend", 400))
    }

    // ── Interrupting a helmet ────────────────────────────────────────────────

    @Test
    fun onlyTheDriverHearsAndInfoStaysSilent() {
        val urgent = quick("pull_over", atMs = 0)
        assertEquals("Pull over", RideDecisions.spokenLine(Role.Driver, urgent))
        assertNull("the pillion is already looking", RideDecisions.spokenLine(Role.Pillion, urgent))
        assertNull(
            "an answer is not an alert",
            RideDecisions.spokenLine(Role.Driver, quick("all_good", atMs = 0, urgency = "info")),
        )
    }

    @Test
    fun aSpokenRouteCarriesItsLandmark() {
        assertEquals(
            "Left in 400 m, after the petrol pump",
            RideDecisions.spokenLine(
                Role.Driver,
                route("left", 400, atMs = 0, note = "after the petrol pump"),
            ),
        )
        assertEquals(
            "Stop",
            RideDecisions.spokenLine(Role.Driver, route("stop", null, atMs = 0)),
        )
    }

    @Test
    fun hapticsFollowTheWireVerdictNotTheLayout() {
        assertArrayEquals(longArrayOf(0, 400, 150, 400), RideDecisions.hapticPattern("urgent"))
        assertArrayEquals(longArrayOf(0, 120), RideDecisions.hapticPattern("notice"))
        assertNull(RideDecisions.hapticPattern("info"))
        // A verdict this build does not know buzzes like nothing rather than
        // like an emergency.
        assertNull(RideDecisions.hapticPattern("cataclysmic"))
    }

    // ── Music at speed ───────────────────────────────────────────────────────

    @Test
    fun theDriverGetsTwoVerbsAndNoScrubber() {
        val driver = RideDecisions.controlsFor(Role.Driver)
        assertTrue(driver.playPause)
        assertTrue(driver.next)
        assertTrue("a scrubber under a driver's thumb is a swerve", !driver.scrubber)
        assertTrue(!driver.previous)
        val pillion = RideDecisions.controlsFor(Role.Pillion)
        assertTrue(pillion.playPause && pillion.next && pillion.previous && pillion.scrubber)
    }

    // ── The composer's tables ────────────────────────────────────────────────

    @Test
    fun everyOfferedPhraseAndManeuverRendersAndNothingRendersThatIsNotOffered() {
        // The grid must never draw a button it cannot label, and the tables
        // must not fall behind the render functions when the catalog grows.
        RideDecisions.PHRASES.forEach { p ->
            assertTrue("phrase $p must render", RideDecisions.phraseText(p) != null)
        }
        RideDecisions.MANEUVERS.forEach { m ->
            assertTrue("maneuver $m must render", RideDecisions.maneuverText(m) != null)
            assertTrue("maneuver $m must have a glyph", RideDecisions.maneuverGlyph(m) != null)
        }
        assertEquals(5, RideDecisions.PHRASES.size)
        assertEquals(6, RideDecisions.MANEUVERS.size)
    }

    @Test
    fun everyOfferedDistanceSurvivesTheWireCap() {
        // `RIDE_DISTANCE_MAX_M` in core is 50 km; a chip the runtime would
        // refuse is a button that fails when pressed.
        RideDecisions.DISTANCES_M.forEach { d -> assertTrue(d in 1..50_000) }
    }
}
