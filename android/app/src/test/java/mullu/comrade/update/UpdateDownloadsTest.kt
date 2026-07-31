package mullu.comrade.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide download state: the cancel flag the service polls, and the
 * self-healing that keeps an "Install" button from pointing at a file the OS
 * reaped out of the cache.
 */
class UpdateDownloadsTest {

    @After
    fun reset() {
        // A process-wide object, so a leaked state or cancel flag would make the
        // next test lie.
        UpdateDownloads.update(UpdateDownloadState.Idle)
        UpdateDownloads.clearCancel()
    }

    @Test
    fun cancelIsStickyUntilCleared() {
        assertFalse(UpdateDownloads.isCancelled())
        UpdateDownloads.cancel()
        assertTrue(UpdateDownloads.isCancelled())
        // Sticky on purpose: the download loop polls it between reads, so a flag
        // that cleared itself could be missed entirely.
        assertTrue(UpdateDownloads.isCancelled())
        UpdateDownloads.clearCancel()
        assertFalse(UpdateDownloads.isCancelled())
    }

    @Test
    fun aReadyApkThatVanishedGoesBackToOfferingTheDownload() {
        UpdateDownloads.update(UpdateDownloadState.Ready("0.0.9", "/cache/updates/comrade-0.0.9.apk"))
        UpdateDownloads.forgetIfGone { false }
        assertEquals(UpdateDownloadState.Idle, UpdateDownloads.state.value)
    }

    @Test
    fun aReadyApkThatIsStillThereIsLeftAlone() {
        val ready = UpdateDownloadState.Ready("0.0.9", "/cache/updates/comrade-0.0.9.apk")
        UpdateDownloads.update(ready)
        UpdateDownloads.forgetIfGone { true }
        assertEquals(ready, UpdateDownloads.state.value)
    }

    @Test
    fun anInFlightDownloadIsNeverForgottenByTheFileCheck() {
        // Only a Ready state names a file; clearing a Downloading one here would
        // abandon a live transfer's progress in the UI while it kept running.
        val downloading = UpdateDownloadState.Downloading(1_000L, 5_000L)
        UpdateDownloads.update(downloading)
        UpdateDownloads.forgetIfGone { false }
        assertEquals(downloading, UpdateDownloads.state.value)
    }
}
