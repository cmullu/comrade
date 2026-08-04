package mullu.comrade.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide download state: the cancel flag the service polls, the
 * self-healing that keeps an "Install" button from pointing at a file the OS
 * reaped out of the cache, and the escape hatch from an install hand-off that
 * never came back.
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
        UpdateDownloads.update(UpdateDownloadState.Ready("0.0.9", APK))
        UpdateDownloads.reconcile(exists = { false }, cached = null)
        assertEquals(UpdateDownloadState.Idle, UpdateDownloads.state.value)
    }

    @Test
    fun aReadyApkThatIsStillThereIsLeftAlone() {
        val ready = UpdateDownloadState.Ready("0.0.9", APK)
        UpdateDownloads.update(ready)
        UpdateDownloads.reconcile(exists = { true }, cached = ready)
        assertEquals(ready, UpdateDownloads.state.value)
    }

    @Test
    fun anInFlightDownloadIsNeverForgottenByTheFileCheck() {
        // Clearing a Downloading state here would abandon a live transfer's
        // progress in the UI while it kept running — and promoting a stale
        // cached APK over it would be worse still.
        val downloading = UpdateDownloadState.Downloading(1_000L, 5_000L)
        UpdateDownloads.update(downloading)
        UpdateDownloads.reconcile(exists = { false }, cached = UpdateDownloadState.Ready("0.0.9", APK))
        assertEquals(downloading, UpdateDownloads.state.value)
    }

    @Test
    fun aFreshProcessPicksUpAnApkItAlreadyDownloaded() {
        // The state is in memory and the APK is on disk, so every process
        // restart starts at Idle with a perfectly good download sitting in the
        // cache. Without this the card asks for the same fifty megabytes again.
        val cached = UpdateDownloadState.Ready("0.0.9", APK)
        UpdateDownloads.update(UpdateDownloadState.Idle)
        UpdateDownloads.reconcile(exists = { true }, cached = cached)
        assertEquals(cached, UpdateDownloads.state.value)
    }

    @Test
    fun aFailedInstallAlsoPicksUpTheApkItAlreadyHas() {
        // "Install cancelled" leaves the APK in place; coming back to the card
        // should offer to install it, not to download it again.
        val cached = UpdateDownloadState.Ready("0.0.9", APK)
        UpdateDownloads.update(UpdateDownloadState.Failed("Install cancelled"))
        UpdateDownloads.reconcile(exists = { true }, cached = cached)
        assertEquals(cached, UpdateDownloads.state.value)
    }

    @Test
    fun anInstallWhoseApkVanishedStopsClaimingToBeInstalling() {
        UpdateDownloads.update(UpdateDownloadState.Installing("0.0.9", APK))
        UpdateDownloads.reconcile(exists = { false }, cached = null)
        assertEquals(UpdateDownloadState.Idle, UpdateDownloads.state.value)
    }

    @Test
    fun aLiveInstallSurvivesReconciliation() {
        // Resuming the settings card while the OS confirmation dialog is up must
        // not rewrite the state behind it.
        val installing = UpdateDownloadState.Installing("0.0.9", APK)
        UpdateDownloads.update(installing)
        UpdateDownloads.reconcile(exists = { true }, cached = UpdateDownloadState.Ready("0.0.9", APK))
        assertEquals(installing, UpdateDownloads.state.value)
    }

    // ── The two phases of Installing ─────────────────────────────────────────

    @Test
    fun aStagingCopyIsTellableFromACommittedHandOff() {
        // The card shows a progress bar for one and "waiting for Android" plus a
        // way out for the other, so conflating them either hides real progress or
        // offers a retry mid-copy.
        val staging = UpdateDownloadState.Installing("0.0.9", APK, bytesStaged = 2_000L, totalBytes = 8_000L)
        val committed = UpdateDownloadState.Installing("0.0.9", APK)
        assertTrue(staging.totalBytes > 0)
        assertEquals(0L, committed.totalBytes)
        assertEquals(0L, committed.bytesStaged)
    }

    @Test
    fun aStagingCopySurvivesReconciliation() {
        // Resuming the settings card mid-copy must not rewrite the progress
        // behind it, and must not promote the cached Ready over a live hand-off.
        val staging = UpdateDownloadState.Installing("0.0.9", APK, bytesStaged = 2_000L, totalBytes = 8_000L)
        UpdateDownloads.update(staging)
        UpdateDownloads.reconcile(exists = { true }, cached = UpdateDownloadState.Ready("0.0.9", APK))
        assertEquals(staging, UpdateDownloads.state.value)
    }

    @Test
    fun aStagingCopyWhoseApkVanishedStopsClaimingToBeInstalling() {
        UpdateDownloads.update(
            UpdateDownloadState.Installing("0.0.9", APK, bytesStaged = 2_000L, totalBytes = 8_000L),
        )
        UpdateDownloads.reconcile(exists = { false }, cached = null)
        assertEquals(UpdateDownloadState.Idle, UpdateDownloads.state.value)
    }

    // ── The way out of a hand-off that never came back ───────────────────────

    @Test
    fun givingUpOnAnInstallGoesBackToReady() {
        // The bug this exists for: Android silently declines to show the install
        // confirmation, no status ever arrives, and the card would otherwise say
        // "Waiting for Android to install it…" until the app is force-stopped.
        UpdateDownloads.update(UpdateDownloadState.Installing("0.0.9", APK))
        UpdateDownloads.dismissInstalling { true }
        assertEquals(UpdateDownloadState.Ready("0.0.9", APK), UpdateDownloads.state.value)
    }

    @Test
    fun givingUpOnAnInstallWhoseApkWentGoesBackToIdle() {
        UpdateDownloads.update(UpdateDownloadState.Installing("0.0.9", APK))
        UpdateDownloads.dismissInstalling { false }
        assertEquals(UpdateDownloadState.Idle, UpdateDownloads.state.value)
    }

    @Test
    fun givingUpDoesNothingWhenNothingIsInstalling() {
        val ready = UpdateDownloadState.Ready("0.0.9", APK)
        UpdateDownloads.update(ready)
        UpdateDownloads.dismissInstalling { true }
        assertEquals(ready, UpdateDownloads.state.value)
    }

    private companion object {
        const val APK = "/cache/updates/comrade-0.0.9.apk"
    }
}
