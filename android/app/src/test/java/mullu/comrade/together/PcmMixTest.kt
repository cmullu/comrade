package mullu.comrade.together

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mixing a voice into what is playing (`docs/TOGETHER.md` §15).
 *
 * The case worth the file: two loud samples summing past the 16-bit range. A
 * wrap turns a loud moment into a full-scale sample of the opposite sign, which
 * is an audible crack on every peak — and it is inaudible in a quiet test and
 * obvious the moment two people talk over a loud scene.
 */
class PcmMixTest {

    /** Little-endian bytes for a list of signed 16-bit samples. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> PcmMix.putSample(out, i * 2, s) }
        return out
    }

    private fun samples(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map { PcmMix.sampleAt(bytes, it * 2) }

    @Test
    fun aSampleRoundTripsThroughItsBytes() {
        for (s in listOf(0, 1, -1, 1234, -1234, 32767, -32768)) {
            val b = pcm(s)
            assertEquals("sample $s", s, PcmMix.sampleAt(b, 0))
        }
    }

    @Test
    fun quietVoiceOverQuietFilmIsJustTheSum() {
        val dest = pcm(100, -100, 0)
        PcmMix.mixInto(dest, pcm(50, 50, -25), dest.size)
        assertEquals(listOf(150, -50, -25), samples(dest))
    }

    @Test
    fun twoLoudSamplesSaturateInsteadOfWrapping() {
        // The bug this file exists for. Naive addition of -20000 + -20000 is
        // -40000, which wraps to +25536: a full-scale positive sample where a
        // loud negative one belonged, once per peak.
        val dest = pcm(20000, -20000)
        PcmMix.mixInto(dest, pcm(20000, -20000), dest.size)
        assertEquals(listOf(PcmMix.MAX_SAMPLE, PcmMix.MIN_SAMPLE), samples(dest))
    }

    @Test
    fun theEndsOfTheRangeAreThemselves() {
        assertEquals(PcmMix.MAX_SAMPLE, PcmMix.clamp(PcmMix.MAX_SAMPLE))
        assertEquals(PcmMix.MIN_SAMPLE, PcmMix.clamp(PcmMix.MIN_SAMPLE))
        assertEquals(PcmMix.MAX_SAMPLE, PcmMix.clamp(999_999))
        assertEquals(PcmMix.MIN_SAMPLE, PcmMix.clamp(-999_999))
        assertEquals(0, PcmMix.clamp(0))
    }

    @Test
    fun aHalfSampleAtTheEndIsLeftAlone() {
        // An odd byte count must not pair a lone byte with the next frame's
        // first one, which would put a spike in the stream once per buffer.
        val dest = pcm(100, 200) + byteArrayOf(7)
        val before = dest.last()
        PcmMix.mixInto(dest, pcm(1, 2) + byteArrayOf(9), dest.size)
        assertEquals(listOf(101, 202), samples(dest.copyOf(4)))
        assertEquals(before, dest.last())
    }

    @Test
    fun mixingStopsAtTheShorterBuffer() {
        val dest = pcm(10, 20, 30)
        PcmMix.mixInto(dest, pcm(1), dest.size)
        assertEquals("only the first sample had anything to add to it", listOf(11, 20, 30), samples(dest))
    }

    @Test
    fun micOffReplacesRatherThanAdds() {
        // The whole meaning of the toggle: nothing of the sender's room reaches
        // the other person, rather than being merely quieter than the film.
        val dest = pcm(9999, -9999)
        PcmMix.replaceInto(dest, pcm(1, 2), dest.size)
        assertEquals(listOf(1, 2), samples(dest))
    }

    @Test
    fun monoWidensToStereoBySayingEachSampleTwice() {
        val dest = ByteArray(8)
        PcmMix.monoToStereo(dest, pcm(1000, -1000), 4)
        assertEquals(listOf(1000, 1000, -1000, -1000), samples(dest))
    }

    @Test
    fun wideningNeverRunsOffTheEndOfTheDestination() {
        // A destination too small for the doubled data must truncate rather
        // than throw on the audio thread.
        val dest = ByteArray(4)
        PcmMix.monoToStereo(dest, pcm(1000, -1000), 4)
        assertEquals(listOf(1000, 1000), samples(dest))
    }

    @Test
    fun mixingNothingLeavesTheMicrophoneAlone() {
        val dest = pcm(500, -500)
        val copy = dest.copyOf()
        PcmMix.mixInto(dest, ByteArray(0), 0)
        assertArrayEquals(copy, dest)
    }
}
