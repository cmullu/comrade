package mullu.comrade.transfer

import mullu.comrade.transfer.ShareReadPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The starve policy, against `comrade_core::share`'s own vectors.
 *
 * Ported name for name rather than paraphrased, the same arrangement
 * `ShareDecisionsTest` uses for the framing: this is policy the two sides must
 * agree on, and a divergence is a red test here rather than a stutter in the
 * field. `docs/TOGETHER.md` §12.
 *
 * **This file compiles and runs under bare `kotlinc` + JUnit**, with no Android
 * SDK and no Gradle — see `CLAUDE.md`. Its subject imports nothing at all, which
 * is what makes that possible, and is the point of keeping the thresholds out of
 * [ShareDecisions] (which needs the generated uniffi types).
 */
class ShareReadPolicyTest {

    private fun verdict(playing: Boolean, runwayMs: Long, tailComplete: Boolean = false) =
        ShareReadPolicy.verdict(playing, runwayMs, tailComplete)

    /** The whole point of two thresholds: too little to start on is not too
     *  little to carry on with. */
    @Test
    fun aReaderStartsOnAFullRunwayAndCarriesOnOverAShorterOne() {
        assertEquals(
            "two seconds is not enough to start on",
            Verdict.HOLD,
            verdict(playing = false, runwayMs = 2_000),
        )
        assertEquals(
            "but a player already running rides those two seconds out",
            Verdict.CONTINUE,
            verdict(playing = true, runwayMs = 2_000),
        )
        assertEquals(Verdict.START, verdict(playing = false, runwayMs = 5_000))
        assertEquals(Verdict.CONTINUE, verdict(playing = true, runwayMs = 5_000))
    }

    /** The case §10 is about: the bytes ran out under a playing reader. */
    @Test
    fun aStarvedReaderHoldsRatherThanStutteringIntoAGap() {
        // The chunk under the playhead is not here at all, so there is nothing
        // to decode however much lies beyond it.
        assertEquals(Verdict.HOLD, verdict(playing = true, runwayMs = 0))
        assertEquals(Verdict.HOLD, verdict(playing = false, runwayMs = 0))
    }

    /** Without this the reader chatters: hold, one chunk lands, start, stop. */
    @Test
    fun aReaderThatHeldNeedsTheFullRunwayAgainNotAScrap() {
        assertEquals(Verdict.HOLD, verdict(playing = false, runwayMs = 1_000))
        assertEquals(
            "three seconds would start a player that stops again immediately",
            Verdict.HOLD,
            verdict(playing = false, runwayMs = 3_000),
        )
        assertEquals(Verdict.START, verdict(playing = false, runwayMs = 5_000))
    }

    @Test
    fun theLastSecondsOfAFilePlayEvenThoughTheRunwayIsShort() {
        assertEquals(
            "nothing more is coming; holding here would hold forever",
            Verdict.START,
            verdict(playing = false, runwayMs = 2_000, tailComplete = true),
        )
        assertEquals(Verdict.CONTINUE, verdict(playing = true, runwayMs = 2_000, tailComplete = true))
        // Even with nothing at all left to read: the file simply ended.
        assertEquals(Verdict.START, verdict(playing = false, runwayMs = 0, tailComplete = true))
    }

    @Test
    fun theBoundariesAreInclusiveOnBothThresholds() {
        // `>=`, not `>`, on both — stated as a test because an off-by-one here
        // is invisible in review and shows up as a player that will not start on
        // exactly five seconds of runway.
        assertEquals(Verdict.START, verdict(false, ShareReadPolicy.PLAYABLE_RUNWAY_MS))
        assertEquals(Verdict.HOLD, verdict(false, ShareReadPolicy.PLAYABLE_RUNWAY_MS - 1))
        assertEquals(Verdict.CONTINUE, verdict(true, ShareReadPolicy.STALL_FLOOR_MS))
        assertEquals(Verdict.HOLD, verdict(true, ShareReadPolicy.STALL_FLOOR_MS - 1))
    }

    @Test
    fun theFloorIsBelowTheStartThresholdOrTheHysteresisIsNotOne() {
        // Core asserts this at compile time (`const _: () = assert!(..)`); this
        // is the same guard on this side. Equal numbers collapse the two
        // thresholds into one and the reader chatters.
        assertTrue(ShareReadPolicy.STALL_FLOOR_MS > 0)
        assertTrue(ShareReadPolicy.STALL_FLOOR_MS * 2 < ShareReadPolicy.PLAYABLE_RUNWAY_MS)
    }

    @Test
    fun thereIsNoArmThatMeansTellThePeer() {
        // §10 kept by construction rather than by discipline. If this set ever
        // grows a fourth member, someone has proposed a wire signal for a local
        // stall — which is the argument the enum exists to force.
        val seen = mutableSetOf<Verdict>()
        for (playing in listOf(false, true)) {
            for (tail in listOf(false, true)) {
                for (runway in listOf(0L, 500L, 1_000L, 4_999L, 5_000L, 60_000L)) {
                    seen += verdict(playing, runway, tail)
                }
            }
        }
        assertEquals(setOf(Verdict.START, Verdict.CONTINUE, Verdict.HOLD), seen)
    }
}
