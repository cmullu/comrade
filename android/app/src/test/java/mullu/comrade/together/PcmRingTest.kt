package mullu.comrade.together

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The buffer between capturing audio and sending it (`docs/TOGETHER.md` §15).
 *
 * Worth testing rather than reading, for one specific reason: the read cursor is
 * `writeAt - stored`, which goes negative every time the newest write has
 * wrapped, and Kotlin's `%` keeps the sign of its left operand. That is a bug
 * that produces an `ArrayIndexOutOfBoundsException` on a real device, minutes
 * into a session, on the audio thread — and never in a short manual test.
 */
class PcmRingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun whatGoesInComesOutInOrder() {
        val r = PcmRing(8)
        r.write(bytes(1, 2, 3), 3)
        assertEquals(3, r.available)
        val out = ByteArray(3)
        assertEquals(3, r.read(out, 3))
        assertArrayEquals(bytes(1, 2, 3), out)
        assertEquals(0, r.available)
    }

    @Test
    fun readingPastWhatIsThereTakesWhatThereIs() {
        val r = PcmRing(8)
        r.write(bytes(1, 2), 2)
        val out = ByteArray(5)
        assertEquals(2, r.read(out, 5))
        // The rest is untouched, which is the silence an underrun must produce
        // rather than repeated audio.
        assertArrayEquals(bytes(1, 2, 0, 0, 0), out)
    }

    @Test
    fun anEmptyRingYieldsSilenceAndNotAnError() {
        val r = PcmRing(8)
        val out = ByteArray(4)
        assertEquals(0, r.read(out, 4))
        assertArrayEquals(bytes(0, 0, 0, 0), out)
    }

    @Test
    fun aFullRingDropsTheOldestNotTheNewest() {
        // The audible rule: the newest sample is the one closest to the picture.
        val r = PcmRing(4)
        r.write(bytes(1, 2, 3, 4), 4)
        r.write(bytes(5, 6), 2)
        assertEquals(4, r.available)
        val out = ByteArray(4)
        r.read(out, 4)
        assertArrayEquals(bytes(3, 4, 5, 6), out)
    }

    @Test
    fun aWriteBiggerThanTheRingKeepsItsTail() {
        val r = PcmRing(3)
        r.write(bytes(1, 2, 3, 4, 5), 5)
        assertEquals(3, r.available)
        val out = ByteArray(3)
        r.read(out, 3)
        assertArrayEquals(bytes(3, 4, 5), out)
    }

    @Test
    fun theCursorSurvivesWrappingManyTimesOver() {
        // The regression this file exists for. `writeAt - stored` is negative
        // for most of this loop; a plain `%` would index backwards and throw on
        // the audio thread, minutes in, never during a quick manual try.
        val r = PcmRing(5)
        var next = 0
        repeat(200) {
            val chunk = ByteArray(3) { (next + it).toByte() }
            next += 3
            r.write(chunk, 3)
            val out = ByteArray(3)
            val got = r.read(out, 3)
            assertEquals(3, got)
            assertArrayEquals(chunk, out)
        }
    }

    @Test
    fun interleavedWritesAndPartialReadsStayInOrder() {
        val r = PcmRing(6)
        r.write(bytes(1, 2, 3, 4), 4)
        val first = ByteArray(2)
        r.read(first, 2)
        assertArrayEquals(bytes(1, 2), first)
        r.write(bytes(5, 6, 7), 3)
        assertEquals(5, r.available)
        val rest = ByteArray(5)
        r.read(rest, 5)
        assertArrayEquals(bytes(3, 4, 5, 6, 7), rest)
    }

    @Test
    fun clearForgetsEverything() {
        val r = PcmRing(4)
        r.write(bytes(1, 2, 3), 3)
        r.clear()
        assertEquals(0, r.available)
        val out = ByteArray(3)
        assertEquals(0, r.read(out, 3))
    }

    @Test
    fun aZeroCapacityRingIsInertRatherThanFatal() {
        // Reachable if a device reports a nonsense minimum buffer size; it must
        // read as "no audio to inject", which passes the microphone through.
        val r = PcmRing(0)
        r.write(bytes(1, 2, 3), 3)
        assertEquals(0, r.available)
        assertEquals(0, r.read(ByteArray(3), 3))
    }
}
