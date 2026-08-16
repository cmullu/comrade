package mullu.comrade.travel

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every Travel-tab decision that must not live in a Composable — pure, with no
 * Android imports, so the JVM lane can pin it.
 *
 * The split is the one `together/TogetherDecisions.kt` established and
 * `CLAUDE.md` argues for: the tested half is the only half this sandbox checks
 * before CI, so the reasoning goes here and `ui/TravelScreen.kt` turns the
 * answers into widgets.
 *
 * **What is deliberately *not* here**: what "legendary" means, how places are
 * ranked, and how far a coordinate is blurred before it leaves the device.
 * Those live in `comrade_core::travel` so three frontends inherit one answer —
 * the same ADR-2 argument the ride and together modules make. A badge computed
 * here would be a second definition of legendary, and the day the thresholds
 * move only one of them would follow.
 *
 * What genuinely is local is everything about *reading a guide on a street*:
 * when a location fix is too old to still be a claim about where you are,
 * whether you have moved far enough to be worth another lookup, and how a
 * distance, a rating and a review count read at a glance.
 */
object TravelDecisions {

    /**
     * How old a location fix may be and still be treated as where the user is.
     *
     * Ten minutes is a long time to stand still and a short walk otherwise. The
     * failure this guards is the one that makes the whole tab useless: a cached
     * fix from the last city, presented without hesitation as "here".
     */
    const val FIX_STALE_MS: Long = 10 * 60 * 1000

    /**
     * How far the user must have moved before a new fix is worth another
     * lookup.
     *
     * Comfortably above `comrade_core::travel::QUERY_PRECISION`'s ~150 m cell:
     * inside a cell the query is byte-identical and the core's own cache would
     * answer it anyway, so a lower threshold would only spend battery on
     * arriving at the same result.
     */
    const val MOVED_ENOUGH_M: Double = 250.0

    /**
     * How long the screen keeps its own answer before offering to look again.
     * Shorter than the core's six-hour cache on purpose — this decides when the
     * *refresh affordance* appears, not when the data is thrown away.
     */
    const val GUIDE_FRESH_MS: Long = 30 * 60 * 1000

    /** Earth's mean radius, for [metresBetween]. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** One place, as the screen holds it. Field names mirror `TravelPlaceDto`. */
    data class Place(
        val id: String,
        val name: String,
        /** Wire name from `comrade_core::travel::PlaceKind` — `"street_food"`, … */
        val kind: String,
        /** `"eat"` or `"do"`, decided in core. */
        val section: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Int,
        val rating: Double?,
        val reviewCount: Int?,
        val legendary: Boolean,
        val address: String?,
        val cuisine: String?,
        val note: String?,
        /** `"google_maps"`, `"openstreetmap"`, `"wikipedia"`. */
        val source: String,
        val openUrl: String?,
    )

    /** One thing worth knowing about where you are standing. */
    data class Fact(
        val title: String,
        val text: String,
        val url: String?,
        val distanceM: Int?,
    )

    /** A whole guide, as the screen holds it. Mirrors `TravelGuideDto`. */
    data class Guide(
        val area: String?,
        val eat: List<Place>,
        val thingsToDo: List<Place>,
        val facts: List<Fact>,
        val ratingsFrom: String?,
        val notice: String?,
        val fetchedAt: Long,
        val fromCache: Boolean,
        val stale: Boolean,
    )

    /** A location fix, with the moment it was taken. */
    data class Fix(val lat: Double, val lon: Double, val atMs: Long)

    /** What the screen is doing. */
    sealed interface State {
        /** Location permission has not been granted, so there is nothing to ask about. */
        data object NeedsPermission : State

        /** Permission is granted and a fix has not arrived yet. */
        data object Locating : State

        /** Permission granted, no fix at all — location is off, or indoors with no signal. */
        data object NoFix : State

        /** A fix is in hand and the guide is being fetched. */
        data object Loading : State

        /** A guide, possibly with a notice attached. */
        data class Ready(val guide: Guide) : State

        /** The lookup failed and there was nothing cached to fall back on. */
        data class Failed(val message: String) : State
    }

    /**
     * The screen's state, from the four things that decide it.
     *
     * Ordered so the *reason* the user is looking at an empty screen is always
     * the most specific one available: no permission beats no fix beats no
     * guide. A screen that says "loading" when location is switched off is a
     * screen that never stops loading.
     */
    fun state(
        hasPermission: Boolean,
        fix: Fix?,
        nowMs: Long,
        loading: Boolean,
        guide: Guide?,
        error: String?,
    ): State = when {
        !hasPermission -> State.NeedsPermission
        // A guide already on screen outlives a fix going stale: the places did
        // not move. Re-asking is offered ([shouldRefetch]), not forced.
        guide != null && !loading -> State.Ready(guide)
        loading && fix == null -> State.Locating
        fix == null -> State.NoFix
        // A fix from an hour ago, with nothing on screen to justify it, is not
        // a claim about where the user is — and a guide built on one would be
        // about wherever they were then.
        !isFresh(fix, nowMs) && !loading -> State.NoFix
        loading -> State.Loading
        error != null -> State.Failed(error)
        else -> State.Loading
    }

    /** Is this fix recent enough to be a claim about where the user is now? */
    fun isFresh(fix: Fix?, nowMs: Long): Boolean =
        fix != null && nowMs - fix.atMs in 0..FIX_STALE_MS

    /**
     * Should a new fix trigger another lookup?
     *
     * Three reasons and no others: there is no guide yet, the user has walked
     * [MOVED_ENOUGH_M], or the guide on screen has aged past [GUIDE_FRESH_MS].
     * A pull-to-refresh bypasses this entirely — it is the user saying "ask
     * again" and does not need to be second-guessed.
     */
    fun shouldRefetch(
        lastFix: Fix?,
        newFix: Fix,
        guideFetchedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (lastFix == null || guideFetchedAtMs == null) return true
        if (metresBetween(lastFix.lat, lastFix.lon, newFix.lat, newFix.lon) >= MOVED_ENOUGH_M) {
            return true
        }
        return nowMs - guideFetchedAtMs >= GUIDE_FRESH_MS
    }

    /** Great-circle distance in metres — the same formula core ranks with. */
    fun metresBetween(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * A distance the way somebody standing on a street reads it.
     *
     * Rounded to 10 m under a kilometre, because a guide that says "137 m" is
     * claiming a precision the coarse-origin blur does not have — see
     * `comrade_core::travel`'s module header.
     */
    fun formatDistance(metres: Int): String = when {
        metres < 20 -> "here"
        metres < 1_000 -> "${(metres / 10) * 10} m"
        metres < 10_000 -> "${roundTo1dp(metres / 1_000.0)} km"
        else -> "${(metres / 1_000.0).roundToInt()} km"
    }

    /**
     * How many people said so, short enough for a card.
     *
     * The review count is the load-bearing half of "legendary", so it is never
     * hidden behind the star average and never rounded to something that could
     * read as smaller than it is — 11 500 becomes "11.5k", not "11k".
     */
    fun formatReviews(count: Int?): String? = when {
        count == null || count <= 0 -> null
        count < 1_000 -> "$count reviews"
        count < 1_000_000 -> "${roundTo1dp(count / 1_000.0)}k reviews"
        else -> "${roundTo1dp(count / 1_000_000.0)}M reviews"
    }

    /**
     * The line under a place's name: stars, crowd, and — when there are none —
     * an honest sentence rather than a blank.
     *
     * "No rating here" names *this app's* situation, not the restaurant's: an
     * unrated row usually means the ratings provider is not configured, and
     * saying "0 reviews" about a place with ten thousand of them would be a lie
     * the user has no way to catch.
     */
    fun ratingLine(place: Place): String {
        val stars = place.rating ?: return "No rating available"
        val crowd = formatReviews(place.reviewCount)
        return if (crowd == null) "${roundTo1dp(stars)}★" else "${roundTo1dp(stars)}★ · $crowd"
    }

    /** The badge text for a place that earns one, decided in core. */
    fun badge(place: Place): String? = if (place.legendary) "Legendary" else null

    /** Human label for a wire kind. Unknown kinds fall back to "Place". */
    fun kindLabel(kind: String): String = when (kind) {
        "restaurant" -> "Restaurant"
        "street_food" -> "Street food"
        "cafe" -> "Café"
        "bakery" -> "Bakery"
        "bar" -> "Bar"
        "sweets" -> "Sweets"
        "museum" -> "Museum"
        "gallery" -> "Gallery"
        "landmark" -> "Landmark"
        "worship_site" -> "Place of worship"
        "park" -> "Park"
        "viewpoint" -> "Viewpoint"
        "beach" -> "Beach"
        "market" -> "Market"
        else -> "Place"
    }

    /** Who said it, for the attribution line under a rating. */
    fun sourceLabel(source: String?): String? = when (source) {
        "google_maps" -> "Google Maps"
        "openstreetmap" -> "OpenStreetMap"
        "wikipedia" -> "Wikipedia"
        else -> null
    }

    /**
     * The line that explains the ratings situation, or `null` when there is
     * nothing to explain.
     *
     * This is the difference between "nothing legendary is near you" and "this
     * app cannot see ratings at all", and it must never render as the same
     * screen — which is why the guide carries `ratingsFrom` and `notice`
     * separately and this is the one place that combines them.
     */
    fun ratingsExplainer(guide: Guide): String? = when {
        guide.notice != null -> guide.notice
        guide.ratingsFrom == null && guide.eat.isNotEmpty() ->
            "These places came from OpenStreetMap. Ratings need a Google Places API key."
        else -> null
    }

    /**
     * Where a tap on a place should go.
     *
     * The provider's own canonical link wins whenever there is one. The `geo:`
     * fallback carries the coordinate *and* the name as a label — never a
     * search string assembled from the name alone, which is how a tap ends up
     * at a different branch of the same chain in another city.
     */
    fun openTarget(place: Place): String =
        place.openUrl ?: "geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}(${place.name})"

    /**
     * A "last checked" line for a guide that is being shown despite being old.
     *
     * Only for a stale guide: a current one needs no apology, and a timestamp
     * on every screen trains people to ignore it on the screen that matters.
     */
    fun lastCheckedLine(guide: Guide, nowMs: Long): String? {
        if (!guide.stale && !guide.fromCache) return null
        val ageMs = nowMs - guide.fetchedAt * 1000
        if (ageMs < 0) return null
        return when {
            ageMs < 60_000 -> "Checked just now"
            ageMs < 60 * 60_000 -> "Checked ${ageMs / 60_000} min ago"
            ageMs < 24 * 60 * 60_000 -> "Checked ${ageMs / (60 * 60_000)} h ago"
            else -> "Checked ${ageMs / (24 * 60 * 60_000)} d ago"
        }
    }

    /**
     * Whether the empty state should say "nothing here" or "nothing loaded".
     *
     * A guide with facts but no places is a real answer about a quiet place; a
     * guide with nothing at all, from a provider that answered, is too. Both
     * are different from a failure, which [State.Failed] carries.
     */
    fun isEmpty(guide: Guide): Boolean =
        guide.eat.isEmpty() && guide.thingsToDo.isEmpty() && guide.facts.isEmpty()

    /** One decimal place, without a trailing `.0`. */
    private fun roundTo1dp(value: Double): String {
        val rounded = (value * 10).roundToLong() / 10.0
        return if (abs(rounded - rounded.roundToLong()) < 0.05) {
            rounded.roundToLong().toString()
        } else {
            rounded.toString()
        }
    }
}
