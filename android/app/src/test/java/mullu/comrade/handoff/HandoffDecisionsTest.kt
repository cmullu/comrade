package mullu.comrade.handoff

import mullu.comrade.transfer.FileTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offer-handling rules, tested against the same cases
 * `comrade_core::handoff`'s own tests use — because every input here is chosen by
 * the peer and two of them (the hash and the name) reach the filesystem and the
 * screen.
 */
class HandoffDecisionsTest {

    // ── Staging a peer-supplied hash ────────────────────────────────────────

    @Test
    fun `an incoming file is staged under its content hash`() {
        val hash = "b".repeat(64)
        assertEquals("$hash.part", HandoffDecisions.stagedFileName(hash))
    }

    @Test
    fun `case and surrounding space are normalised rather than refused`() {
        // A peer sending uppercase hex is not attacking anyone.
        assertEquals(
            "${"b".repeat(64)}.part",
            HandoffDecisions.stagedFileName("  ${"B".repeat(64)}  "),
        )
    }

    @Test
    fun `a hash that is not a hash never becomes a path`() {
        // Mirrors `a_hash_that_is_not_a_hash_never_becomes_a_path` in Rust.
        assertNull(HandoffDecisions.stagedFileName("../../etc/passwd"))
        assertNull(HandoffDecisions.stagedFileName(""))
        assertNull("too short", HandoffDecisions.stagedFileName("a".repeat(63)))
        assertNull("too long", HandoffDecisions.stagedFileName("a".repeat(65)))
        assertNull(
            "right length, wrong alphabet",
            HandoffDecisions.stagedFileName("a".repeat(63) + "/"),
        )
        assertNull("not hex", HandoffDecisions.stagedFileName("z".repeat(64)))
    }

    @Test
    fun `a staged name contains nothing but the hash and its suffix`() {
        // The property that matters: whatever came in, what comes out cannot
        // escape the staging directory.
        val hostile = listOf(
            "../../databases/comrade",
            "/etc/shadow",
            "a".repeat(63) + " ",
            "..",
        )
        for (input in hostile) {
            HandoffDecisions.stagedFileName(input)?.let { name ->
                assertTrue("$input became $name", name.matches(Regex("[0-9a-f]{64}\\.part")))
            }
        }
    }

    // ── Refusing an offer before anything is staged ─────────────────────────

    private val goodHash = "c".repeat(64)

    @Test
    fun `an offer this app can take is not refused`() {
        assertNull(
            HandoffDecisions.offerRefusal(
                totalBytes = 400L * 1024 * 1024,
                chunkBytes = FileTransfer.CHUNK_BYTES,
                sha256 = goodHash,
            ),
        )
    }

    @Test
    fun `an offer with no hash to check against is refused`() {
        assertNotNull(
            HandoffDecisions.offerRefusal(1024, FileTransfer.CHUNK_BYTES, "not-a-hash"),
        )
    }

    @Test
    fun `an empty offer is refused`() {
        assertNotNull(HandoffDecisions.offerRefusal(0, FileTransfer.CHUNK_BYTES, goodHash))
        assertNotNull(HandoffDecisions.offerRefusal(-1, FileTransfer.CHUNK_BYTES, goodHash))
    }

    @Test
    fun `a chunk size we do not speak is refused rather than allocated for`() {
        // A one-byte chunk size on a 4 GB offer is a four-billion-entry array,
        // which is an out-of-memory kill dressed up as a file transfer.
        assertNotNull(HandoffDecisions.offerRefusal(4L * 1024 * 1024 * 1024, 1, goodHash))
        assertNotNull(HandoffDecisions.offerRefusal(1024, 64 * 1024, goodHash))
    }

    @Test
    fun `an offer larger than the tracker can hold is refused`() {
        assertNull(
            "the ceiling is inclusive",
            HandoffDecisions.offerRefusal(
                HandoffDecisions.MAX_HANDOFF_BYTES,
                FileTransfer.CHUNK_BYTES,
                goodHash,
            ),
        )
        assertNotNull(
            HandoffDecisions.offerRefusal(
                HandoffDecisions.MAX_HANDOFF_BYTES + 1,
                FileTransfer.CHUNK_BYTES,
                goodHash,
            ),
        )
    }

    // ── A peer's filename is display text, never a path ─────────────────────

    @Test
    fun `a sender's name survives when it is an ordinary name`() {
        assertEquals("holiday.mp4", HandoffDecisions.displayFileName("holiday.mp4"))
        assertEquals("holiday.mp4", HandoffDecisions.displayFileName("  holiday.mp4 "))
    }

    @Test
    fun `a name cannot draw a directory it is not in`() {
        assertEquals("passwd", HandoffDecisions.displayFileName("../../etc/passwd"))
        assertEquals("payload.mp4", HandoffDecisions.displayFileName("..\\..\\payload.mp4"))
    }

    @Test
    fun `a name cannot draw a second line or a control character`() {
        assertEquals(
            "invoice.pdf.exe",
            HandoffDecisions.displayFileName("invoice.pdf\n\t.exe"),
        )
        assertEquals("clean", HandoffDecisions.displayFileName("cle\u0007a\u007Fn"))
    }

    @Test
    fun `a name long enough to push the buttons off the card is bounded`() {
        val name = HandoffDecisions.displayFileName("f".repeat(4096))
        assertEquals(HandoffDecisions.MAX_DISPLAY_NAME_LENGTH, name.length)
    }

    @Test
    fun `a nameless offer still reads as something`() {
        assertEquals("Unnamed file", HandoffDecisions.displayFileName(""))
        assertEquals("Unnamed file", HandoffDecisions.displayFileName("///"))
    }

    // ── What a person is told ───────────────────────────────────────────────

    @Test
    fun `the peer-to-peer note states both of the ways it can fail`() {
        val note = HandoffDecisions.routeNote(peerToPeer = true)
        assertTrue("says they must be online: $note", note.contains("online now"))
        assertTrue("says a direct path is needed: $note", note.contains("direct path"))
    }

    @Test
    fun `the hosted note says the thing the other road cannot do`() {
        val note = HandoffDecisions.routeNote(peerToPeer = false)
        assertTrue("says it survives them being offline: $note", note.contains("offline"))
    }

    @Test
    fun `a failure names the size that would work instead`() {
        assertTrue(HandoffDecisions.fallbackAdvice().contains("10 MB"))
    }

    @Test
    fun `an offer headline names the kind, the file and the size`() {
        assertEquals(
            "🎬 Video · holiday.mp4 · 400 MB",
            HandoffDecisions.offerHeadline("video/mp4", "holiday.mp4", 400L * 1024 * 1024),
        )
        // The kind comes from the MIME type and the name is still sanitised.
        assertEquals(
            "📎 File · passwd · 1 KB",
            HandoffDecisions.offerHeadline("application/x-thing", "../../etc/passwd", 1024),
        )
    }
}
