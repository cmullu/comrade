package mullu.comrade.handoff

import mullu.comrade.transfer.FileTransfer
import mullu.comrade.ui.MAX_ATTACHMENT_BYTES
import mullu.comrade.ui.MAX_CAPTION_LENGTH
import mullu.comrade.ui.formatAttachmentSize
import mullu.comrade.ui.mediaKindGlyph
import mullu.comrade.ui.mediaKindLabel

/**
 * Everything about a large-attachment handoff that can be decided without a
 * device, with **zero Android imports** so it runs on the JVM in
 * [mullu.comrade.handoff.HandoffDecisionsTest].
 *
 * All of it exists because **every field of an incoming offer is chosen by the
 * peer**: the size, the content hash, the filename, the MIME type and the
 * caption. The hash becomes a path and the name is drawn on screen, so both are
 * functions with tests here rather than interpolations at the call site.
 * [mullu.comrade.transfer.ShareDecisions] is the same arrangement for the chunk
 * arithmetic, and `comrade_core::handoff::staged_file_name` is the Rust this
 * mirrors.
 */
object HandoffDecisions {

    /**
     * The largest offer this device will take, and the reason is an allocation
     * rather than a policy: [mullu.comrade.transfer.ShareDecisions.Tracker]
     * holds one boolean per chunk, so the peer's `total_bytes` chooses the size
     * of an array on this device. 64 GiB at 16 KiB chunks is 4 Mi booleans —
     * about 4 MB, which a phone can hold — and a petabyte-sized offer would be
     * an out-of-memory kill dressed up as a file transfer.
     *
     * The exit condition: if the tracker ever becomes a bitmap or a run-length
     * set, this can rise or go entirely.
     */
    const val MAX_HANDOFF_BYTES = 64L * 1024 * 1024 * 1024

    /** How long a peer-chosen filename may be once it reaches the screen. */
    const val MAX_DISPLAY_NAME_LENGTH = 120

    /**
     * The filename an incoming handoff is staged under, or null.
     *
     * A verbatim mirror of `comrade_core::handoff::staged_file_name`, including
     * its refusals: **the hash is peer-supplied and this becomes a path**, so
     * anything that is not exactly 64 hex characters gets no name at all and the
     * transfer is refused rather than staged somewhere invented. Case and
     * surrounding space are normalised rather than refused — a peer sending
     * uppercase hex is not attacking anyone.
     *
     * Deliberately *stricter* than
     * [mullu.comrade.transfer.ShareDecisions.incomingFileName], which sanitises
     * and falls back to a fixed name: a watch-together handover that arrives with
     * a cosmetically odd hash should not be abandoned mid-session, but nothing is
     * mid-session here, so refusing costs a person one tap and buys a name that
     * cannot be anything but a hash.
     */
    fun stagedFileName(sha256: String): String? {
        val hash = sha256.trim().lowercase()
        if (hash.length != 64 || !hash.all { it.isDigit() || it in 'a'..'f' }) return null
        return "$hash.part"
    }

    /**
     * Why this offer cannot be taken, or null when it can.
     *
     * Answered *before* anything is staged and before a person is shown a
     * button, so a malformed or hostile offer never becomes an Accept that
     * cannot work.
     */
    fun offerRefusal(totalBytes: Long, chunkBytes: Int, sha256: String): String? {
        if (stagedFileName(sha256) == null) {
            return "That offer's content hash isn't a hash, so there is nothing to check " +
                "the file against."
        }
        if (totalBytes <= 0) return "That offer is empty — there is nothing to receive."
        if (chunkBytes != FileTransfer.CHUNK_BYTES) {
            // Both frontends' senders use exactly this. A different value is
            // either an implementation nobody has agreed with or an attempt to
            // choose how large an array this device allocates.
            return "That offer uses a chunk size this app doesn't speak."
        }
        if (totalBytes > MAX_HANDOFF_BYTES) {
            return "That offer is ${formatAttachmentSize(totalBytes)} — larger than this " +
                "device will take in one transfer."
        }
        return null
    }

    /**
     * The sender's filename, as **display text only**.
     *
     * Never a path: the bytes land under the content hash
     * ([stagedFileName]), and this is what a person reads to decide whether they
     * want it. Separators and control characters go — a name is not allowed to
     * draw a fake directory or a fake second line — and the length is bounded so
     * a 4 KB "filename" cannot push the Accept button off the card.
     */
    fun displayFileName(raw: String): String {
        val cleaned = raw
            .replace('\\', '/')
            .substringAfterLast('/')
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .trim()
            .take(MAX_DISPLAY_NAME_LENGTH)
        return cleaned.ifEmpty { "Unnamed file" }
    }

    /**
     * A peer's caption, as display text. The core already bounds and strips
     * captions on the hosted path; a handoff's caption never goes through the
     * core, so it is bounded here instead of being trusted.
     */
    fun displayCaption(raw: String): String = raw
        .filter { it.code >= 0x20 || it == '\n' }
        .trim()
        .take(MAX_CAPTION_LENGTH)

    /**
     * Which road this file takes, in words, for the preview sheet.
     *
     * Both roads are described by what they cost the person rather than by their
     * mechanism, and the peer-to-peer one states **both** of its failure modes up
     * front — being online is not optional and a direct path is not guaranteed —
     * because they are discovered otherwise as a progress bar that never moves.
     * `AUDIT.md` §8.1 puts 30-40% of remote pairs behind CGNAT, so this is a
     * common outcome and not an edge case.
     */
    fun routeNote(peerToPeer: Boolean): String = if (peerToPeer) {
        "Too big for the usual road, so this goes straight to their device: nothing else " +
            "holds a copy. They have to be online now, and it only works if there's a " +
            "direct path between you — on mobile networks there often isn't."
    } else {
        "Goes the usual way — encrypted, held for them, so it arrives even if they're " +
            "offline right now."
    }

    /**
     * What to add when a peer-to-peer handoff cannot happen.
     *
     * The one thing that reliably still works, said plainly, because "transfer
     * failed" leaves a person with nothing to do next.
     */
    fun fallbackAdvice(): String =
        "Anything under ${formatAttachmentSize(MAX_ATTACHMENT_BYTES)} goes the usual way " +
            "instead, which doesn't need a direct path."

    /** "🎬 Video · holiday.mp4 · 412.5 MB" — the one line an offer card leads with. */
    fun offerHeadline(mimeType: String, fileName: String, totalBytes: Long): String =
        "${mediaKindGlyph(mimeType)} ${mediaKindLabel(mimeType)} · " +
            "${displayFileName(fileName)} · ${formatAttachmentSize(totalBytes)}"
}
