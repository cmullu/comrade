package mullu.comrade.together

import mullu.comrade.together.TogetherDecisions.EchoSuppressor
import mullu.comrade.together.TogetherDecisions.Local
import mullu.comrade.together.TogetherDecisions.Op
import mullu.comrade.together.TogetherDecisions.ScrubState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── Picture ─────────────────────────────────────────────────────────────
    //
    // The regression these pin: the session had no video surface at all, so a
    // film opened in watch-together played as audio. Nothing above could catch
    // that, because nothing above knew whether there was a picture.

    @Test
    fun aVideoTrackAsksForASurface() {
        val picture = TogetherDecisions.pictureOf(1920, 1080)
        assertEquals(TogetherDecisions.Picture.Video(1920, 1080), picture)
        assertEquals(16f / 9f, TogetherDecisions.aspectRatioOf(picture)!!, 0.001f)
    }

    @Test
    fun anAudioOnlyRecordingAsksForNoSurface() {
        // What MediaPlayer reports for a track with no video: zero, not null.
        val picture = TogetherDecisions.pictureOf(0, 0)
        assertEquals(TogetherDecisions.Picture.None, picture)
        assertNull("audio gets no black rectangle", TogetherDecisions.aspectRatioOf(picture))
    }

    @Test
    fun dimensionsThatArriveBeforeTheFirstFrameReadAsNoPictureYet() {
        // Deliberately the same answer as audio-only: the surface appears when
        // there is something to put on it.
        assertEquals(TogetherDecisions.Picture.None, TogetherDecisions.pictureOf(1920, 0))
        assertEquals(TogetherDecisions.Picture.None, TogetherDecisions.pictureOf(0, 1080))
    }

    /**
     * Compose's `aspectRatio` throws on a non-positive ratio, and the dimensions
     * come from a file the other person chose. A broken or hostile header must
     * not be able to take the screen down.
     */
    @Test
    fun aBrokenHeaderCannotProduceAnUndrawableShape() {
        for ((w, h) in listOf(1 to 1_000_000, 1_000_000 to 1, 3 to 2, 9 to 16)) {
            val ratio = TogetherDecisions.aspectRatioOf(TogetherDecisions.pictureOf(w, h))
            assertNotNull("${w}x$h produced no ratio", ratio)
            assertTrue("${w}x$h escaped the clamp", ratio!! >= TogetherDecisions.MIN_ASPECT)
            assertTrue("${w}x$h escaped the clamp", ratio <= TogetherDecisions.MAX_ASPECT)
        }
    }

    @Test
    fun negativeDimensionsAreAudio() {
        assertEquals(TogetherDecisions.Picture.None, TogetherDecisions.pictureOf(-1920, -1080))
    }

    @Test
    fun onlyPlayingVideoHoldsTheScreenAwake() {
        val video = TogetherDecisions.pictureOf(1920, 1080)
        val audio = TogetherDecisions.pictureOf(0, 0)
        assertTrue(TogetherDecisions.keepScreenOn(video, playing = true))
        assertFalse("a paused film does not need the screen", TogetherDecisions.keepScreenOn(video, playing = false))
        // The case that matters: two hours of music must not burn the battery
        // holding up a screen with nothing on it.
        assertFalse("audio must never hold the screen", TogetherDecisions.keepScreenOn(audio, playing = true))
    }

    // ── What the two measured numbers are allowed to say ────────────────────
    //
    // The same vectors as `desktop/ui/player_view.test.mjs`. These are the
    // rules the whole design rests on — never claim a precision we cannot
    // measure — so two frontends disagreeing about them is worse than neither
    // showing the numbers at all.

    private fun measure(driftMs: Long, qualityMs: Long, ageMs: Long = 1_000) =
        TogetherDecisions.measurement(driftMs, qualityMs, ageMs)

    @Test
    fun aGapSmallerThanOurOwnErrorIsNotReported() {
        // Printing "0.4 s apart" while the error is 0.8 s is invention.
        assertEquals(TogetherDecisions.Drift.Silent, measure(400, 800).drift)
        assertEquals(TogetherDecisions.Drift.Silent, measure(-400, 800).drift)
        // Bigger than the error, and worth saying.
        assertEquals(
            TogetherDecisions.Drift.Gap(1_200, weAreAhead = true),
            measure(1_200, 300).drift,
        )
        assertEquals(
            TogetherDecisions.Drift.Gap(1_200, weAreAhead = false),
            measure(-1_200, 300).drift,
        )
    }

    @Test
    fun aPerfectClockDoesNotLicenceNarratingATinyGap() {
        // A measured error of zero is what a mesh hop approaches; it must not
        // become permission to report a 50 ms gap nobody can hear.
        assertEquals(TogetherDecisions.Drift.Silent, measure(50, 0).drift)
        assertEquals(TogetherDecisions.Drift.Silent, measure(TogetherDecisions.TOGETHER_MS, 0).drift)
    }

    @Test
    fun thePathIsNamedBecauseItDecidesWhatTheGapIsWorth() {
        assertTrue((measure(0, 30).quality as TogetherDecisions.Quality.Known).direct)
        assertTrue((measure(0, 120).quality as TogetherDecisions.Quality.Known).direct)
        assertFalse((measure(0, 600).quality as TogetherDecisions.Quality.Known).direct)
    }

    @Test
    fun anUnmeasuredPathClaimsNothing() {
        for (bad in listOf(0L, -1L)) {
            assertEquals("quality $bad", TogetherDecisions.Quality.Unknown, measure(0, bad).quality)
        }
    }

    @Test
    fun aDirectPathNeverClaimsToBeTighterThanEitherPlayerCanReport() {
        // Desktop prints "±0.05s" for anything at or under the floor rather
        // than the raw figure, and this is where that floor lives.
        val tiny = measure(0, 5).quality as TogetherDecisions.Quality.Known
        assertEquals(TogetherDecisions.QUALITY_FLOOR_MS, tiny.ms)
        assertEquals(2, tiny.decimals)
        // A relayed figure gets one place: the second digit is not real.
        assertEquals(1, (measure(0, 620).quality as TogetherDecisions.Quality.Known).decimals)
    }

    @Test
    fun aReadingOlderThanTwoHeartbeatsIsNotShownAtAll() {
        // Corrections cross the bridge only when the verdict is not "hold", so
        // a screen that keeps the last pair shows a gap closed minutes ago,
        // underneath the word "together".
        val stale = measure(3_200, 100, ageMs = TogetherDecisions.READING_STALE_MS)
        assertEquals(TogetherDecisions.Drift.Silent, stale.drift)
        assertEquals(TogetherDecisions.Quality.Unknown, stale.quality)
    }

    @Test
    fun theErrorLineAgesOutWithTheGapNeverAfterIt() {
        // "±0.05s" left on screen after the gap it qualified has gone would
        // claim we are still measuring, which inverts the point of showing it.
        val old = measure(9_000, 40, ageMs = 60_000)
        assertEquals(TogetherDecisions.Drift.Silent, old.drift)
        assertEquals(TogetherDecisions.Quality.Unknown, old.quality)
    }

    @Test
    fun aSessionWithNoCorrectionYetShowsBlanksNotZeroes() {
        // What the screen computes before the first correction:
        // `System.currentTimeMillis() - 0`.
        val never = TogetherDecisions.measurement(0, 0, ageMs = System.currentTimeMillis())
        assertEquals(TogetherDecisions.Drift.Silent, never.drift)
        assertEquals(TogetherDecisions.Quality.Unknown, never.quality)
    }

    @Test
    fun aFreshReadingInsideTheDeadbandStillNamesThePath() {
        // The gap is not worth mentioning; how well we can see it still is.
        val m = measure(50, 40)
        assertEquals(TogetherDecisions.Drift.Silent, m.drift)
        assertTrue(m.quality is TogetherDecisions.Quality.Known)
    }

    @Test
    fun aClockThatWentBackwardsShowsNothingRatherThanAFutureReading() {
        val m = TogetherDecisions.measurement(3_200, 100, ageMs = -5_000)
        assertEquals(TogetherDecisions.Drift.Silent, m.drift)
        assertEquals(TogetherDecisions.Quality.Unknown, m.quality)
    }

    // ── A player that reports where it is once a second ─────────────────────

    private fun playhead() = TogetherDecisions.CoarsePlayhead()

    @Test
    fun aPlayheadBetweenTicksIsInterpolatedRatherThanStale() {
        // The whole reason this exists. `onCurrentSecond` fires ~1 Hz and the
        // poll reports 4x a second; handing the ladder a reading a second old
        // is how a session invents drift that is not there.
        val p = playhead()
        p.onPlaying(true, 1_000)
        p.onTick(30_000, 1_000)
        assertEquals(30_000, p.estimateMs(1_000))
        assertEquals(30_250, p.estimateMs(1_250))
        assertEquals(30_900, p.estimateMs(1_900))
    }

    @Test
    fun aPausedPlayheadDoesNotAdvance() {
        val p = playhead()
        p.onPlaying(false, 1_000)
        p.onTick(30_000, 1_000)
        assertEquals(30_000, p.estimateMs(9_000))
    }

    @Test
    fun pausingBanksTheTimeSinceTheLastTickInsteadOfDiscardingIt() {
        // Pause 900ms after a tick and that 900ms of real playback is real.
        // Throwing it away reports a position the video has already passed.
        val p = playhead()
        p.onPlaying(true, 1_000)
        p.onTick(30_000, 1_000)
        p.onPlaying(false, 1_900)
        assertEquals(30_900, p.estimateMs(5_000))
    }

    @Test
    fun ourOwnSeekMovesTheEstimateAtOnce() {
        // The bug this prevents is the sticky-trim sawtooth in a different
        // costume: without it, the second after a correction still reports the
        // old position, so the ladder corrects again for a gap it just closed.
        val p = playhead()
        p.onPlaying(true, 1_000)
        p.onTick(30_000, 1_000)
        p.onSeek(120_000, 1_100)
        assertEquals(120_000, p.estimateMs(1_100))
        assertEquals(120_400, p.estimateMs(1_500))
    }

    @Test
    fun aPlayerThatStoppedTickingStopsBeingGuessedAt() {
        // A stalled video, or one the system froze on backgrounding, sends no
        // ticks. An uncapped estimate would advance a standing-still playhead
        // forever and hide the stall from the drift verdict that should see it.
        val p = playhead()
        p.onPlaying(true, 1_000)
        p.onTick(30_000, 1_000)
        val capped = 30_000 + TogetherDecisions.COARSE_EXTRAPOLATE_MAX_MS
        assertEquals(capped, p.estimateMs(1_000 + TogetherDecisions.COARSE_EXTRAPOLATE_MAX_MS))
        assertEquals(capped, p.estimateMs(60_000))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotRewindThePlayhead() {
        val p = playhead()
        p.onPlaying(true, 10_000)
        p.onTick(30_000, 10_000)
        assertEquals(30_000, p.estimateMs(9_000))
    }

    @Test
    fun aResetPlayheadClaimsNothing() {
        val p = playhead()
        p.onPlaying(true, 1_000)
        p.onTick(30_000, 1_000)
        p.reset()
        assertEquals(0, p.estimateMs(99_000))
    }

    // ── What an embed's state means ─────────────────────────────────────────

    @Test
    fun bufferingIsNeverReportedAsAPause() {
        // docs/TOGETHER.md §10. Telling the peer "they paused" because our own
        // video stalled is the worst ping-pong available: they pause, which
        // makes us re-evaluate, which makes them re-evaluate.
        val stalled = TogetherDecisions.embedState("buffering")
        assertEquals(TogetherDecisions.EmbedState.Stalled, stalled)
        assertFalse(TogetherDecisions.embedStateIsWorthSending(stalled))
    }

    @Test
    fun playingAndPausingAreBothWorthSending() {
        assertEquals(
            TogetherDecisions.EmbedState.Live(playing = true),
            TogetherDecisions.embedState("playing"),
        )
        assertEquals(
            TogetherDecisions.EmbedState.Live(playing = false),
            TogetherDecisions.embedState("paused"),
        )
        for (s in listOf("playing", "paused", "ended")) {
            assertTrue(s, TogetherDecisions.embedStateIsWorthSending(TogetherDecisions.embedState(s)))
        }
    }

    @Test
    fun aPlayerThatHasNotStartedHasNoPositionWorthSending() {
        for (s in listOf("unstarted", "video_cued", "unknown", "", "nonsense")) {
            assertEquals(s, TogetherDecisions.EmbedState.NotReady, TogetherDecisions.embedState(s))
            assertFalse(s, TogetherDecisions.embedStateIsWorthSending(TogetherDecisions.embedState(s)))
        }
    }
}
