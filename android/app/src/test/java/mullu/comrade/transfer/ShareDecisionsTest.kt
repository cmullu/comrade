package mullu.comrade.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.comrade_core.RefusalReason
import uniffi.comrade_ui.ShareVerdictDto

/**
 * The vectors from `comrade_core::share`'s own tests, ported. A divergence
 * between the two implementations shows up here rather than as a file that
 * arrives corrupted.
 */
class ShareDecisionsTest {

    // ── Framing ─────────────────────────────────────────────────────────────

    @Test
    fun `a file divides into chunks with a short last one`() {
        assertEquals(3, ShareDecisions.chunkCount(250, 100))
        assertEquals(0L to 100, ShareDecisions.chunkRange(250, 100, 0))
        assertEquals("the tail is short, not padded", 200L to 50, ShareDecisions.chunkRange(250, 100, 2))
        assertNull(ShareDecisions.chunkRange(250, 100, 3))
    }

    @Test
    fun `a chunk carries its own index rather than relying on arrival order`() {
        val framed = ShareDecisions.frameChunk(7, byteArrayOf(1, 2, 3))
        assertEquals(ShareDecisions.CHUNK_HEADER_BYTES + 3, framed.size)
        val (index, payload) = ShareDecisions.parseChunkFrame(framed)!!
        assertEquals(7, index)
        assertContentEquals(byteArrayOf(1, 2, 3), payload)
    }

    @Test
    fun `a frame too short to hold a header is rejected rather than indexed`() {
        assertNull(ShareDecisions.parseChunkFrame(ByteArray(0)))
        assertNull(ShareDecisions.parseChunkFrame(byteArrayOf(0, 0, 0)))
        assertEquals(1, ShareDecisions.parseChunkFrame(byteArrayOf(0, 0, 0, 1))!!.first)
    }

    @Test
    fun `a high index does not sign-extend into a negative slot`() {
        // The failure this prevents is silent: a negative index would index
        // backwards out of the array rather than being refused as too large.
        val framed = ShareDecisions.frameChunk(-1, byteArrayOf(9)) // 0xFFFFFFFF
        val (index, _) = ShareDecisions.parseChunkFrame(framed)!!
        assertEquals("round-trips as the same 32 bits", -1, index)
        assertFalse(
            "and is refused by the offer rather than written anywhere",
            ShareDecisions.chunkFrameFits(250, 100, index, 1),
        )
    }

    @Test
    fun `a chunk of the wrong length is caught now and not by the hash later`() {
        assertTrue(ShareDecisions.chunkFrameFits(250, 100, 0, 100))
        assertTrue("the last chunk is short", ShareDecisions.chunkFrameFits(250, 100, 2, 50))
        assertFalse("and is not padded to full", ShareDecisions.chunkFrameFits(250, 100, 2, 100))
        assertFalse(ShareDecisions.chunkFrameFits(250, 100, 0, 99))
        assertFalse("past the end of the file", ShareDecisions.chunkFrameFits(250, 100, 3, 100))
    }

    // ── Where an incoming file lands ────────────────────────

    @Test
    fun `an incoming file is named after what it contains`() {
        val sha = "a".repeat(64)
        assertEquals("$sha.bin", ShareDecisions.incomingFileName(sha))
        // Content-addressed, so two handovers in one session cannot land on
        // each other the way one fixed name did.
        assertNotEquals(
            ShareDecisions.incomingFileName("a".repeat(64)),
            ShareDecisions.incomingFileName("b".repeat(64)),
        )
    }

    /**
     * The hash arrives in an offer from the other device, and it is used to
     * build a path. A peer must not be able to choose where their file lands.
     */
    @Test
    fun `a peer cannot steer their file out of the directory meant for it`() {
        for (hostile in listOf(
            "../../databases/comrade",
            "/etc/passwd",
            "..",
            "a/../../b",
            "abc .bin",
        )) {
            val name = ShareDecisions.incomingFileName(hostile)
            assertFalse("$hostile escaped", name.contains('/'))
            assertFalse("$hostile escaped", name.contains('\\'))
            assertFalse("$hostile kept a traversal", name.contains(".."))
            assertTrue("$hostile lost its extension", name.endsWith(".bin"))
        }
    }

    @Test
    fun `a hash with nothing usable in it still names a file`() {
        assertEquals("incoming.bin", ShareDecisions.incomingFileName(""))
        assertEquals("incoming.bin", ShareDecisions.incomingFileName("zzz///"))
    }

    @Test
    fun `an overlong hash cannot choose a name the filesystem will refuse`() {
        val name = ShareDecisions.incomingFileName("f".repeat(4096))
        assertEquals(64 + ".bin".length, name.length)
    }

    // ── The tracker ─────────────────────────────────────────────────────────

    @Test
    fun `an empty offer has nothing to send and is already complete`() {
        val t = ShareDecisions.Tracker(0, 16 * 1024, 0)
        assertEquals(0, t.chunkCount)
        assertTrue(t.isComplete())
        assertEquals(1f, t.fraction(), 1e-6f)
        assertNull(t.nextRequest(0, 4))
        assertTrue(t.playableAt(0))
    }

    @Test
    fun `a duplicate chunk is not an error and is not counted twice`() {
        val t = ShareDecisions.Tracker(250, 100, 0)
        assertTrue(t.accept(1))
        assertFalse("a re-ask after a timeout can deliver both", t.accept(1))
        assertFalse("and an impossible index is refused, not stored", t.accept(99))
        assertEquals(1f / 3f, t.fraction(), 1e-6f)
    }

    @Test
    fun `a gap ends the runway however much lies beyond it`() {
        val t = ShareDecisions.Tracker(10_000, 1000, 10_000)
        for (i in listOf(0, 1, 2, 4, 5, 6, 7, 8, 9)) t.accept(i)
        assertEquals("audio after a hole is a stutter waiting to happen", 3000L, t.runwayMs(0))
        assertFalse("three seconds is under the runway threshold", t.playableAt(0))
        assertTrue("but from past the hole the rest is contiguous", t.playableAt(4000))
    }

    @Test
    fun `what is asked for next is anchored at the playhead, not the file start`() {
        val t = ShareDecisions.Tracker(10_000, 1000, 10_000)
        assertEquals(ShareDecisions.Request(6, 3), t.nextRequest(6000, 3))
        for (i in 6 until 10) t.accept(i)
        assertEquals(
            "everything past the playhead is here, so close the gap rather than declare victory",
            ShareDecisions.Request(0, 3),
            t.nextRequest(6000, 3),
        )
    }

    @Test
    fun `resuming after a drop asks only for what is missing`() {
        val t = ShareDecisions.Tracker(10_000, 1000, 10_000)
        for (i in listOf(0, 1, 2, 5, 6)) t.accept(i)
        assertEquals(ShareDecisions.Request(3, 2), t.nextRequest(0, 10))
    }

    @Test
    fun `a tail that is entirely here is playable even below the runway`() {
        val t = ShareDecisions.Tracker(2000, 1000, 2000)
        t.accept(1)
        assertTrue("a track with two seconds left is playable", t.playableAt(1000))
    }

    // ── A re-delivered signal must not rebuild a live transfer (Q18) ────────

    private fun identity(
        sending: Boolean = false,
        sha: String = "a".repeat(64),
        totalBytes: Long = 700_000_000,
        chunkBytes: Int = 16 * 1024,
    ) = ShareDecisions.TransferIdentity(sending, sha, totalBytes, chunkBytes)

    @Test
    fun `with nothing in flight an offer simply arms`() {
        assertEquals(
            ShareDecisions.ArmDecision.START,
            ShareDecisions.decideArm(null, identity()),
        )
    }

    /**
     * The bug this exists for: gift-wraps are replayed on every reconnect, and
     * acting on the second copy tore down the transfer that was working and left
     * its `PeerConnection`, `DataChannel` and open file behind.
     */
    @Test
    fun `the same offer arriving twice does not restart the transfer that is running`() {
        assertEquals(
            ShareDecisions.ArmDecision.REDELIVERY,
            ShareDecisions.decideArm(identity(), identity()),
        )
    }

    @Test
    fun `a hash in the other case is still the same file, not a new one`() {
        assertEquals(
            "hex from a peer, so case is theirs to choose",
            ShareDecisions.ArmDecision.REDELIVERY,
            ShareDecisions.decideArm(identity(sha = "A".repeat(64)), identity(sha = "a".repeat(64))),
        )
    }

    @Test
    fun `a genuinely different file replaces the one in flight rather than being dropped`() {
        assertEquals(
            ShareDecisions.ArmDecision.REPLACE,
            ShareDecisions.decideArm(identity(sha = "a".repeat(64)), identity(sha = "b".repeat(64))),
        )
        assertEquals(
            "same hash, different length, so one of them is lying about something",
            ShareDecisions.ArmDecision.REPLACE,
            ShareDecisions.decideArm(identity(), identity(totalBytes = 12)),
        )
        assertEquals(
            "and a shape this device cannot be mid-transfer on",
            ShareDecisions.ArmDecision.REPLACE,
            ShareDecisions.decideArm(identity(), identity(chunkBytes = 4096)),
        )
    }

    @Test
    fun `the same file moving the other way is a different transfer`() {
        assertEquals(
            ShareDecisions.ArmDecision.REPLACE,
            ShareDecisions.decideArm(identity(sending = true), identity(sending = false)),
        )
    }

    /**
     * Which way the ambiguous case falls, asserted rather than left to be
     * rediscovered: two offers that agree on a hash that survived sanitising as
     * nothing read as one transfer seen twice.
     */
    @Test
    fun `an offer that cannot be told apart from the live one is treated as a repeat`() {
        assertEquals(
            ShareDecisions.ArmDecision.REDELIVERY,
            ShareDecisions.decideArm(identity(sha = ""), identity(sha = "")),
        )
    }

    // ── Whose negotiation is this? ──────────────────────────────────────────

    private fun route(
        fileEngineArmed: Boolean = false,
        expectingLateFileOffer: Boolean = false,
        isOffer: Boolean = true,
    ) = ShareDecisions.transportPlan(fileEngineArmed, expectingLateFileOffer, isOffer)

    @Test
    fun `an armed transfer keeps its own negotiation`() {
        assertEquals(ShareDecisions.TransportRoute.FILE, route(fileEngineArmed = true).route)
        assertFalse(
            "the engine owns it now, so nothing is still owed",
            route(fileEngineArmed = true, expectingLateFileOffer = true).stillExpecting,
        )
    }

    /**
     * §15's rule, unchanged: with nothing armed and nothing ever accepted, an
     * offer cannot be a file, because nobody asked for one.
     */
    @Test
    fun `an offer nobody asked for is a stream`() {
        assertEquals(ShareDecisions.TransportRoute.STREAM, route().route)
        assertEquals(
            "and so is everything else on a connection nobody else claims",
            ShareDecisions.TransportRoute.STREAM,
            route(isOffer = false).route,
        )
    }

    /**
     * The seam this exists for. The receiver accepted a file, the sender's
     * `Accept` sat at a relay while it was offline, the transfer gave up — and
     * the offer that finally arrives is a *file* offer. Routing it to the stream
     * receiver opens an unasked-for live audio and video connection on a device
     * whose user was told their file never came.
     */
    @Test
    fun `a file offer that arrives after the transfer gave up is nobody's`() {
        assertEquals(
            ShareDecisions.TransportRoute.DROP,
            route(expectingLateFileOffer = true).route,
        )
        assertEquals(
            "and neither are the candidates trailing it",
            ShareDecisions.TransportRoute.DROP,
            route(expectingLateFileOffer = true, isOffer = false).route,
        )
    }

    /**
     * Discharged, not sticky. Exactly one offer is owed per `Accept`, so a host
     * who answers a failed handover by streaming instead is still heard —
     * holding the expectation for the rest of the session would trade one
     * surprise for one silently broken feature.
     */
    @Test
    fun `giving up on one file does not deafen the session to streams`() {
        val late = route(expectingLateFileOffer = true, isOffer = true)
        assertEquals(ShareDecisions.TransportRoute.DROP, late.route)
        assertFalse("the offer settles what was owed", late.stillExpecting)
        // Which is what makes the next one route normally.
        assertEquals(
            ShareDecisions.TransportRoute.STREAM,
            route(expectingLateFileOffer = late.stillExpecting).route,
        )
    }

    @Test
    fun `waiting on the offer survives the signals that are not it`() {
        assertTrue(
            "an answer or a candidate is not what was owed, so it settles nothing",
            route(expectingLateFileOffer = true, isOffer = false).stillExpecting,
        )
    }

    // ── A stalled transfer must end, not hang (Q19) ─────────────────────────

    private fun stall(
        sinceProgressMs: Long,
        started: Boolean = true,
        awaitingPerson: Boolean = false,
        reasksSoFar: Int = 0,
    ) = ShareDecisions.stallAction(sinceProgressMs, started, awaitingPerson, reasksSoFar)

    @Test
    fun `a transfer that is still delivering is left alone`() {
        assertEquals(ShareDecisions.StallAction.WAIT, stall(0))
        assertEquals(
            ShareDecisions.StallAction.WAIT,
            stall(ShareDecisions.STALL_AFTER_MS - 1),
        )
    }

    @Test
    fun `chunks stopping asks again once, then gives up rather than hanging`() {
        assertEquals(
            ShareDecisions.StallAction.REASK,
            stall(ShareDecisions.STALL_AFTER_MS),
        )
        assertEquals(
            "the re-ask gets its own window, or it would be judged before it could be answered",
            ShareDecisions.StallAction.WAIT,
            stall(ShareDecisions.STALL_AFTER_MS, reasksSoFar = 1),
        )
        assertEquals(
            ShareDecisions.StallAction.GIVE_UP,
            stall(2 * ShareDecisions.STALL_AFTER_MS, reasksSoFar = 1),
        )
    }

    @Test
    fun `asking again is bounded, so a dead sender is not polled forever`() {
        assertEquals(
            ShareDecisions.StallAction.GIVE_UP,
            stall(600_000, reasksSoFar = ShareDecisions.STALL_REASKS),
        )
    }

    /**
     * The regression this arm exists for. A person deciding whether to allow a
     * relay is not a stalled transfer: the deadline running underneath them tore
     * down a perfectly healthy handover **and**, when it was this device asking,
     * cleared the dialog off the screen while they were reading it.
     */
    @Test
    fun `no deadline runs at all while a person is being asked`() {
        for (started in listOf(false, true)) {
            for (elapsed in listOf(0L, 30_000L, 600_000L)) {
                assertEquals(
                    "started=$started elapsed=$elapsed",
                    ShareDecisions.StallAction.HOLD,
                    stall(elapsed, started = started, awaitingPerson = true),
                )
            }
        }
        assertEquals(
            "and a person who took their time does not inherit a spent budget",
            ShareDecisions.StallAction.HOLD,
            stall(600_000, awaitingPerson = true, reasksSoFar = ShareDecisions.STALL_REASKS),
        )
    }

    /**
     * Holding is not waiting. `WAIT` spends the budget and `HOLD` restarts it,
     * and collapsing the two is exactly how the deadline came to run under a
     * dialog in the first place.
     */
    @Test
    fun `holding and waiting are different answers to different situations`() {
        assertNotEquals(
            stall(ShareDecisions.STALL_AFTER_MS - 1, awaitingPerson = true),
            stall(ShareDecisions.STALL_AFTER_MS - 1, awaitingPerson = false),
        )
    }

    @Test
    fun `setting a connection up gets a wider window than a link going quiet`() {
        assertEquals(
            "eight seconds is not long enough to negotiate in, and judging it so ends healthy transfers",
            ShareDecisions.StallAction.WAIT,
            stall(ShareDecisions.STALL_AFTER_MS * 2, started = false),
        )
        assertEquals(
            ShareDecisions.StallAction.REASK,
            stall(ShareDecisions.SETUP_GRACE_MS, started = false),
        )
        assertEquals(
            ShareDecisions.StallAction.GIVE_UP,
            stall(2 * ShareDecisions.SETUP_GRACE_MS, started = false, reasksSoFar = 1),
        )
        assertTrue(
            "the setup window has to be the wider of the two",
            ShareDecisions.SETUP_GRACE_MS > ShareDecisions.STALL_AFTER_MS,
        )
    }

    /**
     * Two deadlines over the same failure would mean the sentence a person reads
     * depends on which timer won. `judgePath` names the actual problem ("couldn't
     * work out a route"), so it must be the one that fires.
     */
    @Test
    fun `working out a route is not spent out of the transfer's budget`() {
        val judging = ShareDecisions.PATH_RETRIES * ShareDecisions.PATH_RETRY_TICK_MS
        assertTrue(
            "$judging of path judgement does not fit inside ${ShareDecisions.SETUP_GRACE_MS}",
            ShareDecisions.SETUP_GRACE_MS > judging,
        )
        assertEquals(
            ShareDecisions.StallAction.WAIT,
            stall(judging, started = false),
        )
    }

    /**
     * The ordering that makes the fix visible to a person: the transfer must
     * admit it has stopped *before* the decoder's own read expires, or the
     * player stalls first and nothing on either device says why — which is Q19
     * reproduced one layer down.
     */
    @Test
    fun `the transfer gives up before a reader waiting on it runs out of patience`() {
        // The between-chunks budget, which is the one that matters here: a
        // decoder only waits on bytes once bytes have started.
        val worstCaseMs = ShareDecisions.STALL_AFTER_MS * (ShareDecisions.STALL_REASKS + 1)
        assertTrue(
            "$worstCaseMs is not inside ${ShareReadPolicy.READ_PATIENCE_MS}",
            worstCaseMs < ShareReadPolicy.READ_PATIENCE_MS,
        )
    }

    // ── Refusals say something a person can act on ──────────────────────────

    private fun verdict(
        kind: String,
        reason: RefusalReason? = null,
        relayedBytes: ULong? = null,
    ) = ShareVerdictDto(
        verdict = kind,
        path = if (reason == null && kind == "allow") "host" else "relay",
        reason = reason,
        relayedBytes = relayedBytes,
    )

    @Test
    fun `each refusal names the thing that could be changed about it`() {
        val relay = ShareDecisions.describeVerdict(verdict("refuse", RefusalReason.RelayForbidden))
        assertFalse(relay.proceed)
        assertTrue(relay.message!!.contains("relay"))

        val big = ShareDecisions.describeVerdict(
            verdict("refuse", RefusalReason.TooLargeForRelay(50uL * 1024uL * 1024uL)),
        )
        assertTrue("the limit is the actionable part", big.message!!.contains("50 MB"))

        val unsettled = ShareDecisions.describeVerdict(verdict("refuse", RefusalReason.PathUnknown))
        assertTrue("ICE not having settled is worth waiting out", unsettled.retryable)
    }

    @Test
    fun `consent is asked with the number attached, and is not a silent yes`() {
        val v = ShareDecisions.describeVerdict(
            verdict("needs_consent", relayedBytes = 100uL * 1024uL * 1024uL),
        )
        assertFalse("needing consent must never read as having it", v.proceed)
        assertTrue(v.needsConsent)
        assertTrue(v.message!!.contains("100 MB"))
    }

    @Test
    fun `allow is the only verdict that proceeds, and an absent one does not`() {
        assertTrue(ShareDecisions.describeVerdict(verdict("allow")).proceed)
        assertFalse(ShareDecisions.describeVerdict(null).proceed)
        assertFalse(ShareDecisions.describeVerdict(verdict("something new")).proceed)
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
