package mullu.comrade.update

import org.json.JSONObject

/**
 * The rules behind "is there a newer Comrade than the one you are running" —
 * version comparison, GitHub release parsing, when to look again, and when a
 * finding is worth a notification.
 *
 * Deliberately free of Android types: this is the half that has to be *right*
 * (a version comparison that says "newer" when it isn't nags forever; one that
 * says "older" when it isn't hides a security fix), so it is exercised on the
 * host JVM by `UpdateCheckTest`. The Android-facing state machine around it is
 * [UpdateChecker].
 *
 * Why an update check exists at all: Comrade is distributed by sideload —
 * `RELEASING.md` §3 — so nothing tells a user that a new APK exists. Obtainium
 * users get told; everyone else was, until this, expected to keep an eye on a
 * GitHub page by hand.
 */
object UpdateCheck {

    /**
     * Where releases are published. Hardcoded rather than configurable on
     * purpose: an update source is a code-execution path, and a settable one
     * is a way to point a user's upgrade at someone else's APK.
     *
     * `/releases/latest` is the endpoint that *excludes* drafts and
     * pre-releases (GitHub's own semantics), so those never reach a user even
     * before [parseRelease]'s own guard.
     */
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/iamknownstranger/comrade/releases/latest"

    /** Where a human goes to read about, and download, releases by hand. */
    const val RELEASES_PAGE_URL = "https://github.com/iamknownstranger/comrade/releases/latest"

    /** How long a check stays fresh: once a day is plenty for a sideloaded app. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * Refuse to read an unreasonable response body. A release payload with a
     * long changelog is a few tens of KiB; anything past this is either not
     * GitHub or not worth streaming into memory on a phone.
     */
    const val MAX_BODY_BYTES = 512 * 1024

    // ── Versions ─────────────────────────────────────────────────────────────

    /**
     * An `X.Y.Z` app version, with the pre-release tail kept so `1.2.0-rc1`
     * sorts *below* `1.2.0` (the semver rule, and the one that matters: a
     * release candidate must never look like an upgrade from the release).
     *
     * Build metadata (`+abc`) is parsed and discarded — semver says it takes no
     * part in ordering, and this repo's `versionName` never carries any.
     */
    data class AppVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String? = null,
    ) : Comparable<AppVersion> {

        override fun compareTo(other: AppVersion): Int {
            major.compareTo(other.major).let { if (it != 0) return it }
            minor.compareTo(other.minor).let { if (it != 0) return it }
            patch.compareTo(other.patch).let { if (it != 0) return it }
            // A release outranks any pre-release of the same numbers; two
            // pre-releases fall back to a plain string comparison. That is
            // *not* the full semver dot-identifier rule — this project has
            // never published a pre-release, so the honest thing is a simple
            // total order with a note rather than a spec implementation
            // nothing exercises.
            return when {
                preRelease == null && other.preRelease == null -> 0
                preRelease == null -> 1
                other.preRelease == null -> -1
                else -> preRelease.compareTo(other.preRelease)
            }
        }

        override fun toString(): String =
            "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")
    }

    /**
     * Parse `v1.2.3`, `1.2.3`, `1.2`, `1`, `1.2.3-rc1`, `1.2.3+build7`.
     * Returns null for anything else — a version we cannot read is never
     * silently treated as 0.0.0, because that would make every release look
     * like an upgrade.
     */
    fun parseVersion(raw: String?): AppVersion? {
        val trimmed = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (trimmed.isEmpty()) return null
        val withoutBuild = trimmed.substringBefore('+')
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "").takeIf { it.isNotEmpty() }
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { part ->
            // No leading '+'/'-'/whitespace, and no empty component: "1..2"
            // and "1.-2" are malformed, not 1.0.2.
            if (part.isEmpty() || !part.all { it.isDigit() }) return null
            part.toIntOrNull() ?: return null
        }
        return AppVersion(
            major = numbers[0],
            minor = numbers.getOrElse(1) { 0 },
            patch = numbers.getOrElse(2) { 0 },
            preRelease = pre,
        )
    }

    /**
     * Whether [candidate] is an upgrade from [current]. Unparseable input on
     * either side answers false: with no readable version there is no evidence
     * of an upgrade, and "no evidence" must not read as "yes".
     */
    fun isNewer(current: String?, candidate: String?): Boolean {
        val from = parseVersion(current) ?: return false
        val to = parseVersion(candidate) ?: return false
        return to > from
    }

    // ── Releases ─────────────────────────────────────────────────────────────

    /** A published release, reduced to what the app needs to offer it. */
    data class ReleaseInfo(
        /** The git tag, e.g. `v0.0.9` — what the release is called. */
        val tag: String,
        /** [tag] parsed; a release whose tag will not parse never gets this far. */
        val version: AppVersion,
        /** Release notes, verbatim from GitHub, trimmed and bounded. */
        val notes: String,
        /** The release page — the fallback path, and where "what changed" lives. */
        val pageUrl: String,
        /** Direct APK download, when the release has exactly one identifiable APK. */
        val apkUrl: String?,
        /** Size of [apkUrl]'s asset, 0 when unknown. */
        val apkBytes: Long,
    ) {
        val versionName: String get() = version.toString()
    }

    /** Longest release-notes body kept; the rest is a link away on [ReleaseInfo.pageUrl]. */
    private const val MAX_NOTES_CHARS = 4000

    /**
     * Read GitHub's release JSON. Returns null when the payload is not a
     * release we would ever offer:
     *
     * - a draft or pre-release (belt and braces: `/releases/latest` already
     *   filters both, but a caller pointed at another endpoint must not be
     *   able to smuggle one through);
     * - a tag that does not parse as a version, since nothing can then be
     *   compared against what is installed.
     *
     * Throws nothing: a malformed body is null, not an exception, so a caller
     * reports "could not read the release" once rather than crashing a check.
     */
    fun parseRelease(body: String): ReleaseInfo? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optBoolean("draft", false)) return null
        if (json.optBoolean("prerelease", false)) return null
        val tag = json.optString("tag_name").trim().ifEmpty { return null }
        val version = parseVersion(tag) ?: return null
        val notes = json.optString("body").trim().take(MAX_NOTES_CHARS)
        val page = json.optString("html_url").trim().ifEmpty { RELEASES_PAGE_URL }
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        var apkBytes = 0L
        if (assets != null) {
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .map { Triple(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) }
                .filter { (name, url, _) ->
                    name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")
                }
            // One APK is the shape this project's release workflow produces
            // (`comrade-<version>.apk`, universal). If a release ever carries
            // several — per-ABI splits, say — picking one here would be a
            // guess, so we offer the release page instead of guessing wrong.
            val single = apks.singleOrNull()
                ?: apks.firstOrNull { (name, _, _) -> name.equals("comrade-$version.apk", ignoreCase = true) }
            if (single != null) {
                apkUrl = single.second
                apkBytes = single.third.coerceAtLeast(0L)
            }
        }
        return ReleaseInfo(
            tag = tag,
            version = version,
            notes = notes,
            pageUrl = page,
            apkUrl = apkUrl,
            apkBytes = apkBytes,
        )
    }

    // ── When to look, when to speak ──────────────────────────────────────────

    /**
     * Whether a check is due. A clock that moved *backwards* (a user correcting
     * the date, a device that boots at the epoch) counts as due rather than
     * parking the next check up to [intervalMs] in the future.
     */
    fun shouldCheck(lastCheckedAt: Long, now: Long, intervalMs: Long = CHECK_INTERVAL_MS): Boolean {
        if (lastCheckedAt <= 0L) return true
        if (now < lastCheckedAt) return true
        return now - lastCheckedAt >= intervalMs
    }

    /**
     * Whether finding [release] is worth a notification.
     *
     * Two things silence it: a version the user explicitly skipped (and
     * anything *older* than that, though that cannot arise from
     * `/releases/latest`), and a version we already notified about — an update
     * is not more urgent for being found twice, and a daily buzz about the same
     * release is how people turn notifications off.
     *
     * A version *newer* than the skipped one is announced again: skipping
     * 0.0.9 is a judgement about 0.0.9, not a promise never to mention
     * upgrades again.
     */
    fun shouldNotify(release: ReleaseInfo, skippedVersion: String?, alreadyNotifiedVersion: String?): Boolean {
        parseVersion(skippedVersion)?.let { skipped -> if (release.version <= skipped) return false }
        parseVersion(alreadyNotifiedVersion)?.let { notified -> if (release.version <= notified) return false }
        return true
    }
}
