package mullu.comrade.ui

/**
 * Pure display-name helpers, kept free of Compose/Android imports so plain
 * JVM unit tests (`DisplayNameTest`) can pin the precedence rules.
 */

/** Short display form of an npub: `npub1abcd…wxyz`. */
fun shortNpub(npub: String): String =
    if (npub.length > 16) "${npub.take(10)}…${npub.takeLast(4)}" else npub

/**
 * Display title for a peer, in trust order:
 *  1. the alias *you* chose for the contact (yours, can't be spoofed),
 *  2. the `@handle` *they* published — a self-declared claim, so screens
 *     showing it keep the key visible alongside,
 *  3. the shortened key.
 */
fun peerTitle(peer: String, alias: String?, username: String?): String {
    alias?.takeIf { it.isNotBlank() }?.let { return it }
    username?.takeIf { it.isNotBlank() }?.let { return "@${it.removePrefix("@")}" }
    return shortNpub(peer)
}

/**
 * Deterministic palette index for a peer avatar: the same key always gets
 * the same colour, on every device — identity-stable like the key itself.
 */
fun avatarColorIndex(seed: String, paletteSize: Int): Int {
    if (paletteSize <= 0) return 0
    var hash = 0
    for (c in seed) hash = (hash * 31 + c.code) and 0x7FFFFFFF
    return hash % paletteSize
}

/** Rough relative timestamp for list rows. */
fun relativeTime(epochSecs: Long): String {
    val d = System.currentTimeMillis() / 1000 - epochSecs
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        else -> "${d / 86_400}d"
    }
}

/**
 * Wall-clock send time for a message bubble: "14:05". Bubbles sit under a
 * day separator, so the clock alone is unambiguous — unlike a relative
 * "3d" that drifts while the screen is open. [zone] is injectable so the
 * rule is unit-testable.
 */
fun clockTime(
    epochSecs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String = java.time.Instant.ofEpochSecond(epochSecs).atZone(zone).toLocalTime()
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** `mm:ss` duration for a connected call (e.g. a call-history row). */
fun formatCallDuration(totalSecs: Long): String {
    val secs = totalSecs.coerceAtLeast(0)
    return "%d:%02d".format(secs / 60, secs % 60)
}

/**
 * Human day header for journal grouping: "Today", "Yesterday", else
 * "12 Jul 2026". [zone] is injectable so the rule is unit-testable.
 */
fun dayLabel(
    epochSecs: Long,
    nowEpochSecs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val day = java.time.Instant.ofEpochSecond(epochSecs).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochSecond(nowEpochSecs).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH),
        )
    }
}

/**
 * What to say under a comrade's name, in the order that answers the user's
 * actual question ("can I reach them right now?"):
 *  1. [Online] — the whole point,
 *  2. [LastSeen] — when we last saw them, if we ever did (rendered
 *     Telegram-style, see [lastSeenOf]),
 *  3. [AwaitingReciprocity] — the honest reason a chosen comrade may never
 *     light up: presence is mutual by construction, so until they choose us
 *     back their device tells us nothing, and no amount of waiting changes
 *     that. Showing a bare grey dot here would read as "they're ignoring
 *     you", which is a different (and wrong) thing.
 *  4. [NeverSeen] — chosen, reciprocated, simply never caught online. Reads
 *     as Telegram's deliberately vague "last seen recently", because that is
 *     the truth: we have a beacon-shaped hole, not a timestamp.
 */
enum class PresenceLabel { Online, LastSeen, AwaitingReciprocity, NeverSeen }

/** Which [PresenceLabel] a comrade row should carry. The caller resolves the string. */
fun presenceLabelOf(online: Boolean, lastSeenAt: Long, peerMarkedUs: Boolean): PresenceLabel = when {
    online -> PresenceLabel.Online
    lastSeenAt > 0L -> PresenceLabel.LastSeen
    !peerMarkedUs -> PresenceLabel.AwaitingReciprocity
    else -> PresenceLabel.NeverSeen
}

/**
 * How a "last seen" moment reads, in the vocabulary people already know from
 * Telegram: a fresh sighting is relative ("just now", "5 minutes ago"), an
 * older one is a wall clock you can act on ("at 5:30 PM", "yesterday at
 * 5:30 PM"), and a distant one is a date.
 *
 * A sealed model rather than a formatted string so the *rule* is pure and
 * unit-testable while the wording stays in `strings.xml` — the pattern
 * [PresenceLabel] already follows. The time/date fragments are pre-formatted
 * here (they need the zone and the device's clock preference, not a
 * translation).
 */
sealed interface LastSeen {
    /** Within the last minute. */
    data object JustNow : LastSeen

    /** Within the hour. */
    data class MinutesAgo(val minutes: Int) : LastSeen

    /** Earlier today, e.g. `5:30 PM`. */
    data class TodayAt(val time: String) : LastSeen

    /** Yesterday, e.g. `5:30 PM`. */
    data class YesterdayAt(val time: String) : LastSeen

    /** Earlier this week, e.g. `Monday` + `5:30 PM`. */
    data class WeekdayAt(val weekday: String, val time: String) : LastSeen

    /** Longer ago, e.g. `12 Jul` (this year) or `12 Jul 2025`. */
    data class OnDate(val date: String) : LastSeen
}

/** Cutoffs for [lastSeenOf], named so the rule reads as prose. */
private const val A_MINUTE = 60L
private const val AN_HOUR = 3600L

/**
 * Classify [lastSeenAt] (unix seconds) relative to [nowEpochSecs].
 *
 * [use24Hour] comes from the device's own clock setting
 * (`DateFormat.is24HourFormat`), so a user who reads 17:30 never gets
 * 5:30 PM; both [zone] and [locale] are injectable for the same reason
 * [dayLabel] takes a zone — the rules are then testable without a device.
 *
 * A timestamp in the future (clock skew between two phones is ordinary)
 * reads as "just now" rather than a negative age.
 */
fun lastSeenOf(
    lastSeenAt: Long,
    nowEpochSecs: Long,
    use24Hour: Boolean = false,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.getDefault(),
): LastSeen {
    val age = nowEpochSecs - lastSeenAt
    if (age < A_MINUTE) return LastSeen.JustNow
    if (age < AN_HOUR) return LastSeen.MinutesAgo((age / A_MINUTE).toInt())

    val seen = java.time.Instant.ofEpochSecond(lastSeenAt).atZone(zone)
    val now = java.time.Instant.ofEpochSecond(nowEpochSecs).atZone(zone)
    val time = seen.toLocalTime().format(
        java.time.format.DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale),
    )
    val day = seen.toLocalDate()
    val today = now.toLocalDate()
    return when {
        day == today -> LastSeen.TodayAt(time)
        day == today.minusDays(1) -> LastSeen.YesterdayAt(time)
        // Within the last week the weekday is the most useful handle; beyond
        // that it stops being unambiguous ("Monday" — which Monday?).
        day.isAfter(today.minusDays(7)) -> LastSeen.WeekdayAt(
            seen.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", locale)),
            time,
        )
        day.year == today.year -> LastSeen.OnDate(
            seen.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", locale)),
        )
        else -> LastSeen.OnDate(
            seen.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale)),
        )
    }
}
