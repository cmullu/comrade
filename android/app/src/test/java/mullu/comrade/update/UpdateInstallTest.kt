package mullu.comrade.update

import android.content.pm.PackageInstaller
import mullu.comrade.update.UpdateInstall.ArchiveFacts
import mullu.comrade.update.UpdateInstall.InstalledFacts
import mullu.comrade.update.UpdateInstall.Reason
import mullu.comrade.update.UpdateInstall.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what may be handed to the installer.
 *
 * Every refusal here is a file the app downloaded and then declined to install.
 * Getting these wrong in the permissive direction means feeding the system
 * installer something that is not the release it claims to be; getting them
 * wrong in the strict direction means an update that can never be installed at
 * all — so both directions are asserted, including the awkward middle where the
 * platform will not tell us who signed the archive.
 */
class UpdateInstallTest {

    private val ours = "aa11"
    private val theirs = "bb22"

    private val installed = InstalledFacts(
        packageName = "mullu.comrade",
        versionCode = 100L,
        signerSha256 = setOf(ours),
    )

    private fun archive(
        packageName: String? = "mullu.comrade",
        versionCode: Long = 101L,
        signers: Set<String>? = setOf(ours),
    ) = ArchiveFacts(
        packageName = packageName,
        versionName = "0.0.$versionCode",
        versionCode = versionCode,
        signerSha256 = signers,
    )

    @Test
    fun theOrdinaryUpgradeIsAllowedAndTheSignatureIsReportedAsChecked() {
        assertEquals(Verdict.Ok(signatureChecked = true), UpdateInstall.verify(archive(), installed))
    }

    @Test
    fun anotherAppsApkIsRefused() {
        assertEquals(
            Verdict.Refused(Reason.WRONG_PACKAGE),
            UpdateInstall.verify(archive(packageName = "com.example.other"), installed),
        )
    }

    @Test
    fun anOlderOrIdenticalBuildIsRefused() {
        // The OS refuses a downgrade anyway; refusing here means the user is
        // told why instead of reading "app not installed".
        assertEquals(
            Verdict.Refused(Reason.NOT_NEWER),
            UpdateInstall.verify(archive(versionCode = 99L), installed),
        )
        assertEquals(
            Verdict.Refused(Reason.NOT_NEWER),
            UpdateInstall.verify(archive(versionCode = 100L), installed),
        )
    }

    @Test
    fun aDifferentSignerIsRefused() {
        assertEquals(
            Verdict.Refused(Reason.SIGNER_MISMATCH),
            UpdateInstall.verify(archive(signers = setOf(theirs)), installed),
        )
    }

    @Test
    fun anArchiveSignedByBothTheOldAndNewKeyIsAllowed() {
        // Key rotation (v3) legitimately presents more than one signer; set
        // equality would refuse exactly the update that carries the rotation.
        assertEquals(
            Verdict.Ok(signatureChecked = true),
            UpdateInstall.verify(archive(signers = setOf(ours, theirs)), installed),
        )
    }

    @Test
    fun anArchiveWithNoSignersAtAllIsRefused() {
        // Empty is different from unknown: an APK that reports zero signing
        // certificates is not signed, and must not reach the installer.
        assertEquals(
            Verdict.Refused(Reason.SIGNER_MISMATCH),
            UpdateInstall.verify(archive(signers = emptySet()), installed),
        )
    }

    @Test
    fun unreadableSignersAreAllowedThroughButNotReportedAsChecked() {
        // Some devices' getPackageArchiveInfo will not surface an archive's
        // certificates. Refusing there would block every legitimate update on
        // those devices; the platform's own same-signature enforcement is what
        // remains, and the caller says so rather than claiming a check it did
        // not make.
        val verdict = UpdateInstall.verify(archive(signers = null), installed)
        assertEquals(Verdict.Ok(signatureChecked = false), verdict)
        assertFalse((verdict as Verdict.Ok).signatureChecked)
    }

    @Test
    fun anUnparseableArchiveIsRefused() {
        assertEquals(Verdict.Refused(Reason.UNREADABLE), UpdateInstall.verify(null, installed))
        assertEquals(
            Verdict.Refused(Reason.UNREADABLE),
            UpdateInstall.verify(archive(packageName = null), installed),
        )
    }

    @Test
    fun packageIsCheckedBeforeVersion() {
        // A wholly different app that happens to have a lower version code must
        // report the *useful* reason.
        assertEquals(
            Verdict.Refused(Reason.WRONG_PACKAGE),
            UpdateInstall.verify(archive(packageName = "com.example.other", versionCode = 1L), installed),
        )
    }

    @Test
    fun sizeMustMatchWhenTheReleasePublishedOne() {
        assertTrue(UpdateInstall.sizeLooksRight(expectedBytes = 1_000L, actualBytes = 1_000L))
        assertFalse(UpdateInstall.sizeLooksRight(expectedBytes = 1_000L, actualBytes = 999L))
        // A truncated download is the case this exists for.
        assertFalse(UpdateInstall.sizeLooksRight(expectedBytes = 1_000L, actualBytes = 0L))
    }

    @Test
    fun anUnknownExpectedSizeCannotFailTheCheck() {
        // 0 means the release listed no single APK asset — "we were never told",
        // not "the file should be empty".
        assertTrue(UpdateInstall.sizeLooksRight(expectedBytes = 0L, actualBytes = 1_000L))
        assertFalse(UpdateInstall.sizeLooksRight(expectedBytes = 0L, actualBytes = 0L))
    }

    // ── Reading the session's answer ─────────────────────────────────────────

    @Test
    fun pendingUserActionIsAnInstructionNotAFailure() {
        // The one the first version got wrong. Treating this as anything other
        // than "start the Intent it handed you" leaves the install in limbo with
        // nothing on screen and no error.
        assertEquals(
            UpdateInstall.Outcome.NeedsConfirmation,
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
        )
    }

    @Test
    fun successAndRefusalAreToldApart() {
        assertEquals(
            UpdateInstall.Outcome.Installed,
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_SUCCESS, null),
        )
        // Declining at the OS dialog is not an error worth deleting the APK for.
        assertEquals(
            UpdateInstall.Outcome.Cancelled,
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_FAILURE_ABORTED, null),
        )
        assertEquals(
            UpdateInstall.Outcome.NoSpace,
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_FAILURE_STORAGE, null),
        )
    }

    @Test
    fun anyOtherFailureCarriesWhateverThePlatformSaid() {
        assertEquals(
            UpdateInstall.Outcome.Failed("Package is invalid"),
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_FAILURE_INVALID, "Package is invalid"),
        )
        // The platform frequently says nothing at all; a blank message must not
        // become a blank line in the settings card.
        assertEquals(
            UpdateInstall.Outcome.Failed(null),
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_FAILURE, "   "),
        )
        assertEquals(
            UpdateInstall.Outcome.Failed(null),
            UpdateInstall.outcomeOf(PackageInstaller.STATUS_FAILURE_CONFLICT, null),
        )
    }

    @Test
    fun anIntentWithNoStatusAtAllIsAFailureNotASuccess() {
        // Int.MIN_VALUE is the "extra absent" sentinel the caller reads with.
        // It must never fall through to Installed — STATUS_SUCCESS is 0, and a
        // missing extra defaulting to 0 would report an install that never ran.
        assertEquals(
            UpdateInstall.Outcome.Failed(null),
            UpdateInstall.outcomeOf(Int.MIN_VALUE, null),
        )
    }
}
