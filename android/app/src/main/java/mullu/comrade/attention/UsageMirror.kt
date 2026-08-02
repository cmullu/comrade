package mullu.comrade.attention

/**
 * Pure reduction of raw Android usage events into the one-day rollup Comrade
 * stores — phase 1 of `docs/ATTENTION.md`, the *mirror*.
 *
 * This file is deliberately Android-free so the reduction can be unit-tested
 * on a plain JVM (`UsageMirrorTest`); [UsageStatsReader] is the thin shim that
 * reads the real `UsageStatsManager` and calls in here.
 *
 * ## The data-minimisation rule this file exists to enforce
 *
 * The OS can tell us every app the user opened and exactly when. Almost none
 * of that is needed to show someone their own day, and all of it would be a
 * uniquely sensitive thing to keep. So the raw events are reduced **here, in
 * memory** to three integers — screen minutes, pickups, and minutes in the
 * apps the *user themself* tagged — and then dropped. Nothing per-app, and no
 * timeline, is ever returned from this layer, let alone persisted.
 *
 * That is why the rollup is computed rather than the events stored: a design
 * that "just kept the events for later analysis" would be one refactor away
 * from being a surveillance log of someone's phone, sitting in the app that
 * promised it would never build one.
 */
object UsageMirror {

    /**
     * One foreground stretch of one app, as read from the OS. Only the fields
     * the reduction actually needs — this is not a general event model, and
     * deliberately has nowhere to put a window title or a URL.
     */
    data class Stretch(val packageName: String, val startMs: Long, val endMs: Long)

    /** The rollup Comrade stores. Three integers, nothing else. */
    data class DayRollup(val screenMinutes: Int, val pickups: Int, val doomMinutes: Int)

    /**
     * A foreground stretch shorter than this is not a real visit. Tapping
     * through a launcher, or an app briefly resuming behind a dialog, would
     * otherwise inflate the pickup count into nonsense.
     */
    const val MIN_STRETCH_MS = 3_000L

    /**
     * Reduce [stretches] (any order, possibly overlapping) to a day rollup.
     *
     * @param doomPackages the user's own scroll-trap list. Empty is normal and
     *   means `doomMinutes == 0` — Comrade ships no default list, so a user who
     *   has not tagged anything sees screen time only, which is honest rather
     *   than guessed.
     * @param ownPackage this app's own package, excluded from every figure.
     *   Comrade counting the time someone spends *in Comrade* against them —
     *   including the time spent looking at this very card — would be both
     *   absurd and self-serving.
     */
    fun reduce(
        stretches: List<Stretch>,
        doomPackages: Set<String>,
        ownPackage: String? = null,
    ): DayRollup {
        val real = stretches
            .filter { it.endMs - it.startMs >= MIN_STRETCH_MS }
            .filter { it.packageName != ownPackage }
        // Screen time is the union of the stretches, not their sum: two apps
        // the OS reports as overlapping (split screen, or a handover the
        // events straddle) are still one span of a person looking at a phone,
        // and adding them would let a day exceed 24 hours.
        val screenMs = unionMillis(real.map { it.startMs to it.endMs })
        val doomMs = unionMillis(
            real.filter { it.packageName in doomPackages }.map { it.startMs to it.endMs },
        )
        return DayRollup(
            screenMinutes = (screenMs / 60_000L).toInt(),
            pickups = real.size,
            doomMinutes = (doomMs / 60_000L).toInt(),
        )
    }

    /** Total length covered by [ranges], counting overlaps once. */
    private fun unionMillis(ranges: List<Pair<Long, Long>>): Long {
        if (ranges.isEmpty()) return 0L
        val sorted = ranges.sortedBy { it.first }
        var total = 0L
        var cursorStart = sorted[0].first
        var cursorEnd = sorted[0].second
        for ((start, end) in sorted.drop(1)) {
            if (start > cursorEnd) {
                total += cursorEnd - cursorStart
                cursorStart = start
                cursorEnd = end
            } else if (end > cursorEnd) {
                cursorEnd = end
            }
        }
        return total + (cursorEnd - cursorStart)
    }
}
