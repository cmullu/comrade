package mullu.comrade.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Behaviour of the shared on-demand model install pipeline ([ModelInstaller]) —
 * the part of every "download the model?" flow that must never install
 * unverified or path-escaping bytes:
 *
 *  - the official zips' single wrapper folder is flattened away, so the
 *    install dir directly contains `am/`, `conf/`, … (what Vosk's `Model`
 *    constructor expects);
 *  - a single-file model (a companion LLM's weights) installs verbatim;
 *  - a checksum mismatch refuses the download outright;
 *  - a zip-slip entry aborts extraction without writing outside the target;
 *  - cancellation and failure leave no partial install behind;
 *  - a model with no pinned source is refused before any network work.
 */
class ModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The layout check the speech model uses; the installer takes it as data. */
    private val speechEntries = listOf("am", "conf")

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Writes [entries] (name → bytes, null bytes = directory entry) into a fresh zip. */
    private fun zipOf(entries: Map<String, ByteArray?>): File {
        val zip = tmp.newFile("${entries.hashCode()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                bytes?.let { out.write(it) }
                out.closeEntry()
            }
        }
        return zip
    }

    /** The layout the official `vosk-model-small-en-us-0.15.zip` uses: one wrapper folder around everything. */
    private fun officialStyleModelZip(): File = zipOf(
        linkedMapOf(
            "vosk-model-small-en-us-0.15/" to null,
            "vosk-model-small-en-us-0.15/am/" to null,
            "vosk-model-small-en-us-0.15/am/final.mdl" to "acoustic-model".toByteArray(),
            "vosk-model-small-en-us-0.15/conf/" to null,
            "vosk-model-small-en-us-0.15/conf/model.conf" to "config".toByteArray(),
            "vosk-model-small-en-us-0.15/graph/phones/word_boundary.int" to "graph".toByteArray(),
            "vosk-model-small-en-us-0.15/README" to "readme".toByteArray(),
        ),
    )

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    // ── extraction ───────────────────────────────────────────────────────────

    @Test
    fun `extraction flattens the wrapper folder the official zips use`() {
        val dest = tmp.newFolder("model")

        ModelInstaller.extractInto(officialStyleModelZip(), dest)

        assertTrue("am/ must land at the root", File(dest, "am").isDirectory)
        assertTrue("conf/ must land at the root", File(dest, "conf").isDirectory)
        assertEquals("acoustic-model", File(dest, "am/final.mdl").readText())
        assertEquals("graph", File(dest, "graph/phones/word_boundary.int").readText())
        assertFalse(
            "the wrapper folder must not survive",
            File(dest, "vosk-model-small-en-us-0.15").exists(),
        )
        assertTrue(ModelInstaller.looksLikeSpeechModel(dest))
    }

    @Test
    fun `extraction keeps a flat archive as-is`() {
        val zip = zipOf(
            linkedMapOf(
                "am/" to null,
                "am/final.mdl" to "acoustic".toByteArray(),
                "conf/model.conf" to "conf".toByteArray(),
            ),
        )
        val dest = tmp.newFolder("flat")

        ModelInstaller.extractInto(zip, dest)

        assertEquals("acoustic", File(dest, "am/final.mdl").readText())
        assertTrue(ModelInstaller.looksLikeSpeechModel(dest))
    }

    @Test
    fun `a zip-slip entry aborts extraction without writing outside the target`() {
        val parent = tmp.newFolder("parent")
        val dest = File(parent, "inner").apply { check(mkdirs()) }
        // Two entries on purpose: with a traversal entry *alone* the archive
        // has a single shared root ("..") and the wrapper-flattening strips it
        // harmlessly, so it would never reach the canonical-path guard. A
        // second entry is what makes this a real escape attempt.
        val zip = zipOf(
            linkedMapOf(
                "ok.txt" to "fine".toByteArray(),
                "../escaped.txt" to "evil".toByteArray(),
            ),
        )

        assertThrows(IOException::class.java) { ModelInstaller.extractInto(zip, dest) }

        assertFalse(
            "the traversal entry must not land next to the target dir",
            File(parent, "escaped.txt").exists(),
        )
    }

    @Test
    fun `sha256Hex matches the known digest of abc`() {
        val file = tmp.newFile("abc.txt").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelInstaller.sha256Hex(file),
        )
    }

    // ── the full pipeline: archive models ────────────────────────────────────

    @Test
    fun `fetchAndInstall installs a verified archive model end to end`() {
        val zip = officialStyleModelZip()
        val downloadCache = File(tmp.root, "cache/model.part")
        val staging = File(tmp.root, "voice-model.staging")
        val install = File(tmp.root, "voice-model")
        val progress = mutableListOf<Pair<Long, Long>>()
        var installingSignalled = false

        ModelInstaller.fetchAndInstall(
            url = zip.toURI().toURL(),
            expectedSha256 = sha256(zip),
            downloadCache = downloadCache,
            stagingDir = staging,
            installTarget = install,
            packaging = ModelPackaging.ZIP_DIRECTORY,
            requiredEntries = speechEntries,
            onProgress = { read, total -> progress += read to total },
            onInstalling = { installingSignalled = true },
        )

        assertTrue("the finished install must look like a model", ModelInstaller.looksLikeSpeechModel(install))
        assertEquals("acoustic-model", File(install, "am/final.mdl").readText())
        assertTrue("the installing phase must be signalled", installingSignalled)
        assertEquals("download must report completion", zip.length(), progress.last().first)
        assertFalse("the downloaded archive must be cleaned up", downloadCache.exists())
        assertFalse("the staging dir must be cleaned up", staging.exists())
    }

    @Test
    fun `fetchAndInstall replaces a previous broken install`() {
        val zip = officialStyleModelZip()
        val install = File(tmp.root, "voice-model")
        check(File(install, "am").mkdirs()) // stale halfway install, no conf/
        File(install, "stale.txt").writeText("stale")

        ModelInstaller.fetchAndInstall(
            url = zip.toURI().toURL(),
            expectedSha256 = sha256(zip),
            downloadCache = File(tmp.root, "model.part"),
            stagingDir = File(tmp.root, "staging"),
            installTarget = install,
            requiredEntries = speechEntries,
        )

        assertTrue(ModelInstaller.looksLikeSpeechModel(install))
        assertFalse("stale content must be gone", File(install, "stale.txt").exists())
    }

    @Test
    fun `an archive missing the expected layout is refused`() {
        val zip = zipOf(linkedMapOf("wrapper/" to null, "wrapper/readme.txt" to "not a model".toByteArray()))
        val install = File(tmp.root, "voice-model")

        val failure = assertThrows(IOException::class.java) {
            ModelInstaller.fetchAndInstall(
                url = zip.toURI().toURL(),
                expectedSha256 = sha256(zip),
                downloadCache = File(tmp.root, "model.part"),
                stagingDir = File(tmp.root, "staging"),
                installTarget = install,
                requiredEntries = speechEntries,
            )
        }

        assertTrue(failure.message.orEmpty().contains("not the expected model"))
        assertFalse(install.exists())
    }

    // ── the full pipeline: single-file models ────────────────────────────────

    @Test
    fun `fetchAndInstall installs a verified single-file model verbatim`() {
        val weights = tmp.newFile("weights.bin").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val downloadCache = File(tmp.root, "cache/companion.part")
        val install = File(tmp.root, "companion-model.bin")

        ModelInstaller.fetchAndInstall(
            url = weights.toURI().toURL(),
            expectedSha256 = sha256(weights),
            downloadCache = downloadCache,
            stagingDir = File(tmp.root, "companion.staging"),
            installTarget = install,
            packaging = ModelPackaging.SINGLE_FILE,
        )

        assertTrue("the weights file must land in place", install.isFile)
        assertArrayEqualsBytes(weights.readBytes(), install.readBytes())
        assertFalse("the download cache must be cleaned up", downloadCache.exists())
    }

    @Test
    fun `a single-file checksum mismatch installs nothing`() {
        val weights = tmp.newFile("bad-weights.bin").apply { writeText("not the real weights") }
        val install = File(tmp.root, "companion-model.bin")

        assertThrows(IOException::class.java) {
            ModelInstaller.fetchAndInstall(
                url = weights.toURI().toURL(),
                expectedSha256 = "0".repeat(64),
                downloadCache = File(tmp.root, "companion.part"),
                stagingDir = File(tmp.root, "companion.staging"),
                installTarget = install,
                packaging = ModelPackaging.SINGLE_FILE,
            )
        }

        assertFalse("nothing may be installed from unverified bytes", install.exists())
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `a checksum mismatch refuses the download and installs nothing`() {
        val zip = officialStyleModelZip()
        val downloadCache = File(tmp.root, "model.part")
        val staging = File(tmp.root, "staging")
        val install = File(tmp.root, "voice-model")

        val failure = assertThrows(IOException::class.java) {
            ModelInstaller.fetchAndInstall(
                url = zip.toURI().toURL(),
                expectedSha256 = "0".repeat(64),
                downloadCache = downloadCache,
                stagingDir = staging,
                installTarget = install,
                requiredEntries = speechEntries,
            )
        }

        assertTrue("the error must say why", failure.message.orEmpty().contains("checksum mismatch"))
        assertFalse("nothing may be installed from unverified bytes", install.exists())
        assertFalse("the rejected download must be deleted", downloadCache.exists())
        assertFalse("staging must be cleaned up", staging.exists())
    }

    @Test
    fun `an unpinned model is refused before any download happens`() {
        // The companion model ships deliberately unconfigured until the owner
        // picks one (AUDIT §8 OQ9) — asking for it must fail loudly, not fetch
        // something unverified.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ModelInstaller.fetchAndInstall(
                spec = ModelCatalog.COMPANION,
                cacheDir = tmp.newFolder("cache"),
                filesDir = tmp.newFolder("files"),
            )
        }
        assertTrue(failure.message.orEmpty().contains("no pinned source"))
    }

    @Test
    fun `cancelling mid-download aborts and leaves nothing behind`() {
        val zip = officialStyleModelZip()
        val downloadCache = File(tmp.root, "model.part")
        val install = File(tmp.root, "voice-model")
        var checks = 0

        assertThrows(InstallCancelledException::class.java) {
            ModelInstaller.fetchAndInstall(
                url = zip.toURI().toURL(),
                expectedSha256 = sha256(zip),
                downloadCache = downloadCache,
                stagingDir = File(tmp.root, "staging"),
                installTarget = install,
                requiredEntries = speechEntries,
                // First poll lets the download start; the next aborts it.
                isCancelled = { checks++ > 0 },
            )
        }

        assertFalse("a cancelled download must not install", install.exists())
        assertFalse("the partial download must be deleted", downloadCache.exists())
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        assertTrue("contents must match byte for byte", expected.contentEquals(actual))
    }
}
