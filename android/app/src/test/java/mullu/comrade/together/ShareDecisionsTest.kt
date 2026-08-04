package mullu.comrade.together

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertEquals(200L to 50, ShareDecisions.chunkRange(250, 100, 2), "the tail is short, not padded")
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
        assertEquals(-1, index, "round-trips as the same 32 bits")
        assertFalse(
            ShareDecisions.chunkFrameFits(250, 100, index, 1),
            "and is refused by the offer rather than written anywhere",
        )
    }

    @Test
    fun `a chunk of the wrong length is caught now and not by the hash later`() {
        assertTrue(ShareDecisions.chunkFrameFits(250, 100, 0, 100))
        assertTrue(ShareDecisions.chunkFrameFits(250, 100, 2, 50), "the last chunk is short")
        assertFalse(ShareDecisions.chunkFrameFits(250, 100, 2, 100), "and is not padded to full")
        assertFalse(ShareDecisions.chunkFrameFits(250, 100, 0, 99))
        assertFalse(ShareDecisions.chunkFrameFits(250, 100, 3, 100), "past the end of the file")
    }

    // ── The tracker ─────────────────────────────────────────────────────────

    @Test
    fun `an empty offer has nothing to send and is already complete`() {
        val t = ShareDecisions.Tracker(0, 16 * 1024, 0)
        assertEquals(0, t.chunkCount)
        assertTrue(t.isComplete())
        assertEquals(1f, t.fraction())
        assertNull(t.nextRequest(0, 4))
        assertTrue(t.playableAt(0))
    }

    @Test
    fun `a duplicate chunk is not an error and is not counted twice`() {
        val t = ShareDecisions.Tracker(250, 100, 0)
        assertTrue(t.accept(1))
        assertFalse(t.accept(1), "a re-ask after a timeout can deliver both")
        assertFalse(t.accept(99), "and an impossible index is refused, not stored")
        assertEquals(1f / 3f, t.fraction())
    }

    @Test
    fun `a gap ends the runway however much lies beyond it`() {
        val t = ShareDecisions.Tracker(10_000, 1000, 10_000)
        for (i in listOf(0, 1, 2, 4, 5, 6, 7, 8, 9)) t.accept(i)
        assertEquals(3000L, t.runwayMs(0), "audio after a hole is a stutter waiting to happen")
        assertFalse(t.playableAt(0), "three seconds is under the runway threshold")
        assertTrue(t.playableAt(4000), "but from past the hole the rest is contiguous")
    }

    @Test
    fun `what is asked for next is anchored at the playhead, not the file start`() {
        val t = ShareDecisions.Tracker(10_000, 1000, 10_000)
        assertEquals(ShareDecisions.Request(6, 3), t.nextRequest(6000, 3))
        for (i in 6 until 10) t.accept(i)
        assertEquals(
            ShareDecisions.Request(0, 3),
            t.nextRequest(6000, 3),
            "everything past the playhead is here, so close the gap rather than declare victory",
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
        assertTrue(t.playableAt(1000), "a track with two seconds left is playable")
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
        assertContains(relay.message!!, "relay")

        val big = ShareDecisions.describeVerdict(
            verdict("refuse", RefusalReason.TooLargeForRelay(50uL * 1024uL * 1024uL)),
        )
        assertContains(big.message!!, "50 MB", "the limit is the actionable part")

        val unsettled = ShareDecisions.describeVerdict(verdict("refuse", RefusalReason.PathUnknown))
        assertTrue(unsettled.retryable, "ICE not having settled is worth waiting out")
    }

    @Test
    fun `consent is asked with the number attached, and is not a silent yes`() {
        val v = ShareDecisions.describeVerdict(
            verdict("needs_consent", relayedBytes = 100uL * 1024uL * 1024uL),
        )
        assertFalse(v.proceed, "needing consent must never read as having it")
        assertTrue(v.needsConsent)
        assertContains(v.message!!, "100 MB")
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
