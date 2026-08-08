package mullu.comrade.transfer

import android.media.MediaDataSource
import java.io.RandomAccessFile

/**
 * A file that is still arriving, handed to `MediaPlayer` as if it were finished.
 *
 * `docs/TOGETHER.md` §12: *"even when one person has the file it should be
 * streamed"*. `MediaDataSource.readAt` is called by the decoder on demand, so a
 * source backed by the receiver's own chunk bitmap can hand over the bytes it
 * has and **block** on the ones it does not — which is what turns a two-minute
 * wait for a whole film into a five-second wait for the head of it.
 *
 * ## The three rules this has to keep, in order of how badly they bite
 *
 * **Never return a hole.** [readAt] answers only with bytes that have actually
 * arrived, and stops at the first gap. Handing the decoder zeroes where a chunk
 * is missing does not produce a stall — it produces a corrupt frame, or a
 * decoder that gives up on the file entirely, and neither looks like a transfer
 * problem from a bug report.
 *
 * **Never block forever.** The wait is bounded and re-checks [closed] each time
 * round, so leaving the session, a peer that vanished or a refused transfer all
 * unblock the decoder rather than stranding its thread. `MediaPlayer` gives no
 * way to interrupt a `readAt` in progress, so the only exit is the one this
 * writes for itself.
 *
 * **Never take a lock the writer needs.** The decoder thread waits here while
 * the WebRTC thread writes the chunk being waited for; a monitor either of them
 * could hold across the other's work is a deadlock. So the only shared object is
 * [gate], and the writer touches it for exactly as long as `notifyAll` takes.
 *
 * The bitmap is read without a lock, and it is safe for one specific reason
 * rather than by luck: `FileTransfer.onChunk` **writes the bytes before it
 * records the chunk**, so a flag that is visible here implies the bytes behind
 * it are too — and the `synchronized(gate)` re-check inside the wait is the edge
 * that makes both visible together. A read outside the monitor can only ever be
 * *stale* — it can miss a chunk that has arrived, which costs one more wait
 * slice, and it can never claim one that has not.
 *
 * ## What it is not
 *
 * Not the decision about whether to *play*. That is
 * [ShareReadPolicy]/[ShareDecisions.Tracker.readVerdictAt], applied by the
 * session, and the split matters: this keeps the decoder fed byte by byte, while
 * the session decides whether the playhead should be moving at all and holds it
 * locally when it should not. A source that blocked without the session holding
 * would let `MediaPlayer` sit inside a read while the session went on telling
 * the other device it was playing.
 */
class PartialFileDataSource(
    private val path: String,
    private val totalBytes: Long,
    private val chunkBytes: Int,
    private val tracker: ShareDecisions.Tracker,
    /** How long one wait for bytes may last before the caller gets another look
     *  at [closed]. Short enough that leaving is prompt, long enough that a busy
     *  loop is not what waiting looks like. */
    private val waitSliceMs: Long = 200,
    /** The whole patience of one read. Past this the decoder is told "no bytes
     *  right now" rather than held indefinitely — a transfer that has genuinely
     *  died must surface as a stalled player, not as a wedged thread. The number
     *  lives in [ShareReadPolicy] so the transfer's stall watchdog can be tested
     *  to give up before this expires. */
    private val readTimeoutMs: Long = ShareReadPolicy.READ_PATIENCE_MS,
) : MediaDataSource() {

    /** The one object the two threads share, and only to wake each other. */
    private val gate = Object()

    @Volatile
    private var closed = false

    private var file: RandomAccessFile? = null

    /**
     * A chunk landed. Called by the receiver on its own thread.
     *
     * Deliberately does nothing but wake: any work done here would be work done
     * on the transfer's thread while a decoder waits for it.
     */
    fun onChunkArrived() {
        synchronized(gate) { gate.notifyAll() }
    }

    override fun getSize(): Long = totalBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (position < 0 || position >= totalBytes) return -1
        if (size <= 0) return 0

        val wantEnd = minOf(position + size, totalBytes)
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (true) {
            if (closed) return -1
            val available = contiguousFrom(position, wantEnd)
            if (available > 0) return readFile(position, buffer, offset, available)
            if (System.currentTimeMillis() >= deadline) {
                // Zero, not -1: `-1` is end-of-stream and would make the decoder
                // treat a stalled transfer as a finished file, cutting the track
                // short instead of stopping on it.
                return 0
            }
            synchronized(gate) {
                // Re-checked inside the monitor: the chunk may have landed
                // between the check above and taking it, and a wait entered
                // after its own notification is a wait that only ends on the
                // timeout.
                if (!closed && contiguousFrom(position, wantEnd) == 0) {
                    runCatching { gate.wait(waitSliceMs) }
                }
            }
        }
    }

    /**
     * How many bytes from `position` up to `wantEnd` are actually here.
     *
     * Walks whole chunks, because that is the granularity the bitmap records —
     * a chunk is present or it is not, and asking about a byte inside one that
     * has not arrived has no answer.
     */
    private fun contiguousFrom(position: Long, wantEnd: Long): Int {
        if (chunkBytes <= 0) return 0
        var cursor = position
        while (cursor < wantEnd) {
            val index = (cursor / chunkBytes).toInt()
            if (!tracker.has(index)) break
            cursor = (index + 1).toLong() * chunkBytes
        }
        return (minOf(cursor, wantEnd) - position).coerceAtLeast(0).toInt()
    }

    private fun readFile(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val f = file ?: runCatching { RandomAccessFile(path, "r") }.getOrNull()?.also { file = it }
            ?: return -1
        return runCatching {
            f.seek(position)
            // `read`, not `readFully`: a short read is legal here — `readAt` may
            // return fewer bytes than asked for — and `readFully` would throw on
            // the decoder's thread instead.
            f.read(buffer, offset, length)
        }.getOrDefault(-1)
        // `-1` on failure means end-of-stream to the decoder, which is not what
        // an IO error *is*. It is nonetheless the right answer: the staging file
        // is local and pre-sized, so a read that fails is a file we cannot read
        // at all, and for this source that genuinely is the end. Distinguishing
        // the two would mean throwing `IOException` from a decoder callback, and
        // there is nothing better on the far side of that to catch it.
    }

    /**
     * Called by `MediaPlayer` when it is done, and by the session when it tears
     * down. Both must wake anything waiting, or the decoder thread outlives the
     * session that owned it.
     */
    override fun close() {
        closed = true
        synchronized(gate) { gate.notifyAll() }
        runCatching { file?.close() }
        file = null
    }
}
