package mullu.comrade.voice

/**
 * Backend surface the voice layer needs. Abstracted behind an interface so the
 * [CommandDispatcher] decision logic is unit-testable with a fake, without
 * loading the native `comrade_jni` library. [ComradeCoreBackend] is the real,
 * JNI-backed implementation.
 */
/** One crisis helpline, as the voice layer needs to say it. */
data class Helpline(val name: String, val contact: String)

/**
 * Tara's reply, plus what the voice layer needs to handle a crisis hand-off
 * without a screen. [helplines] is populated only when [crisis] is true.
 */
data class TaraReply(
    val text: String,
    val crisis: Boolean,
    val helplines: List<Helpline> = emptyList(),
)

interface ComradeBackend {
    /** Broadcast a public Chitthi; returns the new event id. */
    fun post(text: String): Result<String>

    /** Save a private, local-only journal entry; returns its id. */
    fun journal(text: String): Result<String>

    /** Say something to Tara and get her reply; local-only, never networked. */
    fun tara(text: String): Result<TaraReply>

    /** Most recent cached Chitthi bodies, newest first. */
    fun timeline(): Result<List<String>>

    /** Switch workspace by `ComradeCore` key; returns its human label. */
    fun switchWorkspace(key: String): Result<String>

    /** Mint a fresh identity; returns the public `npub` (never the nsec). */
    fun generateIdentity(): Result<String>

    /**
     * Start a focus session of [minutes], or of the engine's own suggested
     * length when null. Returns the length actually started, so the spoken
     * reply can state it rather than guess.
     */
    fun startFocus(minutes: Int?): Result<Int>
}

/**
 * Turns a parsed [VoiceCommand] into a backend action plus the sentence the
 * assistant should speak back. Pure with respect to Android — the only
 * dependency is the injected [ComradeBackend].
 */
class CommandDispatcher(private val backend: ComradeBackend) {

    fun handle(command: VoiceCommand): String = when (command) {
        is VoiceCommand.Post -> backend.post(command.text).fold(
            onSuccess = { "Posted your Chitthi." },
            onFailure = { "I couldn't post that. ${it.message ?: "Unknown error"}." },
        )

        is VoiceCommand.Journal -> backend.journal(command.text).fold(
            onSuccess = { "Saved to your journal. It stays on this phone." },
            onFailure = { "I couldn't save that. ${it.message ?: "Unknown error"}." },
        )

        is VoiceCommand.Tara -> backend.tara(command.text).fold(
            onSuccess = { reply -> spoken(reply) },
            onFailure = { "I couldn't reach Tara. ${it.message ?: "Unknown error"}." },
        )

        is VoiceCommand.ReadTimeline -> backend.timeline().fold(
            onSuccess = { bodies ->
                if (bodies.isEmpty()) {
                    "Your timeline is empty."
                } else {
                    val head = bodies.take(MAX_SPOKEN_ITEMS)
                    buildString {
                        append("Here are your latest ")
                        append(head.size)
                        append(if (head.size == 1) " Chitthi. " else " Chitthis. ")
                        head.forEachIndexed { i, body ->
                            append(i + 1); append(". "); append(body); append(". ")
                        }
                    }.trim()
                }
            },
            onFailure = { "I couldn't read your timeline. ${it.message ?: "Unknown error"}." },
        )

        is VoiceCommand.SwitchWorkspace -> backend.switchWorkspace(command.workspaceKey).fold(
            onSuccess = { label -> "Switched to $label." },
            onFailure = { "I couldn't switch workspace. ${it.message ?: "Unknown error"}." },
        )

        is VoiceCommand.StartFocus -> backend.startFocus(command.minutes).fold(
            onSuccess = { started ->
                "Focus session started — $started minutes on one thing. " +
                    "Stop whenever you need to."
            },
            onFailure = { "I couldn't start a focus session. ${it.message ?: "Unknown error"}." },
        )

        VoiceCommand.GenerateKeypair -> backend.generateIdentity().fold(
            onSuccess = { "Created a new identity. Your public key is on screen." },
            onFailure = { "I couldn't create an identity. ${it.message ?: "Unknown error"}." },
        )

        VoiceCommand.Help -> HELP_SENTENCE

        VoiceCommand.Empty ->
            "I'm listening. Try: journal, tara, post, start a focus session, " +
                "read my timeline, or switch workspace."

        is VoiceCommand.Unknown ->
            "Sorry, I can't do that yet. Say \"help\" to hear what I understand."
    }

    /**
     * What to say for one of Tara's replies.
     *
     * A reflective reply is spoken as-is. A crisis hand-off is not: the reply
     * text is modality-neutral by design (see `comrade_core::tara`'s
     * CRISIS_REPLY), so on a screen the app shows the helpline card — but over
     * voice there is no card, and "reach out to a crisis helpline" with no
     * number attached is advice the listener cannot act on. So read one out.
     */
    private fun spoken(reply: TaraReply): String {
        if (!reply.crisis) return reply.text
        val first = reply.helplines.firstOrNull() ?: return reply.text
        return "${reply.text} You can call ${first.name} on ${spellForSpeech(first.contact)}."
    }

    /**
     * Space out a phone number's digits so text-to-speech reads "1 4 4 1 6"
     * rather than "fourteen thousand four hundred and sixteen" — a number the
     * listener has to be able to dial.
     */
    private fun spellForSpeech(contact: String): String {
        // A non-numeric contact (a directory URL) is left alone.
        if (contact.none(Char::isDigit)) return contact
        val out = StringBuilder()
        for (ch in contact) {
            when {
                ch.isDigit() -> {
                    if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                    out.append(ch)
                }
                // Separators become a single space; a run never doubles up.
                ch.isWhitespace() || ch == '-' -> {
                    if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                }
                // Anything else ("+") is kept — TTS reads it as "plus".
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }

    private companion object {
        const val MAX_SPOKEN_ITEMS = 3
        const val HELP_SENTENCE =
            "You can say: journal, followed by a private thought; tara, followed " +
                "by whatever is on your mind; post, followed by your message; " +
                "start a focus session; read my timeline; switch to off grid, " +
                "base, sakha or sakhi; or create a new identity."
    }
}
