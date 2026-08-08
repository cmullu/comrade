package mullu.comrade.ui

import mullu.comrade.ComradeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun theComposerKnowsTheAudienceFromTheMomentTaraIsAddressed() {
        // Eager on purpose, and now in two directions: `/tara` never leaves the
        // device and `@tara` reaches the other person. Either label being wrong
        // is how somebody publishes a private thought, or asks a question the
        // peer turns out never to have seen.
        assertEquals(ChatCommands.TaraAudience.Shared, ChatCommands.taraDraft("@tara "))
        assertEquals(ChatCommands.TaraAudience.Shared, ChatCommands.taraDraft("@tara"))
        assertEquals(ChatCommands.TaraAudience.Shared, ChatCommands.taraDraft("@Tara what about"))
        assertEquals(ChatCommands.TaraAudience.Shared, ChatCommands.taraDraft("  @tara hello"))
        assertEquals(ChatCommands.TaraAudience.Private, ChatCommands.taraDraft("/tara hello"))
        assertEquals(ChatCommands.TaraAudience.Private, ChatCommands.taraDraft("/tara"))
    }

    @Test
    fun aPersonWhoseHandleStartsWithTaraIsNotTara() {
        assertEquals(null, ChatCommands.taraDraft("@taranjeet are you around"))
        assertEquals(null, ChatCommands.taraDraft("hello @tara"))
        assertEquals(null, ChatCommands.taraDraft(""))
    }

    // ── Tara in the room (`@tara …`) ──────────────────────────────────────────

    @Test
    fun sharedAndPrivateTaraAreDifferentPlans() {
        // Same words, two audiences. One plan reaches `tara_in_chat` (two
        // messages into the thread), the other `tara_aside` (nothing sent).
        assertEquals(
            ComposerPlan.TaraHere("what film should we watch"),
            ChatCommands.planFor(ChatCommand.TaraHere("what film should we watch")),
        )
        assertEquals(
            ComposerPlan.Aside("what film should we watch"),
            ChatCommands.planFor(ChatCommand.AskTara("what film should we watch")),
        )
    }

    @Test
    fun anEmptySharedAddressSaysTheAnswerWillBeSeenByBoth() {
        val plan = ChatCommands.planFor(ChatCommand.TaraHere(" "))
        assertTrue(plan is ComposerPlan.Explain)
        assertTrue((plan as ComposerPlan.Explain).message.contains("you'll both see"))
    }

    @Test
    fun theHelplinesAreShownAsLinesWhereThereIsNoCardToShowThem() {
        // AUDIT §8's honesty gate: the resources go beside *any* distress reply,
        // and the composer's note is a single Text with no card slot.
        val lines = ChatCommands.crisisLines(
            listOf(
                ComradeCore.CrisisResourceInfo("Tele-MANAS (India)", "14416", "24×7"),
                ComradeCore.CrisisResourceInfo("KIRAN", "1800-599-0019", "24×7"),
            ),
        )
        assertTrue(lines.contains("14416"))
        assertTrue(lines.contains("KIRAN"))
        // Empty in, empty out — a note must not end on a dangling heading.
        assertEquals("", ChatCommands.crisisLines(emptyList()))
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
        assertTrue(plan is ComposerPlan.Choose)
        val choose = plan as ComposerPlan.Choose
        assertEquals("ana", choose.handle)
        assertEquals(2, choose.candidates.size)
    }

    @Test
    fun choosingOneOfTwoLetsTheSameDraftThrough() {
        // The fix for the dead end: the old plan said "pick which one" and gave
        // nothing to pick, so the command could never be completed at all.
        val mentions = ChatCommands.withChoices(
            listOf(ambiguous("ana")),
            mapOf("ana" to "npub1b"),
        )
        assertEquals(
            ComposerPlan.Task("do it", "npub1b"),
            ChatCommands.planFor(ChatCommand.Task("do it", listOf(mention("ana"))), mentions),
        )
    }

    @Test
    fun aChoiceThatNamesNobodyOnTheListIsIgnored() {
        // A pin left over from an earlier draft, or from before a contact was
        // removed, must not silently retarget a message — that is the exact
        // failure the ambiguity exists to prevent, arriving by another door.
        val stale = ChatCommands.withChoices(
            listOf(ambiguous("ana")),
            mapOf("ana" to "npub1someone-else"),
        )
        assertEquals(null, stale[0].npub)
        assertTrue(
            ChatCommands.planFor(
                ChatCommand.Task("do it", listOf(mention("ana"))),
                stale,
            ) is ComposerPlan.Choose,
        )
    }

    @Test
    fun aChooserRowCarriesTheKeyBecauseBothMayShareTheName() {
        // The usual reason a handle is ambiguous is that both people chose the
        // same name, so a row showing only the name offers two identical
        // choices — which is no choice at all.
        val a = ChatCommands.candidateLabel("ana", "npub1aaaaaaaaaaaaaaaaaaaa")
        val b = ChatCommands.candidateLabel("ana", "npub1bbbbbbbbbbbbbbbbbbbb")
        assertFalse(a == b)
        assertTrue(a.contains("ana"))
    }

    @Test
    fun anAmbiguousHandleInAnOfferAsksTooRatherThanPickingOne() {
        val plan = ChatCommands.planFor(
            ChatCommand.OfferTo(AppAction.BREATHE, listOf(mention("ana"))),
            listOf(ambiguous("ana")),
        )
        assertTrue(plan is ComposerPlan.Choose)
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
    fun everyPlayRouteSaysSomethingAndOnlyTheTwoThatPlayClaimTheyDid() {
        // Three of the six routes are refusals, and a refusal naming the wrong
        // reason sends someone looking for a file picker when the real problem
        // is that no account is connected. So each route gets its own sentence,
        // and none may be blank.
        val started = setOf(PlayRoute.START_TOGETHER, PlayRoute.PLAY_ON_SERVICE)
        for (route in PlayRoute.entries) {
            val note = ChatCommands.playNote(route, MusicService.SPOTIFY, "Kun Faya Kun")
            assertTrue("$route", note.isNotBlank())
            if (route !in started) {
                assertFalse("$route", note.contains("Playing"))
            }
        }
        for (route in started) {
            assertTrue(
                "$route",
                ChatCommands.playNote(route, MusicService.SPOTIFY, "Kun Faya Kun")
                    .contains("Kun Faya Kun"),
            )
        }
    }

    @Test
    fun theServiceRouteNamesWhereItIsPlayingAndTheRefusalNamesWhatIsMissing() {
        // The two Spotify sentences must not read alike: one is a live session
        // on this listener's own subscription, the other is "you have no account
        // here". Before 2026-08-08 the refusal blamed DRM, which was the wrong
        // reason — driving their client is exactly what their SDK is for.
        val playing = ChatCommands.playNote(PlayRoute.PLAY_ON_SERVICE, MusicService.SPOTIFY, "x")
        val refused = ChatCommands.playNote(PlayRoute.OPEN_ELSEWHERE, MusicService.SPOTIFY, "x")
        assertTrue(playing.contains("Spotify"))
        assertTrue(refused.contains("Spotify"))
        assertNotEquals(playing, refused)
    }

    @Test
    fun aServiceRefusalNamesTheServiceItCannotReach() {
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
        // It has to name *its* reason — being unable to look — because that is
        // the half the other sentence cannot say.
        assertTrue(ChatCommands.LIBRARY_UNSEEN.contains("library"))
        // And it must not claim absence, which is the thing it does not know.
        assertFalse(ChatCommands.LIBRARY_UNSEEN.contains("No copy"))
        // Neither sentence sends anyone to a screen any more: both routes open
        // the picker themselves (`ConversationScreen`'s `onPickTogetherFile`),
        // so naming a destination would be naming one already arrived at.
        for (note in listOf(ChatCommands.LIBRARY_UNSEEN, absent)) {
            assertFalse(note, note.contains("Open Together"))
        }
    }

    @Test
    fun theAskForAFileRouteDoesNotBlameTheService() {
        // "You have no copy" and "Spotify won't allow it" are different
        // problems with different next steps; conflating them is the failure
        // these two cases exist to prevent.
        val note = ChatCommands.playNote(PlayRoute.ASK_FOR_FILE, MusicService.SPOTIFY, "x")
        assertFalse(note.contains("Spotify"))
        // Still has to say what is being asked for, now that the picker opening
        // is the action and this sentence is only the reason it opened.
        assertTrue(note.contains("file"))
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
