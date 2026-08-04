package mullu.comrade.together

import mullu.comrade.together.TogetherDecisions.EchoSuppressor
import mullu.comrade.together.TogetherDecisions.Local
import mullu.comrade.together.TogetherDecisions.Op
import mullu.comrade.together.TogetherDecisions.ScrubState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-together decision vectors.
 *
 * Deliberately the same cases as `desktop/ui/together_sync.test.mjs`, with the
 * same numbers, so the two frontends drifting apart is a red test rather than a
 * field bug — the remedy `docs/COMMS_ARCHITECTURE.md` ADR-2 prescribes after the
 * call state machines diverged.
 */
class TogetherDecisionsTest {

    private val playing = Local(posMs = 60_000, playing = true, prepared = true)
    private val paused = Local(posMs = 10_000, playing = false, prepared = true)

    // ── Echo suppression ────────────────────────────────────────────────────

    @Test
    fun aSeekWeAppliedDoesNotComeBackOut() {
        val s = EchoSuppressor()
        s.expect("seek", 42_000, 1_000)
        assertNull(TogetherDecisions.classifyCallback("seek", 42_000, true, s, 1_000))
        assertEquals("the entry should have been consumed", 0, s.size)
    }

    @Test
    fun sampleSnappingStillCountsAsTheSeekWeApplied() {
        val s = EchoSuppressor()
        s.expect("seek", 42_000, 1_000)
        assertNull(TogetherDecisions.classifyCallback("seek", 42_004, true, s, 1_000))
    }

    @Test
    fun aUserSeekDuringAnInFlightApplyIsStillReported() {
        val s = EchoSuppressor()
        s.expect("seek", 42_000, 1_000)
        val emit = TogetherDecisions.classifyCallback("seek", 12_000, true, s, 1_000)
        assertEquals(12_000L, emit?.posMs)
    }

    /**
     * The case that permanently wedges a boolean latch: seeking to the position
     * the player already holds may raise no callback, so nothing ever clears the
     * flag and every later user seek is swallowed.
     */
    @Test
    fun anApplyThatRaisesNoCallbackExpiresInsteadOfGoingDeaf() {
        val s = EchoSuppressor()
        s.expect("seek", 42_000, 1_000)
        val later = 1_000 + TogetherDecisions.SUPPRESS_TTL_MS + 1
        val emit = TogetherDecisions.classifyCallback("seek", 42_000, true, s, later)
        assertNotNull("a stale entry must not keep swallowing user seeks", emit)
        assertEquals(0, s.size)
    }

    @Test
    fun coalescedAppliesLeaveNoEntryThatSwallowsALaterRealSeek() {
        val s = EchoSuppressor()
        s.expect("seek", 42_000, 1_000)
        s.expect("seek", 60_000, 1_000)
        // Only the second one produced a callback.
        assertNull(TogetherDecisions.classifyCallback("seek", 60_000, true, s, 1_000))
        val later = 1_000 + TogetherDecisions.SUPPRESS_TTL_MS + 1
        assertNotNull(TogetherDecisions.classifyCallback("seek", 42_000, true, s, later))
    }

    @Test
    fun anAppliedPauseIsSilentButTheUsersNextOneIsNot() {
        val s = EchoSuppressor()
        s.expect("pause", null, 1_000)
        assertNull(TogetherDecisions.classifyCallback("pause", 10_000, false, s, 1_000))
        assertNotNull(TogetherDecisions.classifyCallback("pause", 10_000, false, s, 1_000))
    }

    @Test
    fun reachingTheEndIsAPauseNeverASeek() {
        val s = EchoSuppressor()
        val emit = TogetherDecisions.classifyCallback("completion", 7_200_000, true, s, 1_000)
        assertEquals(7_200_000L, emit?.posMs)
        assertEquals(false, emit?.playing)
    }

    @Test
    fun theLedgerStaysBoundedUnderAnApplyStorm() {
        val s = EchoSuppressor()
        repeat(100) { s.expect("seek", it.toLong(), 1_000) }
        assertTrue("unbounded ledger: ${s.size}", s.size <= 16)
    }

    // ── Corrections ─────────────────────────────────────────────────────────

    @Test
    fun holdingDoesNothingAtAll() {
        val plan = TogetherDecisions.planCorrection("hold", 0, 1f, true, playing)
        assertTrue(plan.ops.isEmpty() && plan.expect.isEmpty())
    }

    @Test
    fun aRateTrimRaisesNoCallbackSoItArmsNothing() {
        val plan = TogetherDecisions.planCorrection("nudge", 0, 1.04f, true, playing)
        assertEquals(listOf(Op.Rate(1.04f)), plan.ops)
        assertTrue(plan.expect.isEmpty())
    }

    @Test
    fun aRateTheCoreAskedForIsStillClampedToSomethingListenable() {
        val fast = TogetherDecisions.planCorrection("nudge", 0, 3f, true, playing)
        assertEquals(Op.Rate(TogetherDecisions.RATE_MAX), fast.ops.single())
        val slow = TogetherDecisions.planCorrection("nudge", 0, 0.1f, true, playing)
        assertEquals(Op.Rate(TogetherDecisions.RATE_MIN), slow.ops.single())
    }

    /**
     * `setPlaybackParams` on a paused `MediaPlayer` starts playback on several
     * Android versions. A drift correction must never do that.
     */
    @Test
    fun aRateTrimIsRefusedWhileThePlayerIsPaused() {
        val plan = TogetherDecisions.planCorrection("nudge", 0, 1.04f, true, paused)
        assertTrue("a rate trim on a paused player would start it", plan.ops.isEmpty())
    }

    @Test
    fun aCorrectionSeeksButNeverStartsPlayback() {
        val plan = TogetherDecisions.planCorrection("seek", 42_000, 1f, true, paused)
        assertEquals(listOf(Op.Seek(42_000)), plan.ops)
        assertTrue(plan.ops.none { it is Op.Play })
    }

    @Test
    fun adoptingAMissedCommandTakesTheirPlayingStateToo() {
        val plan = TogetherDecisions.planCorrection("adopt", 42_000, 1f, true, paused)
        assertEquals(listOf(Op.Seek(42_000), Op.Play), plan.ops)
    }

    @Test
    fun adoptingAStateWeAreAlreadyInDoesNothing() {
        val plan = TogetherDecisions.planCorrection("adopt", 60_000, 1f, true, playing)
        assertTrue(plan.ops.isEmpty() && plan.expect.isEmpty())
    }

    /** `seekTo` before `prepare()` throws, so nothing is planned until then. */
    @Test
    fun nothingIsPlannedBeforeThePlayerIsPrepared() {
        val unprepared = Local(posMs = 0, playing = false, prepared = false)
        assertTrue(TogetherDecisions.planCorrection("seek", 42_000, 1f, true, unprepared).ops.isEmpty())
        assertTrue(TogetherDecisions.planCommand(42_000, true, unprepared).ops.isEmpty())
    }

    /**
     * The property that makes the feedback loop unreachable rather than merely
     * unlikely: anything that can raise a callback arms a matching expectation,
     * and feeding those callbacks back in produces nothing outbound.
     */
    @Test
    fun everyOpThatCanRaiseACallbackArmsAMatchingExpectation() {
        val verdicts = listOf(
            Triple("hold", 0L, true),
            Triple("nudge", 0L, true),
            Triple("seek", 42_000L, true),
            Triple("adopt", 42_000L, true),
            Triple("adopt", 42_000L, false),
            Triple("something-new", 0L, true),
        )
        for ((kind, pos, play) in verdicts) {
            for (local in listOf(playing, paused)) {
                val plan = TogetherDecisions.planCorrection(kind, pos, 1.02f, play, local)
                val noisy = plan.ops.filterNot { it is Op.Rate }
                assertEquals(
                    "unarmed op in $kind: ${plan.ops}",
                    noisy.size,
                    plan.expect.size,
                )
                val s = EchoSuppressor()
                plan.expect.forEach { (k, p) -> s.expect(k, p, 1_000) }
                for (op in noisy) {
                    val (k, p) = when (op) {
                        is Op.Seek -> "seek" to op.posMs
                        Op.Play -> "play" to local.posMs
                        Op.Pause -> "pause" to local.posMs
                        is Op.Rate -> continue
                    }
                    assertNull(
                        "applying $kind echoed back out",
                        TogetherDecisions.classifyCallback(k, p, play, s, 1_000),
                    )
                }
            }
        }
    }

    // ── The scrubber ────────────────────────────────────────────────────────

    @Test
    fun thePollDoesNotFightAFingerOnTheSlider() {
        assertTrue(TogetherDecisions.pollMayMoveSlider(ScrubState(scrubbing = false, pendingRemoteMs = null)))
        assertTrue(!TogetherDecisions.pollMayMoveSlider(ScrubState(scrubbing = true, pendingRemoteMs = null)))
    }

    @Test
    fun aRemoteSeekMidDragIsQueuedNotApplied() {
        val dragging = ScrubState(scrubbing = true, pendingRemoteMs = null)
        val queued = TogetherDecisions.onRemoteSeek(dragging, 42_000)
        assertEquals(42_000L, queued.pendingRemoteMs)
        assertNull("nothing may be applied while the finger is down", TogetherDecisions.pendingRemoteToApply(queued))
    }

    @Test
    fun theUsersOwnPositionWinsOnRelease() {
        val queued = ScrubState(scrubbing = true, pendingRemoteMs = 42_000)
        val released = TogetherDecisions.onScrubRelease(queued)
        assertNull(
            "a remote seek from mid-drag must not land after the user named a position",
            TogetherDecisions.pendingRemoteToApply(released),
        )
    }

    @Test
    fun aRemoteSeekWithNoFingerDownAppliesStraightAway() {
        val idle = ScrubState(scrubbing = false, pendingRemoteMs = null)
        val next = TogetherDecisions.onRemoteSeek(idle, 42_000)
        assertNull(next.pendingRemoteMs)
    }
}
