package mullu.comrade.attention

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StretchGuideTest {

    @Test
    fun `a held stretch is eased into, not snapped to`() {
        // A figure that arrives instantly demonstrates the one thing a stretch
        // routine most wants not to be done.
        assertEquals(0f, StretchGuide.extent("hold", 0, 20), 1e-6f)
        assertTrue(StretchGuide.extent("hold", 1, 20) > 0.3f)
        assertEquals(1f, StretchGuide.extent("hold", 3, 20), 1e-6f)
        assertEquals(1f, StretchGuide.extent("hold", 19, 20), 1e-6f)
        // A step shorter than the travel still completes inside itself.
        assertEquals(1f, StretchGuide.extent("hold", 1, 1), 1e-6f)
    }

    @Test
    fun `a cycling movement starts at rest, goes out and comes back`() {
        assertEquals(0f, StretchGuide.extent("cycle", 0, 20), 1e-6f)
        assertEquals(1f, StretchGuide.extent("cycle", 2, 20), 1e-5f)
        assertEquals(0f, StretchGuide.extent("cycle", 4, 20), 1e-5f)
        // And it repeats for as long as the step lasts.
        assertEquals(1f, StretchGuide.extent("cycle", 6, 20), 1e-5f)
    }

    @Test
    fun `stillness is the instruction, and nonsense is still too`() {
        for (t in listOf(0, 1, 5, 19)) {
            assertEquals(0f, StretchGuide.extent("still", t, 20), 1e-6f)
        }
        // A NaN in a Compose transform is a figure that vanishes, so an
        // unrecognised motion must be still rather than undefined.
        assertEquals(0f, StretchGuide.extent("levitate", 3, 20), 1e-6f)
    }

    @Test
    fun `no timing produces a value outside the range the figure can use`() {
        val timings = listOf(
            Triple("hold", -5, 20),
            Triple("hold", 3, 0),
            Triple("cycle", 3, 0),
            Triple("cycle", -1, -1),
            Triple("still", 0, 0),
        )
        for ((motion, into, seconds) in timings) {
            val e = StretchGuide.extent(motion, into, seconds)
            assertTrue("$motion/$into/$seconds gave $e", !e.isNaN() && e >= 0f && e <= 1f)
        }
        // And across a whole step, every second is in range.
        var t = 0
        while (t <= 30) {
            val e = StretchGuide.extent("cycle", t, 20)
            assertTrue("cycle at ${t}s gave $e", e >= 0f && e <= 1f)
            t += 1
        }
    }

    @Test
    fun `the figure leans toward the side the instruction names`() {
        // A sign error here would have the figure lean away from the named side,
        // which is the whole reason one drawing serves a left step and a right.
        val left = StretchGuide.pose("neck", "left", "hold", 20, 20)
        val right = StretchGuide.pose("neck", "right", "hold", 20, 20)
        assertTrue(left.rotation < 0f)
        assertTrue(right.rotation > 0f)
        assertEquals(-right.rotation, left.rotation, 1e-6f)
        assertEquals(-right.offsetX, left.offsetX, 1e-6f)
    }

    @Test
    fun `a centred movement does not lean, but it still moves`() {
        val pose = StretchGuide.pose("shoulders", "center", "cycle", 2, 20)
        assertEquals(0f, pose.rotation, 1e-6f)
        assertEquals(0f, pose.offsetX, 1e-6f)
        assertTrue("a shoulder roll shown as a still figure is a picture of sitting there",
            abs(pose.offsetY) > 0f)
    }

    @Test
    fun `the eyes step holds the figure completely still`() {
        // The step whose instruction is to look away from the screen must not put
        // the one animated thing in the room on the screen.
        val pose = StretchGuide.pose("eyes", "center", "still", 10, 20)
        assertEquals(0f, pose.rotation, 1e-6f)
        assertEquals(0f, pose.offsetX, 1e-6f)
        assertEquals(0f, pose.offsetY, 1e-6f)
        assertEquals(1f, pose.scale, 1e-6f)
    }

    @Test
    fun `every pose the engine can ask for is finite and small`() {
        // The guide is a diagram of a movement, not a cartoon of one. The bounds
        // are the same ones `stretch_view.test.mjs` asserts on the desktop copy,
        // so the two figures cannot drift into leaning differently.
        val parts = listOf("neck", "shoulders", "arms", "back", "hips", "legs", "eyes", "whole")
        for (part in parts) {
            for (side in listOf("left", "right", "center")) {
                for (motion in listOf("hold", "cycle", "still")) {
                    val pose = StretchGuide.pose(part, side, motion, 7, 20)
                    val where = "$part/$side/$motion"
                    assertTrue(where, !pose.rotation.isNaN() && abs(pose.rotation) <= 20f)
                    assertTrue(where, !pose.offsetX.isNaN() && abs(pose.offsetX) <= 15f)
                    assertTrue(where, !pose.offsetY.isNaN() && abs(pose.offsetY) <= 15f)
                    assertTrue(where, pose.scale in 0.9f..1.1f)
                }
            }
        }
    }

    @Test
    fun `a part this build does not know still draws a figure`() {
        // A routine added to the engine before this file catches up must render
        // as a gentle nod, not as a crash on the calm screen.
        val pose = StretchGuide.pose("tail", "center", "hold", 5, 20)
        assertTrue(!pose.rotation.isNaN() && !pose.offsetY.isNaN())
        assertTrue(pose.scale > 1f)
    }
}
