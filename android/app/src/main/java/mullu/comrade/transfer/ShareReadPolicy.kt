package mullu.comrade.transfer

/**
 * What a reader sitting at a playhead should do about the bytes it has: start,
 * keep going, or hold and wait for more.
 *
 * `docs/TOGETHER.md` §12, and the port of `comrade_core::share::read_verdict`.
 * Core owns the thresholds; the frontend owns the bitmap, because the frontend
 * owns the bytes — the same division `chunks_to_send` already makes.
 *
 * **Zero imports of any kind, deliberately**, unlike its neighbour
 * [ShareDecisions], which pulls in `uniffi.comrade_core` for the refusal types
 * and therefore needs Gradle's generated bindings to compile at all. This file
 * runs under bare `kotlinc` + JUnit in the sandbox (`CLAUDE.md`), which on the
 * priority frontend is the difference between "checked" and "CI will tell us".
 * Keep it that way: the moment something here needs a generated type, the rule
 * it encodes stops being checkable before CI.
 *
 * A port rather than a call over the bridge because the answer is wanted inside
 * `MediaDataSource.readAt`, on the decoder's own thread, once per read. There is
 * an FFI spelling of the same policy for the cases that can afford it
 * (`Comrade::share_read_verdict`); a divergence between the two is a red test
 * here, not a stutter in the field.
 */
object ShareReadPolicy {

    /**
     * How much *uninterrupted* audio to have in hand before starting. Mirrors
     * `SHARE_PLAYABLE_RUNWAY_MS`.
     */
    const val PLAYABLE_RUNWAY_MS: Long = 5_000

    /**
     * How little may be left before a running reader stops. Mirrors
     * `SHARE_STALL_FLOOR_MS`.
     *
     * The gap between the two thresholds is the whole point rather than a spare
     * knob. A reader that stopped and started at the same number would sit on it
     * and chatter; one that stopped only at exactly zero would run out inside
     * the decoder rather than on a decision. About one chunk at a typical music
     * bitrate, which is also the quantum the runway is measured in.
     */
    const val STALL_FLOOR_MS: Long = 1_000

    /**
     * The three answers, and there is deliberately no fourth.
     *
     * None of them means "tell the peer", which is how §10's rule is kept by
     * construction rather than by discipline: there is nothing to send. A
     * frontend that wanted a wire signal for a stall would have to argue for it
     * here first, which is the argument this enum exists to force.
     */
    enum class Verdict {
        /** The bytes are there to begin here — or to resume from a [Hold].
         *
         * Permission, not an instruction: it says the *transfer* is no longer a
         * reason not to play, and says nothing about whether the session wants
         * to. A player the person paused, or one a `pause` command from the peer
         * paused, stays paused. */
        START,

        /** Keep going. Less than starting would have needed, more than stopping
         *  for is worth. */
        CONTINUE,

        /**
         * Stop the player **on this device only** and wait for bytes.
         *
         * Concretely: pause the local player and keep asking for chunks. Report
         * it with `together_report_position(pos, playing = false, latency)` — a
         * heartbeat, which the peer's next `sync_verdict` reads as "they are not
         * playing" and answers by holding rather than correcting.
         *
         * It is **not** `together_set_state(.., playing: false, ..)`. That is a
         * command: it takes the next sequence number and it pauses the other
         * person, which is precisely the ping-pong §10 rules out. This is the
         * single easiest thing to get wrong in the whole feature.
         */
        HOLD,
    }

    /**
     * The whole starve policy, from the three facts a caller can see.
     *
     * 1. Everything from here to the end of the file has arrived — play, at any
     *    runway. Nothing more is coming, so waiting waits forever.
     * 2. At least [PLAYABLE_RUNWAY_MS] of uninterrupted audio — play.
     * 3. Already playing, and at least [STALL_FLOOR_MS] left — keep going. This
     *    is the whole of the hysteresis: what is too little to start on is not
     *    too little to carry on with.
     * 4. Otherwise hold.
     *
     * **It is not a prediction**, and a caller built on a misreading of that
     * will look buggy in a way that is not the caller's fault. Every input is
     * about bytes already here; nothing in it knows the transfer's throughput,
     * and a [Verdict.CONTINUE] on five seconds of runway is not a promise about
     * the sixth. A transfer that stops dead still stalls — one second of
     * playback later per second of runway that was banked. The answer only
     * changes when a chunk arrives or the playhead moves, so a reader that asks
     * once has learned nothing.
     *
     * @param playing whether sound is coming out of this device *right now* —
     *   not whether the session wants to play. It only picks which threshold
     *   applies, because the cost of stopping a running player is a stutter and
     *   the cost of not starting one is a wait.
     */
    fun verdict(playing: Boolean, runwayMs: Long, tailComplete: Boolean): Verdict {
        if (tailComplete || runwayMs >= PLAYABLE_RUNWAY_MS) {
            return if (playing) Verdict.CONTINUE else Verdict.START
        }
        if (playing && runwayMs >= STALL_FLOOR_MS) return Verdict.CONTINUE
        return Verdict.HOLD
    }
}
