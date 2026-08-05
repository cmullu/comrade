package mullu.comrade.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The calming lines as *shipped copy*, not as arithmetic.
 *
 * `BreathingScreenTest` covers the selector — `showsInhaleLine` flips on the
 * out-breath, `breathingPairIndex` advances once per cycle — but a selector that
 * picks correctly between two identical strings still puts the same sentence on
 * screen for the whole breath. That was reported from a handset ("the same
 * message is still there for both breath in and breath out") at a moment when
 * the selector was already correct and merged, so the arithmetic tests could not
 * have caught it either way. This closes the gap from the other end: whatever
 * the selector does, the two halves of a pair must not say the same thing.
 *
 * Reads `strings.xml` directly with the JDK's own parser. There is no Robolectric
 * on the unit-test classpath, and the mockable `android.jar` stubs every
 * resources method to throw — so the alternative was an emulator test, where a
 * copy invariant does not belong. The precedent for a host-side reader is the
 * real `org.json` already on this classpath for `UpdateCheck`.
 */
class BreathingLinePairingTest {

    @Test
    fun theTwoHalvesOfAPairNeverSayTheSameThing() {
        val inLines = stringArray("breathe_lines_in")
        val outLines = stringArray("breathe_lines_out")

        // Paired by index, so a length mismatch desyncs every pair after the
        // gap. The caller takes minOf() so it cannot crash, but truncating the
        // set silently is not the same as the copy being right.
        assertEquals(
            "breathe_lines_in and breathe_lines_out are paired by index",
            inLines.size,
            outLines.size,
        )
        assertTrue("expected a set of pairs, found ${inLines.size}", inLines.size >= 2)

        inLines.zip(outLines).forEachIndexed { i, (inhale, exhale) ->
            // THE regression this file exists for. If these ever match, the
            // screen shows one sentence across the whole breath — which is
            // exactly the symptom the pairing was introduced to fix, and it
            // would look like the pairing had silently reverted.
            assertNotEquals("pair $i says the same thing on both halves", inhale, exhale)
            assertTrue("breathe_lines_in[$i] is blank", inhale.isNotBlank())
            assertTrue("breathe_lines_out[$i] is blank", exhale.isNotBlank())
        }
    }

    @Test
    fun noLineIsUsedTwiceInTheSameHalf() {
        // A duplicate inside one array is a copy-paste in the middle of a set
        // meant to carry someone through five minutes — 21 cycles against eight
        // pairs already repeats the set two and a half times, which is
        // deliberate (see theSetRepeatingOnALongSitIsAllowed); a pair appearing
        // twice *within* one pass is not.
        for (name in listOf("breathe_lines_in", "breathe_lines_out")) {
            val lines = stringArray(name)
            assertEquals("$name repeats a line", lines.size, lines.toSet().size)
        }
    }

    private companion object {

        /** Items of `<string-array name="[name]">`, in document order. */
        fun stringArray(name: String): List<String> {
            val document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(stringsXml())
            val arrays = document.getElementsByTagName("string-array")
            for (i in 0 until arrays.length) {
                val array = arrays.item(i) as Element
                if (array.getAttribute("name") != name) continue
                val items = array.getElementsByTagName("item")
                return (0 until items.length).map { items.item(it).textContent.trim() }
            }
            throw AssertionError("no <string-array name=\"$name\"> in ${stringsXml()}")
        }

        /**
         * Walks up from the working directory rather than assuming one, because
         * Gradle and an IDE do not agree on what it is for a unit test — and a
         * test that passes in CI while silently reading nothing locally is worse
         * than one that cannot find the file and says so.
         */
        fun stringsXml(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                for (relative in listOf(
                    "src/main/res/values/strings.xml",
                    "app/src/main/res/values/strings.xml",
                    "android/app/src/main/res/values/strings.xml",
                )) {
                    val candidate = File(dir, relative)
                    if (candidate.isFile) return candidate
                }
                dir = dir.parentFile
            }
            throw AssertionError("no strings.xml above ${File("").absolutePath}")
        }
    }
}
