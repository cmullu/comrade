package mullu.comrade.ui

import mullu.comrade.ComradeCore
import uniffi.comrade_core.AppAction
import uniffi.comrade_core.ChatCommand
import uniffi.comrade_core.CommandSpec

/**
 * Decisions behind the chat composer's in-chat commands — the Android half of
 * what `comrade_core::command` parses.
 *
 * Kept free of any Compose or Android import so the whole thing is unit-testable
 * on a plain JVM, exactly like [ComposerMode], [ChatThread] and
 * [mullu.comrade.voice.VoiceCommand]. The desktop twin is
 * `desktop/ui/chat_commands.mjs` and the two are pinned by mirrored test
 * vectors — where they disagree on purpose (which screens exist), each says so.
 *
 * ## What is *not* here
 *
 * **The grammar.** Which words are commands, what `@handle` means, and how free
 * text becomes a recording are all Rust's answers, reached through
 * [ComradeCore.parseChatCommand] / [ComradeCore.resolveMentions]. Nothing in
 * this file re-parses composer text: a second grammar is precisely the drift
 * `/pay` already suffered across four implementations.
 *
 * [mullu.comrade.voice.VoiceCommand] **still has its own grammar** and has not
 * been folded into core. That is a known follow-up, recorded in
 * `docs/CHAT_ACTIONS.md` §7 — not a claim that it already happened. It is a
 * refactor of a parser with its own passing tests, and was left alone rather
 * than done blind.
 */

/** What the composer should do once a command has been parsed. */
sealed interface ComposerPlan {
    /** Not a command — send the text as typed. */
    data object Send : ComposerPlan

    /** Route to [ComradeCore.taraAsideTyped]; never to a DM. */
    data class Aside(val text: String) : ComposerPlan

    /** Route to [ComradeCore.assignTaskTyped]. [peer] null is a note to self. */
    data class Task(val text: String, val peer: String?) : ComposerPlan

    /** Route to [ComradeCore.offerActionTyped]. */
    data class Offer(val action: AppAction, val peers: List<String>) : ComposerPlan

    /** Open a screen on this device. */
    data class Open(val action: AppAction) : ComposerPlan

    /** Start a `/play` flow with this query. */
    data class Play(val query: String) : ComposerPlan

    /** Show the command list. */
    data object Help : ComposerPlan

    /**
     * Cannot proceed, and here is the sentence to say. Covers both "this window
     * cannot do that" and "you have not said enough yet" — the composer treats
     * them the same way (say it, keep the text), so they are one case.
     */
    data class Explain(val message: String) : ComposerPlan
}

object ChatCommands {

    /**
     * Screens this Android build has.
     *
     * All five, unlike desktop — which is why the divergence is a named set on
     * both sides rather than an assumption of parity on either.
     */
    val AVAILABLE_SCREENS: Set<AppAction> = setOf(
        AppAction.BREATHE,
        AppAction.FOCUS,
        AppAction.JOURNAL,
        AppAction.TARA,
        AppAction.READ,
    )

    /**
     * What the composer should do with [command].
     *
     * [mentions] is the resolved form from [ComradeCore.resolveMentions], keyed
     * by handle. The one rule worth stating: **an unrecognised command never
     * sends.** A message silently swallowed is worse than one that arrives
     * confusing, because the sender believes they sent it.
     */
    fun planFor(
        command: ChatCommand,
        mentions: List<ComradeCore.MentionMatchInfo> = emptyList(),
    ): ComposerPlan = when (command) {
        is ChatCommand.Plain -> ComposerPlan.Send

        // Recognised so it is never called a typo, then sent as text exactly as
        // it always has been — the chip and the peer's own client do the rest.
        is ChatCommand.Pay -> ComposerPlan.Send

        is ChatCommand.Help -> ComposerPlan.Help

        is ChatCommand.AskTara ->
            if (command.text.isBlank()) {
                ComposerPlan.Explain("Say what you want to think through — only you will see it.")
            } else {
                ComposerPlan.Aside(command.text.trim())
            }

        is ChatCommand.Task -> {
            if (command.text.isBlank()) {
                ComposerPlan.Explain("What needs doing?")
            } else {
                when (val targets = resolve(command.assignees.map { it.handle }, mentions)) {
                    is Resolution.Problem -> ComposerPlan.Explain(targets.message)
                    is Resolution.Ok ->
                        ComposerPlan.Task(command.text.trim(), targets.npubs.firstOrNull())
                }
            }
        }

        is ChatCommand.OfferTo -> {
            if (command.targets.isEmpty()) {
                ComposerPlan.Explain("Name the comrade you want to offer this to, like @ana.")
            } else {
                when (val targets = resolve(command.targets.map { it.handle }, mentions)) {
                    is Resolution.Problem -> ComposerPlan.Explain(targets.message)
                    is Resolution.Ok -> ComposerPlan.Offer(command.action, targets.npubs)
                }
            }
        }

        is ChatCommand.Open ->
            if (command.action in AVAILABLE_SCREENS) {
                ComposerPlan.Open(command.action)
            } else {
                ComposerPlan.Explain("${labelFor(command.action)} isn't in this app yet.")
            }

        is ChatCommand.Play ->
            if (command.query.isBlank()) {
                ComposerPlan.Explain("Name a song, or paste a link.")
            } else {
                ComposerPlan.Play(command.query.trim())
            }

        is ChatCommand.Unknown ->
            ComposerPlan.Explain("There is no /${command.name} — type / to see what there is.")
    }

    private sealed interface Resolution {
        data class Ok(val npubs: List<String>) : Resolution
        data class Problem(val message: String) : Resolution
    }

    /**
     * Turn handles into npubs, or explain why not.
     *
     * The ambiguous case is the one that matters: a handle is a self-declared
     * alias and two contacts can answer to one, so picking the first is how a
     * private message reaches the wrong person.
     */
    private fun resolve(
        handles: List<String>,
        mentions: List<ComradeCore.MentionMatchInfo>,
    ): Resolution {
        val npubs = mutableListOf<String>()
        for (handle in handles) {
            val match = mentions.firstOrNull { it.handle == handle }
            when {
                match == null || (match.npub == null && match.candidates.isEmpty()) ->
                    return Resolution.Problem("@$handle isn't in your contacts.")
                match.npub == null ->
                    return Resolution.Problem(
                        "More than one contact answers to @$handle — pick which one.",
                    )
                else -> npubs.add(match.npub)
            }
        }
        return Resolution.Ok(npubs)
    }

    /** Human name for a destination, for the sentences above. */
    fun labelFor(action: AppAction): String = when (action) {
        AppAction.BREATHE -> "Taking a deep breath"
        AppAction.FOCUS -> "Focus sessions"
        AppAction.JOURNAL -> "The journal"
        AppAction.TARA -> "Tara"
        AppAction.READ -> "The reader"
    }

    /**
     * The `/` picker's rows for what has been typed so far, or an empty list when
     * the picker should be closed.
     *
     * Matches aliases as well as names, so typing `/listen` finds `play` — an
     * alias that works on submit but is invisible while typing reads as not
     * existing. Closes once there is a space, because what follows is an
     * argument rather than a name.
     */
    fun pickerRows(text: String, catalog: List<CommandSpec>): List<CommandSpec> {
        if (!text.startsWith("/")) return emptyList()
        if (text.any { it.isWhitespace() }) return emptyList()
        val typed = text.removePrefix("/").lowercase()
        return catalog.filter { spec ->
            spec.name.startsWith(typed) || spec.aliases.any { it.startsWith(typed) }
        }
    }

    /**
     * The text to put in the composer when a picker row is chosen.
     *
     * A trailing space when something follows, so the caret is already where the
     * next word goes; none when nothing does, so the send button submits instead
     * of sending `"/breathe "`.
     */
    fun completionFor(spec: CommandSpec): String =
        if (spec.argument.isNotEmpty() || spec.takesMention) "/${spec.name} " else "/${spec.name}"

    /**
     * Whether the composer should look like a private aside right now.
     *
     * Decided from the raw text rather than a parse, so it is true from the
     * moment `@tara ` is typed — before there is anything to parse. A private
     * thing that looks like a message is how somebody sends one by accident, so
     * this is deliberately eager.
     */
    fun isAsideDraft(text: String): Boolean {
        val t = text.trimStart()
        val lower = t.lowercase()
        for (prefix in listOf("@tara", "/tara")) {
            if (lower == prefix) return true
            if (lower.startsWith(prefix) && t.length > prefix.length &&
                t[prefix.length].isWhitespace()
            ) {
                return true
            }
        }
        return false
    }
}
