package mullu.comrade.update

/**
 * Whether a downloaded APK may be handed to the installer.
 *
 * Pure, so `UpdateInstallTest` can drive every refusal on the host JVM. The
 * Android side that reads these facts off the archive (and off ourselves) is
 * [UpdateInstaller].
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
}
