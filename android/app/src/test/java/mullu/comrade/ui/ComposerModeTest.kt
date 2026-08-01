package mullu.comrade.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the composer's capture-mode rules: what a device offers, what the round
 * button falls back to, and where the swap control goes next.
 *
 * Mirrored in `app/test/composer_mode_test.dart` so the two frontends cycle in
 * the same order.
 */
class ComposerModeTest {

    @Test
    fun availableModesFollowCapabilityInCycleOrder() {
        assertEquals(
            listOf(CaptureMode.Voice, CaptureMode.Photo, CaptureMode.Video),
            availableCaptureModes(canRecordAudio = true, canTakePhoto = true, canRecordVideo = true),
        )
        assertEquals(
            listOf(CaptureMode.Voice),
            availableCaptureModes(canRecordAudio = true, canTakePhoto = false, canRecordVideo = false),
        )
        assertEquals(
            listOf(CaptureMode.Photo, CaptureMode.Video),
            availableCaptureModes(canRecordAudio = false, canTakePhoto = true, canRecordVideo = true),
        )
    }

    @Test
    fun aDeviceWithNoRecorderAndNoCameraOffersNothing() {
        val none = availableCaptureModes(
            canRecordAudio = false,
            canTakePhoto = false,
            canRecordVideo = false,
        )
        assertEquals(emptyList<CaptureMode>(), none)
        // No mode at all → the caller shows a plain (disabled) Send rather than
        // a control that fails on tap.
        assertNull(effectiveCaptureMode(CaptureMode.Voice, none))
        assertNull(nextCaptureMode(CaptureMode.Voice, none))
    }

    @Test
    fun aStaleModeFallsBackToSomethingTheDeviceHas() {
        val cameraOnly = listOf(CaptureMode.Photo, CaptureMode.Video)
        // Voice is the default, but this device has no microphone — the button
        // must not sit there doing nothing.
        assertEquals(CaptureMode.Photo, effectiveCaptureMode(CaptureMode.Voice, cameraOnly))
        assertEquals(CaptureMode.Video, effectiveCaptureMode(CaptureMode.Video, cameraOnly))
        assertEquals(CaptureMode.Photo, effectiveCaptureMode(null, cameraOnly))
    }

    @Test
    fun theSwapControlNamesTheModeItMovesToAndWrapsAround() {
        val all = listOf(CaptureMode.Voice, CaptureMode.Photo, CaptureMode.Video)
        assertEquals(CaptureMode.Photo, nextCaptureMode(CaptureMode.Voice, all))
        assertEquals(CaptureMode.Video, nextCaptureMode(CaptureMode.Photo, all))
        // Wraps, so the cycle never dead-ends on the last mode.
        assertEquals(CaptureMode.Voice, nextCaptureMode(CaptureMode.Video, all))
    }

    @Test
    fun thereIsNothingToSwapToWithOneMode() {
        // One mode → the caller hides the swap control entirely, rather than
        // showing a button that swaps a mode for itself.
        assertNull(nextCaptureMode(CaptureMode.Voice, listOf(CaptureMode.Voice)))
    }

    @Test
    fun swappingFromAStaleModeStillAdvances() {
        val cameraOnly = listOf(CaptureMode.Photo, CaptureMode.Video)
        // Corrected to Photo first, so the swap goes to Video rather than
        // returning null or bouncing back to an unavailable Voice.
        assertEquals(CaptureMode.Video, nextCaptureMode(CaptureMode.Voice, cameraOnly))
    }

    @Test
    fun cyclingEveryModeReturnsToTheStart() {
        val all = listOf(CaptureMode.Voice, CaptureMode.Photo, CaptureMode.Video)
        var mode: CaptureMode? = CaptureMode.Voice
        repeat(all.size) { mode = nextCaptureMode(mode, all) }
        assertEquals(CaptureMode.Voice, mode)
    }
}
