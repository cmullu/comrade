package mullu.comrade.together

/**
 * Putting two people's audio into one stream: the film, and the person watching
 * it talking over it.
 *
 * `docs/TOGETHER.md` §15. A streamed session sends one audio track, so the
 * sender's voice and what they are playing have to arrive as one thing. That is
 * a sum, and the sum is where the trap is.
 *
 * **Two 16-bit samples do not fit in 16 bits.** `-20000 + -20000` is `-40000`,
 * which wraps to a large *positive* number, which is a full-scale sample where a
 * quiet one belonged — an audible crack on every loud moment, not a gentle
 * distortion. So every sum saturates at the ends of the range instead of
 * wrapping. This is the whole reason the mixing is a separate, tested function
 * rather than three lines inside a callback on the audio deadline.
 *
 * **No imports at all**, like [PcmRing], so it runs under `kotlinc` + JUnit here
 * (`CLAUDE.md`) rather than only in CI.
 *
 * Everything is little-endian signed 16-bit PCM, which is what both
 * `AudioRecord` and WebRTC's record buffer deal in.
 */
object PcmMix {

    /** The ends of the signed 16-bit range, where a sum stops instead of wrapping. */
    const val MAX_SAMPLE = 32767
    const val MIN_SAMPLE = -32768

    /**
     * Add `count` bytes of `add` into `dest`, sample by sample, saturating.
     *
     * `dest` is the buffer WebRTC is about to send — it arrives holding the
     * microphone — and `add` is what we are playing. Mixing in place is
     * deliberate: the callback owns that buffer for the length of the call and
     * allocating a second one per audio frame is exactly the kind of churn an
     * audio deadline does not forgive.
     *
     * An odd `count` leaves its last byte alone rather than pairing it with
     * whatever follows: half a sample is not a sample, and combining it with the
     * next frame's first byte would put a spike in the stream once per buffer.
     */
    fun mixInto(dest: ByteArray, add: ByteArray, count: Int) {
        val n = minOf(minOf(count, dest.size), add.size)
        var i = 0
        while (i + 1 < n) {
            val a = sampleAt(dest, i)
            val b = sampleAt(add, i)
            putSample(dest, i, clamp(a + b))
            i += 2
        }
    }

    /**
     * Copy `count` bytes of `src` over `dest`, replacing whatever was there.
     *
     * What "mic off" means: the microphone's samples are overwritten rather than
     * added to, so nothing of the sender's room reaches the other person.
     */
    fun replaceInto(dest: ByteArray, src: ByteArray, count: Int) {
        val n = minOf(minOf(count, dest.size), src.size)
        src.copyInto(dest, 0, 0, n)
    }

    /**
     * Write mono `src` into `dest` as stereo, each sample twice.
     *
     * `dest` needs room for `2 * count` bytes. Doubling rather than downmixing,
     * because the capture is mono and widening is exact where narrowing is a
     * judgement.
     */
    fun monoToStereo(dest: ByteArray, src: ByteArray, count: Int) {
        var read = 0
        var write = 0
        while (read + 1 < count && write + 3 < dest.size) {
            dest[write] = src[read]
            dest[write + 1] = src[read + 1]
            dest[write + 2] = src[read]
            dest[write + 3] = src[read + 1]
            read += 2
            write += 4
        }
    }

    /** Saturate to the 16-bit range. The one line this file exists for. */
    fun clamp(value: Int): Int = when {
        value > MAX_SAMPLE -> MAX_SAMPLE
        value < MIN_SAMPLE -> MIN_SAMPLE
        else -> value
    }

    /** The little-endian sample starting at `at`. */
    fun sampleAt(bytes: ByteArray, at: Int): Int {
        val lo = bytes[at].toInt() and 0xFF
        val hi = bytes[at + 1].toInt() // signed: this is the high byte
        return (hi shl 8) or lo
    }

    /** Write `value` as a little-endian sample at `at`. */
    fun putSample(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
