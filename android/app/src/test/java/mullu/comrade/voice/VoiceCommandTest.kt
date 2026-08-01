package mullu.comrade.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `post prefix captures the body`() {
        assertEquals(
            VoiceCommand.Post("hello world"),
            VoiceCommand.parse("post hello world"),
        )
        assertEquals(
            VoiceCommand.Post("gm from the mesh"),
            VoiceCommand.parse("broadcast gm from the mesh"),
        )
    }

    @Test
    fun `journal prefixes capture a private entry and never a post`() {
        assertEquals(
            VoiceCommand.Journal("felt calm after the walk"),
            VoiceCommand.parse("journal felt calm after the walk"),
        )
        assertEquals(
            VoiceCommand.Journal("call the doctor tomorrow"),
            VoiceCommand.parse("write down call the doctor tomorrow"),
        )
        assertEquals(
            VoiceCommand.Journal("i am grateful"),
            VoiceCommand.parse("hey comrade dear diary i am grateful"),
        )
        // A bare "journal" has no body — must not become a Post or Unknown.
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse("journal"))
    }

    @Test
    fun `tara prefixes address the companion and never the public feed`() {
        assertEquals(
            VoiceCommand.Tara("i've been anxious all week"),
            VoiceCommand.parse("tara i've been anxious all week"),
        )
        assertEquals(
            VoiceCommand.Tara("should i take the job"),
            VoiceCommand.parse("talk to tara should i take the job"),
        )
        assertEquals(
            VoiceCommand.Tara("i feel stuck"),
            VoiceCommand.parse("hey comrade ask tara i feel stuck"),
        )
        assertEquals(
            VoiceCommand.Tara("today was hard"),
            VoiceCommand.parse("Tell Tara today was hard."),
        )
        // The longer phrasings must win over the bare "tara" prefix, or the
        // body would come out as "to tara should i take the job".
        assertEquals(
            VoiceCommand.Tara("what now"),
            VoiceCommand.parse("talk to tara what now"),
        )
        // Addressing her with nothing to say prompts instead of sending "".
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse("tara"))
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse("talk to tara"))
    }

    @Test
    fun `tara never collides with journal or post prefixes`() {
        // Both are private, but they are distinct destinations: a thought meant
        // for the companion must not silently become a journal entry, and
        // neither may fall through to the public feed.
        assertEquals(VoiceCommand.Journal("tara called"), VoiceCommand.parse("journal tara called"))
        assertEquals(VoiceCommand.Post("tara is a name"), VoiceCommand.parse("post tara is a name"))
    }

    @Test
    fun `wake phrase is stripped before parsing`() {
        assertEquals(
            VoiceCommand.Post("running late"),
            VoiceCommand.parse("hey comrade post running late"),
        )
        assertEquals(
            VoiceCommand.ReadTimeline,
            VoiceCommand.parse("Hey Comrade, read my feed"),
        )
    }

    @Test
    fun `timeline synonyms all resolve`() {
        for (phrase in listOf("read timeline", "show feed", "what's new", "catch me up")) {
            assertEquals(
                "phrase='$phrase'",
                VoiceCommand.ReadTimeline,
                VoiceCommand.parse(phrase),
            )
        }
    }

    @Test
    fun `workspace aliases map to core keys`() {
        assertEquals(
            VoiceCommand.SwitchWorkspace("OffGridTravel"),
            VoiceCommand.parse("switch to off grid"),
        )
        assertEquals(
            VoiceCommand.SwitchWorkspace("OffGridTravel"),
            VoiceCommand.parse("go to travel"),
        )
        assertEquals(
            VoiceCommand.SwitchWorkspace("CoupleSandboxSakhi"),
            VoiceCommand.parse("switch to sakhi"),
        )
        assertEquals(
            VoiceCommand.SwitchWorkspace("Base"),
            VoiceCommand.parse("open home"),
        )
    }

    @Test
    fun `keygen and help phrases resolve`() {
        assertEquals(VoiceCommand.GenerateKeypair, VoiceCommand.parse("new identity"))
        assertEquals(VoiceCommand.Help, VoiceCommand.parse("what can you do"))
    }

    @Test
    fun `empty or wake-only utterance is Empty`() {
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse("hey comrade"))
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse(""))
        assertEquals(VoiceCommand.Empty, VoiceCommand.parse("post"))
    }

    @Test
    fun `unrecognised speech becomes Unknown with normalised text`() {
        val result = VoiceCommand.parse("please order me a pizza")
        assertTrue(result is VoiceCommand.Unknown)
        assertEquals("please order me a pizza", (result as VoiceCommand.Unknown).transcript)
    }

    @Test
    fun `punctuation and casing are normalised away`() {
        assertEquals("post hello", VoiceCommand.normalise("  Post, HELLO!! "))
    }

    @Test
    fun `focus phrases resolve with no duration`() {
        for (phrase in listOf(
            "start a focus session",
            "start focus session",
            "focus session",
            "let's focus",
            "hey comrade start a focus session",
        )) {
            assertEquals(
                "should parse: $phrase",
                VoiceCommand.StartFocus(null),
                VoiceCommand.parse(phrase),
            )
        }
    }

    @Test
    fun `a spoken digit duration is honoured`() {
        assertEquals(VoiceCommand.StartFocus(45), VoiceCommand.parse("focus for 45 minutes"))
        assertEquals(
            VoiceCommand.StartFocus(90),
            VoiceCommand.parse("start a focus session for 90 minutes"),
        )
    }

    @Test
    fun `a number word falls back to the suggested length`() {
        // Vosk emits number words as often as digits, and half-parsing "forty
        // five" into 40 would start a session nobody asked for. Falling back to
        // the engine's own suggestion is the safe reading.
        assertEquals(
            VoiceCommand.StartFocus(null),
            VoiceCommand.parse("focus for forty five minutes"),
        )
    }

    @Test
    fun `a focus request is never mistaken for a public post`() {
        // "share"/"say"/"post" prefixes must not swallow this: broadcasting
        // "a focus session" to open relays would be a privacy surprise.
        val parsed = VoiceCommand.parse("start a focus session")
        assertTrue(parsed is VoiceCommand.StartFocus)
    }
}
