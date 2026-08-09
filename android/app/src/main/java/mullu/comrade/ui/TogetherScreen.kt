package mullu.comrade.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.call.CallManager
import mullu.comrade.together.LibraryResolver
import mullu.comrade.together.MediaLibraryAccess
import mullu.comrade.together.MediaSessionAccess
import mullu.comrade.together.MusicLibrary
import mullu.comrade.together.PlaybackModeDecision
import mullu.comrade.together.PlaybackOwnership
import mullu.comrade.together.ShareTransfer
import mullu.comrade.together.TogetherDecisions
import mullu.comrade.together.TogetherManager
import java.util.Locale

/**
 * The listen-together surface, and **the only way into a session** since
 * 2026-08-08.
 *
 * It used to be a screen you arrived at, having started a session from a ▶ in a
 * conversation's header. That button is gone and this is the whole flow: choose
 * something — the music on this phone, a file, or a link — then choose who to
 * listen with, and they get asked. One place, so "how do I listen with someone"
 * has one answer instead of depending on which screen you happen to be on.
 *
 * ## What still is not here
 * No sync logic at all. Every decision about what the player does lives in
 * [TogetherDecisions] (pure, unit-tested — the only half of this feature the JVM
 * lane can check before CI) and `comrade_core::together` (shared with desktop),
 * and the session outlives this composition because [TogetherManager] and its
 * foreground service own it. Disposing this screen must not stop the music.
 *
 * The two rules in the copy are the ones `docs/PRESENCE.md` §5 sets and
 * `docs/TOGETHER.md` §7 repeats: it never says "synced" or "in sync", because we
 * do not know that; and when the heartbeats stop it says we lost track of
 * *them*, because that is what we observed.
 *
 * @param onPickFileWith open the file picker to start a session with this person
 * @param onPickFileToJoin open the file picker to answer an invitation
 */
@Composable
fun TogetherScreen(
    onPickFileWith: (peer: String, label: String) -> Unit,
    onPickFileToJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by TogetherManager.state.collectAsState()
    val context = LocalContext.current

    // An invitation is the one moment reading the library is obviously worth
    // something, so it is the one place besides `/play` that asks. Two separate
    // flags rather than one tri-state, because "refused" and "looked and it
    // isn't here" are different answers and only the second one has anything to
    // say — after a refusal the Join button already offers the route that needs
    // no permission. Hoisted out of the `when` so the launcher is created on
    // every composition rather than only while an invitation is showing.
    var libraryAsked by remember { mutableStateOf(false) }
    var libraryMissed by remember { mutableStateOf(false) }
    // Following another app is a *special access*: there is no dialog to launch
    // and no result to receive, only a system settings screen the user may or
    // may not have acted on. So the state here is the refusal to explain, and it
    // is re-asked on the next tap rather than watched for — which is also the
    // only honest way to detect the grant, since coming back from settings
    // produces no callback of any kind.
    var followRefusal by remember { mutableStateOf<TogetherManager.FollowRefusal?>(null) }

    // Streaming what this device is playing (docs/TOGETHER.md §15). Two system
    // prompts in sequence, both hoisted out of the `when` for the same reason
    // the library launcher is: a launcher created inside a branch is created and
    // destroyed as the session changes state.
    //
    // RECORD_AUDIO first, and it is worth knowing *why* a feature that is not
    // about the microphone asks for it: the media audio joins the outgoing
    // stream on the capture path, so there has to be a capture running at all.
    // A refusal is not fatal — the picture still goes.
    val askToCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Refused is a real answer: the picture streams with no sound, rather
        // than nothing happening and the button looking broken.
        TogetherManager.startStreamingFromConsent(context, result.resultCode, result.data)
    }
    val askToRecord = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Granted or not, go on to the capture consent — the two failures are
        // independent and the picture does not depend on either.
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val intent = manager?.createScreenCaptureIntent()
        if (intent != null) askToCapture.launch(intent)
    }
    val askToReadLibrary = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runCatching { MediaLibraryAccess.rememberAsked(context) }
        libraryAsked = true
        // A match starts the session, so this only ever reads true on the
        // "allowed to look, and it is genuinely not here" path.
        libraryMissed = granted && !TogetherManager.lookAgain(context)
    }
    // The microphone, which is now offered in every mode rather than only in a
    // streamed one. A separate launcher from `askToRecord` above even though
    // both ask for `RECORD_AUDIO`, because what follows a grant is completely
    // different: that one goes on to the screen-capture consent, this one opens
    // a voice channel and nothing else.
    //
    // The microphone cannot be switched on right now, and it is not the
    // permission. One case only: a picture is already arriving on the single
    // connection this session has, and adding our voice to it means
    // renegotiating, which would take the picture away to add a microphone.
    var micBlocked by remember { mutableStateOf(false) }
    val askToTalk = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // A refusal is silent on purpose: they were just shown the system
        // dialog and said no, so a sentence explaining the dialog they closed
        // would be telling them what they already decided.
        if (granted) micBlocked = !TogetherManager.toggleMic(context)
    }

    // Choosing something *else* to play, without being asked who with again.
    // Held here rather than inside `LiveSession` because it survives the
    // session states the chooser passes through — it is open across the end of
    // the old session and the start of the new one.
    var choosingAgain by remember { mutableStateOf(false) }

    TogetherOverlay(modifier) {
        val s = state
        // The chooser covers two cases and they are the same screen: nothing is
        // playing, or something is and they want something else. The pairing is
        // what carries between them — `PlayerHome` reads it and skips the
        // who-with sheet, so putting a second thing on is one step rather than
        // starting over.
        //
        // Written as an `if` rather than a `when` guard because guards are a
        // Kotlin 2.1 feature and this module is on 1.9.22.
        val choosing = s is TogetherManager.UiState.Idle ||
            (s is TogetherManager.UiState.Live && choosingAgain)
        if (choosing) {
            // Not wrapped in the scrolling column below: it owns its own
            // scrolling, because a library of two thousand tracks is a
            // LazyColumn and nesting one inside a `verticalScroll` measures
            // every row.
            PlayerHome(
                onPickFileWith = onPickFileWith,
                onClose = if (choosingAgain) ({ choosingAgain = false }) else null,
                onStarted = { choosingAgain = false },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // A video surface plus controls plus the two honest notes
                    // overflows a short screen in landscape, which is exactly
                    // the orientation a film is watched in.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (s) {
                    is TogetherManager.UiState.Invited -> {
                        Text(
                            stringResource(R.string.together_invited, s.peerLabel, s.title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // **What Join does is `TogetherDecisions.joinAction`'s
                            // to say, and it changed on 2026-08-08.** It used to
                            // open the document picker for a local file and ask
                            // the person to find their own copy — of the thing
                            // the invitation exists *because* they do not have.
                            // The answer that gets them to a player is their
                            // copy, over the session's own connection, playing
                            // as it lands (§12); the picker is the second
                            // answer, for whoever does have the file and would
                            // rather not spend the bytes.
                            when (TogetherDecisions.joinAction(s.youtube, s.contentKind)) {
                                TogetherDecisions.JoinAction.WatchVideo ->
                                    Button(onClick = { TogetherManager.joinEmbed(context) }) {
                                        Text(stringResource(R.string.together_join_video))
                                    }

                                TogetherDecisions.JoinAction.FetchTheStream ->
                                    Button(onClick = { TogetherManager.joinStream(context) }) {
                                        Text(stringResource(R.string.together_join_stream))
                                    }

                                TogetherDecisions.JoinAction.TakeTheirCopy -> {
                                    Button(onClick = { TogetherManager.askForTheirCopy(context) }) {
                                        Text(stringResource(R.string.together_join))
                                    }
                                    TextButton(onClick = onPickFileToJoin) {
                                        Text(stringResource(R.string.together_join_own_copy))
                                    }
                                }
                            }
                            TextButton(onClick = { TogetherManager.leave() }) {
                                Text(stringResource(R.string.together_not_now))
                            }
                        }
                        // What joining a stream actually does, before they do
                        // it: their device fetches a URL the other person named.
                        if (s.contentKind == TogetherDecisions.STREAM_KIND) {
                            Text(
                                stringResource(R.string.together_stream_join_note),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        FollowWhatIsPlaying(
                            invited = s,
                            refusal = followRefusal,
                            onTry = { followRefusal = TogetherManager.followExternal(context) },
                        )
                        // Only when a lookup could find anything: a YouTube or
                        // stream invitation names no recording we could match,
                        // and a blank title means none was carried.
                        val couldLook = !s.youtube &&
                            s.contentKind != TogetherDecisions.STREAM_KIND &&
                            s.title.isNotBlank()
                        // The same one-ask rule `/play` follows, and for the
                        // same reason: someone who has already refused gets no
                        // dialog from Android, so offering the button again would
                        // be offering a button that does nothing. `libraryAsked`
                        // is the local half — the preference is what persists,
                        // this is what recomposes.
                        val step = MediaLibraryAccess.next(
                            granted = runCatching { LibraryResolver.mayRead(context) }
                                .getOrDefault(false),
                            askedBefore = libraryAsked ||
                                runCatching { MediaLibraryAccess.asked(context) }.getOrDefault(true),
                        )
                        if (couldLook && step == MediaLibraryAccess.Step.Ask) {
                            TextButton(onClick = {
                                askToReadLibrary.launch(
                                    MediaLibraryAccess.permissionFor(Build.VERSION.SDK_INT),
                                )
                            }) {
                                Text(stringResource(R.string.together_look_in_library))
                            }
                        }
                        if (libraryMissed) {
                            Text(
                                stringResource(R.string.together_library_missed),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    is TogetherManager.UiState.Live -> LiveSession(
                        s = s,
                        onStream = {
                            askToRecord.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        micBlocked = micBlocked,
                        onMic = {
                            micBlocked = false
                            if (!TogetherManager.toggleMic(context)) {
                                // Two reasons it can say no, and only one of
                                // them has a dialog behind it.
                                if (TogetherManager.micNeedsPermission(context)) {
                                    askToTalk.launch(android.Manifest.permission.RECORD_AUDIO)
                                } else {
                                    micBlocked = true
                                }
                            }
                        },
                        onPlaySomethingElse = { choosingAgain = true },
                    )

                    // Covered above; the compiler needs the arm.
                    is TogetherManager.UiState.Idle -> Unit
                }

                // Outside the inner `when` on purpose: the relay question can
                // arrive while a handover is running in any of these states, and
                // it must not be possible to leave it unanswered by whatever the
                // session does next.
                ShareRelayConsent()
            }
        }
    }
}

/**
 * The full-screen backdrop this screen is drawn on.
 *
 * `MainActivity` stacks this over the whole app for an invitation, so without a
 * background of its own the session drew as floating text over whatever tab was
 * behind it — the chat list showing through the film's controls — and taps on
 * the gaps between the controls reached that tab instead of stopping here. Both
 * halves of that are fixed in this one composable, matching `CallOverlay` in
 * `call/CallScreen.kt`, which covers the app the same way for the same reason.
 *
 * **It used to paint its own dark blue gradient, and that was the wrong call.**
 * The argument was that a picture wants a dark chrome around it whatever the
 * system theme says — true of a film, and this tab is mostly a music player,
 * reached by tapping a bottom-nav item next to four screens that do follow the
 * theme. So it landed as the one tab that ignored Material You and read as a
 * different app. It now sits on `colorScheme` like everything else; a hard-coded
 * dark room is not worth being the odd one out for.
 */
@Composable
private fun TogetherOverlay(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow taps that miss a control. Compose routes a tap on an
            // unhandled area to whatever sits behind it, so a background alone
            // would still let someone open a chat through the film.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) { content() }
}

/*
 * The screen's five colours, every one of them a `colorScheme` token.
 *
 * Composable getters rather than constants, which is what makes them follow the
 * theme — including Material You, which the rest of the app has and this screen
 * did not. The names are kept short because they appear on nearly every line
 * below; what they must not do is claim a colour the theme is not providing,
 * which is why the old `OnDark`/`CardColor` pair went with the gradient.
 */

/** The sleeve behind the artwork, and the badge behind a source icon. */
private val TogetherSleeve: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/** Cards on the backdrop — the same fill the other tabs' rows use. */
private val TogetherCard: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private val TogetherText: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground

private val TogetherMuted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** How far the skip buttons move. Matches the desktop transport. */
private const val SKIP_MS: Long = 10_000

// ── Choosing something to play ───────────────────────────────────────────────

/** Where the home screen is in the two-step "what, then who" flow. */
private sealed interface HomeStep {
    data object Choosing : HomeStep
    data object Browsing : HomeStep
    data object Linking : HomeStep
    data object Searching : HomeStep
}

/**
 * What has been chosen and is waiting for a person to play it with.
 *
 * The "what" is settled before the "who" is asked, which is the order the flow
 * reads in: you find something, then you think of someone. The reverse order was
 * what the old ▶-in-a-chat did, and it made "listen to music with a friend" a
 * thing you could only start from a conversation.
 */
private sealed interface Chosen {
    /**
     * @param queue the list it was picked out of, so prev and next mean the
     *   list the person was looking at rather than the whole library.
     */
    data class Track(
        val track: TogetherDecisions.Track,
        val queue: List<TogetherDecisions.Track>,
    ) : Chosen

    /** A YouTube video or a public media URL, already classified by core. */
    data class Link(val link: TogetherDecisions.Link) : Chosen

    /**
     * A file to be picked once we know who for.
     *
     * The one case where the picker cannot run first: its result arrives in
     * `MainActivity`, which has to know who the session is with before it can
     * start one — so the person is chosen and then the picker opens.
     */
    data object AFile : Chosen
}

/**
 * The tab's own screen: three ways to start, and the people to start with.
 *
 * **"And who with?" is asked once per session, not once per track.** The person
 * is [TogetherManager.pairing] and the step is
 * [TogetherDecisions.startStep], so the sheet appears when there is nobody yet
 * and never again for the length of the session. Choosing something while the
 * *other* person is the one playing is the one case that stops to ask, because
 * it takes their music off on both devices.
 *
 * @param onClose the way back to a session that is already running, or `null`
 *   when this is the idle tab and there is nothing behind it.
 * @param onStarted something began playing, so the caller can put the session
 *   back on screen.
 */
@Composable
private fun PlayerHome(
    onPickFileWith: (peer: String, label: String) -> Unit,
    onClose: (() -> Unit)?,
    onStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf<HomeStep>(HomeStep.Choosing) }
    var chosen by remember { mutableStateOf<Chosen?>(null) }
    // Chosen, and waiting for a yes because it would stop what they put on.
    var confirming by remember { mutableStateOf<Pair<Chosen, TogetherDecisions.Pairing>?>(null) }
    val pairing by TogetherManager.pairing.collectAsState()
    val live by TogetherManager.state.collectAsState()
    // A start that failed. Held rather than logged and forgotten: core can
    // refuse the content (a stream URL it will not admit), and a tap that
    // produced neither a session nor a sentence is the failure mode the rest of
    // this feature is written to avoid.
    var failed by remember { mutableStateOf(false) }
    // Held rather than derived, because a grant arrives while this screen is on
    // screen: the launcher's result sets it and the browser redraws.
    var libraryGranted by remember {
        mutableStateOf(runCatching { LibraryResolver.mayRead(context) }.getOrDefault(false))
    }
    val askToReadLibrary = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runCatching { MediaLibraryAccess.rememberAsked(context) }
        libraryGranted = granted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        if (failed) {
            Text(
                stringResource(R.string.together_start_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        when (step) {
            is HomeStep.Choosing -> ChooseASource(
                libraryGranted = libraryGranted,
                onBrowse = { step = HomeStep.Browsing },
                onPickAFile = { chosen = Chosen.AFile },
                onLink = { step = HomeStep.Linking },
                onSearch = { step = HomeStep.Searching },
                onClose = onClose,
            )

            is HomeStep.Browsing -> LibraryBrowser(
                libraryGranted = libraryGranted,
                onAsk = {
                    askToReadLibrary.launch(MediaLibraryAccess.permissionFor(Build.VERSION.SDK_INT))
                },
                onBack = { step = HomeStep.Choosing },
                onPlay = { track, shown -> chosen = Chosen.Track(track, shown) },
            )

            is HomeStep.Linking -> LinkField(
                onBack = { step = HomeStep.Choosing },
                onPlay = { link -> chosen = Chosen.Link(link) },
            )

            is HomeStep.Searching -> SearchByName(
                onBack = { step = HomeStep.Choosing },
                onPlay = { chosenTrack, shown -> chosen = Chosen.Track(chosenTrack, shown) },
            )
        }
    }

    // What happens next to whatever was just chosen. The three answers are
    // `TogetherDecisions.startStep`'s, so the sheet, the question and the
    // straight-to-playing case cannot disagree about which applies.
    chosen?.let { what ->
        when (
            val next = TogetherDecisions.startStep(
                pairing = pairing,
                sessionLive = live is TogetherManager.UiState.Live,
                weLead = (live as? TogetherManager.UiState.Live)?.weLead ?: false,
            )
        ) {
            // The second step, over whichever of the three is showing. A sheet
            // rather than a screen because the thing they just chose is still
            // behind it, which is the difference between "and who with?" and
            // starting over.
            is TogetherDecisions.StartStep.AskWho -> ListenWithSheet(
                onDismiss = { chosen = null },
                onChosen = { listener ->
                    chosen = null
                    failed = !startWith(
                        context,
                        what,
                        TogetherDecisions.Pairing(listener.npub, listener.label),
                        onPickFileWith,
                    )
                    if (!failed) onStarted()
                },
                onAlone = {
                    chosen = null
                    // The same call as any other, with the pairing that has
                    // nobody in it — which is what keeps listening alone from
                    // being a second implementation of the player.
                    failed = !startWith(context, what, TogetherDecisions.ALONE, onPickFileWith)
                    if (!failed) onStarted()
                },
            )

            is TogetherDecisions.StartStep.ConfirmTakeover -> {
                // Set from a side effect rather than drawn inline, so the
                // dialog below is the only thing that can clear `chosen` — two
                // places clearing it is how a tap ends up doing nothing.
                LaunchedEffect(what, next.pairing) {
                    confirming = what to next.pairing
                    chosen = null
                }
            }

            // Cleared last, deliberately: writing `chosen = null` first would
            // leave the rest of this block running in an effect whose composable
            // is on its way out of the composition.
            is TogetherDecisions.StartStep.PlayNow -> LaunchedEffect(what, next.pairing) {
                failed = !startWith(context, what, next.pairing, onPickFileWith)
                if (!failed) onStarted()
                chosen = null
            }
        }
    }

    // Taking the music off somebody else. Asked because it is their choice we
    // are ending, and answered on their own screen a moment later — the
    // follower may put something on as freely as the leader, which is what a
    // pair means, but not silently.
    confirming?.let { (what, with) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.together_takeover_title)) },
            text = { Text(stringResource(R.string.together_takeover_body, with.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    failed = !startWith(context, what, with, onPickFileWith)
                    if (!failed) onStarted()
                }) {
                    Text(stringResource(R.string.together_takeover_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.together_takeover_no))
                }
            },
        )
    }
}

/**
 * Start the session, or hand back to the picker when that is the next step.
 *
 * Every route out of the home screen funnels through here so there is one place
 * that knows how a choice becomes a session — and so a failure is reported once
 * rather than in three places that each forgot a different case.
 *
 * @return whether something actually happened. `false` is a refusal worth a
 *   sentence: core declining the content is the one outcome a tap can have that
 *   changes nothing on screen, and the caller says so rather than leaving a
 *   button that looks broken.
 */
private fun startWith(
    context: Context,
    what: Chosen,
    with: TogetherDecisions.Pairing,
    onPickFileWith: (peer: String, label: String) -> Unit,
): Boolean = when (what) {
    // The one route that carries a queue, because it is the only source that is
    // a list. Everything else here is one thing, and says so by passing none.
    is Chosen.Track -> TogetherManager.playTrack(context, with, what.track, what.queue)

    is Chosen.Link -> when (val link = what.link) {
        is TogetherDecisions.Link.Video -> runCatching {
            TogetherManager.startEmbed(context, with.npub, with.label, link.videoId)
        }.onFailure { Log.w(TAG, "could not start on a video", it) }.isSuccess

        is TogetherDecisions.Link.Stream -> runCatching {
            // Rebuilt through core rather than carried as a typed value: the
            // content that goes on the wire has to be the one core validated,
            // and `Link.Stream` is the pure layer's answer with no Android and
            // no uniffi types in it. Core refuses again on the way out, so this
            // cannot smuggle a URL past `valid_stream_url` — and a refusal there
            // throws, which is what turns into the sentence above.
            val content = ComradeCore.togetherStreamContentTyped(link.url)
                as? uniffi.comrade_core.TogetherContent.Stream
                // The URL is deliberately not in the message: it goes to logcat,
                // it can be 2 kB, and it was core's own answer a moment ago —
                // there is nothing to learn from seeing it again.
                ?: error("core no longer accepts this stream URL")
            TogetherManager.startStream(context, with.npub, with.label, content)
        }.onFailure { Log.w(TAG, "could not start on a link", it) }.isSuccess

        // Unreachable: `LinkField` never offers an unplayable link. Answered
        // rather than ignored so a future caller cannot get a silent no.
        is TogetherDecisions.Link.NotPlayable -> false
    }

    // The picker is the next step, not the last one — so this succeeded at what
    // it was asked to do even though no session exists yet.
    is Chosen.AFile -> {
        onPickFileWith(with.npub, with.label)
        true
    }
}

private const val TAG = "TogetherScreen"

/** The four ways in. */
@Composable
private fun ChooseASource(
    libraryGranted: Boolean,
    onBrowse: () -> Unit,
    onPickAFile: () -> Unit,
    onLink: () -> Unit,
    onSearch: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        // Only over a running session: on the idle tab there is nothing behind
        // this to go back to, and a back arrow that leads nowhere is worse than
        // none.
        onClose?.let { close ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.together_back_to_session),
                        tint = TogetherText,
                    )
                }
                Text(
                    stringResource(R.string.together_back_to_session),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TogetherMuted,
                )
            }
        }
        Text(
            stringResource(R.string.together_home_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = TogetherText,
        )
        Text(
            stringResource(R.string.together_home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TogetherMuted,
        )
        Spacer(Modifier.height(8.dp))
        // Order and content are `TogetherDecisions.sources`', so the list the
        // screen draws and the list the tests pin cannot come apart.
        TogetherDecisions.sources(libraryGranted).forEach { source ->
            when (source) {
                is TogetherDecisions.Source.OnThisPhone -> SourceCard(
                    icon = QueueMusicIcon,
                    title = stringResource(R.string.together_source_phone),
                    // Named while it is still true: after a grant the same card
                    // simply opens the list.
                    subtitle = stringResource(
                        if (source.needsPermission) {
                            R.string.together_source_phone_locked
                        } else {
                            R.string.together_source_phone_note
                        },
                    ),
                    onClick = onBrowse,
                )

                is TogetherDecisions.Source.PickAFile -> SourceCard(
                    icon = AttachFileIcon,
                    title = stringResource(R.string.together_source_file),
                    subtitle = stringResource(R.string.together_source_file_note),
                    onClick = onPickAFile,
                )

                is TogetherDecisions.Source.FromALink -> SourceCard(
                    icon = LinkIcon,
                    title = stringResource(R.string.together_source_link),
                    subtitle = stringResource(R.string.together_source_link_note),
                    onClick = onLink,
                )

                is TogetherDecisions.Source.SearchByName -> SourceCard(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.together_source_search),
                    // Says out loud that this one leaves the phone. It is the
                    // only source that does, and a person choosing between four
                    // cards should not have to read the source to learn that.
                    subtitle = stringResource(R.string.together_source_search_note),
                    onClick = onSearch,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.together_home_note),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(TogetherCard)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(TogetherSleeve),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = TogetherText, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TogetherText)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TogetherMuted)
        }
    }
}

/**
 * The phone's own music, as a list.
 *
 * Read on a background thread and once per grant, not per recomposition: a
 * `MediaStore` query with two thousand rows in it is not something to run while
 * somebody types into the search field. The filtering is
 * [TogetherDecisions.filterTracks] over the list already in memory, which is why
 * typing is instant and why the behaviour is testable.
 */
@Composable
private fun LibraryBrowser(
    libraryGranted: Boolean,
    onAsk: () -> Unit,
    onBack: () -> Unit,
    /**
     * The track, **and the list it came out of** — which is the list as the
     * search field had narrowed it, not the whole library. That is what the
     * person was looking at when they chose, so it is what next should mean.
     */
    onPlay: (TogetherDecisions.Track, List<TogetherDecisions.Track>) -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<MusicLibrary.Page?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(libraryGranted) {
        page = if (libraryGranted) {
            withContext(Dispatchers.IO) { MusicLibrary.page(context) }
        } else {
            null
        }
    }
    // Covers are a cache in `MusicLibrary`, not a per-row leak — but a cache
    // held after the browser is gone is memory nothing is looking at.
    DisposableEffect(Unit) { onDispose { MusicLibrary.forgetArtwork() } }

    Column(Modifier.fillMaxSize()) {
        BrowserHeader(stringResource(R.string.together_source_phone), onBack)
        if (!libraryGranted) {
            // Asking, not an empty list: "no music here" and "not allowed to
            // look" are different sentences with different next steps, the same
            // distinction `MediaLibraryAccess` draws for an invitation.
            //
            // And `Step.Picker` is the third case, which is why the button is
            // conditional: after a refusal Android shows no dialog at all, so an
            // "Allow" here would be a button that does nothing — the one outcome
            // guaranteed to teach someone the feature is broken. That case gets
            // the sentence that names the only route left, which is Settings,
            // and the other two sources are one tap back.
            val step = MediaLibraryAccess.next(
                granted = false,
                askedBefore = runCatching { MediaLibraryAccess.asked(context) }
                    .getOrDefault(true),
            )
            Text(
                stringResource(
                    if (step == MediaLibraryAccess.Step.Ask) {
                        R.string.together_library_why
                    } else {
                        R.string.together_library_refused
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (step == MediaLibraryAccess.Step.Ask) {
                Button(onClick = onAsk) { Text(stringResource(R.string.together_library_allow)) }
            }
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.together_library_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
        val loaded = page
        val shown = remember(loaded, query) {
            TogetherDecisions.filterTracks(loaded?.tracks.orEmpty(), query)
        }
        when {
            loaded == null -> Text(
                stringResource(R.string.together_library_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
            )

            shown.isEmpty() -> Text(
                stringResource(
                    if (loaded.tracks.isEmpty()) {
                        R.string.together_library_empty
                    } else {
                        R.string.together_library_no_match
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                // A stable key, like every data-driven list in this app: without
                // one, row state reattaches to the wrong item as the filter
                // narrows.
                items(shown, key = { it.uri }) { track ->
                    TrackRow(track, onClick = { onPlay(track, shown) })
                }
                if (loaded.truncated) {
                    item(key = "truncated") {
                        // Said out loud. A list silently cut off reads as "that
                        // is all your music", and the person whose album is past
                        // the cut concludes the feature cannot see it.
                        Text(
                            stringResource(R.string.together_library_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = TogetherMuted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.together_back),
                tint = TogetherText,
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = TogetherText)
    }
}

@Composable
private fun TrackRow(track: TogetherDecisions.Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            uri = track.uri,
            albumId = track.albumId,
            requestDp = ROW_COVER_DP,
            corner = 8.dp,
            glyphDp = 22,
            modifier = Modifier.size(ROW_COVER_DP.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TogetherText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                TogetherDecisions.trackSubtitle(track),
                style = MaterialTheme.typography.bodySmall,
                color = TogetherMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TogetherMuted)
    }
}

/**
 * An album cover, or the note glyph when the file carries none.
 *
 * Decoded on the IO dispatcher and keyed on what it is for, so scrolling does
 * not decode on the frame thread and a recomposition does not re-decode. The
 * `null` result is cached only by `MusicLibrary`'s own cache of *hits* — a miss
 * is re-attempted, which costs one failed provider call on a file with no art
 * and keeps this from having to hold a second negative cache.
 *
 * [requestDp] is what the provider is asked for and [modifier] is what the box
 * actually measures, which are two different numbers on purpose: the session's
 * sleeve fills its parent and its width is not known until layout, so asking for
 * a fixed reasonable square is what stops a rotation re-decoding the cover at a
 * new size.
 */
@Composable
private fun Cover(
    uri: String?,
    albumId: Long?,
    requestDp: Int,
    corner: Dp,
    glyphDp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val requestPx = with(LocalDensity.current) { requestDp.dp.roundToPx() }
    var art by remember(uri, albumId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, albumId, requestPx) {
        art = uri?.let { at ->
            withContext(Dispatchers.IO) {
                runCatching { MusicLibrary.artwork(context, at, albumId, requestPx) }
                    .getOrNull()
                    ?.let(Bitmap::asImageBitmap)
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(TogetherSleeve),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = art
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                QueueMusicIcon,
                contentDescription = null,
                tint = TogetherMuted,
                modifier = Modifier.size(glyphDp.dp),
            )
        }
    }
}

/**
 * The pasted-link field.
 *
 * **Core classifies, this only asks.** `play_query` knows the service hosts and
 * `TogetherContent::stream` knows what a media URL is; the ordering between
 * their two answers is [TogetherDecisions.classifyLink], which the JVM lane
 * pins. Nothing in here looks at the text itself, which is the whole point — a
 * third opinion about what a link is would be the drift `docs/CHAT_ACTIONS.md`
 * §7 records for `/pay`.
 */
@Composable
private fun LinkField(onBack: () -> Unit, onPlay: (TogetherDecisions.Link) -> Unit) {
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrowserHeader(stringResource(R.string.together_source_link), onBack)
        Text(
            stringResource(R.string.together_link_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = TogetherMuted,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                refused = false
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.together_link_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = text.isNotBlank() && !asking,
            onClick = {
                asking = true
                scope.launch {
                    // Off the main thread: both of these cross the FFI and
                    // `play_query` takes the runtime's read lock, which is the
                    // same reason `ChatsScreen` runs the identical pair on
                    // `Dispatchers.IO`.
                    val link = withContext(Dispatchers.IO) {
                        TogetherDecisions.classifyLink(
                            videoId = (
                                runCatching { ComradeCore.playQuery(text, null).content }
                                    .getOrNull() as? uniffi.comrade_core.TogetherContent.Youtube
                                )?.videoId,
                            streamUrl = (
                                ComradeCore.togetherStreamContentTyped(text)
                                    as? uniffi.comrade_core.TogetherContent.Stream
                                )?.url,
                        )
                    }
                    asking = false
                    if (link is TogetherDecisions.Link.NotPlayable) refused = true else onPlay(link)
                }
            },
        ) {
            Text(stringResource(R.string.together_link_go))
        }
        if (refused) {
            // Names what would work rather than what was wrong: someone who
            // pasted a page link wants to know a direct file link is the thing
            // to look for.
            Text(
                stringResource(R.string.together_link_refused),
                style = MaterialTheme.typography.bodySmall,
                color = TogetherMuted,
            )
        }
    }
}

/**
 * Search a public catalogue by name, and play whichever local copy it turns out
 * to mean.
 *
 * **This screen finds a *recording*, not audio**, and the distinction is the
 * whole design. `catalogue_lookup` answers "what is that song" — title, artist,
 * album, ISRC when known — and `audio_plan` then decides where the bytes come
 * from. What this build can act on today is the `library` tier: the catalogue
 * tells us the proper name, `MediaStore` is searched for it, and a session opens
 * on the copy already on the phone. The other three tiers are named honestly and
 * do not pretend to be buttons that work — see [TogetherDecisions.CandidateAction]
 * and `catalogue.rs`'s header for why `EmbedOrNameIt` is a floor rather than a
 * gap.
 *
 * Every decision here is [TogetherDecisions]', which the JVM lane pins: the five
 * outcomes, the tier naming, and — the one that matters most — that "this build
 * cannot search" never renders as "no such song".
 */
@Composable
private fun SearchByName(
    onBack: () -> Unit,
    onPlay: (TogetherDecisions.Track, List<TogetherDecisions.Track>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var outcome by remember {
        mutableStateOf<TogetherDecisions.SearchOutcome>(TogetherDecisions.SearchOutcome.Idle)
    }
    // Kept alongside the drawn candidates so a tap can ask `audio_plan` about the
    // same match the catalogue returned, rather than re-deriving one from the row.
    var matches by remember {
        mutableStateOf<List<uniffi.comrade_core.CatalogueMatch>>(emptyList())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrowserHeader(stringResource(R.string.together_source_search), onBack)
        Text(
            stringResource(R.string.together_search_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = TogetherMuted,
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                // Clearing the field clears the answer. Leaving a previous
                // result under an empty query invites playing the wrong song.
                if (it.isBlank()) {
                    outcome = TogetherDecisions.SearchOutcome.Idle
                    matches = emptyList()
                }
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.together_search_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = query.isNotBlank() &&
                outcome !is TogetherDecisions.SearchOutcome.Searching,
            onClick = {
                outcome = TogetherDecisions.SearchOutcome.Searching
                scope.launch {
                    // `Dispatchers.IO`, not the main thread: this is a relay-free
                    // but genuinely networked call, and `runBlocking` on it from
                    // a click handler is the ANR of docs/TOGETHER.md §17.
                    val result = withContext(Dispatchers.IO) {
                        ComradeCore.catalogueLookup(query)
                    }
                    matches = (result as? ComradeCore.CatalogueResult.Found)?.matches.orEmpty()
                    outcome = TogetherDecisions.searchOutcome(
                        unavailable = result is ComradeCore.CatalogueResult.Unavailable,
                        error = (result as? ComradeCore.CatalogueResult.Failed)?.reason,
                        candidates = matches.map { it.asCandidate() },
                    )
                }
            },
        ) {
            Text(stringResource(R.string.together_search_go))
        }

        when (val shown = outcome) {
            is TogetherDecisions.SearchOutcome.Idle -> Unit

            is TogetherDecisions.SearchOutcome.Searching -> Text(
                stringResource(R.string.together_search_running),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
            )

            // The two that must not read alike. This one is about the build.
            is TogetherDecisions.SearchOutcome.NoCatalogue -> Text(
                stringResource(R.string.together_search_no_catalogue),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
            )

            // …and this one is about the song.
            is TogetherDecisions.SearchOutcome.NothingFound -> Text(
                stringResource(R.string.together_search_nothing_found),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
            )

            is TogetherDecisions.SearchOutcome.Failed -> Text(
                stringResource(R.string.together_search_failed, shown.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            // Both list-bearing outcomes draw the same rows, and that is the fix:
            // tapping a row that has no local copy must not take the other rows
            // away. `NotOnThisPhone` adds a line about the row that was tapped and
            // leaves the rest to try.
            is TogetherDecisions.SearchOutcome.Found,
            is TogetherDecisions.SearchOutcome.NotOnThisPhone,
            -> Column {
                val rows = when (shown) {
                    is TogetherDecisions.SearchOutcome.Found -> shown.candidates
                    is TogetherDecisions.SearchOutcome.NotOnThisPhone -> shown.candidates
                    // Unreachable: this arm covers exactly the two above.
                    else -> emptyList()
                }
                (shown as? TogetherDecisions.SearchOutcome.NotOnThisPhone)?.let { miss ->
                    Text(
                        // Names the song rather than the search, because the
                        // search worked. No "Couldn't search" prefix.
                        stringResource(R.string.together_search_not_on_phone, miss.wanted.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TogetherMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                rows.forEachIndexed { i, candidate ->
                    CandidateRow(
                        candidate = candidate,
                        onClick = {
                            val match = matches.getOrNull(i) ?: return@CandidateRow
                            scope.launch {
                                val opened = withContext(Dispatchers.IO) {
                                    openLocalCopyOf(context, match)
                                }
                                if (opened == null) {
                                    // No local copy above the confidence bar. Say
                                    // so rather than opening the nearest thing —
                                    // MATCH_CONFIDENT exists precisely so this
                                    // asks instead of guessing.
                                    //
                                    // **Not `Failed`.** That is what shipped, and
                                    // it rendered a search that worked as
                                    // "Couldn't search: …" while wiping the list,
                                    // so there was no other candidate left to try.
                                    // The search succeeded; only this row has no
                                    // copy here.
                                    outcome = TogetherDecisions.notOnThisPhone(
                                        candidates = rows,
                                        wanted = candidate,
                                    )
                                } else {
                                    onPlay(opened.first, opened.second)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One catalogue answer, with the catalogue that gave it named on the row. */
@Composable
private fun CandidateRow(candidate: TogetherDecisions.Candidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(QueueMusicIcon, contentDescription = null, tint = TogetherMuted)
        Column(Modifier.weight(1f)) {
            Text(
                candidate.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TogetherText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The catalogue is named on every row, not once at the top of the
                // list: `CatalogueResolver::name`'s contract is that a guess
                // presented without its source is worse than no guess, and a
                // header scrolls away while the row does not.
                stringResource(
                    R.string.together_search_result_subtitle,
                    candidate.artist.ifBlank { stringResource(R.string.together_search_unknown_artist) },
                    candidate.catalogue,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = TogetherMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [uniffi.comrade_core.CatalogueMatch] in the shape the pure decisions take. */
private fun uniffi.comrade_core.CatalogueMatch.asCandidate() = TogetherDecisions.Candidate(
    title = recording.title,
    artist = recording.artist,
    album = recording.album,
    durationMs = durationMs?.toLong() ?: 0L,
    catalogue = MUSICBRAINZ,
    durationKnown = durationMs != null,
)

/**
 * The catalogue behind [ComradeCore.catalogueLookup].
 *
 * Named here rather than plumbed through the FFI because there is exactly one
 * resolver and `CatalogueMatch` carries no source field. **If a second catalogue
 * is ever added, this constant becomes a lie and the field has to move onto the
 * match** — which is the point of stating it as a constant with this comment
 * rather than inlining the string at the call site.
 */
private const val MUSICBRAINZ = "MusicBrainz"

/**
 * Find this phone's own copy of `match`, or `null`.
 *
 * The `library` rung of `audio_plan`, carried out — and deliberately delegated
 * to [LibraryResolver] rather than reimplemented. That object already supplies
 * `MediaStore` candidates and applies `comrade_core::together::match_score`, so
 * the "is this the same recording" judgement stays core's one answer instead of
 * becoming a second opinion in this file. An earlier draft of this screen scored
 * titles itself, which is exactly the drift `TogetherDecisions`' header exists to
 * prevent.
 *
 * `null` covers both "no copy" and "nothing confident enough", deliberately:
 * `MATCH_CONFIDENT` exists so that opening the wrong track on somebody's behalf
 * is not a thing this does.
 */
private fun openLocalCopyOf(
    context: Context,
    match: uniffi.comrade_core.CatalogueMatch,
): Pair<TogetherDecisions.Track, List<TogetherDecisions.Track>>? {
    val wantMs = (match.durationMs ?: 0UL).toLong()
    // Core decides which rung applies; this screen can only act on `library`.
    // The other three are real answers with no button behind them yet, and the
    // caller says so rather than silently doing nothing.
    val resolved = LibraryResolver.resolve(context, match.recording, wantMs) ?: return null
    val page = runCatching { MusicLibrary.page(context) }.getOrNull() ?: return null
    // The queue is the library the person is choosing from, so prev/next mean
    // that list — the same contract `LibraryBrowser` passes to `onPlay`.
    val track = page.tracks.firstOrNull { it.uri == resolved.uri.toString() } ?: return null
    return track to page.tracks
}

/**
 * "And who with?" — the second half of starting a session.
 *
 * Comrades first and online first, which is [TogetherDecisions.listenersFor]'s
 * rule and not this composable's. Contacts who are not comrades are still
 * offered: an invitation is a DM like any other, and presence is a thing you opt
 * into mutually rather than a precondition for asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenWithSheet(
    onDismiss: () -> Unit,
    onChosen: (TogetherDecisions.Listener) -> Unit,
    onAlone: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var all by remember { mutableStateOf<List<TogetherDecisions.Listener>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { listeners() }
        loaded = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.together_who_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.together_who_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Above the people and outside the search, because it is not a
            // person and because it is the answer that always works: it needs no
            // contact, no permission and no network, which is exactly what makes
            // the tab usable as an ordinary music player.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAlone() }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    QueueMusicIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.together_who_alone),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.together_who_alone_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.together_who_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            val shown = remember(all, query) { TogetherDecisions.listenersFor(all, query) }
            when {
                !loaded -> Text(
                    stringResource(R.string.together_who_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )

                shown.isEmpty() -> Text(
                    stringResource(
                        if (all.isEmpty()) {
                            R.string.together_who_nobody
                        } else {
                            R.string.together_who_no_match
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(shown, key = { it.npub }) { listener ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChosen(listener) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                listener.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Only when it is true, and never a grey dot for
                            // "offline": presence is mutual, so its absence
                            // means "we cannot see them" as often as it means
                            // they are away, and a grey dot claims the second.
                            if (listener.online) {
                                Text(
                                    stringResource(R.string.together_who_online),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Everyone this device could invite, with presence where there is any.
 *
 * Contacts are the population and comrades are the presence: a comrade is a
 * contact we exchange beacons with, so the two lists are joined on the npub
 * rather than one being read instead of the other. Reading only comrades would
 * hide everybody the user has not chosen, and reading only contacts would drop
 * the one signal that says who is actually there.
 */
private fun listeners(): List<TogetherDecisions.Listener> {
    val online = runCatching { ComradeCore.comrades() }.getOrDefault(emptyList())
        .associateBy { it.npub }
    return runCatching { ComradeCore.contacts() }.getOrDefault(emptyList()).map { contact ->
        TogetherDecisions.Listener(
            npub = contact.npub,
            label = peerTitle(contact.npub, contact.alias, contact.name),
            comrade = contact.comrade,
            online = online[contact.npub]?.online == true,
        )
    }
}

// ── The session ──────────────────────────────────────────────────────────────

/**
 * Where the picture goes.
 *
 * **This is the fix for a film playing as sound only**: `MediaPlayer` decodes
 * video to whatever surface it is given and silently discards it when given
 * none, and until now nothing gave it one. The surface is created and destroyed
 * on every rotation while the session and the player outlive both, so ownership
 * runs one way — the holder callbacks tell [TogetherManager] what exists, and
 * the player re-attaches whenever it is handed something.
 *
 * Rendered only once the decoder reports a picture, so a shared album gets the
 * controls with no black rectangle above them.
 */
@Composable
private fun VideoSurface(picture: TogetherDecisions.Picture.Video, modifier: Modifier = Modifier) {
    // The sleeve that contains this already carries the aspect ratio, so the
    // surface only fills it. Two things applying a ratio is how a film ends up
    // letterboxed inside a box that was already the right shape.
    if (TogetherDecisions.aspectRatioOf(picture) == null) return
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        TogetherManager.attachSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    // Not tidiness: a destroyed Surface the decoder still holds
                    // is a use-after-free in the media server.
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        TogetherManager.attachSurface(null)
                    }
                })
            }
        },
    )
}

/**
 * The third way to accept an invitation: follow the app this phone is already
 * playing in.
 *
 * `docs/TOGETHER.md` §13. Offered only for content Comrade cannot play itself —
 * [PlaybackModeDecision.ownershipFor] is what says so, and the manager asks it
 * rather than this screen guessing from the kind string.
 *
 * **The copy names no service and no client, and that is a rule rather than an
 * oversight.** The feature drives whatever published a media session; naming a
 * particular app — especially a patched one — would convert a neutral tool into
 * a targeted one regardless of what the code does. §13 explains why that
 * distinction matters, and it applies to strings, docs and store listing alike.
 */
@Composable
private fun FollowWhatIsPlaying(
    invited: TogetherManager.UiState.Invited,
    refusal: TogetherManager.FollowRefusal?,
    onTry: () -> Unit,
) {
    val context = LocalContext.current
    // A video plays here and a file is opened here, so neither wants this. Asked
    // of the same decision the manager will apply, so the button and the action
    // cannot disagree about when it is available.
    val couldFollow = PlaybackModeDecision.ownershipFor(
        contentKind = invited.contentKind,
        haveOurCopy = false,
        externalSessionAvailable = true,
    ) == PlaybackOwnership.EXTERNAL
    if (!couldFollow) return

    TextButton(onClick = onTry) { Text(stringResource(R.string.together_follow)) }

    when (refusal) {
        // The explainer comes *before* the settings screen, not after: the
        // permission cannot be requested in-app, so the system screen arrives
        // with no context of its own and a notification-access prompt with no
        // explanation is one people are right to refuse.
        TogetherManager.FollowRefusal.NeedsAccess -> {
            Text(
                stringResource(R.string.together_follow_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(MediaSessionAccess.settingsIntent()) }
            }) {
                Text(stringResource(R.string.together_follow_grant))
            }
        }
        // Granted, and there is simply nothing to follow. A different sentence
        // on purpose: sending someone back to a settings screen they have
        // already used is the refusal that teaches people the button is broken.
        TogetherManager.FollowRefusal.NothingPlaying -> Text(
            stringResource(R.string.together_follow_nothing_playing),
            style = MaterialTheme.typography.bodySmall,
        )
        // The invitation went away underneath us; the screen is about to change
        // anyway, so it says nothing.
        TogetherManager.FollowRefusal.NoInvitation, null -> Unit
    }
}

/**
 * A `VideoTrack` on screen, for a streamed session (`docs/TOGETHER.md` §15).
 *
 * Deliberately a much plainer thing than the call screen's renderer: there is no
 * mirroring (nobody is looking at themselves), no picture-in-picture z-order and
 * no letterbox decision, because the sleeve around this already carries the
 * aspect ratio. What it keeps is the part that is not optional — `release()` on
 * disposal, and detaching the sink before that, since a renderer left attached
 * to a live track is a native buffer nobody frees.
 */
@Composable
private fun StreamRenderer(track: VideoTrack?, modifier: Modifier = Modifier) {
    val egl = CallManager.eglBaseContext
    if (egl == null) {
        // No WebRTC on this device: an empty sleeve is honest, where a black
        // rectangle would look like a picture that failed to arrive.
        return
    }
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(egl, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) {
        track?.addSink(renderer)
        onDispose { track?.removeSink(renderer) }
    }
    AndroidView(factory = { renderer }, modifier = modifier.fillMaxSize())
}

/**
 * The YouTube embed, hosted in our own window.
 *
 * **The standard player, with its controls and its ads, and that is a term of
 * use rather than a default nobody changed.** YouTube's API Services Terms
 * prohibit hiding the player or stripping ads; `docs/TOGETHER.md` §11a records
 * why the ReVanced/InnerTube route is declined and what the ad-free answer
 * actually is (§11a's `Stream` sources). So `controls(1)`, and no custom UI.
 *
 * `enableAutomaticInitialization` is switched off because the automatic path
 * binds the view to a `LifecycleOwner` and releases the player when that owner
 * stops — which is a Compose screen here, and a session must not end because a
 * screen was disposed. [TogetherManager] owns the player's lifetime instead,
 * exactly as it owns the `MediaPlayer`'s, and `onRelease` below hands the view
 * back rather than tearing the session down.
 */
@Composable
private fun EmbedSurface(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            YouTubePlayerView(ctx).apply {
                enableAutomaticInitialization = false
                initialize(
                    object : com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener() {},
                    // Network events handled: the library re-loads the player
                    // when connectivity comes back, which is the difference
                    // between a session that survives a tunnel and one that
                    // needs the app restarting.
                    true,
                    IFramePlayerOptions.Builder()
                        .controls(1)
                        // Autoplay off: the session decides when playback
                        // starts, so both people start together rather than one
                        // of them starting on arrival.
                        .autoplay(0)
                        .rel(0)
                        .build(),
                )
                TogetherManager.attachEmbedView(this)
            }
        },
        // Not tidiness, and the same shape as `surfaceDestroyed` above: a
        // released `WebView` the session still holds is a player being driven
        // into a dead page.
        onRelease = { view ->
            TogetherManager.attachEmbedView(null)
            view.release()
        },
    )
}

/**
 * The one question this feature asks before spending someone else's bandwidth.
 *
 * Modal because it is genuinely blocking — the transfer sits still until it is
 * answered — and dismissible only into "no", since there is no third outcome
 * and a silently dropped question would be the stall this was built to fix.
 */
@Composable
private fun ShareRelayConsent() {
    val question by ShareTransfer.consentQuestion.collectAsState()
    val text = question ?: return
    AlertDialog(
        onDismissRequest = { ShareTransfer.refuseShareConsent() },
        title = { Text(stringResource(R.string.together_relay_title)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { ShareTransfer.grantShareConsent() }) {
                Text(stringResource(R.string.together_relay_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = { ShareTransfer.refuseShareConsent() }) {
                Text(stringResource(R.string.together_relay_no))
            }
        },
    )
}

/**
 * What to say when the embed will not play this — and where to go instead.
 *
 * The panel above it is YouTube's own, and §11a is why it may not be replaced or
 * hidden. This is the session's answer underneath it, which until now was
 * nothing at all: the error reached logcat, the transport stayed, and the status
 * line went on saying we were waiting for the other person to open something
 * that was never going to open.
 *
 * By far the most common cause is a video whose owner does not allow it outside
 * YouTube, and that one has a real next step — the video is fine, it just has to
 * be watched over there. Which sentence applies is
 * [TogetherDecisions.embedFailure]'s to decide.
 */
@Composable
private fun EmbedRefusal() {
    val context = LocalContext.current
    val failure by TogetherManager.embedFailure.collectAsState()
    val why = failure ?: return
    Text(
        stringResource(
            when (why) {
                TogetherDecisions.EmbedFailure.NotEmbeddable -> R.string.together_embed_not_embeddable
                TogetherDecisions.EmbedFailure.NotFound -> R.string.together_embed_not_found
                TogetherDecisions.EmbedFailure.Unknown -> R.string.together_embed_failed
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    // Only when there is somewhere to send them. `watchUrl` answers null for
    // anything that is not an id, which is the one rule for building a URL out
    // of a string that arrived over the wire.
    TogetherManager.watchUrl()?.let { url ->
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)),
                )
            }.onFailure { Log.w(TAG, "no browser for the video", it) }
        }) {
            Text(stringResource(R.string.together_embed_watch_on_youtube))
        }
    }
    // Said plainly, because leaving for YouTube takes them out of the session:
    // the embed pauses in the background and nothing on the other device
    // changes.
    Text(
        stringResource(R.string.together_embed_watch_note),
        style = MaterialTheme.typography.bodySmall,
        color = TogetherMuted,
    )
}

@Composable
private fun LiveSession(
    s: TogetherManager.UiState.Live,
    onStream: () -> Unit,
    micBlocked: Boolean,
    onMic: () -> Unit,
    onPlaySomethingElse: () -> Unit,
) {
    // Hold the screen awake for a playing film and nothing else — two hours of
    // music must not burn the battery lighting up a screen with nothing on it.
    // The rule is TogetherDecisions.keepScreenOn, tested there; this only
    // applies it and hands it back on the way out.
    val view = LocalView.current
    val keepOn = TogetherDecisions.keepScreenOn(s.picture, s.playing)
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    // The centrepiece, and music-first: a square sleeve with the cover in it,
    // and the video surface *inside* the same block when the recording turns out
    // to have a picture. One block, so an album gets a cover and a film gets a
    // screen without two layouts to keep in step — the same shape the desktop
    // player uses.
    //
    // Absent entirely when another app holds the playback (docs/TOGETHER.md
    // §13): there is nothing of ours to draw, and an empty sleeve over somebody
    // else's music would be a picture of a player Comrade does not have.
    if (!s.external) Sleeve(s)

    Spacer(Modifier.height(4.dp))
    Text(
        s.title.ifBlank { s.peerLabel },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = TogetherText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    // Everything from here to the transport is *about the other person*, so
    // listening alone draws none of it — there is no name to put in "with …",
    // no status that is not about them, and no gap between two playheads when
    // there is one playhead. What a solo session keeps is the sleeve, the
    // title, the transport and the queue, which is a music player.
    if (!s.solo) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.together_with, s.peerLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = TogetherMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("·", color = TogetherMuted)
            Text(statusLabel(s), style = MaterialTheme.typography.bodyMedium, color = TogetherMuted)
        }

        // The measured half, which the desktop player has had since 2026-08-05
        // and this one did not. Recomputed on every recomposition rather than
        // stored, because whether these may be shown at all depends on how old
        // the reading is *now* — see `TogetherDecisions.measurement`. The
        // position poll drives a recomposition every 250 ms while playing, which
        // is what ages them off the screen once corrections stop arriving.
        val measured = TogetherDecisions.measurement(
            driftMs = s.driftMs,
            qualityMs = s.qualityMs,
            ageMs = System.currentTimeMillis() - s.correctedAtMs,
        )
        driftLabel(measured.drift)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TogetherMuted)
        }
        // Deliberately not colour-coded, on either frontend: "we've lost track
        // of them" is an honest report of poor measurement, not a fault, and red
        // would say otherwise.
        qualityLabel(measured.quality)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TogetherMuted)
        }

        // What we tried to tell them did not go. The player kept playing, which
        // is the point — your own music has no business waiting on a relay — so
        // this is what admits the other half did not happen.
        val unsent by TogetherManager.sendFailed.collectAsState()
        if (unsent) {
            Text(
                stringResource(R.string.together_send_failed),
                style = MaterialTheme.typography.bodySmall,
                color = TogetherMuted,
            )
        }
    }

    // Control-and-status, and the honest limit of it, while another app plays.
    if (s.external) {
        Text(
            stringResource(R.string.together_follow_note),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
    }

    // The source refused to play. Said here rather than only in logcat, because
    // a pasted link that turns out to be a web page fails several seconds after
    // the session opens and nothing else on this screen would change.
    val failed by TogetherManager.openFailed.collectAsState()
    if (failed) {
        Text(
            stringResource(R.string.together_could_not_play),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    EmbedRefusal()

    Transport(s, onMic = onMic, onPlaySomethingElse = onPlaySomethingElse)

    if (micBlocked) {
        Text(
            stringResource(R.string.together_mic_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
    }

    // The third answer to §9a's question, beside "find your own copy" and "take
    // mine": let them watch this one as it plays. Offered only by the side that
    // holds the file and only for our own player — an embed is already on both
    // screens, and an external session is somebody else's audio to send.
    if (s.weLead && !s.solo && !s.embed && !s.external && !s.streaming) {
        TextButton(onClick = onStream) { Text(stringResource(R.string.together_stream)) }
        Text(
            stringResource(R.string.together_stream_note),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
        // Before the system dialog, not after: it arrives with no explanation of
        // its own, and a recording prompt nobody can account for is one people
        // are right to refuse.
        Text(
            stringResource(R.string.together_stream_consent),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
    }
    if (s.streaming) {
        Text(
            stringResource(R.string.together_streaming),
            style = MaterialTheme.typography.bodyMedium,
            color = TogetherText,
        )
        // The one thing about the microphone that is not obvious from the icon.
        Text(
            stringResource(R.string.together_mic_note),
            style = MaterialTheme.typography.bodySmall,
            color = TogetherMuted,
        )
    }

    TextButton(onClick = { TogetherManager.leave() }) {
        Text(
            stringResource(
                if (s.solo) R.string.together_stop else R.string.together_leave,
            ),
        )
    }

    // The honest limits, on screen rather than in a doc nobody reads.
    Text(
        stringResource(R.string.together_accuracy_note),
        style = MaterialTheme.typography.bodySmall,
        color = TogetherMuted,
    )
    // The background promise is true of our own player and false of an embed —
    // YouTube pauses a backgrounded one, and turning that off is a feature of
    // their client rather than something this app may grant on their behalf. A
    // note that claimed otherwise would be the kind of comment-shaped lie the
    // repo's conventions call a bug, printed at the user instead.
    Text(
        stringResource(
            if (s.embed) R.string.together_embed_background_note else R.string.together_background_note,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = TogetherMuted,
    )
    Spacer(Modifier.height(24.dp))
}

/**
 * The artwork block: a cover, a video surface, an embed or an incoming stream —
 * whichever this session turned out to have, in one frame that owns the shape.
 *
 * The gentle scale between playing and paused is the only animation on this
 * screen. It is there because a paused player and a playing one otherwise look
 * identical apart from one glyph, and a still cover that visibly settles when
 * the other person pauses says "something happened" before the status line has
 * been read.
 */
@Composable
private fun Sleeve(s: TogetherManager.UiState.Live) {
    val video = s.picture as? TogetherDecisions.Picture.Video
    val scale by animateFloatAsState(
        targetValue = if (s.playing) 1f else 0.94f,
        animationSpec = tween(durationMillis = 260),
        label = "sleeve",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                video
                    ?.let { p -> TogetherDecisions.aspectRatioOf(p)?.let { Modifier.aspectRatio(it) } }
                    ?: Modifier.aspectRatio(1f),
            )
            // Only the cover breathes. A video surface and a WebView are handed
            // to the framework, and scaling either one costs a re-layout of a
            // view that is decoding — so the transform is applied to the still
            // case only, which is also the only case it says anything about.
            .then(
                if (video == null && !s.embed && !s.streaming) {
                    Modifier.scale(scale)
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(24.dp))
            .background(TogetherSleeve),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Streaming takes the player's only output surface, so the sender
            // cannot also watch a SurfaceView — they watch the very track the
            // other person receives. One picture path instead of two, and the
            // same thing the call screen does with local camera video.
            s.streaming -> {
                val outgoing by TogetherManager.localVideo.collectAsState()
                val incoming by TogetherManager.remoteVideo.collectAsState()
                // Whichever exists: the sender has an outgoing track and no
                // incoming one, the receiver the reverse. Asked this way round
                // rather than from `weLead` because a stream's direction is a
                // property of which tracks are present, and those are what the
                // renderer actually needs.
                StreamRenderer(outgoing ?: incoming)
            }
            // The embed draws itself, controls and all, inside the same sleeve
            // the file path uses — so a video has one owner of the aspect ratio
            // whichever player is behind it.
            s.embed -> EmbedSurface()
            video == null -> Cover(
                uri = s.sourceUri,
                // Not known for a session: `MediaStore`'s album id is a library
                // detail and a session remembers only what it opened. Costs the
                // cover below API 29 — see `MusicLibrary.artwork`.
                albumId = null,
                requestDp = SLEEVE_COVER_DP,
                corner = 24.dp,
                glyphDp = 72,
                modifier = Modifier.fillMaxSize(),
            )
            else -> VideoSurface(video)
        }
    }
}

/**
 * How big a cover is asked of the provider.
 *
 * Roughly the width each one is drawn at, and fixed rather than measured — see
 * [Cover]. The sleeve one is deliberately under a phone's full width: a cover is
 * a JPEG inside an MP3, so asking for more pixels than it has buys an upscale
 * and a bigger bitmap in the cache.
 */
private const val SLEEVE_COVER_DP = 320
private const val ROW_COVER_DP = 48

/**
 * Scrubber and transport.
 *
 * The scrubber is drawn only when there is a distance for it to express —
 * [TogetherDecisions.scrubbable], which is the rule and not a local judgement. A
 * `MediaSession` carries no duration we can trust and an embed reports none
 * until it loads, so both would otherwise get a bar with no end on it: a
 * scrubber that lies about where the end is, which is worse than no scrubber.
 * Play, pause and the four skips all still work, because those need no length.
 *
 * **Two rows, because this is a music player.** The top one is track-level —
 * previous, ten back, play, ten forward, next — and the bottom one is the two
 * controls that are about the session rather than the playhead: the microphone
 * and the way to put something else on. Cramming seven controls into one line
 * makes the play button small, and the play button is the one thing anybody
 * reaches for without looking.
 *
 * @param onMic pressed the microphone. Hoisted because turning it on may need a
 *   runtime permission, and a launcher belongs at the top of the screen rather
 *   than inside a row that is created and destroyed as the session changes.
 * @param onPlaySomethingElse open the chooser without asking who again.
 */
@Composable
private fun Transport(
    s: TogetherManager.UiState.Live,
    onMic: () -> Unit,
    onPlaySomethingElse: () -> Unit,
) {
    // While a finger is on the slider the poll must not move it — the decision
    // is TogetherDecisions.pollMayMoveSlider, and the manager honours it; this
    // only has to report the drag boundaries.
    var dragging by remember { mutableFloatStateOf(-1f) }
    val max = s.durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging >= 0f) dragging.toLong() else s.positionMs

    if (TogetherDecisions.scrubbable(s.durationMs, s.external)) {
        Slider(
            value = shown.toFloat().coerceIn(0f, max),
            onValueChange = {
                if (dragging < 0f) TogetherManager.onScrubStart()
                dragging = it
            },
            onValueChangeFinished = {
                val target = dragging.toLong()
                dragging = -1f
                TogetherManager.onScrubRelease(target)
            },
            valueRange = 0f..max,
            colors = SliderDefaults.colors(
                thumbColor = TogetherText,
                activeTrackColor = TogetherText,
                inactiveTrackColor = TogetherCard,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                TogetherDecisions.clock(shown),
                style = MaterialTheme.typography.labelMedium,
                color = TogetherMuted,
            )
            // Nothing at all rather than `0:00` when no length is known — the
            // decision is `remainingClock`'s, tested there.
            TogetherDecisions.remainingClock(shown, s.durationMs)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = TogetherMuted)
            }
        }
    }

    val context = LocalContext.current
    // Previous / back / play-pause / forward / next, centred. Every one of them
    // goes through `setState` or through the manager's queue, so they are
    // ordered by the same Lamport counter as the other person's and cannot race
    // them.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val skip = { delta: Long ->
            // Clamped at the top only when a length is known: an external
            // session reports none, and clamping to zero there would turn every
            // skip into "back to the start".
            val ceiling = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
            val target = (s.positionMs + delta).coerceIn(0L, ceiling)
            TogetherManager.setState(target, s.playing)
        }
        // Previous is always live, because it always does something: with no
        // track behind this one it restarts this one, which is
        // `TogetherDecisions.backStep`'s answer and the reason a back button
        // never has to be greyed out.
        IconButton(onClick = { TogetherManager.skipBack(context) }) {
            Icon(
                SkipPreviousIcon,
                contentDescription = stringResource(R.string.together_previous),
                tint = TogetherText,
            )
        }
        TextButton(onClick = { skip(-SKIP_MS) }) {
            Text(stringResource(R.string.together_back_ten), color = TogetherText)
        }
        // The one big control. A filled circle rather than a Button, because at
        // this size the label would be the shape — and because it is the only
        // thing on the screen anybody reaches for in the dark.
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { TogetherManager.setState(s.positionMs, !s.playing) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (s.playing) PauseIcon else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (s.playing) R.string.together_pause else R.string.together_play,
                ),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
        TextButton(onClick = { skip(SKIP_MS) }) {
            Text(stringResource(R.string.together_forward_ten), color = TogetherText)
        }
        // Next, unlike previous, genuinely has nothing to do at the end of a
        // queue or with no queue at all — a pasted link and a picked file are
        // one thing each. Drawn and disabled rather than absent: a control that
        // appears and disappears under the thumb is worse than one that is
        // visibly not available.
        val queue by TogetherManager.queue.collectAsState()
        val hasNext = TogetherDecisions.nextTrack(queue) != null
        IconButton(
            onClick = { TogetherManager.skipForward(context) },
            enabled = hasNext,
        ) {
            Icon(
                SkipNextIcon,
                contentDescription = stringResource(R.string.together_next),
                tint = if (hasNext) TogetherText else TogetherMuted.copy(alpha = 0.4f),
            )
        }
    }

    // The session-level row. The microphone first, because it is the one people
    // look for.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // **In every mode, not only a streamed one.** It used to appear only
        // while streaming, on the argument that there was otherwise no audio of
        // ours going anywhere — true of the wire and beside the point for the
        // person: listening to an album together with no way to say "this bit"
        // is a worse version of listening alone. What differs between the modes
        // is only what the voice rides on, which is [TogetherManager.toggleMic]'s
        // problem rather than this row's.
        //
        // Off by default, always. A session that opened with a live microphone
        // would have decided something about a room it cannot see.
        val micOn by TogetherManager.micEnabled.collectAsState()
        IconButton(onClick = onMic) {
            Icon(
                if (micOn) MicIcon else MicOffIcon,
                contentDescription = stringResource(
                    if (micOn) R.string.together_mic_off else R.string.together_mic_on,
                ),
                tint = if (micOn) MaterialTheme.colorScheme.primary else TogetherMuted,
            )
        }
        // The second half of "choose a person once": something else to play,
        // with no second trip through the who-with sheet. Absent for an
        // external session, where Comrade holds no player and putting something
        // on would mean taking the playback away from the app that has it.
        if (!s.external) {
            TextButton(onClick = onPlaySomethingElse) {
                Text(stringResource(R.string.together_play_something_else), color = TogetherText)
            }
        }
    }
}

/**
 * The gap, or nothing. Mirrors `driftLabel` in `desktop/ui/player_view.mjs`;
 * the decision to say anything at all is [TogetherDecisions.measurement]'s, and
 * this only puts it into words.
 */
@Composable
private fun driftLabel(drift: TogetherDecisions.Drift): String? = when (drift) {
    is TogetherDecisions.Drift.Silent -> null
    is TogetherDecisions.Drift.Gap -> stringResource(
        if (drift.weAreAhead) R.string.together_drift_ahead else R.string.together_drift_behind,
        secondsText(drift.ms, decimals = 1),
    )
}

/** How well we can measure. Mirrors `qualityLabel` in the desktop module. */
@Composable
private fun qualityLabel(quality: TogetherDecisions.Quality): String? = when (quality) {
    is TogetherDecisions.Quality.Unknown -> null
    is TogetherDecisions.Quality.Known -> stringResource(
        if (quality.direct) R.string.together_quality_direct else R.string.together_quality_relayed,
        secondsText(quality.ms, quality.decimals),
    )
}

/**
 * Milliseconds as seconds, to a fixed number of places.
 *
 * Formatted in the reader's own locale rather than [Locale.ROOT] — this number
 * sits inside a translated sentence, and "0,05" is what a decimal comma reader
 * expects to see there. The *arithmetic* is what the JVM tests pin, in
 * `TogetherDecisionsTest`, which is why it is not in here.
 */
private fun secondsText(ms: Long, decimals: Int): String =
    String.format(Locale.getDefault(), "%.${decimals}f", ms / 1000.0)

/** The status vocabulary, mirroring `sessionStatusLabel` in the desktop module. */
@Composable
private fun statusLabel(s: TogetherManager.UiState.Live): String = when (s.status) {
    TogetherManager.Status.WaitingForThem -> stringResource(R.string.together_waiting_for_them)
    TogetherManager.Status.OpenYourCopy -> stringResource(R.string.together_open_your_copy)
    TogetherManager.Status.Together -> stringResource(R.string.together_together)
    TogetherManager.Status.CatchingUp -> stringResource(R.string.together_catching_up)
    TogetherManager.Status.LostTrack -> stringResource(R.string.together_lost_track)
    TogetherManager.Status.TheyPaused -> stringResource(R.string.together_they_paused, s.peerLabel)
}
