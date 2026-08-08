package mullu.comrade.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Comrade keeps its own player *and* gains the ability to follow someone
 * else's; this is what decides which one a session gets.
 *
 * Runs under plain `kotlinc` + JUnit with no Android SDK — see `CLAUDE.md`.
 */
class PlaybackModeDecisionTest {

    private fun ownership(
        kind: String,
        haveOurCopy: Boolean = false,
        external: Boolean = false,
    ) = PlaybackModeDecision.ownershipFor(kind, haveOurCopy, external)

    @Test
    fun aFileWeHoldIsAlwaysOurs() {
        // Following an external app for a file we could open ourselves would
        // trade the sleeve, the video surface and an accurate playhead for
        // nothing at all.
        assertEquals(PlaybackOwnership.OURS, ownership("local_file", haveOurCopy = true))
        assertEquals(
            PlaybackOwnership.OURS,
            ownership("local_file", haveOurCopy = true, external = true),
        )
    }

    @Test
    fun aFileWeDoNotHoldIsNotYetAnything() {
        // Not EXTERNAL: the handover exists precisely so this becomes ours.
        // Claiming an external player here would start a session against
        // whatever happened to be playing, which is not what was invited.
        assertNull(ownership("local_file", haveOurCopy = false))
        assertNull(ownership("local_file", haveOurCopy = false, external = true))
    }

    @Test
    fun anHttpsStreamFollowsTheSameRuleAsAFile() {
        assertEquals(PlaybackOwnership.OURS, ownership("stream", haveOurCopy = true))
        assertNull(ownership("stream", haveOurCopy = false))
    }

    @Test
    fun anEmbedIsOursEvenThoughWeDoNotDecodeIt() {
        // We host the WebView, so the picture lands in our window and the
        // scrubber is ours to draw — no external session required.
        assertEquals(PlaybackOwnership.OURS, ownership("youtube"))
        assertEquals(PlaybackOwnership.OURS, ownership("youtube", external = false))
    }

    @Test
    fun aStreamingServiceIsOnlyEverSomebodyElsesPlayer() {
        // There is no version of this where Comrade holds the audio.
        assertEquals(PlaybackOwnership.EXTERNAL, ownership("service", external = true))
        assertEquals(PlaybackOwnership.EXTERNAL, ownership("now_playing", external = true))
    }

    @Test
    fun aServiceTrackWithNothingPlayingIsNoSession() {
        // The honest state: we cannot start their app's playback for them, so
        // there is nothing to follow and nothing to claim.
        assertNull(ownership("service", external = false))
        assertNull(ownership("now_playing", external = false))
        // And holding a local file does not make a service track playable —
        // the same line `play_route` holds in core.
        assertNull(ownership("service", haveOurCopy = true, external = false))
    }

    @Test
    fun aContentKindThisBuildHasNotLearnedGetsNoPlayer() {
        // Release skew: core gained a variant before this frontend did.
        // Refusing beats guessing at a player, the same call `play_flow.mjs`
        // makes for an unknown route.
        for (kind in listOf("", "teleport", "local_File", "LOCAL_FILE")) {
            assertNull(kind, ownership(kind, haveOurCopy = true, external = true))
        }
    }

    @Test
    fun theModeNeverChangesUnderALiveSession() {
        // Swapping the player mid-session means tearing down one playhead and
        // standing another up, and the gap between is a session that is neither
        // following nor playing. A handover finishing does not change the mode
        // — OURS was already the answer; the file simply arrived.
        assertFalse(PlaybackModeDecision.mayChangeMidSession())
    }
}
