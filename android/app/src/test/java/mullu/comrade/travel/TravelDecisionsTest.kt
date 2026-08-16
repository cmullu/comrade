package mullu.comrade.travel

import mullu.comrade.travel.TravelDecisions.Fix
import mullu.comrade.travel.TravelDecisions.Guide
import mullu.comrade.travel.TravelDecisions.Place
import mullu.comrade.travel.TravelDecisions.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Travel tab's reading rules — no Android imports, so
 * the sandbox `kotlinc` lane and `./gradlew test` both pin them.
 *
 * The wire-name tables here (`street_food`, `google_maps`, `eat`) are the
 * strings `comrade_core::travel::PlaceKind::as_str` and friends emit, so a
 * rename on either side is a red test rather than a card that quietly reads
 * "Place" for every restaurant in the city.
 */
class TravelDecisionsTest {

    private fun place(
        name: String = "Britannia & Co.",
        kind: String = "restaurant",
        section: String = "eat",
        distanceM: Int = 400,
        rating: Double? = 4.4,
        reviewCount: Int? = 11_500,
        legendary: Boolean = true,
        source: String = "google_maps",
        openUrl: String? = null,
    ) = Place(
        id = "gm:$name",
        name = name,
        kind = kind,
        section = section,
        lat = 18.9337,
        lon = 72.8434,
        distanceM = distanceM,
        rating = rating,
        reviewCount = reviewCount,
        legendary = legendary,
        address = null,
        cuisine = null,
        note = null,
        source = source,
        openUrl = openUrl,
    )

    private fun guide(
        eat: List<Place> = listOf(place()),
        thingsToDo: List<Place> = emptyList(),
        facts: List<TravelDecisions.Fact> = emptyList(),
        ratingsFrom: String? = "google_maps",
        notice: String? = null,
        fetchedAt: Long = 1_000,
        fromCache: Boolean = false,
        stale: Boolean = false,
    ) = Guide(
        area = null,
        eat = eat,
        thingsToDo = thingsToDo,
        facts = facts,
        ratingsFrom = ratingsFrom,
        notice = notice,
        fetchedAt = fetchedAt,
        fromCache = fromCache,
        stale = stale,
    )

    // ── State ────────────────────────────────────────────────────────────

    @Test
    fun `without permission the screen never claims to be loading`() {
        // The bug this guards is a spinner that never stops because location is
        // off and nothing will ever arrive to stop it.
        val state = TravelDecisions.state(
            hasPermission = false,
            fix = null,
            nowMs = 10_000,
            loading = true,
            guide = null,
            error = null,
        )
        assertEquals(State.NeedsPermission, state)
    }

    @Test
    fun `permission granted but no fix says so rather than spinning forever`() {
        val state = TravelDecisions.state(
            hasPermission = true,
            fix = null,
            nowMs = 10_000,
            loading = false,
            guide = null,
            error = null,
        )
        assertEquals(State.NoFix, state)
    }

    @Test
    fun `a guide already on screen survives its fix going stale`() {
        // The places did not move. Re-asking is offered, not forced.
        val state = TravelDecisions.state(
            hasPermission = true,
            fix = Fix(18.92, 72.83, atMs = 0),
            nowMs = TravelDecisions.FIX_STALE_MS * 10,
            loading = false,
            guide = guide(),
            error = null,
        )
        assertTrue(state is State.Ready)
    }

    @Test
    fun `a stale fix with nothing on screen is not presented as here`() {
        // The guide it would build is about wherever the user was an hour ago.
        val state = TravelDecisions.state(
            hasPermission = true,
            fix = Fix(18.92, 72.83, atMs = 0),
            nowMs = TravelDecisions.FIX_STALE_MS + 1,
            loading = false,
            guide = null,
            error = null,
        )
        assertEquals(State.NoFix, state)
    }

    @Test
    fun `a failure with nothing cached is reported, not shown as an empty guide`() {
        val state = TravelDecisions.state(
            hasPermission = true,
            fix = Fix(18.92, 72.83, atMs = 9_000),
            nowMs = 10_000,
            loading = false,
            guide = null,
            error = "Overpass unavailable",
        )
        assertEquals(State.Failed("Overpass unavailable"), state)
    }

    // ── Freshness and refetching ─────────────────────────────────────────

    @Test
    fun `a fix from the last city is not where you are`() {
        val old = Fix(18.92, 72.83, atMs = 0)
        assertTrue(TravelDecisions.isFresh(old, TravelDecisions.FIX_STALE_MS - 1))
        assertFalse(TravelDecisions.isFresh(old, TravelDecisions.FIX_STALE_MS + 1))
        assertFalse(TravelDecisions.isFresh(null, 0))
        assertFalse(
            "a fix from the future is a broken clock, not a fresh fix",
            TravelDecisions.isFresh(Fix(18.92, 72.83, atMs = 5_000), 1_000),
        )
    }

    @Test
    fun `standing still does not re-query`() {
        val at = Fix(18.9220, 72.8347, atMs = 1_000)
        // ~10 m away: well inside the ~150 m cell the query is rounded to, so
        // the request would be byte-identical.
        val shuffled = Fix(18.92209, 72.83470, atMs = 2_000)
        assertFalse(
            TravelDecisions.shouldRefetch(at, shuffled, guideFetchedAtMs = 1_000, nowMs = 2_000),
        )
    }

    @Test
    fun `walking a few blocks does re-query`() {
        val at = Fix(18.9220, 72.8347, atMs = 1_000)
        val moved = Fix(18.9260, 72.8347, atMs = 2_000) // ~440 m north
        assertTrue(
            TravelDecisions.shouldRefetch(at, moved, guideFetchedAtMs = 1_000, nowMs = 2_000),
        )
    }

    @Test
    fun `the first fix always fetches`() {
        val first = Fix(18.9220, 72.8347, atMs = 1_000)
        assertTrue(TravelDecisions.shouldRefetch(null, first, null, 1_000))
        assertTrue(
            "a fix with no guide behind it is the first fix, whatever the coordinates say",
            TravelDecisions.shouldRefetch(first, first, guideFetchedAtMs = null, nowMs = 1_000),
        )
    }

    @Test
    fun `an old guide is refreshed even when the user has not moved`() {
        val at = Fix(18.9220, 72.8347, atMs = 0)
        assertTrue(
            TravelDecisions.shouldRefetch(
                at,
                at,
                guideFetchedAtMs = 0,
                nowMs = TravelDecisions.GUIDE_FRESH_MS + 1,
            ),
        )
    }

    @Test
    fun `distance matches the formula core ranks with`() {
        // Gateway of India → Chhatrapati Shivaji Terminus, ~2.2 km.
        val d = TravelDecisions.metresBetween(18.9220, 72.8347, 18.9398, 72.8355)
        assertTrue("got $d m", d in 1_900.0..2_400.0)
        assertEquals(0.0, TravelDecisions.metresBetween(18.92, 72.83, 18.92, 72.83), 0.001)
    }

    // ── Reading a card ───────────────────────────────────────────────────

    @Test
    fun `a distance never claims more precision than the blur has`() {
        assertEquals("here", TravelDecisions.formatDistance(8))
        assertEquals("130 m", TravelDecisions.formatDistance(137))
        assertEquals("1.4 km", TravelDecisions.formatDistance(1_430))
        assertEquals("12 km", TravelDecisions.formatDistance(12_400))
    }

    @Test
    fun `a crowd is never rounded down into looking smaller than it is`() {
        assertEquals("11.5k reviews", TravelDecisions.formatReviews(11_500))
        assertEquals("940 reviews", TravelDecisions.formatReviews(940))
        assertEquals("1.2M reviews", TravelDecisions.formatReviews(1_200_000))
        assertNull(TravelDecisions.formatReviews(0))
        assertNull(TravelDecisions.formatReviews(null))
    }

    @Test
    fun `an unrated place says the rating is missing, not that it is zero`() {
        // "0 reviews" about a place with ten thousand of them is a lie the user
        // has no way to catch.
        assertEquals(
            "No rating available",
            TravelDecisions.ratingLine(place(rating = null, reviewCount = null, legendary = false)),
        )
        assertEquals("4.4★ · 11.5k reviews", TravelDecisions.ratingLine(place()))
    }

    @Test
    fun `the badge is core's answer, not a threshold re-derived here`() {
        assertEquals("Legendary", TravelDecisions.badge(place(legendary = true)))
        assertNull(
            "a screen that decided this itself would be a second definition of legendary",
            TravelDecisions.badge(place(rating = 4.9, reviewCount = 20_000, legendary = false)),
        )
    }

    @Test
    fun `every wire kind has a label and an unknown one does not crash the card`() {
        val known = listOf(
            "restaurant", "street_food", "cafe", "bakery", "bar", "sweets",
            "museum", "gallery", "landmark", "worship_site", "park",
            "viewpoint", "beach", "market",
        )
        for (kind in known) {
            assertTrue(kind, TravelDecisions.kindLabel(kind) != "Place")
        }
        assertEquals("Place", TravelDecisions.kindLabel("something_added_next_year"))
        assertEquals("Street food", TravelDecisions.kindLabel("street_food"))
    }

    @Test
    fun `a rated card can always say who said so`() {
        assertEquals("Google Maps", TravelDecisions.sourceLabel("google_maps"))
        assertEquals("OpenStreetMap", TravelDecisions.sourceLabel("openstreetmap"))
        assertNull(TravelDecisions.sourceLabel(null))
    }

    // ── Explaining the ratings situation ─────────────────────────────────

    @Test
    fun `no ratings configured does not read like no legendary places nearby`() {
        val unrated = guide(
            eat = listOf(place(rating = null, reviewCount = null, legendary = false, source = "openstreetmap")),
            ratingsFrom = null,
        )
        val explained = TravelDecisions.ratingsExplainer(unrated)
        assertNotNull(explained)
        assertTrue(explained!!, explained.contains("API key"))

        assertNull(
            "a working guide needs no explanation",
            TravelDecisions.ratingsExplainer(guide()),
        )
    }

    @Test
    fun `a notice from core is shown verbatim rather than second-guessed`() {
        val withNotice = guide(notice = "Google Maps ratings unavailable (status 429)")
        assertEquals(
            "Google Maps ratings unavailable (status 429)",
            TravelDecisions.ratingsExplainer(withNotice),
        )
    }

    @Test
    fun `a stale guide says when it was checked and a current one does not`() {
        val nowMs = 10_000_000L
        val old = guide(fetchedAt = (nowMs - 2 * 60 * 60_000) / 1000, stale = true)
        assertEquals("Checked 2 h ago", TravelDecisions.lastCheckedLine(old, nowMs))
        assertNull(
            "a timestamp on every screen trains people to ignore it on the one that matters",
            TravelDecisions.lastCheckedLine(guide(fetchedAt = nowMs / 1000), nowMs),
        )
    }

    @Test
    fun `an empty guide is distinguishable from a failed one`() {
        assertTrue(TravelDecisions.isEmpty(guide(eat = emptyList())))
        assertFalse(TravelDecisions.isEmpty(guide()))
        assertFalse(
            "facts with no restaurants is a real answer about a quiet place",
            TravelDecisions.isEmpty(
                guide(
                    eat = emptyList(),
                    facts = listOf(TravelDecisions.Fact("Gateway of India", "An arch.", null, 40)),
                ),
            ),
        )
    }

    // ── Opening a place ──────────────────────────────────────────────────

    @Test
    fun `a providers own link wins over a search string`() {
        val withLink = place(openUrl = "https://maps.google.com/?cid=9")
        assertEquals("https://maps.google.com/?cid=9", TravelDecisions.openTarget(withLink))
    }

    @Test
    fun `the fallback carries the coordinate, not just the name`() {
        // A name-only search is how a tap lands at another branch of the same
        // chain in another city.
        val target = TravelDecisions.openTarget(place(openUrl = null))
        assertTrue(target, target.startsWith("geo:18.9337,72.8434"))
        assertTrue(target, target.contains("q=18.9337,72.8434"))
        assertTrue(target, target.contains("Britannia"))
    }
}
