package mullu.comrade.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shared model catalog's invariants — the rules that keep the two very
 * different on-device models ("Hey Comrade" speech recognition and Tara's
 * companion) on one download pipeline without letting an unverified artifact
 * through.
 */
class ModelCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `ids are unique and resolvable`() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        assertSame(ModelCatalog.SPEECH, ModelCatalog.byId(ModelCatalog.ID_SPEECH))
        assertSame(ModelCatalog.COMPANION, ModelCatalog.byId(ModelCatalog.ID_COMPANION))
        assertNull(ModelCatalog.byId("nope"))
    }

    @Test
    fun `the speech model is fully pinned`() {
        val speech = ModelCatalog.SPEECH
        assertTrue("the shipped speech model must be downloadable", speech.configured)
        assertEquals(64, speech.sha256.length)
        assertTrue("must be fetched over TLS", speech.url.startsWith("https://"))
        assertTrue(speech.downloadBytes > 0)
        assertEquals(ModelPackaging.ZIP_DIRECTORY, speech.packaging)
        assertEquals(listOf("am", "conf"), speech.requiredEntries)
        assertEquals(
            "an upgrading install must keep finding its already-downloaded model",
            "voice-model",
            speech.installName,
        )
    }

    @Test
    fun `the companion model is deliberately unconfigured until an owner picks one`() {
        // AUDIT §8 OQ9 is an open owner decision, and no inference backend
        // exists yet. Anything other than "not configured" here would mean the
        // app offers a download it cannot verify or use.
        val companion = ModelCatalog.COMPANION
        assertFalse("must not be offered before it is pinned", companion.configured)
        assertTrue(companion.url.isEmpty())
        assertTrue(companion.sha256.isEmpty())
        assertEquals(
            "finishing it should return the user to Tara",
            ModelCatalog.TAB_TARA,
            companion.returnTab,
        )
    }

    @Test
    fun `an archive model counts as installed only with every required entry`() {
        val spec = ModelCatalog.SPEECH
        val dir = tmp.newFolder("voice-model")
        assertFalse("an empty dir is not a model", spec.looksInstalled(dir))
        check(File(dir, "am").mkdirs())
        assertFalse("am/ alone is not a model", spec.looksInstalled(dir))
        check(File(dir, "conf").mkdirs())
        assertTrue(spec.looksInstalled(dir))
    }

    @Test
    fun `a single-file model counts as installed only when non-empty`() {
        val spec = ModelCatalog.COMPANION
        val target = File(tmp.root, spec.installName)
        assertFalse("absent is not installed", spec.looksInstalled(target))
        target.writeText("")
        assertFalse("a zero-byte file is not a usable model", spec.looksInstalled(target))
        target.writeText("weights")
        assertTrue(spec.looksInstalled(target))
    }

    @Test
    fun `install, staging and cache paths never collide`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val paths = ModelCatalog.all.flatMap {
            listOf(it.installTarget(files), it.stagingTarget(files), it.downloadCache(cache))
        }
        assertEquals("every model path must be distinct", paths.size, paths.map { it.path }.toSet().size)
    }

    @Test
    fun `download progress is clamped and safe before the size is known`() {
        assertEquals("unknown size reads as 0%", 0, downloadPercent(1_000, -1))
        assertEquals(0, downloadPercent(0, 100))
        assertEquals(50, downloadPercent(50, 100))
        assertEquals(100, downloadPercent(100, 100))
        assertEquals("over-read cannot exceed 100%", 100, downloadPercent(500, 100))
        assertEquals("negative read cannot go below 0%", 0, downloadPercent(-5, 100))
        // A real 40 MB download must not overflow or stall at 0. Progress
        // truncates rather than rounds, so a hair under halfway reads 49 — the
        // bar must never claim more progress than has actually happened.
        assertEquals(49, downloadPercent(20_602_965, 41_205_931))
        assertEquals(99, downloadPercent(41_205_930, 41_205_931))
    }
}
