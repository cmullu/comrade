package mullu.comrade

/** One line waiting in the notification shade: what was said, and when it arrived. */
data class ShadeLine(val text: String, val atMs: Long)

/**
 * How many of a peer's unread lines the shade keeps. Six is what fits an
 * expanded `MessagingStyle` before Android starts eliding anyway, and the point
 * past which the answer a user wants is "open the app", not "scroll a
 * notification".
 */
const val MAX_SHADE_LINES = 6

/**
 * Append [line] to the lines already showing for a peer, keeping the newest
 * [max].
 *
 * Pure, so `MessageShadeTest` can pin the bound — the whole reason this exists
 * is that a notification used to hold *one* line. Every message from a peer
 * reused that peer's notification id with a fresh `setContentText`, so the
 * second message silently erased the first: three messages arriving while the
 * phone sat on a table left a shade entry claiming there was one, and the other
 * two were only discoverable by opening the app. Accumulating instead is what
 * lets `MessagingStyle` show the conversation the way Telegram does.
 */
fun appendShadeLine(
    existing: List<ShadeLine>,
    line: ShadeLine,
    max: Int = MAX_SHADE_LINES,
): List<ShadeLine> = (existing + line).takeLast(max.coerceAtLeast(1))

/**
 * The per-peer unread lines currently posted, so a new message can re-post the
 * whole conversation rather than replace it.
 *
 * In-memory only, and deliberately: these are decrypted message previews, and
 * the app's rule is that they reach the OS notification store and nothing else
 * (see [Notifier]). A process death loses them, which is correct — the
 * notifications went with it.
 *
 * Guarded by its own monitor: [record] runs on the event-drain thread and
 * [clear] on whichever thread opened the conversation.
 */
object MessageShade {
    private val byPeer = mutableMapOf<String, List<ShadeLine>>()

    /** Record [line] for [peer] and return every line now showing, oldest first. */
    @Synchronized
    fun record(peer: String, line: ShadeLine): List<ShadeLine> {
        val next = appendShadeLine(byPeer[peer].orEmpty(), line)
        byPeer[peer] = next
        return next
    }

    /** Forget [peer]'s lines — their notification is gone (opened, or dismissed). */
    @Synchronized
    fun clear(peer: String) {
        byPeer.remove(peer)
    }

    /** Forget everything. For tests, and for a future explicit "clear all". */
    @Synchronized
    fun clearAll() {
        byPeer.clear()
    }
}
