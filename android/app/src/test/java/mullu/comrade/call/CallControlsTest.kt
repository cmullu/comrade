package mullu.comrade.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression these pin: the in-call bar used to be a straight `Row` of
 * every control, which on a video call was six 60dp buttons on a screen that
 * fits five. A `Row` does not wrap — the overflow simply runs off the right
 * edge — and the last child was End call, so a video call could not be hung up
 * from the call screen.
 *
 * Every case below is really the same assertion twice: the bar fits, and End
 * is in it. The vectors are shared with `desktop/ui/call_decisions.test.mjs`
 * and `app/test/call_screen_test.dart`, so the three frontends cannot drift on
 * which controls the bar holds.
 */
class CallControlsTest {

    private data class Case(val name: String, val layout: CallControlLayout)

    private val cases = listOf(
        Case("video call, camera on", layoutCallControls(video = true, cameraOn = true)),
        Case("video call, camera off", layoutCallControls(video = true, cameraOn = false)),
        Case("voice call", layoutCallControls(video = false)),
        Case(
            "desktop video call (no routes, no camera flip)",
            layoutCallControls(
                video = true,
                hasAudioRoutes = false,
                hasCameraSwitch = false,
            ),
        ),
    )

    @Test
    fun `End call is always the last control in the bar`() {
        for ((name, layout) in cases) {
            assertEquals(name, CallControl.HANGUP, layout.primary.last())
            assertFalse("$name: End call must never be hidden behind the dock", CallControl.HANGUP in layout.dock)
        }
    }

    @Test
    fun `the bar never holds more controls than fit`() {
        for ((name, layout) in cases) {
            assertTrue(
                "$name: bar holds ${layout.primary.size}, more than the $MAX_PRIMARY_CALL_CONTROLS that fit",
                layout.primary.size <= MAX_PRIMARY_CALL_CONTROLS,
            )
        }
    }

    @Test
    fun `no control is in both the bar and the dock`() {
        for ((name, layout) in cases) {
            assertTrue("$name: duplicate in the bar", layout.primary.size == layout.primary.toSet().size)
            assertTrue("$name: duplicate in the dock", layout.dock.size == layout.dock.toSet().size)
            assertTrue(
                "$name: ${layout.primary.intersect(layout.dock.toSet())} is in both",
                layout.primary.intersect(layout.dock.toSet()).isEmpty(),
            )
        }
    }

    @Test
    fun `the bar is ordered the way Telegram orders it`() {
        assertEquals(
            listOf(
                CallControl.CAMERA,
                CallControl.MIC,
                CallControl.SPEAKER,
                CallControl.MORE,
                CallControl.HANGUP,
            ),
            layoutCallControls(video = true).primary,
        )
        // A voice call drops the camera; everything else keeps its place, so
        // mute does not move under your thumb when a call gains video.
        assertEquals(
            listOf(
                CallControl.MIC,
                CallControl.SPEAKER,
                CallControl.MORE,
                CallControl.HANGUP,
            ),
            layoutCallControls(video = false).primary,
        )
    }

    @Test
    fun `screen share is offered on voice calls too`() {
        // The point of it: a voice call that starts sharing grows a picture it
        // did not have.
        assertTrue(CallControl.SCREEN_SHARE in layoutCallControls(video = false).dock)
        assertTrue(CallControl.SCREEN_SHARE in layoutCallControls(video = true).dock)
    }

    @Test
    fun `a camera flip is only offered when there is a camera running`() {
        assertTrue(
            CallControl.SWITCH_CAMERA in layoutCallControls(video = true, cameraOn = true).dock,
        )
        assertFalse(
            CallControl.SWITCH_CAMERA in layoutCallControls(video = true, cameraOn = false).dock,
        )
        assertFalse(
            CallControl.SWITCH_CAMERA in layoutCallControls(video = false).dock,
        )
        assertFalse(
            CallControl.SWITCH_CAMERA in
                layoutCallControls(video = true, hasCameraSwitch = false).dock,
        )
    }

    @Test
    fun `a platform with no audio routes gets no route button`() {
        val desktop = layoutCallControls(video = true, hasAudioRoutes = false, hasCameraSwitch = false)
        assertEquals(
            listOf(CallControl.CAMERA, CallControl.MIC, CallControl.MORE, CallControl.HANGUP),
            desktop.primary,
        )
        assertEquals(listOf(CallControl.SCREEN_SHARE, CallControl.CHAT), desktop.dock)
    }

    @Test
    fun `the overflow button appears exactly when there is something behind it`() {
        for ((name, layout) in cases) {
            assertEquals(
                "$name: a ⋮ that opens nothing, or a dock nothing opens",
                layout.dock.isNotEmpty(),
                CallControl.MORE in layout.primary,
            )
        }
    }
}
