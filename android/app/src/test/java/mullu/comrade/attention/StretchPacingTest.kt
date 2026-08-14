package mullu.comrade.attention

import mullu.comrade.attention.StretchPacing.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pacing semantics to the same behaviour the desktop player's
 * `stretch_view.test.mjs` pins, so the two frontends cannot drift on when a
 * side changes or when the break is over.
 */
class StretchPacingTest {

    // Deliberately small numbers so the boundary arithmetic reads at a glance.
    private val routine = listOf(
        StretchPacing.Step("neck-tilt", "Neck tilt", "Ear to shoulder.", seconds = 10, mirrored = true),
        StretchPacing.Step("chin-tuck", "Chin tuck", "Chin back.", seconds = 5, mirrored = false),
    )

    @Test
    fun mirroredStepsFlattenToLeftThenRight() {
        val segments = StretchPacing.segments(routine)
        assertEquals(
            listOf("neck-tilt" to Side.LEFT, "neck-tilt" to Side.RIGHT, "chin-tuck" to null),
            segments.map { it.key to it.side },
        )
        assertEquals(25, StretchPacing.totalSeconds(segments))
    }

    @Test
    fun aMalformedStepIsSkippedNotACrash() {
        // The routine crosses an FFI bridge; one bad row must not cost the break.
        val segments = StretchPacing.segments(
            listOf(
                StretchPacing.Step("", "x", "", seconds = 10, mirrored = false),
                StretchPacing.Step("ok", "", "", seconds = 0, mirrored = false),
                StretchPacing.Step("good", "Good", "Fine.", seconds = 8, mirrored = false),
            ),
        )
        assertEquals(listOf("good"), segments.map { it.key })
        assertNull("no segments: no player, not an empty one", StretchPacing.at(emptyList(), 5.0))
    }

    @Test
    fun theClockWalksSegmentBoundariesAndNeverSkipsASide() {
        val segments = StretchPacing.segments(routine)
        val opening = StretchPacing.at(segments, 0.0)!!
        assertEquals(0, opening.index)
        assertEquals(10.0, opening.remainingSeconds, 1e-9)

        // The last instant of the left side still belongs to the left side…
        assertEquals(0, StretchPacing.at(segments, 9.5)!!.index)
        // …and the boundary second starts the right side.
        val right = StretchPacing.at(segments, 10.0)!!
        assertEquals(1, right.index)
        assertEquals(Side.RIGHT, right.segment.side)

        val tuck = StretchPacing.at(segments, 22.0)!!
        assertEquals("chin-tuck", tuck.segment.key)
        assertEquals(2.0, tuck.intoSeconds, 1e-9)
        assertEquals(false, tuck.done)
    }

    @Test
    fun pastTheEndPinsToTheFinalStretchAtRest() {
        val segments = StretchPacing.segments(routine)
        for (elapsed in listOf(25.0, 26.0, 9_999.0)) {
            val end = StretchPacing.at(segments, elapsed)!!
            assertTrue(end.done)
            assertEquals(segments.lastIndex, end.index)
            assertEquals(0.0, end.remainingSeconds, 1e-9)
        }
        // A nonsense clock reads as the opening frame, not a crash mid-break.
        assertEquals(0, StretchPacing.at(segments, Double.NaN)!!.index)
        assertEquals(0, StretchPacing.at(segments, -3.0)!!.index)
    }

    @Test
    fun progressRunsZeroToOneAndClampsAtBothEnds() {
        val segments = StretchPacing.segments(routine)
        assertEquals(0f, StretchPacing.progress(segments, 0.0))
        assertEquals(0.5f, StretchPacing.progress(segments, 12.5))
        assertEquals(1f, StretchPacing.progress(segments, 25.0))
        assertEquals(1f, StretchPacing.progress(segments, 9_999.0))
        assertEquals(0f, StretchPacing.progress(segments, -4.0))
        assertEquals(0f, StretchPacing.progress(emptyList(), 10.0))
    }
}
