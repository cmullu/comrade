package mullu.comrade.together

/**
 * The handful of bytes between the thread capturing audio and the thread
 * sending it — and the two policy choices in it that are audible when wrong.
 *
 * `docs/TOGETHER.md` §15. Pulled out of [PlaybackCapture] with **no imports at
 * all**, because it is the part of that file most likely to be subtly wrong —
 * modulo arithmetic on a cursor that can go negative — and the only part that
 * can be checked without a device. It runs under `kotlinc` + JUnit here
 * (`CLAUDE.md`).
 *
 * Not thread-safe by design: the caller owns the lock, holds it only for a copy,
 * and never holds it across an `AudioRecord.read` or a write to WebRTC. Putting
 * the lock inside would invite exactly the "hold it a bit longer" edit that
 * turns an audio deadline into a glitch.
 *
 * **The two policies, and why each is the audible one:**
 *
 * - **A full ring drops the oldest**, never the newest. The newest sample is the
 *   one closest to the picture; preferring stale audio to it makes a momentary
 *   lag permanent.
 * - **An underrun yields silence**, never a repeat. A gap is a click; repeated
 *   audio is a stutter, and a stutter sounds like a damaged file rather than a
 *   busy network.
 */
class PcmRing(capacityBytes: Int) {

    private val ring = ByteArray(capacityBytes.coerceAtLeast(0))
    private var writeAt = 0
    private var stored = 0

    /** How many bytes are waiting to be sent. */
    val available: Int get() = stored

    val capacity: Int get() = ring.size

    /**
     * Append `count` bytes of `src`, overwriting the oldest audio if it does not
     * fit.
     *
     * A write larger than the whole ring keeps only its **tail** — the newest
     * audio in the write — for the same reason a full ring drops the oldest.
     */
    fun write(src: ByteArray, count: Int) {
        val cap = ring.size
        if (cap == 0 || count <= 0) return
        val n = minOf(count, cap)
        val from = count - n
        for (i in 0 until n) {
            ring[(writeAt + i) % cap] = src[from + i]
        }
        writeAt = (writeAt + n) % cap
        stored = minOf(stored + n, cap)
    }

    /**
     * Take up to `want` of the oldest bytes into `dest`, and return how many.
     *
     * Anything not filled is left as the caller had it — which for a freshly
     * allocated buffer is the silence an underrun is supposed to produce.
     */
    fun read(dest: ByteArray, want: Int): Int {
        val cap = ring.size
        if (cap == 0 || stored == 0 || want <= 0) return 0
        val n = minOf(minOf(want, stored), dest.size)
        // `writeAt` is one past the newest byte, so the oldest of `stored` sits
        // this far back. The `+ cap` is not decoration: the subtraction goes
        // negative whenever the newest write has wrapped, which is most of the
        // time, and Kotlin's `%` keeps the sign of the left operand.
        val start = ((writeAt - stored) % cap + cap) % cap
        for (i in 0 until n) {
            dest[i] = ring[(start + i) % cap]
        }
        stored -= n
        return n
    }

    /** Forget everything buffered — a new session, or a stopped capture. */
    fun clear() {
        writeAt = 0
        stored = 0
    }
}
