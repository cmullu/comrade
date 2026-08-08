package mullu.comrade.together

import mullu.comrade.together.MediaSessionDecisions.Candidate
import mullu.comrade.together.MediaSessionDecisions.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following whatever the device is already playing.
 *
 * These run under plain `kotlinc` + JUnit with no Android SDK (see `CLAUDE.md`),
 * which is the whole reason the decisions live in a file with no framework
 * imports: on the priority frontend it is the difference between a change that
 * was checked and one CI finds out about.
 */
class MediaSessionDecisionsTest {

    private val us = "mullu.comrade"

    private fun candidate(
        pkg: String = "com.spotify.music",
        state: String = "playing",
        canSeek: Boolean = true,
        positionMs: Long = 60_000,
        reportedAtMs: Long = 1_000,
        speed: Float = 1.0f,
        trackKey: String = "kun faya kun|a. r. rahman",
    ) = Candidate(pkg, state, canSeek, positionMs, reportedAtMs, speed, trackKey)

    // ── Which session to follow ─────────────────────────────────────────────

    @Test
    fun weNeverFollowOurOwnSession() {
        // The one bug in here that would be near-impossible to diagnose from a
        // bug report. Comrade publishes a MediaSession for the foreground
        // service that keeps a file session alive, so a picker that did not
        // exclude it would sync Comrade to Comrade and feed every correction
        // back into the player that produced it.
        val ours = candidate(pkg = us)
        assertNull(MediaSessionDecisions.pick(listOf(ours), us))
    }

    @Test
    fun ourOwnSessionDoesNotHideARealOne() {
        val ours = candidate(pkg = us)
        val theirs = candidate(pkg = "com.spotify.music")
        assertEquals(theirs, MediaSessionDecisions.pick(listOf(ours, theirs), us))
    }

    @Test
    fun somethingPlayingBeatsSomethingPaused() {
        val paused = candidate(pkg = "com.podcast.app", state = "paused")
        val playing = candidate(pkg = "com.spotify.music", state = "playing")
        // Order reversed so this cannot pass by accident on list position.
        assertEquals(playing, MediaSessionDecisions.pick(listOf(paused, playing), us))
    }

    @Test
    fun aStallingSessionIsStillTheOneWeAreListeningTo() {
        // Demoting `buffering` would hand the session to a paused app in some
        // other tab the moment the network hiccuped.
        val buffering = candidate(pkg = "com.google.android.youtube", state = "buffering")
        val paused = candidate(pkg = "com.spotify.music", state = "paused")
        assertEquals(buffering, MediaSessionDecisions.pick(listOf(buffering, paused), us))
    }

    @Test
    fun amongEqualsTheSystemsOwnPriorityWins() {
        // The framework puts the session the user last touched first, which is
        // the best available answer to "which of my three music apps did I
        // mean". We must not reorder it.
        val first = candidate(pkg = "com.spotify.music")
        val second = candidate(pkg = "com.google.android.youtube")
        assertEquals(first, MediaSessionDecisions.pick(listOf(first, second), us))
    }

    @Test
    fun nothingPlayingAnywhereIsNoSession() {
        assertNull(MediaSessionDecisions.pick(emptyList(), us))
        val stopped = candidate(state = "stopped")
        assertNull(MediaSessionDecisions.pick(listOf(stopped), us))
    }

    // ── Where its playhead is ───────────────────────────────────────────────

    @Test
    fun aPlayingSessionIsCarriedForwardFromItsLastReport() {
        val c = candidate(positionMs = 60_000, reportedAtMs = 1_000)
        assertEquals(60_000, MediaSessionDecisions.positionAtMs(c, 1_000))
        assertEquals(60_500, MediaSessionDecisions.positionAtMs(c, 1_500))
    }

    @Test
    fun speedIsHonouredBecauseAPodcastAtOnePointFiveDriftsHalfASecondPerSecond() {
        val c = candidate(positionMs = 60_000, reportedAtMs = 1_000, speed = 1.5f)
        assertEquals(61_500, MediaSessionDecisions.positionAtMs(c, 2_000))
        // Extrapolating a 1.5x session at 1.0 would be worse than not
        // extrapolating at all, which is why this is not a rounding detail.
        val naive = 60_000 + (2_000 - 1_000)
        assertTrue(MediaSessionDecisions.positionAtMs(c, 2_000) > naive)
    }

    @Test
    fun aPausedSessionDoesNotAdvance() {
        val c = candidate(state = "paused", positionMs = 60_000, reportedAtMs = 1_000)
        assertEquals(60_000, MediaSessionDecisions.positionAtMs(c, 99_000))
    }

    @Test
    fun aSessionThatStoppedReportingStopsBeingGuessedAt() {
        val c = candidate(positionMs = 60_000, reportedAtMs = 1_000)
        val capped = 60_000 + MediaSessionDecisions.EXTRAPOLATE_MAX_MS
        assertEquals(capped, MediaSessionDecisions.positionAtMs(c, 1_000 + MediaSessionDecisions.EXTRAPOLATE_MAX_MS))
        assertEquals(capped, MediaSessionDecisions.positionAtMs(c, 600_000))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotRewindThePlayhead() {
        val c = candidate(positionMs = 60_000, reportedAtMs = 10_000)
        assertEquals(60_000, MediaSessionDecisions.positionAtMs(c, 9_000))
    }

    @Test
    fun aNegativePositionIsNeverReported() {
        val c = candidate(state = "paused", positionMs = -1)
        assertEquals(0, MediaSessionDecisions.positionAtMs(c, 5_000))
    }

    // ── What we may promise about it ────────────────────────────────────────

    @Test
    fun anAppThatCannotSeekGetsAStartOnlySession() {
        // Running the ladder against it would emit corrections nothing applies,
        // and a screen that says "catching up…" while nothing catches up.
        val noSeek = candidate(canSeek = false)
        assertEquals(Control.START_ONLY, MediaSessionDecisions.control(noSeek))
        assertFalse(MediaSessionDecisions.corrects(Control.START_ONLY))
    }

    @Test
    fun anAppThatCanSeekGetsTheWholeLadder() {
        assertEquals(Control.FULL, MediaSessionDecisions.control(candidate()))
        assertTrue(MediaSessionDecisions.corrects(Control.FULL))
    }

    @Test
    fun noSessionPromisesNothing() {
        assertEquals(Control.NONE, MediaSessionDecisions.control(null))
        assertFalse(MediaSessionDecisions.corrects(Control.NONE))
    }

    @Test
    fun bufferingIsNeverReportedAsAPause() {
        // docs/TOGETHER.md §10, the same rule TogetherDecisions.embedState holds
        // for the YouTube embed. Two sources, one answer.
        assertFalse(MediaSessionDecisions.stateIsWorthSending(candidate(state = "buffering")))
        assertTrue(MediaSessionDecisions.stateIsWorthSending(candidate(state = "playing")))
        assertTrue(MediaSessionDecisions.stateIsWorthSending(candidate(state = "paused")))
    }

    // ── Are we even on the same thing ───────────────────────────────────────

    @Test
    fun aSkipToTheNextTrackEndsTheClaim() {
        // Syncing on regardless drives someone's playhead into a different song
        // and calls it "together".
        assertTrue(MediaSessionDecisions.sameTrack("Kun Faya Kun|A. R. Rahman", "kun faya kun|a. r. rahman"))
        assertFalse(MediaSessionDecisions.sameTrack("Kun Faya Kun|A. R. Rahman", "Nadaan Parindey|A. R. Rahman"))
    }

    @Test
    fun silenceIsNotAgreement() {
        // An app publishing no metadata has told us nothing, and reading that
        // as "same track" is the invention this whole design refuses.
        for (blank in listOf(null, "", "   ")) {
            assertFalse("$blank", MediaSessionDecisions.sameTrack("Kun Faya Kun", blank))
            assertFalse("$blank", MediaSessionDecisions.sameTrack(blank, "Kun Faya Kun"))
        }
        assertFalse(MediaSessionDecisions.sameTrack(null, null))
    }

    @Test
    fun twoDifferentSpeedsAreNotSomethingASeekCanFix() {
        // At 1.5x against 1.0x the gap reopens at half a second per second, so
        // the ladder would chase it forever and the screen would say "catching
        // up…" forever. Better to stop claiming than to claim and fail.
        assertTrue(MediaSessionDecisions.speedsAgree(1.0f, 1.0f))
        assertTrue("float noise must not read as disagreement", MediaSessionDecisions.speedsAgree(1.0f, 1.02f))
        assertFalse(MediaSessionDecisions.speedsAgree(1.0f, 1.5f))
        assertFalse(MediaSessionDecisions.speedsAgree(1.0f, 1.25f))
    }
}
