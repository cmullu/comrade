package mullu.comrade.ui

import mullu.comrade.ComradeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.comrade_core.AppAction
import uniffi.comrade_core.ChatCommand
import uniffi.comrade_core.CommandSpec
import uniffi.comrade_core.Mention
import uniffi.comrade_core.MusicService
import uniffi.comrade_ui.PlayRoute

/**
 * Mirrors `desktop/ui/chat_commands.test.mjs` case for case, so the two
 * frontends cannot drift on what a parsed command means. Where they differ on
 * purpose — Android has all five screens, desktop has two — each side's test
 * says so rather than one of them being silently wrong.
 */
class ChatCommandsTest {

    private fun mention(handle: String) =
        Mention(handle = handle, start = 0u, end = (handle.length + 1).toUInt())

    private fun resolved(handle: String, npub: String) = ComradeCore.MentionMatchInfo(
        handle = handle,
        start = 0,
        end = handle.length + 1,
        npub = npub,
        candidates = emptyList(),
    )

    private fun unknown(handle: String) = ComradeCore.MentionMatchInfo(
        handle = handle,
        start = 0,
        end = handle.length + 1,
        npub = null,
        candidates = emptyList(),
    )

    private fun ambiguous(handle: String) = ComradeCore.MentionMatchInfo(
        handle = handle,
        start = 0,
        end = handle.length + 1,
        npub = null,
        candidates = listOf(
            ComradeCore.ContactInfo("npub1a", "ana", null, false),
            ComradeCore.ContactInfo("npub1b", "ana", null, false),
        ),
    )

    private fun spec(
        name: String,
        aliases: List<String> = emptyList(),
        argument: String = "",
        takesMention: Boolean = false,
    ) = CommandSpec(
        name = name,
        aliases = aliases,
        argument = argument,
        help = "help for $name",
        takesMention = takesMention,
    )

    @Test
    fun ordinaryTextIsSentAsTyped() {
        assertEquals(ComposerPlan.Send, ChatCommands.planFor(ChatCommand.Plain))
    }

    @Test
    fun payStillGoesOutAsText() {
        // Recognised so it is never called a typo; nothing else changes.
        assertEquals(ComposerPlan.Send, ChatCommands.planFor(ChatCommand.Pay))
    }

    @Test
    fun anUnknownCommandIsReportedRatherThanSwallowed() {
        // A message silently eaten is worse than one that arrives confusing —
        // the sender believes they sent it.
        val plan = ChatCommands.planFor(ChatCommand.Unknown("frobnicate"))
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("/frobnicate"))
    }

    // ── Tara asides ──────────────────────────────────────────────────────────

    @Test
    fun anAsideRoutesToTaraNotToThePeer() {
        val plan = ChatCommands.planFor(ChatCommand.AskTara("i keep putting this off"))
        assertEquals(ComposerPlan.Aside("i keep putting this off"), plan)
    }

    @Test
    fun anAddressedButEmptyAsidePromptsInsteadOfSendingNothing() {
        val plan = ChatCommands.planFor(ChatCommand.AskTara("  "))
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("only you will see it"))
    }

    @Test
    fun theComposerLooksPrivateFromTheMomentAtTaraIsTyped() {
        // Eager on purpose: a private thing that looks like a message is how
        // somebody sends one by accident.
        assertTrue(ChatCommands.isAsideDraft("@tara "))
        assertTrue(ChatCommands.isAsideDraft("@tara"))
        assertTrue(ChatCommands.isAsideDraft("@Tara what about"))
        assertTrue(ChatCommands.isAsideDraft("/tara hello"))
        assertTrue(ChatCommands.isAsideDraft("  @tara hello"))
    }

    @Test
    fun aPersonWhoseHandleStartsWithTaraIsNotAnAside() {
        assertFalse(ChatCommands.isAsideDraft("@taranjeet are you around"))
        assertFalse(ChatCommands.isAsideDraft("hello @tara"))
        assertFalse(ChatCommands.isAsideDraft(""))
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    @Test
    fun aTaskWithNobodyNamedIsANoteToSelf() {
        val plan = ChatCommands.planFor(ChatCommand.Task("water the plants", emptyList()))
        assertEquals(ComposerPlan.Task("water the plants", null), plan)
    }

    @Test
    fun aTaskNamingAKnownContactCarriesTheirNpub() {
        val plan = ChatCommands.planFor(
            ChatCommand.Task("get some work done", listOf(mention("ana"))),
            listOf(resolved("ana", "npub1ana")),
        )
        assertEquals(ComposerPlan.Task("get some work done", "npub1ana"), plan)
    }

    @Test
    fun anEmptyTaskAsksWhatNeedsDoing() {
        val plan = ChatCommands.planFor(ChatCommand.Task("   ", emptyList()))
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("What needs doing"))
    }

    @Test
    fun aHandleThatIsNobodysIsReportedRatherThanGuessedAt() {
        val plan = ChatCommands.planFor(
            ChatCommand.Task("do it", listOf(mention("bina"))),
            listOf(unknown("bina")),
        )
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("@bina isn't in your contacts"))
    }

    @Test
    fun twoContactsAnsweringToOneHandleBecomeAQuestionNotACoinFlip() {
        // A handle is a self-declared alias; anyone may publish any name.
        // Picking one is how a private message reaches the wrong person.
        val plan = ChatCommands.planFor(
            ChatCommand.Task("do it", listOf(mention("ana"))),
            listOf(ambiguous("ana")),
        )
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("More than one contact"))
    }

    // ── Offers ───────────────────────────────────────────────────────────────

    @Test
    fun anOfferToAKnownComradeCarriesTheActionAndTheNpub() {
        val plan = ChatCommands.planFor(
            ChatCommand.OfferTo(AppAction.BREATHE, listOf(mention("ana"))),
            listOf(resolved("ana", "npub1ana")),
        )
        assertEquals(ComposerPlan.Offer(AppAction.BREATHE, listOf("npub1ana")), plan)
    }

    @Test
    fun anOfferWithNobodyNamedAsksWhoItIsFor() {
        val plan = ChatCommands.planFor(ChatCommand.OfferTo(AppAction.BREATHE, emptyList()))
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("Name the comrade"))
    }

    // ── Screens ──────────────────────────────────────────────────────────────

    @Test
    fun everyScreenThisAppHasOpens() {
        // Android has all five, which is the deliberate divergence from the
        // desktop module's two.
        for (action in AppAction.entries) {
            assertTrue("$action", action in ChatCommands.AVAILABLE_SCREENS)
            assertEquals(ComposerPlan.Open(action), ChatCommands.planFor(ChatCommand.Open(action)))
        }
    }

    @Test
    fun everyActionHasALabelForTheSentences() {
        for (action in AppAction.entries) {
            assertTrue("$action", ChatCommands.labelFor(action).isNotBlank())
        }
    }

    // ── Play ─────────────────────────────────────────────────────────────────

    @Test
    fun playCarriesTheQueryAndAnEmptyOneAsksForOne() {
        assertEquals(
            ComposerPlan.Play("Kun Faya Kun", null),
            ChatCommands.planFor(ChatCommand.Play("Kun Faya Kun", null)),
        )
        val empty = ChatCommands.planFor(ChatCommand.Play("  ", null))
        assertTrue(empty is ComposerPlan.Explain)
    }

    @Test
    fun playCarriesTheServiceTheCommandNamed() {
        // `/spotify kun faya kun` has to reach `play_query`, which is where a
        // link's own service is allowed to outrank the alias that was typed.
        assertEquals(
            ComposerPlan.Play("Kun Faya Kun", MusicService.SPOTIFY),
            ChatCommands.planFor(ChatCommand.Play("Kun Faya Kun", MusicService.SPOTIFY)),
        )
    }

    @Test
    fun everyPlayRouteSaysSomethingAndOnlyOneClaimsItStarted() {
        // Three of the five routes are refusals, and a refusal naming the wrong
        // reason sends someone looking for a file picker when the real problem
        // is DRM. So each route gets its own sentence, and none may be blank.
        for (route in PlayRoute.entries) {
            val note = ChatCommands.playNote(route, MusicService.SPOTIFY, "Kun Faya Kun")
            assertTrue("$route", note.isNotBlank())
            if (route != PlayRoute.START_TOGETHER) {
                assertFalse("$route", note.contains("Playing"))
            }
        }
        assertTrue(
            ChatCommands.playNote(PlayRoute.START_TOGETHER, null, "Kun Faya Kun")
                .contains("Kun Faya Kun"),
        )
    }

    @Test
    fun aDrmRefusalNamesTheServiceThatRefused() {
        assertTrue(
            ChatCommands.playNote(PlayRoute.OPEN_ELSEWHERE, MusicService.SPOTIFY, "x")
                .contains("Spotify"),
        )
        assertTrue(
            ChatCommands.playNote(PlayRoute.OPEN_ELSEWHERE, MusicService.APPLE_MUSIC, "x")
                .contains("Apple Music"),
        )
        // No service on a DRM link is possible; a vague true sentence beats a
        // confidently wrong brand name.
        assertFalse(
            ChatCommands.playNote(PlayRoute.OPEN_ELSEWHERE, null, "x").contains("Spotify"),
        )
    }

    @Test
    fun everyServiceHasAName() {
        for (service in MusicService.entries) {
            assertTrue("$service", ChatCommands.serviceLabel(service).isNotBlank())
        }
        assertTrue(ChatCommands.serviceLabel(null).isNotBlank())
    }

    @Test
    fun notAllowedToLookReadsDifferentlyFromLookedAndNotThere() {
        // The two are different problems: one is answered by picking a file, the
        // other by granting access. A single sentence for both would send
        // someone hunting for a file that is already on the phone.
        val absent = ChatCommands.playNote(PlayRoute.ASK_FOR_FILE, null, "Kun Faya Kun")
        assertTrue(ChatCommands.LIBRARY_UNSEEN != absent)
        assertTrue(ChatCommands.LIBRARY_UNSEEN.contains("Together"))
        // And it must not claim absence, which is the thing it does not know.
        assertFalse(ChatCommands.LIBRARY_UNSEEN.contains("No copy"))
    }

    @Test
    fun theAskForAFileRouteDoesNotBlameTheService() {
        // "You have no copy" and "Spotify won't allow it" are different
        // problems with different next steps; conflating them is the failure
        // these two cases exist to prevent.
        val note = ChatCommands.playNote(PlayRoute.ASK_FOR_FILE, MusicService.SPOTIFY, "x")
        assertFalse(note.contains("Spotify"))
        assertTrue(note.contains("Together"))
    }

    // ── The / picker ─────────────────────────────────────────────────────────

    private val catalog = listOf(
        spec("play", listOf("listen", "watch"), "<song>"),
        spec("task", listOf("todo"), "<what>", takesMention = true),
        spec("breathe", listOf("breath")),
    )

    @Test
    fun thePickerMatchesAliasesAsWellAsNames() {
        // An alias that works on submit but is invisible while typing reads as
        // not existing.
        val rows = ChatCommands.pickerRows("/lis", catalog)
        assertEquals(1, rows.size)
        assertEquals("play", rows[0].name)
    }

    @Test
    fun thePickerIsClosedForOrdinaryText() {
        assertTrue(ChatCommands.pickerRows("hello", catalog).isEmpty())
        assertTrue(ChatCommands.pickerRows("20/80 split", catalog).isEmpty())
        assertTrue(ChatCommands.pickerRows("/nomatch", catalog).isEmpty())
    }

    @Test
    fun thePickerClosesOnceTheCommandHasBeenChosen() {
        // A space means what follows is an argument, not a name.
        assertTrue(ChatCommands.pickerRows("/task ship it", catalog).isEmpty())
    }

    @Test
    fun aBareSlashOffersEverything() {
        assertEquals(3, ChatCommands.pickerRows("/", catalog).size)
    }

    @Test
    fun completionLeavesTheCaretWhereTheNextWordGoesOrSubmits() {
        assertEquals("/play ", ChatCommands.completionFor(catalog[0]))
        assertEquals("/task ", ChatCommands.completionFor(catalog[1]))
        // Nothing follows /breathe, so no trailing space — the send button
        // submits instead of sending "/breathe ".
        assertEquals("/breathe", ChatCommands.completionFor(catalog[2]))
    }
}
