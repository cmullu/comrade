package mullu.comrade.ride

/**
 * The ride-mode decisions that must not live in a Composable — pure, with no
 * Android imports, so the JVM lane can pin them.
 *
 * The protocol is **not** here: the catalog, the caps, the urgency verdicts and
 * the replay TTL live in `comrade_core::ride`, so every frontend inherits one
 * answer (the ADR-2 argument `together/TogetherDecisions.kt` already makes).
 * What is genuinely local is everything about *reading at speed*: which one
 * card a glance gets when several signals are competing, when a card is too old
 * to still be a claim about the road, what is spoken into a helmet and what
 * only buzzes, and which music controls are safe under a driver's thumb.
 *
 * Two rules shape all of it:
 *
 * **One card.** A driver gets a single glance, so this module never returns a
 * list — it ranks. A fresh quick phrase interrupts (it is about the riders,
 * now); the route suggestion is what the card falls back to when the interruption
 * has had its [QUICK_SHOW_MS]; and a card older than [CARD_STALE_MS] is not
 * shown dimmed or crossed out, it is gone, because "left in 400 m" from three
 * minutes ago is not stale information — it is false.
 *
 * **The screen may be ignored.** Everything urgent is also spoken and buzzed
 * ([spokenLine], [hapticPattern]), because the failure mode of a ride surface
 * is a driver reading it. The wire's urgency verdict decides those, not the
 * layout — two phones must not disagree about whether "pull over" is worth a
 * buzz, which is why the verdict travels from core rather than being re-derived
 * here.
 */
object RideDecisions {

    /** How long a quick phrase owns the card before the route suggestion
     *  returns. Long enough to be seen at a glance cadence, short enough that
     *  an interrupted turn instruction comes back while it still matters. */
    const val QUICK_SHOW_MS: Long = 8_000

    /** When a card stops being shown at all. Matches the wire acceptance TTL
     *  (`RIDE_TTL_SECS` in `comrade_core::ride`) deliberately: the receiver
     *  refuses anything older than a minute, and the screen must not keep a
     *  card alive longer than the wire would have admitted it. */
    const val CARD_STALE_MS: Long = 60_000

    /** The two seats. Local presentation only — the wire is symmetric, and who
     *  is driving is a fact about the motorcycle, not the protocol. */
    enum class Role { Driver, Pillion }

    /** One received signal, as the screen holds it. Field names mirror
     *  `RideSignalDto`; strings are the wire names, so this file and core can
     *  never disagree about what a phrase is called. */
    data class Sig(
        val kind: String,
        val phrase: String?,
        val maneuver: String?,
        val distanceM: Long?,
        val note: String?,
        val urgency: String,
        val peerLabel: String,
        val atMs: Long,
    )

    /** What one glance shows. */
    data class Card(
        /** The big line — a phrase, or a maneuver with its distance. */
        val headline: String,
        /** The small line under it — the landmark note, or who said it. */
        val detail: String?,
        /** The maneuver glyph, or null for a quick phrase. */
        val glyph: String?,
        /** `"urgent"`, `"notice"` or `"info"` — drives colour weight only;
         *  haptics and speech were decided on arrival, not on render. */
        val urgency: String,
    )

    // ── The catalog, rendered ────────────────────────────────────────────────

    /** Display words for a quick phrase's wire name, or null for a phrase this
     *  build does not know — the caller drops the signal whole rather than
     *  showing a blank card (the same release-skew rule core's parser applies
     *  on receive). */
    fun phraseText(phrase: String?): String? = when (phrase) {
        "slow_down" -> "Slow down"
        "pull_over" -> "Pull over"
        "fuel_soon" -> "Fuel soon"
        "break_please" -> "Break, please"
        "all_good" -> "All good"
        else -> null
    }

    /** The arrow a maneuver draws beside its words, or null for an unknown
     *  one. Text glyphs rather than icon resources so this stays testable and
     *  the two frontends that will eventually draw them share one table. */
    fun maneuverGlyph(maneuver: String?): String? = when (maneuver) {
        "left" -> "←"
        "right" -> "→"
        "straight" -> "↑"
        "u_turn" -> "⤺"
        "stop" -> "■"
        "hazard" -> "⚠"
        else -> null
    }

    /** Display words for a maneuver's wire name, or null for an unknown one. */
    fun maneuverText(maneuver: String?): String? = when (maneuver) {
        "left" -> "Left"
        "right" -> "Right"
        "straight" -> "Straight on"
        "u_turn" -> "U-turn"
        "stop" -> "Stop"
        "hazard" -> "Hazard"
        else -> null
    }

    /**
     * "400 m" under a kilometre, "1.2 km" above it, "9 km" once a decimal stops
     * meaning anything. Metres are what the composer sends; the reader gets the
     * shortest string that is still the truth.
     */
    fun distanceText(distanceM: Long): String = when {
        distanceM < 1_000 -> "$distanceM m"
        distanceM < 10_000 -> {
            val tenths = (distanceM + 50) / 100 // round to 0.1 km
            if (tenths % 10 == 0L) "${tenths / 10} km" else "${tenths / 10}.${tenths % 10} km"
        }
        else -> "${(distanceM + 500) / 1_000} km"
    }

    /** The card's big line for a route signal: "Left in 400 m", or just "Left"
     *  when no distance was named. Null when the maneuver is unknown. */
    fun routeHeadline(maneuver: String?, distanceM: Long?): String? {
        val words = maneuverText(maneuver) ?: return null
        return if (distanceM == null) words else "$words in ${distanceText(distanceM)}"
    }

    // ── The one card ─────────────────────────────────────────────────────────

    /**
     * What the glance shows at [nowMs], given the newest quick phrase and the
     * newest route suggestion that have arrived. Ranking, not merging:
     * a fresh quick phrase owns the card for [QUICK_SHOW_MS], the route
     * suggestion is what it falls back to, and anything past [CARD_STALE_MS]
     * is gone entirely. Unknown wire names (release skew) are skipped, never
     * blank-carded.
     */
    fun card(nowMs: Long, quick: Sig?, route: Sig?): Card? {
        val liveQuick = quick?.takeIf {
            nowMs - it.atMs < QUICK_SHOW_MS && phraseText(it.phrase) != null
        }
        if (liveQuick != null) {
            return Card(
                headline = phraseText(liveQuick.phrase)!!,
                detail = liveQuick.peerLabel,
                glyph = null,
                urgency = liveQuick.urgency,
            )
        }
        val liveRoute = route?.takeIf {
            nowMs - it.atMs < CARD_STALE_MS && routeHeadline(it.maneuver, it.distanceM) != null
        } ?: return null
        return Card(
            headline = routeHeadline(liveRoute.maneuver, liveRoute.distanceM)!!,
            detail = liveRoute.note,
            glyph = maneuverGlyph(liveRoute.maneuver),
            urgency = liveRoute.urgency,
        )
    }

    // ── Interrupting a helmet ────────────────────────────────────────────────

    /**
     * What is spoken aloud when a signal arrives, or null for silence. Spoken
     * on the driver's side only — the pillion is looking at their phone by
     * definition, and two phones announcing the same thing is an echo. Info
     * stays silent everywhere: "all good" is an answer, not an alert.
     */
    fun spokenLine(role: Role, sig: Sig): String? {
        if (role != Role.Driver || sig.urgency == "info") return null
        return when (sig.kind) {
            "quick" -> phraseText(sig.phrase)
            "route" -> listOfNotNull(
                routeHeadline(sig.maneuver, sig.distanceM),
                sig.note,
            ).joinToString(", ").ifEmpty { null }
            else -> null
        }
    }

    /**
     * The vibration for a signal's arrival, or null for none. Urgent is two
     * long pulses a glove can feel through a tank bag; notice is one short
     * tap; info is nothing. Keyed on the wire's urgency verdict alone, so a
     * new phrase gets the right buzz by being classified in core, not by being
     * added here too.
     */
    fun hapticPattern(urgency: String): LongArray? = when (urgency) {
        "urgent" -> longArrayOf(0, 400, 150, 400)
        "notice" -> longArrayOf(0, 120)
        else -> null
    }

    // ── Music at speed ───────────────────────────────────────────────────────

    /**
     * Which together-session controls a seat gets. The pillion keeps the full
     * player (their hands are free — the Together tab is one tap away and this
     * only mirrors its verbs); the driver gets exactly two verbs, play-pause
     * and next, drawn glove-sized, because a scrubber under a driver's thumb
     * is a swerve. Previous is not a riding decision — nobody rewinds a song
     * at 80 km/h on purpose, and a mis-tap that restarts the track also
     * restarts it on the other seat's phone.
     */
    data class Controls(
        val playPause: Boolean,
        val next: Boolean,
        val previous: Boolean,
        val scrubber: Boolean,
    )

    fun controlsFor(role: Role): Controls = when (role) {
        Role.Driver -> Controls(playPause = true, next = true, previous = false, scrubber = false)
        Role.Pillion -> Controls(playPause = true, next = true, previous = true, scrubber = true)
    }

    /**
     * Whether this seat's screen offers the phrase grid. **Both do** — the
     * driver answering a glance with "all good" is half the point, and five
     * big buttons is the one composer a gloved thumb can hit without looking.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mayComposeQuick(role: Role): Boolean = true

    /**
     * Whether this seat's screen offers the route composer. **The pillion's
     * does; the driver's does not.** Not a wire permission — the protocol is
     * symmetric and either side may send one — but a maneuver, a distance chip
     * and a typed landmark is three deliberate taps and a keyboard, and the
     * person doing that should not be the person steering.
     */
    fun mayComposeRoute(role: Role): Boolean = role == Role.Pillion

    // ── The composer's own tables (pillion side) ─────────────────────────────

    /** Every phrase the composer offers, in the order the grid draws them —
     *  safety first, comfort second, reassurance last. Wire names; the grid
     *  labels each with [phraseText]. */
    val PHRASES: List<String> =
        listOf("slow_down", "pull_over", "fuel_soon", "break_please", "all_good")

    /** Every maneuver the composer offers, reading order matching a road:
     *  directions first, then the stops and the warning. */
    val MANEUVERS: List<String> =
        listOf("left", "right", "straight", "u_turn", "stop", "hazard")

    /** The distances the composer offers as chips, in metres. Chips rather
     *  than a keyboard because the pillion is on the same motorcycle — 100 m
     *  to 5 km covers "now" to "start looking", and anything further belongs
     *  in the note or the chat. */
    val DISTANCES_M: List<Long> = listOf(100, 400, 1_000, 2_000, 5_000)

    /** The note cap the composer enforces while typing — must equal
     *  `RIDE_NOTE_MAX_CHARS` in `comrade_core::ride`, which refuses (not
     *  truncates) anything longer on send and on receive. */
    const val NOTE_MAX_CHARS: Int = 80
}
