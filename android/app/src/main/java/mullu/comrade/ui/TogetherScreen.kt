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
import androidx.compose.ui.graphics.Brush
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

    TogetherOverlay(modifier) {
        when (val s = state) {
            // The default surface, and the reason this tab exists. Not wrapped
            // in the scrolling column below: it owns its own scrolling, because
            // a library of two thousand tracks is a LazyColumn and nesting one
            // inside a `verticalScroll` measures every row.
            is TogetherManager.UiState.Idle -> PlayerHome(
                onPickFileWith = onPickFileWith,
            )

            else -> Column(
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
                            // Neither a video nor a stream needs a file or a
                            // permission, so each is a single unconditional tap;
                            // a file still sends someone to the picker, because
                            // that is genuinely the next step.
                            when {
                                s.youtube -> Button(onClick = { TogetherManager.joinEmbed(context) }) {
                                    Text(stringResource(R.string.together_join_video))
                                }
                                s.contentKind == STREAM_KIND ->
                                    Button(onClick = { TogetherManager.joinStream(context) }) {
                                        Text(stringResource(R.string.together_join_stream))
                                    }
                                else -> {
                                    Button(onClick = onPickFileToJoin) {
                                        Text(stringResource(R.string.together_join))
                                    }
                                    // The case `together` otherwise assumes
                                    // away: you do not have it. Their copy comes
                                    // straight from their device — never through
                                    // a server of ours. Absent for a video and a
                                    // stream: there is no file either side
                                    // holds, so the offer would be one nobody
                                    // could accept.
                                    TextButton(onClick = { TogetherManager.askForTheirCopy(context) }) {
                                        Text(stringResource(R.string.together_ask_for_copy))
                                    }
                                }
                            }
                            TextButton(onClick = { TogetherManager.leave() }) {
                                Text(stringResource(R.string.together_not_now))
                            }
                        }
                        // What joining a stream actually does, before they do
                        // it: their device fetches a URL the other person named.
                        if (s.contentKind == STREAM_KIND) {
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
                            s.contentKind != STREAM_KIND &&
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

                    is TogetherManager.UiState.Live -> LiveSession(s) {
                        askToRecord.launch(android.Manifest.permission.RECORD_AUDIO)
                    }

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
 * `TogetherContent::Stream`'s tag as it crosses the bridge.
 *
 * The string rather than the typed variant because that is what
 * `UiState.Invited.contentKind` carries, and it carries a string so
 * `RelayConnectionService` can hand over a variant this build has not learned
 * without failing to compile a lane `ci.yml` does not run.
 */
private const val STREAM_KIND = "stream"

/**
 * The full-screen backdrop this screen is drawn on.
 *
 * `MainActivity` stacks this over the whole app, so without a background of its
 * own the session drew as floating text over whatever tab was behind it — the
 * chat list showing through the film's controls — and taps on the gaps between
 * the controls reached that tab instead of stopping here. Both halves of that
 * are fixed in this one composable, matching `CallOverlay` in
 * `call/CallScreen.kt`, which covers the app the same way for the same reason.
 *
 * Dark rather than `colorScheme.background`, and built on the same value the
 * call overlay uses: this is a surface a picture is watched on, and a light
 * chrome around a film is the wrong thing in a dark room whatever the system
 * theme says.
 *
 * The gradient is fixed rather than pulled out of the artwork. Sampling a cover
 * would need `androidx.palette`, which is a dependency this repo would not take
 * for one background — and a colour sampled from a cover is as often muddy as it
 * is lovely, on a screen whose whole job is to stay out of the way.
 */
@Composable
private fun TogetherOverlay(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(TogetherBackgroundTop, TogetherBackground)),
            )
            // Swallow taps that miss a control. Compose routes a tap on an
            // unhandled area to whatever sits behind it, so a background alone
            // would still let someone open a chat through the film.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) { content() }
}

/** Mirrors `CallBackground` in `call/CallScreen.kt`. */
private val TogetherBackground = Color(0xFF0E1621)

/** One step lighter at the top, so the screen has a direction to it. */
private val TogetherBackgroundTop = Color(0xFF1B2740)

/** The sleeve behind the artwork — one step up from the backdrop, not black, so
 *  an audio session reads as a record cover rather than a dead screen. */
private val SleeveColor = Color(0xFF1A2438)

/** Cards on the dark backdrop. Translucent so the gradient reads through. */
private val CardColor = Color(0x14FFFFFF)

/** Text on the dark backdrop, which is not `colorScheme.onSurface`. */
private val OnDark = Color(0xFFF2F5FA)
private val OnDarkMuted = Color(0xFF9FB0C7)

/** How far the skip buttons move. Matches the desktop transport. */
private const val SKIP_MS: Long = 10_000

// ── Choosing something to play ───────────────────────────────────────────────

/** Where the home screen is in the two-step "what, then who" flow. */
private sealed interface HomeStep {
    data object Choosing : HomeStep
    data object Browsing : HomeStep
    data object Linking : HomeStep
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
    data class Track(val track: TogetherDecisions.Track) : Chosen

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
 */
@Composable
private fun PlayerHome(onPickFileWith: (peer: String, label: String) -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf<HomeStep>(HomeStep.Choosing) }
    var chosen by remember { mutableStateOf<Chosen?>(null) }
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
            )

            is HomeStep.Browsing -> LibraryBrowser(
                libraryGranted = libraryGranted,
                onAsk = {
                    askToReadLibrary.launch(MediaLibraryAccess.permissionFor(Build.VERSION.SDK_INT))
                },
                onBack = { step = HomeStep.Choosing },
                onPlay = { track -> chosen = Chosen.Track(track) },
            )

            is HomeStep.Linking -> LinkField(
                onBack = { step = HomeStep.Choosing },
                onPlay = { link -> chosen = Chosen.Link(link) },
            )
        }
    }

    // The second step, over whichever of the three is showing. A sheet rather
    // than a screen because the thing they just chose is still behind it, which
    // is the difference between "and who with?" and starting over.
    chosen?.let { what ->
        ListenWithSheet(
            onDismiss = { chosen = null },
            onChosen = { listener ->
                chosen = null
                failed = !startWith(context, what, listener, onPickFileWith)
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
    listener: TogetherDecisions.Listener,
    onPickFileWith: (peer: String, label: String) -> Unit,
): Boolean = when (what) {
    is Chosen.Track -> runCatching {
        TogetherManager.start(
            context,
            listener.npub,
            listener.label,
            Uri.parse(what.track.uri),
            MusicLibrary.recordingOf(what.track),
        )
    }.onFailure { Log.w(TAG, "could not start on a library track", it) }.isSuccess

    is Chosen.Link -> when (val link = what.link) {
        is TogetherDecisions.Link.Video -> runCatching {
            TogetherManager.startEmbed(context, listener.npub, listener.label, link.videoId)
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
            TogetherManager.startStream(context, listener.npub, listener.label, content)
        }.onFailure { Log.w(TAG, "could not start on a link", it) }.isSuccess

        // Unreachable: `LinkField` never offers an unplayable link. Answered
        // rather than ignored so a future caller cannot get a silent no.
        is TogetherDecisions.Link.NotPlayable -> false
    }

    // The picker is the next step, not the last one — so this succeeded at what
    // it was asked to do even though no session exists yet.
    is Chosen.AFile -> {
        onPickFileWith(listener.npub, listener.label)
        true
    }
}

private const val TAG = "TogetherScreen"

/** The three ways in. */
@Composable
private fun ChooseASource(
    libraryGranted: Boolean,
    onBrowse: () -> Unit,
    onPickAFile: () -> Unit,
    onLink: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.together_home_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnDark,
        )
        Text(
            stringResource(R.string.together_home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkMuted,
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
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.together_home_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
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
            .background(CardColor)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SleeveColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = OnDark, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = OnDark)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OnDarkMuted)
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
    onPlay: (TogetherDecisions.Track) -> Unit,
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
                color = OnDarkMuted,
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
                color = OnDarkMuted,
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
                color = OnDarkMuted,
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                // A stable key, like every data-driven list in this app: without
                // one, row state reattaches to the wrong item as the filter
                // narrows.
                items(shown, key = { it.uri }) { track ->
                    TrackRow(track, onClick = { onPlay(track) })
                }
                if (loaded.truncated) {
                    item(key = "truncated") {
                        // Said out loud. A list silently cut off reads as "that
                        // is all your music", and the person whose album is past
                        // the cut concludes the feature cannot see it.
                        Text(
                            stringResource(R.string.together_library_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkMuted,
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
                tint = OnDark,
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = OnDark)
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
                color = OnDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                TogetherDecisions.trackSubtitle(track),
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OnDarkMuted)
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
            .background(SleeveColor),
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
                tint = OnDarkMuted,
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
            color = OnDarkMuted,
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
                color = OnDarkMuted,
            )
        }
    }
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

@Composable
private fun LiveSession(s: TogetherManager.UiState.Live, onStream: () -> Unit) {
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
        color = OnDark,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.together_with, s.peerLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("·", color = OnDarkMuted)
        Text(statusLabel(s), style = MaterialTheme.typography.bodyMedium, color = OnDarkMuted)
    }

    // The measured half, which the desktop player has had since 2026-08-05 and
    // this one did not. Recomputed on every recomposition rather than stored,
    // because whether these may be shown at all depends on how old the reading
    // is *now* — see `TogetherDecisions.measurement`. The position poll drives
    // a recomposition every 250 ms while playing, which is what ages them off
    // the screen once corrections stop arriving.
    val measured = TogetherDecisions.measurement(
        driftMs = s.driftMs,
        qualityMs = s.qualityMs,
        ageMs = System.currentTimeMillis() - s.correctedAtMs,
    )
    driftLabel(measured.drift)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = OnDarkMuted)
    }
    // Deliberately not colour-coded, on either frontend: "we've lost track of
    // them" is an honest report of poor measurement, not a fault, and red would
    // say otherwise.
    qualityLabel(measured.quality)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = OnDarkMuted)
    }

    // Control-and-status, and the honest limit of it, while another app plays.
    if (s.external) {
        Text(
            stringResource(R.string.together_follow_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
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

    Transport(s)

    // The third answer to §9a's question, beside "find your own copy" and "take
    // mine": let them watch this one as it plays. Offered only by the side that
    // holds the file and only for our own player — an embed is already on both
    // screens, and an external session is somebody else's audio to send.
    if (s.weLead && !s.embed && !s.external && !s.streaming) {
        TextButton(onClick = onStream) { Text(stringResource(R.string.together_stream)) }
        Text(
            stringResource(R.string.together_stream_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
        )
        // Before the system dialog, not after: it arrives with no explanation of
        // its own, and a recording prompt nobody can account for is one people
        // are right to refuse.
        Text(
            stringResource(R.string.together_stream_consent),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
        )
    }
    if (s.streaming) {
        Text(
            stringResource(R.string.together_streaming),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDark,
        )
        // The one thing about the microphone that is not obvious from the icon.
        Text(
            stringResource(R.string.together_mic_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
        )
    }

    TextButton(onClick = { TogetherManager.leave() }) {
        Text(stringResource(R.string.together_leave))
    }

    // The honest limits, on screen rather than in a doc nobody reads.
    Text(
        stringResource(R.string.together_accuracy_note),
        style = MaterialTheme.typography.bodySmall,
        color = OnDarkMuted,
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
        color = OnDarkMuted,
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
            .background(SleeveColor),
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
 * Play, pause and the two skips all still work, because those need no length.
 */
@Composable
private fun Transport(s: TogetherManager.UiState.Live) {
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
                thumbColor = OnDark,
                activeTrackColor = OnDark,
                inactiveTrackColor = CardColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                TogetherDecisions.clock(shown),
                style = MaterialTheme.typography.labelMedium,
                color = OnDarkMuted,
            )
            // Nothing at all rather than `0:00` when no length is known — the
            // decision is `remainingClock`'s, tested there.
            TogetherDecisions.remainingClock(shown, s.durationMs)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = OnDarkMuted)
            }
        }
    }

    // Back / play-pause / forward, centred, matching the desktop transport. The
    // skips go through `setState` like every other command, so they are ordered
    // by the same Lamport counter and cannot race the other side's.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
        // The microphone, and only where it means something: a streamed session
        // carries one audio track that the sender's voice shares with what they
        // are playing (docs/TOGETHER.md §15). In every other mode there is no
        // audio of ours going anywhere, and a control that toggles nothing is
        // worse than no control — the same rule the library button follows.
        if (s.streaming) {
            val micOn by TogetherManager.micEnabled.collectAsState()
            IconButton(onClick = { TogetherManager.toggleMic() }) {
                Icon(
                    MicIcon,
                    contentDescription = stringResource(
                        if (micOn) R.string.together_mic_off else R.string.together_mic_on,
                    ),
                    tint = if (micOn) MaterialTheme.colorScheme.primary else OnDarkMuted,
                )
            }
        }
        TextButton(onClick = { skip(-SKIP_MS) }) {
            Text(stringResource(R.string.together_back_ten), color = OnDark)
        }
        // The one big control. A filled circle rather than a Button, because at
        // this size the label would be the shape — and because it is the only
        // thing on the screen anybody reaches for in the dark.
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(OnDark)
                .clickable { TogetherManager.setState(s.positionMs, !s.playing) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (s.playing) PauseIcon else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (s.playing) R.string.together_pause else R.string.together_play,
                ),
                tint = TogetherBackground,
                modifier = Modifier.size(32.dp),
            )
        }
        TextButton(onClick = { skip(SKIP_MS) }) {
            Text(stringResource(R.string.together_forward_ten), color = OnDark)
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
