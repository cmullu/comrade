package mullu.comrade.attention

/**
 * The **calm feed contract**, phase 0 of `docs/ATTENTION.md`.
 *
 * Comrade ships a public feed, and today it is *accidentally* calm:
 * chronological, no ranking, no autoplay, no engagement counters, no infinite
 * recommendation tail. A wellbeing app that later "improved engagement" there
 * would become the disease it treats, so this file turns the accident into a
 * rule with a test behind it.
 *
 * The rule itself is deliberately tiny and pure — no Android types, no clock
 * of its own — for the same reason [mullu.comrade.NotificationPolicy] is: a
 * decision inlined in a composable can only be checked by reading it and
 * hoping. `now` is passed in.
 *
 * ## What this is not
 *
 * It is not a blocker and not a punishment. Crossing the threshold shows one
 * inline card offering somewhere better to go; dismissing it is honoured for
 * the rest of the sitting, and nothing is recorded about the user having been
 * there (gate 3: no guilt loops, no variable rewards).
 */
object ScrollSitting {

    /**
     * How long one continuous sitting may run before the gentle stop appears.
     *
     * Ten minutes, chosen to be long enough that ordinary catching-up never
     * trips it and short enough to land before a scroll becomes an hour. It is
     * not tuned from telemetry, because there is none to tune from — nothing
     * about feed use leaves the device (and the mirror in phase 1 stores only
     * whole-day rollups, never per-screen dwell).
     */
    const val THRESHOLD_MS: Long = 10 * 60 * 1000L

    /**
     * How long the user must be away from the feed for the next visit to count
     * as a fresh sitting. Switching to a chat and back is the same sitting;
     * coming back after a couple of minutes elsewhere is a new one.
     */
    const val RESET_AFTER_AWAY_MS: Long = 2 * 60 * 1000L

    /**
     * Whether to show the gentle-stop card.
     *
     * @param sittingStartedAt when the current sitting began (0 = not on the feed).
     * @param now current time.
     * @param dismissed whether the user already dismissed the card this sitting.
     */
    fun shouldOffer(sittingStartedAt: Long, now: Long, dismissed: Boolean): Boolean {
        if (dismissed || sittingStartedAt <= 0L) return false
        return now - sittingStartedAt >= THRESHOLD_MS
    }

    /**
     * The sitting-start timestamp to hold after arriving on the feed at [now],
     * given when the user [leftAt] last time (0 if they never have).
     *
     * A short round trip keeps the original start — otherwise bouncing to a
     * chat and back would reset the clock forever and the card could never
     * appear, which is precisely how an app would technically "have" a limit
     * while never enforcing one.
     */
    fun resumeOrRestart(previousStart: Long, leftAt: Long, now: Long): Long = when {
        previousStart <= 0L -> now
        leftAt <= 0L -> previousStart
        now - leftAt >= RESET_AFTER_AWAY_MS -> now
        else -> previousStart
    }
}
