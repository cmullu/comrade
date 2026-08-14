package mullu.comrade.ui

import mullu.comrade.ComradeCore
import uniffi.comrade_core.AppAction
import uniffi.comrade_core.ChatCommand
import uniffi.comrade_core.CommandSpec
import uniffi.comrade_core.MusicService
import uniffi.comrade_ui.PlayRoute

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

    /** Route to [ComradeCore.taraAsideTyped]; never to a DM. `/tara`. */
    data class Aside(val text: String) : ComposerPlan

    /**
     * Route to [ComradeCore.taraInChatTyped] — `@tara`, which the peer sees.
     *
     * Separate from [Aside] because the audience is different, and a composer
     * that treated them the same would either publish a private thought or hide
     * an answer the other person was waiting for.
     */
    data class TaraHere(val text: String) : ComposerPlan

    /**
     * Two contacts answer to one `@handle`, so the command cannot run until the
     * user says which person they meant.
     *
     * Not an [Explain]: the old behaviour said "pick which one" and offered
     * nothing to pick, which is a dead end dressed as an instruction. The
     * composer shows [candidates] and re-runs the same draft once one is chosen
     * (see [withChoices]).
     */
    data class Choose(
        val handle: String,
        val candidates: List<ComradeCore.ContactInfo>,
    ) : ComposerPlan

    /** Route to [ComradeCore.assignTaskTyped]. [peer] null is a note to self. */
    data class Task(val text: String, val peer: String?) : ComposerPlan

    /**
     * File the thread containing [messageId] under [slug] —
     * [ComradeCore.assignThreadTyped].
     *
     * [messageId] is whatever the composer had selected, not the thread root:
     * core walks up the reply chain, so a frontend cannot file the wrong thread
     * by picking the wrong end of one.
     */
    data class AssignTopic(val slug: String, val messageId: String) : ComposerPlan

    /**
     * Open the topic sheet — a bare `/assign`, which is the discoverable way in
     * rather than a refusal.
     *
     * Not an [Explain]: "name a topic" with no way to see which topics exist is
     * the same dead end [Choose] was created to fix.
     */
    data object OpenTopics : ComposerPlan

    /** Route to [ComradeCore.offerActionTyped]. */
    data class Offer(val action: AppAction, val peers: List<String>) : ComposerPlan

    /** Open a screen on this device. */
    data class Open(val action: AppAction) : ComposerPlan

    /**
     * Start a `/play` flow with this query.
     *
     * [service] is what the *command* named (`/spotify …`), not what the query
     * turns out to be — a Spotify URL pasted after `/youtube` is still Spotify,
     * and `comrade_ui::play_query` is where that is settled.
     */
    data class Play(val query: String, val service: MusicService?) : ComposerPlan

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
        replyTarget: String? = null,
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

        is ChatCommand.TaraHere ->
            if (command.text.isBlank()) {
                ComposerPlan.Explain("Ask her something — you'll both see her answer.")
            } else {
                ComposerPlan.TaraHere(command.text.trim())
            }

        is ChatCommand.Task -> {
            if (command.text.isBlank()) {
                ComposerPlan.Explain("What needs doing?")
            } else {
                when (val targets = resolve(command.assignees.map { it.handle }, mentions)) {
                    is Resolution.Problem -> ComposerPlan.Explain(targets.message)
                    is Resolution.Ambiguous ->
                        ComposerPlan.Choose(targets.handle, targets.candidates)
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
                    is Resolution.Ambiguous ->
                        ComposerPlan.Choose(targets.handle, targets.candidates)
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
                ComposerPlan.Play(command.query.trim(), command.service)
            }

        // The three-way answer is `TopicDecisions.planAssign`'s, shared with the
        // desktop and pinned by mirrored vectors — this only turns it into the
        // sentence Android says.
        is ChatCommand.AssignTopic ->
            when (
                val plan = mullu.comrade.topic.TopicDecisions.planAssign(
                    command.topics.map { it.slug },
                    replyTarget,
                )
            ) {
                is mullu.comrade.topic.TopicDecisions.AssignPlan.OpenPicker ->
                    ComposerPlan.OpenTopics
                is mullu.comrade.topic.TopicDecisions.AssignPlan.NeedsTarget ->
                    ComposerPlan.Explain(
                        "Reply to a message first — then /assign #${plan.slug} files that thread.",
                    )
                is mullu.comrade.topic.TopicDecisions.AssignPlan.File ->
                    ComposerPlan.AssignTopic(plan.slug, plan.messageId)
            }

        is ChatCommand.Unknown ->
            ComposerPlan.Explain("There is no /${command.name} — type / to see what there is.")
    }

    private sealed interface Resolution {
        data class Ok(val npubs: List<String>) : Resolution
        data class Ambiguous(
            val handle: String,
            val candidates: List<ComradeCore.ContactInfo>,
        ) : Resolution

        data class Problem(val message: String) : Resolution
    }

    /**
     * Turn handles into npubs, or say what stopped it.
     *
     * The ambiguous case is the one that matters: a handle is a self-declared
     * alias and two contacts can answer to one, so picking the first is how a
     * private message reaches the wrong person. It comes back as
     * [Resolution.Ambiguous] rather than an error, because the answer exists —
     * only the user has it.
     *
     * One handle at a time, deliberately: `/comrade-breathe @ana @ana` would have
     * two questions to ask, and asking them at once means a chooser that can be
     * half-answered. The first unresolved handle is returned and the next is
     * asked about on the re-run.
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
                match.npub == null -> return Resolution.Ambiguous(handle, match.candidates)
                else -> npubs.add(match.npub)
            }
        }
        return Resolution.Ok(npubs)
    }

    /**
     * Apply the choices a user has made for ambiguous handles, so re-running the
     * same draft reaches the person they picked.
     *
     * A choice is honoured **only** when it names one of that handle's own
     * candidates. Without that check, a pin left over from an earlier draft — or
     * from before a contact was removed — would silently retarget a message,
     * which is the exact failure the ambiguity exists to prevent.
     */
    fun withChoices(
        mentions: List<ComradeCore.MentionMatchInfo>,
        chosen: Map<String, String>,
    ): List<ComradeCore.MentionMatchInfo> = mentions.map { match ->
        val pick = chosen[match.handle]
        if (match.npub == null && pick != null && match.candidates.any { it.npub == pick }) {
            match.copy(npub = pick)
        } else {
            match
        }
    }

    /**
     * How one candidate reads in the chooser.
     *
     * The key is not decoration here: two contacts answering to one handle very
     * often have the *same alias too* — that is why the handle is ambiguous — so
     * a row showing only the name would offer two identical choices. [title] is
     * the caller's resolved display name; the short key is what tells them apart.
     */
    fun candidateLabel(title: String, npub: String): String = "$title · ${shortNpub(npub)}"

    /**
     * The helplines as the composer can show them.
     *
     * `AUDIT.md` §8's honesty gate says the resources appear beside *any* reply
     * that tripped the distress detector — and Tara can now be asked from a chat,
     * where the only affordance is a single `Text`. So `TaraScreen.kt`'s
     * `CrisisCard` gets a plain-text twin rather than the gate quietly applying to
     * one screen. Empty in, empty out: a note must not end with a dangling
     * heading when core returned nothing.
     */
    fun crisisLines(resources: List<ComradeCore.CrisisResourceInfo>): String =
        if (resources.isEmpty()) {
            ""
        } else {
            "You don't have to carry this alone:\n" +
                resources.joinToString("\n") { "· ${it.name} — ${it.contact}" }
        }

    /**
     * What to say after a `/play`, given the route core decided.
     *
     * Pure and tested, because every one of these sentences is a claim about
     * what the app just did or can do, and three of the five are refusals. A
     * refusal that names the wrong reason is worse than none: it sends someone
     * looking for a file picker when the real problem is that Spotify will not
     * let another app decode its audio.
     *
     * Returns `null` for [PlayRoute.START_TOGETHER] handled with a title — the
     * caller says which recording opened, which it knows and this does not.
     */
    fun playNote(route: PlayRoute, service: MusicService?, query: String): String = when (route) {
        // No longer "it's under Together": the session overlay covers the app
        // the moment one exists, so it is already on screen by the time this is
        // read, and sending someone to look for it was sending them nowhere.
        PlayRoute.START_TOGETHER -> "Playing \"$query\" together."
        // The confidence bar did its job — opening the wrong track on someone's
        // behalf is worse than asking. The picker is already open by the time
        // this is read, so this says *why* it opened rather than what to do.
        PlayRoute.ASK_FOR_FILE ->
            "No copy of \"$query\" on this phone — pick the file."
        // Reworded 2026-08-08. The old sentence said the service "won't let
        // another app play its audio", and that was never quite the reason —
        // decoding their bytes is out, but driving playback inside their own
        // client on this listener's own subscription is exactly what their SDKs
        // are for. What is actually missing on this device is the connected
        // account, so that is what it now says. See docs/TOGETHER.md §11.
        PlayRoute.OPEN_ELSEWHERE ->
            "No ${serviceLabel(service)} account connected here — open it there."
        // The account is connected and the track can be driven: a real session,
        // playing from this listener's own subscription. Nothing is shared but
        // the playhead.
        PlayRoute.PLAY_ON_SERVICE ->
            "Playing \"$query\" together on ${serviceLabel(service)}."
        // Reworded 2026-08-08, when the embed landed. This used to refuse —
        // "Comrade can't play YouTube here" — because core could carry the
        // invitation and nothing on this side could play it. It can now, and the
        // sentence has to change with it or the app tells people it cannot do
        // the thing it just did (docs/TOGETHER.md §11b).
        //
        // The one route to "play something neither of us has" that needs no
        // account on either side, which is worth saying in the note: the query
        // is a link, so there is nothing about a local copy to explain.
        PlayRoute.PLAY_EMBED -> "Watching that together — neither of you needs a copy."
        PlayRoute.NOTHING -> "Name a song, or paste a link."
    }

    /**
     * What to say when the route is "no copy here" but this device was never
     * allowed to look.
     *
     * Separate from [playNote]'s `ASK_FOR_FILE` on purpose: that sentence says a
     * copy is not on the phone, and saying it when Comrade simply cannot read
     * the music library would send someone hunting for a file they already have.
     * `LibraryResolver.mayRead` is what distinguishes them.
     */
    const val LIBRARY_UNSEEN: String =
        "Comrade can't read your music library, so it can't look — pick the file."

    /** Human name for a music service, for [playNote]. */
    fun serviceLabel(service: MusicService?): String = when (service) {
        MusicService.SPOTIFY -> "Spotify"
        MusicService.APPLE_MUSIC -> "Apple Music"
        MusicService.YOUTUBE -> "YouTube"
        // A link we recognised as DRM-bearing but whose service did not survive
        // — better a true vague sentence than a confidently wrong brand.
        null -> "That service"
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
     * Who will read what is in the composer, when Tara is being addressed.
     *
     * The sigil is the whole difference, so the composer has to show it *before*
     * the send button is pressed — a private thought that looks like a message,
     * or a question the other person turns out never to have seen, are both
     * failures of this one label.
     */
    enum class TaraAudience {
        /** `/tara` — never leaves the device. */
        Private,

        /** `@tara` — the question and her answer both go to the peer. */
        Shared,
    }

    /**
     * Which Tara audience the raw draft implies, or null if she is not addressed.
     *
     * Decided from the raw text rather than a parse, so it is right from the
     * moment `@tara ` is typed — before there is anything to parse. Deliberately
     * eager for the same reason it always was: mislabelling this is how somebody
     * sends a private thought by accident.
     */
    fun taraDraft(text: String): TaraAudience? {
        val t = text.trimStart()
        val lower = t.lowercase()
        for ((prefix, audience) in
            listOf("@tara" to TaraAudience.Shared, "/tara" to TaraAudience.Private)
        ) {
            if (lower == prefix) return audience
            if (lower.startsWith(prefix) && t.length > prefix.length &&
                t[prefix.length].isWhitespace()
            ) {
                return audience
            }
        }
        return null
    }
}
