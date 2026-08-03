package mullu.comrade.update

import android.content.pm.PackageInstaller

/**
 * Whether a downloaded APK may be handed to the installer, and what the
 * installer's answer means.
 *
 * Pure, so `UpdateInstallTest` can drive every refusal and every session status
 * on the host JVM. The only Android reference is [PackageInstaller]'s `STATUS_*`
 * constants, which are `public static final int` literals and so are inlined at
 * compile time — nothing here loads an Android class at runtime. The side that
 * reads these facts off the archive (and off ourselves) is [UpdateInstaller].
 *
 * ## Why this exists when the OS already checks
 *
 * Android refuses to install an update signed by a different key than the
 * installed app — that guarantee is the platform's and this code cannot improve
 * on it. What it can do is refuse *earlier* and *say why*: an APK that turns out
 * to be a different package, an older build, or signed by someone else should
 * be deleted with an explanation, not handed to a system dialog that reports
 * "app not installed" and leaves the user guessing. The pre-flight also means a
 * substituted download never reaches the installer at all.
 */
object UpdateInstall {

    /** What the downloaded archive claims to be. */
    data class ArchiveFacts(
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        /**
         * SHA-256 of each signing certificate in the archive, lower-case hex —
         * or **null** when the platform would not tell us (older releases of
         * `getPackageArchiveInfo` do not collect certificates for every archive).
         * Null is "unknown", never "none": treating it as "none" would refuse
         * every legitimate update on those devices.
         */
        val signerSha256: Set<String>?,
    )

    /** What is installed right now — us. */
    data class InstalledFacts(
        val packageName: String,
        val versionCode: Long,
        val signerSha256: Set<String>,
    )

    sealed class Verdict {
        /**
         * Installable. [signatureChecked] is false when the archive's
         * certificates could not be read at all, in which case the platform's
         * own same-signature enforcement is the only thing standing between the
         * user and a substituted build — which is why the caller says so in the
         * log rather than claiming a check it did not make.
         */
        data class Ok(val signatureChecked: Boolean) : Verdict()

        data class Refused(val reason: Reason) : Verdict()
    }

    enum class Reason {
        /** Not Comrade. Nothing to do with an update. */
        WRONG_PACKAGE,

        /** Same or older build — the OS would refuse the downgrade anyway. */
        NOT_NEWER,

        /**
         * Signed by a different key. The strongest signal available that the
         * file is not the release it claims to be.
         */
        SIGNER_MISMATCH,

        /** The archive could not be parsed as an APK at all. */
        UNREADABLE,
    }

    fun verify(archive: ArchiveFacts?, installed: InstalledFacts): Verdict {
        if (archive?.packageName == null) return Verdict.Refused(Reason.UNREADABLE)
        if (archive.packageName != installed.packageName) return Verdict.Refused(Reason.WRONG_PACKAGE)
        if (archive.versionCode <= installed.versionCode) return Verdict.Refused(Reason.NOT_NEWER)
        val signers = archive.signerSha256
        if (signers != null) {
            // Any shared signer is enough: a rotated key (v3 rotation, or an
            // APK signed by both old and new) legitimately presents more than
            // one, and requiring set equality would refuse those updates.
            if (signers.isEmpty()) return Verdict.Refused(Reason.SIGNER_MISMATCH)
            val shared = signers.any { it in installed.signerSha256 }
            if (!shared) return Verdict.Refused(Reason.SIGNER_MISMATCH)
        }
        return Verdict.Ok(signatureChecked = signers != null)
    }

    /**
     * Whether a finished download is the size the release said it would be.
     * An unknown expected size (0 — the release listed no single APK asset)
     * cannot fail this: refusing on "we were never told" would block the very
     * case the fallback exists for.
     */
    fun sizeLooksRight(expectedBytes: Long, actualBytes: Long): Boolean {
        if (actualBytes <= 0L) return false
        if (expectedBytes <= 0L) return true
        return expectedBytes == actualBytes
    }

    // ── What the installer session said ──────────────────────────────────────

    /**
     * A `PackageInstaller` session status, reduced to the four things the app
     * actually does about it.
     *
     * The distinction that matters is [NeedsConfirmation]: it is not a failure
     * and not a success but an *instruction* — the OS has handed back an Intent
     * and expects the app to start it. Getting that one wrong is silent (the
     * platform reports nothing when an activity start is dropped), which is
     * exactly how it went unnoticed the first time.
     */
    sealed class Outcome {
        /** Show the OS's own confirmation dialog. Nothing is installed yet. */
        object NeedsConfirmation : Outcome()

        object Installed : Outcome()

        /** The user declined at the OS dialog. The APK is kept for a retry. */
        object Cancelled : Outcome()

        /** No room for the install. Worth its own wording — it is fixable. */
        object NoSpace : Outcome()

        /** [message] is the platform's, when it gave one. */
        data class Failed(val message: String?) : Outcome()
    }

    /**
     * @param status `EXTRA_STATUS` from the session's callback intent.
     * @param message `EXTRA_STATUS_MESSAGE`, which the platform often omits.
     */
    fun outcomeOf(status: Int, message: String?): Outcome = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> Outcome.NeedsConfirmation
        PackageInstaller.STATUS_SUCCESS -> Outcome.Installed
        PackageInstaller.STATUS_FAILURE_ABORTED -> Outcome.Cancelled
        PackageInstaller.STATUS_FAILURE_STORAGE -> Outcome.NoSpace
        else -> Outcome.Failed(message?.takeIf { it.isNotBlank() })
    }
}
