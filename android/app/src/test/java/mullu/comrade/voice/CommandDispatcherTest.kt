package mullu.comrade.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDispatcherTest {

    private class FakeBackend(
        var postResult: Result<String> = Result.success("evt123"),
        var journalResult: Result<String> = Result.success("entry123"),
        var timelineResult: Result<List<String>> = Result.success(emptyList()),
        var switchResult: Result<String> = Result.success("Off-Grid Travel"),
        var identityResult: Result<String> = Result.success("npub1abc"),
        /** The length the backend reports actually starting. */
        var focusResult: Result<Int> = Result.success(25),
        var taraResult: Result<TaraReply> = Result.success(
            TaraReply("What feels most important about that to you?", crisis = false),
        ),
    ) : ComradeBackend {
        var lastPost: String? = null
        var lastJournal: String? = null
        var lastTara: String? = null
        var lastSwitchKey: String? = null
        var lastFocusMinutes: Int? = null
        var focusCalled = false
        override fun post(text: String): Result<String> { lastPost = text; return postResult }
        override fun journal(text: String): Result<String> {
            lastJournal = text; return journalResult
        }
        override fun tara(text: String): Result<TaraReply> { lastTara = text; return taraResult }
        override fun timeline(): Result<List<String>> = timelineResult
        override fun switchWorkspace(key: String): Result<String> {
            lastSwitchKey = key; return switchResult
        }
        override fun generateIdentity(): Result<String> = identityResult
        override fun startFocus(minutes: Int?): Result<Int> {
            focusCalled = true
            lastFocusMinutes = minutes
            return focusResult
        }
    }

    @Test
    fun `focus with no duration lets the engine choose and states the result`() {
        val backend = FakeBackend(focusResult = Result.success(45))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.StartFocus(null))
        assertTrue(backend.focusCalled)
        assertEquals(null, backend.lastFocusMinutes)
        // The reply must state what actually started, not what was asked for.
        assertTrue(reply, reply.contains("45 minutes"))
    }

    @Test
    fun `focus with a spoken duration forwards it`() {
        val backend = FakeBackend(focusResult = Result.success(90))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.StartFocus(90))
        assertEquals(90, backend.lastFocusMinutes)
        assertTrue(reply, reply.contains("90 minutes"))
    }

    @Test
    fun `focus failure is spoken back with the error`() {
        val backend = FakeBackend(focusResult = Result.failure(RuntimeException("vault is locked")))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.StartFocus(null))
        assertTrue(reply, reply.contains("couldn't start", ignoreCase = true))
        assertTrue(reply, reply.contains("vault is locked"))
    }

    @Test
    fun `post forwards body to backend and confirms`() {
        val backend = FakeBackend()
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Post("gm"))
        assertEquals("gm", backend.lastPost)
        assertTrue(reply.contains("Posted", ignoreCase = true))
    }

    @Test
    fun `post failure is spoken back with the error`() {
        val backend = FakeBackend(postResult = Result.failure(RuntimeException("relay down")))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Post("gm"))
        assertTrue(reply.contains("couldn't post", ignoreCase = true))
        assertTrue(reply.contains("relay down"))
    }

    @Test
    fun `journal saves privately and never posts`() {
        val backend = FakeBackend()
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Journal("felt anxious"))
        assertEquals("felt anxious", backend.lastJournal)
        assertEquals("a journal entry must never hit the public feed", null, backend.lastPost)
        assertTrue(reply.contains("journal", ignoreCase = true))
        assertTrue(reply.contains("this phone", ignoreCase = true))
    }

    @Test
    fun `journal failure is spoken back`() {
        val backend = FakeBackend(journalResult = Result.failure(RuntimeException("vault locked")))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Journal("hello"))
        assertTrue(reply.contains("couldn't save", ignoreCase = true))
        assertTrue(reply.contains("vault locked"))
    }

    // ── Tara ─────────────────────────────────────────────────────────────────

    @Test
    fun `tara forwards the message and speaks her reply verbatim`() {
        val backend = FakeBackend(
            taraResult = Result.success(TaraReply("That sounds anxious.", crisis = false)),
        )
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Tara("work is a lot"))
        assertEquals("work is a lot", backend.lastTara)
        assertEquals("a reflective reply is spoken as-is", "That sounds anxious.", reply)
        assertEquals("talking to Tara must never hit the public feed", null, backend.lastPost)
    }

    @Test
    fun `a spoken crisis hand-off reads out a real number`() {
        // Over voice there is no helpline card to point at, so the number has to
        // be in the sentence — and spaced out, or TTS says "fourteen thousand
        // four hundred and sixteen" for 14416.
        val backend = FakeBackend(
            taraResult = Result.success(
                TaraReply(
                    "I'm a reflective companion, not a therapist or crisis service.",
                    crisis = true,
                    helplines = listOf(
                        Helpline("Tele-MANAS", "14416"),
                        Helpline("KIRAN", "1800-599-0019"),
                    ),
                ),
            ),
        )
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Tara("i want to end it all"))

        assertTrue("must keep the not-a-therapist framing", reply.contains("not a therapist"))
        assertTrue("must name the helpline", reply.contains("Tele-MANAS"))
        assertTrue("digits must be spoken one by one", reply.contains("1 4 4 1 6"))
    }

    @Test
    fun `a crisis reply with no helplines still speaks the hand-off`() {
        val backend = FakeBackend(
            taraResult = Result.success(TaraReply("Please reach out.", crisis = true)),
        )
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Tara("i want to die"))
        assertEquals("Please reach out.", reply)
    }

    @Test
    fun `a hyphenated helpline number is spelled out digit by digit`() {
        val backend = FakeBackend(
            taraResult = Result.success(
                TaraReply(
                    "Reaching out matters.",
                    crisis = true,
                    helplines = listOf(Helpline("KIRAN", "1800-599-0019")),
                ),
            ),
        )
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Tara("self harm"))
        assertTrue(reply.contains("1 8 0 0 5 9 9 0 0 1 9"))
    }

    @Test
    fun `tara failure is spoken back`() {
        val backend = FakeBackend(taraResult = Result.failure(RuntimeException("vault locked")))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.Tara("hello"))
        assertTrue(reply.contains("couldn't reach Tara", ignoreCase = true))
        assertTrue(reply.contains("vault locked"))
    }

    @Test
    fun `help and the listening prompt both mention Tara`() {
        val dispatcher = CommandDispatcher(FakeBackend())
        assertTrue(dispatcher.handle(VoiceCommand.Help).contains("tara", ignoreCase = true))
        assertTrue(dispatcher.handle(VoiceCommand.Empty).contains("tara", ignoreCase = true))
    }

    @Test
    fun `empty timeline is reported`() {
        val reply = CommandDispatcher(FakeBackend()).handle(VoiceCommand.ReadTimeline)
        assertTrue(reply.contains("empty", ignoreCase = true))
    }

    @Test
    fun `timeline reads at most three items`() {
        val backend = FakeBackend(
            timelineResult = Result.success(listOf("one", "two", "three", "four", "five")),
        )
        val reply = CommandDispatcher(backend).handle(VoiceCommand.ReadTimeline)
        assertTrue(reply.contains("one"))
        assertTrue(reply.contains("three"))
        assertTrue("should cap at three spoken items", !reply.contains("four"))
    }

    @Test
    fun `switch forwards the core key`() {
        val backend = FakeBackend()
        val reply = CommandDispatcher(backend)
            .handle(VoiceCommand.SwitchWorkspace("OffGridTravel"))
        assertEquals("OffGridTravel", backend.lastSwitchKey)
        assertTrue(reply.contains("Off-Grid Travel"))
    }

    @Test
    fun `identity response never leaks the secret key`() {
        val backend = FakeBackend(identityResult = Result.success("npub1public"))
        val reply = CommandDispatcher(backend).handle(VoiceCommand.GenerateKeypair)
        assertTrue(!reply.contains("nsec"))
        assertTrue(!reply.contains("npub1public")) // spoken responses stay off the key material
    }

    @Test
    fun `unknown command points at help`() {
        val reply = CommandDispatcher(FakeBackend()).handle(VoiceCommand.Unknown("order pizza"))
        assertTrue(reply.contains("help", ignoreCase = true))
    }
}
