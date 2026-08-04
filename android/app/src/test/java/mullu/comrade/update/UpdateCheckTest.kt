package mullu.comrade.update

import mullu.comrade.update.UpdateCheck.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the update rule. Every case here is one the app gets wrong in a way a
 * user would feel: a nag that never stops, an upgrade that is never announced,
 * or a "newer version" that is actually older.
 */
class UpdateCheckTest {

    // ── Version parsing ──────────────────────────────────────────────────────

    @Test
    fun parsesTheShapesThisProjectPublishes() {
        assertEquals(AppVersion(0, 0, 9), UpdateCheck.parseVersion("v0.0.9"))
        assertEquals(AppVersion(1, 2, 3), UpdateCheck.parseVersion("1.2.3"))
        assertEquals(AppVersion(1, 2, 0), UpdateCheck.parseVersion("1.2"))
        assertEquals(AppVersion(2, 0, 0), UpdateCheck.parseVersion("2"))
        assertEquals(AppVersion(1, 2, 3), UpdateCheck.parseVersion("  V1.2.3  "))
    }

    @Test
    fun keepsThePreReleaseTailAndDiscardsBuildMetadata() {
        assertEquals(AppVersion(1, 2, 3, "rc1"), UpdateCheck.parseVersion("1.2.3-rc1"))
        assertEquals(AppVersion(1, 2, 3), UpdateCheck.parseVersion("1.2.3+build7"))
        assertEquals(AppVersion(1, 2, 3, "rc1"), UpdateCheck.parseVersion("1.2.3-rc1+build7"))
    }

    @Test
    fun refusesWhatItCannotRead() {
        // Never 0.0.0-by-default: that would make every release an "upgrade".
        for (bad in listOf(null, "", "   ", "latest", "1.2.3.4", "1..2", "1.x", "-1.2", "v")) {
            assertNull("expected null for '$bad'", UpdateCheck.parseVersion(bad))
        }
    }

    @Test
    fun ordersByNumbersThenTreatsAReleaseAsNewerThanItsCandidate() {
        assertTrue(UpdateCheck.parseVersion("1.0.0")!! > UpdateCheck.parseVersion("0.9.9")!!)
        assertTrue(UpdateCheck.parseVersion("0.1.0")!! > UpdateCheck.parseVersion("0.0.99")!!)
        assertTrue(UpdateCheck.parseVersion("0.0.10")!! > UpdateCheck.parseVersion("0.0.9")!!)
        assertTrue(UpdateCheck.parseVersion("1.2.3")!! > UpdateCheck.parseVersion("1.2.3-rc1")!!)
        assertEquals(0, UpdateCheck.parseVersion("1.2.3")!!.compareTo(UpdateCheck.parseVersion("v1.2.3")!!))
    }

    @Test
    fun isNewerIsFalseWhenEitherSideIsUnreadable() {
        assertTrue(UpdateCheck.isNewer("0.0.8", "0.0.9"))
        assertFalse(UpdateCheck.isNewer("0.0.9", "0.0.9"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.0.9"))
        // A build whose versionName is not semver must not be told it is out of
        // date by every release that exists.
        assertFalse(UpdateCheck.isNewer("dev", "0.0.9"))
        assertFalse(UpdateCheck.isNewer("0.0.9", "nightly"))
    }

    // ── Release parsing ──────────────────────────────────────────────────────

    private fun releaseJson(
        tag: String = "v0.0.9",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"comrade-0.0.9.apk","browser_download_url":"https://example.test/comrade-0.0.9.apk","size":42}]""",
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "html_url": "https://github.test/releases/tag/$tag",
          "body": "Fixed the thing — really",
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun readsATagNotesPageAndApk() {
        val release = UpdateCheck.parseRelease(releaseJson())
        assertNotNull(release)
        release!!
        assertEquals("v0.0.9", release.tag)
        assertEquals("0.0.9", release.versionName)
        assertEquals("https://github.test/releases/tag/v0.0.9", release.pageUrl)
        assertEquals("https://example.test/comrade-0.0.9.apk", release.apkUrl)
        assertEquals(42L, release.apkBytes)
        // Non-ASCII survives: the notes are decoded as UTF-8, not per chunk.
        assertTrue(release.notes.contains("—"))
    }

    @Test
    fun refusesDraftsAndPreReleases() {
        assertNull(UpdateCheck.parseRelease(releaseJson(draft = true)))
        assertNull(UpdateCheck.parseRelease(releaseJson(prerelease = true)))
    }

    @Test
    fun refusesAnUnreadableTagOrBody() {
        assertNull(UpdateCheck.parseRelease(releaseJson(tag = "nightly")))
        assertNull(UpdateCheck.parseRelease(releaseJson(tag = "")))
        assertNull(UpdateCheck.parseRelease("not json at all"))
        assertNull(UpdateCheck.parseRelease(""))
    }

    @Test
    fun ignoresNonApkAssetsAndInsecureUrls() {
        val release = UpdateCheck.parseRelease(
            releaseJson(
                assets = """
                    [
                      {"name":"comrade-0.0.9.aab","browser_download_url":"https://example.test/x.aab","size":9},
                      {"name":"comrade-0.0.9.apk.idsig","browser_download_url":"https://example.test/x.idsig","size":9},
                      {"name":"comrade-0.0.9.apk","browser_download_url":"https://example.test/ok.apk","size":7}
                    ]
                """.trimIndent(),
            ),
        )
        assertEquals("https://example.test/ok.apk", release?.apkUrl)

        // An APK offered over plain http is not an APK we will point anyone at.
        val insecure = UpdateCheck.parseRelease(
            releaseJson(assets = """[{"name":"c.apk","browser_download_url":"http://example.test/c.apk","size":7}]"""),
        )
        assertNull(insecure?.apkUrl)
    }

    @Test
    fun refusesToGuessBetweenSeveralApks() {
        // Per-ABI splits, say. The tag-matching name still wins; an ambiguous
        // set leaves apkUrl null so the caller offers the release page instead.
        val matching = UpdateCheck.parseRelease(
            releaseJson(
                assets = """
                    [
                      {"name":"comrade-0.0.9-arm64.apk","browser_download_url":"https://example.test/a.apk","size":1},
                      {"name":"comrade-0.0.9.apk","browser_download_url":"https://example.test/b.apk","size":2}
                    ]
                """.trimIndent(),
            ),
        )
        assertEquals("https://example.test/b.apk", matching?.apkUrl)

        val ambiguous = UpdateCheck.parseRelease(
            releaseJson(
                assets = """
                    [
                      {"name":"comrade-arm64.apk","browser_download_url":"https://example.test/a.apk","size":1},
                      {"name":"comrade-x86.apk","browser_download_url":"https://example.test/b.apk","size":2}
                    ]
                """.trimIndent(),
            ),
        )
        assertNotNull(ambiguous)
        assertNull(ambiguous?.apkUrl)
    }

    @Test
    fun aReleaseWithNoAssetsIsStillARelease() {
        val release = UpdateCheck.parseRelease(releaseJson(assets = "[]"))
        assertNotNull(release)
        assertNull(release?.apkUrl)
        assertEquals(0L, release?.apkBytes)
    }

    @Test
    fun fallsBackToTheReleasesPageWhenTheBodyHasNoUrl() {
        val release = UpdateCheck.parseRelease("""{"tag_name":"v1.0.0","body":"","assets":[]}""")
        assertEquals(UpdateCheck.RELEASES_PAGE_URL, release?.pageUrl)
    }

    // ── Cadence ──────────────────────────────────────────────────────────────

    @Test
    fun checksOnceADayAndAlwaysOnAFreshInstall() {
        val day = UpdateCheck.CHECK_INTERVAL_MS
        assertTrue(UpdateCheck.shouldCheck(lastCheckedAt = 0L, now = 1_000L))
        assertFalse(UpdateCheck.shouldCheck(lastCheckedAt = 1_000L, now = 1_000L + day - 1))
        assertTrue(UpdateCheck.shouldCheck(lastCheckedAt = 1_000L, now = 1_000L + day))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotParkTheNextCheckADayAway() {
        assertTrue(UpdateCheck.shouldCheck(lastCheckedAt = 5_000_000L, now = 1_000L))
    }

    // ── Notifying ────────────────────────────────────────────────────────────

    private fun release(version: String) = UpdateCheck.parseRelease(releaseJson(tag = version))!!

    @Test
    fun announcesAFindingOnce() {
        val found = release("v0.0.9")
        assertTrue(UpdateCheck.shouldNotify(found, skippedVersion = null, alreadyNotifiedVersion = null))
        // Found again tomorrow: still true, still 0.0.9 — do not buzz again.
        assertFalse(UpdateCheck.shouldNotify(found, skippedVersion = null, alreadyNotifiedVersion = "0.0.9"))
    }

    @Test
    fun staysQuietAboutASkippedVersionButNotAboutALaterOne() {
        assertFalse(UpdateCheck.shouldNotify(release("v0.0.9"), skippedVersion = "0.0.9", alreadyNotifiedVersion = null))
        assertTrue(UpdateCheck.shouldNotify(release("v0.1.0"), skippedVersion = "0.0.9", alreadyNotifiedVersion = null))
    }

    @Test
    fun anUnreadableWatermarkIsIgnoredRatherThanSilencing() {
        // Garbage in the preference must not be able to mute updates forever.
        assertTrue(UpdateCheck.shouldNotify(release("v0.0.9"), skippedVersion = "??", alreadyNotifiedVersion = "??"))
    }

    @Test
    fun aNewerFindingIsAnnouncedEvenAfterAnEarlierOneWas() {
        assertTrue(UpdateCheck.shouldNotify(release("v0.1.0"), skippedVersion = null, alreadyNotifiedVersion = "0.0.9"))
    }

    // ── The scheduled check ──────────────────────────────────────────────────

    @Test
    fun aJobThatFiredInsideItsFlexWindowStillChecks() {
        // The reason SCHEDULED_CHECK_MIN_AGE_MS exists. The scheduler may fire up
        // to CHECK_JOB_FLEX_MS early, so gating a scheduled run on the full
        // interval would make it a no-op and push the next one a whole day out —
        // the daily check would then run roughly never.
        val lastChecked = 1_000L
        val firedEarly = lastChecked + UpdateCheck.CHECK_INTERVAL_MS - UpdateCheck.CHECK_JOB_FLEX_MS
        assertFalse(UpdateCheck.shouldCheck(lastChecked, firedEarly))
        assertTrue(UpdateCheck.shouldCheck(lastChecked, firedEarly, UpdateCheck.SCHEDULED_CHECK_MIN_AGE_MS))
    }

    @Test
    fun aCheckThatJustRanIsNotRepeatedByTheScheduler() {
        // Opening the app half an hour ago already asked. The job firing now
        // should not ask again.
        val lastChecked = 1_000L
        val halfAnHourLater = lastChecked + 30L * 60 * 1000
        assertFalse(UpdateCheck.shouldCheck(lastChecked, halfAnHourLater, UpdateCheck.SCHEDULED_CHECK_MIN_AGE_MS))
    }

    @Test
    fun anAlreadyQueuedJobIsLeftAlone() {
        // The trap: JobScheduler.schedule() on a queued id restarts its period,
        // so re-scheduling at every process start means an app opened daily never
        // reaches the end of a one-day period.
        assertFalse(
            UpdateCheck.shouldScheduleCheckJob(
                autoCheckEnabled = true,
                pendingIntervalMs = UpdateCheck.CHECK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun aMissingOrOutOfDateJobIsQueued() {
        assertTrue(UpdateCheck.shouldScheduleCheckJob(autoCheckEnabled = true, pendingIntervalMs = null))
        // A build that changed the interval must replace what is queued, or the
        // old period outlives the decision to change it.
        assertTrue(
            UpdateCheck.shouldScheduleCheckJob(
                autoCheckEnabled = true,
                pendingIntervalMs = UpdateCheck.CHECK_INTERVAL_MS / 2,
            ),
        )
    }

    @Test
    fun autoCheckOffMeansNoJobAtAll() {
        // The privacy promise: off has to stop the requests, not just stop
        // reading the answers.
        assertFalse(UpdateCheck.shouldScheduleCheckJob(autoCheckEnabled = false, pendingIntervalMs = null))
        assertFalse(
            UpdateCheck.shouldScheduleCheckJob(
                autoCheckEnabled = false,
                pendingIntervalMs = UpdateCheck.CHECK_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun onlyAFailedCheckIsWorthRetrying() {
        assertTrue(UpdateCheck.shouldRetryScheduledCheck(UpdateStatus.Failed("offline", 1L)))
        // Both of these are complete answers; retrying either is a second
        // request for a fact we already have.
        assertFalse(UpdateCheck.shouldRetryScheduledCheck(UpdateStatus.UpToDate(1L)))
        assertFalse(UpdateCheck.shouldRetryScheduledCheck(UpdateStatus.Available(release("v0.0.9"), 1L)))
        assertFalse(UpdateCheck.shouldRetryScheduledCheck(UpdateStatus.Unknown))
    }
}
