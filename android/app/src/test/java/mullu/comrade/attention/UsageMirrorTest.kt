package mullu.comrade.attention

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The usage reduction — where the raw event stream becomes three integers and
 * everything else is dropped.
 *
 * The overlap cases matter more than they look: `UsageStatsManager` reports
 * app foreground stretches that can genuinely overlap (split screen, a handover
 * the events straddle), and summing them would produce days longer than a day —
 * the kind of number that makes a user distrust every other figure on the card.
 */
class UsageMirrorTest {

    private val base = 1_700_000_000_000L

    private fun stretch(pkg: String, startMin: Int, endMin: Int) =
        UsageMirror.Stretch(pkg, base + startMin * 60_000L, base + endMin * 60_000L)

    @Test
    fun `an empty day is all zeroes`() {
        val r = UsageMirror.reduce(emptyList(), emptySet())
        assertEquals(UsageMirror.DayRollup(0, 0, 0), r)
    }

    @Test
    fun `screen time sums non-overlapping stretches and counts pickups`() {
        val r = UsageMirror.reduce(
            listOf(
                stretch("com.a", 0, 10),
                stretch("com.b", 20, 35),
            ),
            doomPackages = emptySet(),
        )
        assertEquals(25, r.screenMinutes)
        assertEquals(2, r.pickups)
        assertEquals(0, r.doomMinutes)
    }

    @Test
    fun `overlapping stretches count once`() {
        val r = UsageMirror.reduce(
            listOf(
                stretch("com.a", 0, 30),
                stretch("com.b", 10, 20), // fully inside
                stretch("com.c", 25, 40), // partial overlap
            ),
            doomPackages = emptySet(),
        )
        assertEquals("overlaps count once, so a day can never exceed itself", 40, r.screenMinutes)
        assertEquals("every real stretch is still a pickup", 3, r.pickups)
    }

    @Test
    fun `out of order input is handled`() {
        val r = UsageMirror.reduce(
            listOf(
                stretch("com.c", 40, 50),
                stretch("com.a", 0, 10),
                stretch("com.b", 20, 30),
            ),
            doomPackages = emptySet(),
        )
        assertEquals(30, r.screenMinutes)
    }

    @Test
    fun `only user-marked apps count toward doom minutes`() {
        val stretches = listOf(
            stretch("com.feed", 0, 40),
            stretch("com.bank", 45, 50),
        )
        // No list: the line is zero, honestly — Comrade ships no default
        // blacklist, so a user who marked nothing sees screen time only.
        assertEquals(0, UsageMirror.reduce(stretches, emptySet()).doomMinutes)
        assertEquals(40, UsageMirror.reduce(stretches, setOf("com.feed")).doomMinutes)
        // Marking both counts both, with overlap rules applying there too.
        assertEquals(
            45,
            UsageMirror.reduce(stretches, setOf("com.feed", "com.bank")).doomMinutes,
        )
    }

    @Test
    fun `comrade does not count its own screen time against the user`() {
        val r = UsageMirror.reduce(
            listOf(
                stretch("com.feed", 0, 10),
                stretch("mullu.comrade", 20, 60),
            ),
            doomPackages = setOf("mullu.comrade"),
            ownPackage = "mullu.comrade",
        )
        assertEquals(10, r.screenMinutes)
        assertEquals(1, r.pickups)
        // Even if the user somehow marked Comrade itself, it stays excluded:
        // counting the time spent looking at the mirror against the person
        // looking would be both absurd and self-serving.
        assertEquals(0, r.doomMinutes)
    }

    @Test
    fun `flickers shorter than the floor are not pickups`() {
        val r = UsageMirror.reduce(
            listOf(
                UsageMirror.Stretch("com.launcher", base, base + 500),
                UsageMirror.Stretch("com.a", base + 1_000, base + 2_000),
                stretch("com.b", 10, 25),
            ),
            doomPackages = emptySet(),
        )
        assertEquals("a launcher flicker is not a pickup", 1, r.pickups)
        assertEquals(15, r.screenMinutes)
    }

    @Test
    fun `partial minutes round down rather than inflating the day`() {
        val r = UsageMirror.reduce(
            listOf(UsageMirror.Stretch("com.a", base, base + 119_000)),
            doomPackages = emptySet(),
        )
        assertEquals(1, r.screenMinutes)
    }
}
