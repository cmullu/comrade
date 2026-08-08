package mullu.comrade.ui

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import mullu.comrade.R
import mullu.comrade.call.CallManager
import mullu.comrade.together.LibraryResolver
import mullu.comrade.together.MediaLibraryAccess
import mullu.comrade.together.MediaSessionAccess
import mullu.comrade.together.PlaybackModeDecision
import mullu.comrade.together.PlaybackOwnership
import mullu.comrade.together.ShareTransfer
import mullu.comrade.together.TogetherDecisions
import mullu.comrade.together.TogetherManager
import java.util.Locale

/**
 * The watch-together surface.
 *
 * Deliberately plain: this screen owns no sync logic at all. Every decision
 * about what the player does lives in `TogetherDecisions` (pure, unit-tested)
 * and `comrade_core::together` (shared with desktop), and the session outlives
 * this composition because [TogetherManager] and its foreground service own it.
 * Disposing this screen must not stop the film.
 *
 * The two rules in the copy are the ones `docs/PRESENCE.md` §5 sets and
 * `docs/TOGETHER.md` §7 repeats: it never says "synced" or "in sync", because we
 * do not know that; and when the heartbeats stop it says we lost track of
 * *them*, because that is what we observed.
 */
@Composable
fun TogetherScreen(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by TogetherManager.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                // A video surface plus controls plus the two honest notes overflows
                // a short screen in landscape, which is exactly the orientation a
                // film is watched in.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is TogetherManager.UiState.Idle -> {
                    Text(stringResource(R.string.together_title), style = MaterialTheme.typography.titleLarge)
                    Button(onClick = onPickFile) { Text(stringResource(R.string.together_pick_file)) }
                }

                is TogetherManager.UiState.Invited -> {
                    Text(
                        stringResource(R.string.together_invited, s.peerLabel, s.title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A video needs neither a file nor a permission, so
                        // "Join" is a single unconditional tap; a file still
                        // sends someone to the picker, because that is genuinely
                        // the next step.
                        if (s.youtube) {
                            Button(onClick = { TogetherManager.joinEmbed(context) }) {
                                Text(stringResource(R.string.together_join_video))
                            }
                        } else {
                            Button(onClick = onPickFile) { Text(stringResource(R.string.together_join)) }
                            // The case `together` otherwise assumes away: you do
                            // not have it. Their copy comes straight from their
                            // device — never through a server of ours. Absent
                            // for a video: there is no file either side holds,
                            // so the offer would be one nobody could accept.
                            TextButton(onClick = { TogetherManager.askForTheirCopy(context) }) {
                                Text(stringResource(R.string.together_ask_for_copy))
                            }
                        }
                        TextButton(onClick = { TogetherManager.leave() }) {
                            Text(stringResource(R.string.together_not_now))
                        }
                    }
                    FollowWhatIsPlaying(
                        invited = s,
                        refusal = followRefusal,
                        onTry = { followRefusal = TogetherManager.followExternal(context) },
                    )
                    // Only when a lookup could find anything: a YouTube invitation
                    // names no recording, and a blank title means none was carried.
                    val couldLook = !s.youtube && s.title.isNotBlank()
                    // The same one-ask rule `/play` follows, and for the same
                    // reason: someone who has already refused gets no dialog from
                    // Android, so offering the button again would be offering a
                    // button that does nothing. `libraryAsked` is the local half —
                    // the preference is what persists, this is what recomposes.
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

                is TogetherManager.UiState.Live -> LiveSession(s)
            }

            // Outside the `when` on purpose: the relay question can arrive while a
            // handover is running in any of these states, and it must not be
            // possible to leave it unanswered by whatever the session does next.
            ShareRelayConsent()
        }
    }
}

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
 * Dark rather than `colorScheme.background`, and the same value the call
 * overlay uses: this is a surface a picture is watched on, and a light chrome
 * around a film is the wrong thing in a dark room whatever the system theme
 * says.
 */
@Composable
private fun TogetherOverlay(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TogetherBackground)
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

/** The sleeve behind the artwork — one step up from the backdrop, not black, so
 *  an audio session reads as a record cover rather than a dead screen. */
private val SleeveColor = Color(0xFF1A2438)

/** How far the skip buttons move. Matches the desktop transport. */
private const val SKIP_MS: Long = 10_000

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
    val context = androidx.compose.ui.platform.LocalContext.current
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
private fun LiveSession(s: TogetherManager.UiState.Live) {
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

    // The centrepiece, and music-first: a square sleeve with a note in it, and
    // the video surface *inside* the same block when the recording turns out to
    // have a picture. One block, so an album gets a cover and a film gets a
    // screen without two layouts to keep in step — the same shape the desktop
    // player uses.
    //
    // Absent entirely when another app holds the playback (docs/TOGETHER.md
    // §13): there is nothing of ours to draw, and an empty sleeve over somebody
    // else's music would be a picture of a player Comrade does not have.
    if (!s.external) Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                (s.picture as? TogetherDecisions.Picture.Video)
                    ?.let { p -> TogetherDecisions.aspectRatioOf(p)?.let { Modifier.aspectRatio(it) } }
                    ?: Modifier.aspectRatio(1f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(SleeveColor),
        contentAlignment = Alignment.Center,
    ) {
        val video = s.picture as? TogetherDecisions.Picture.Video
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
            video == null -> Icon(
                QueueMusicIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
            else -> VideoSurface(video)
        }
    }

    Text(
        s.title.ifBlank { s.peerLabel },
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(statusLabel(s), style = MaterialTheme.typography.bodyMedium)

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
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    // Deliberately not colour-coded, on either frontend: "we've lost track of
    // them" is an honest report of poor measurement, not a fault, and red would
    // say otherwise.
    qualityLabel(measured.quality)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }

    // Control-and-status, and the honest limit of it, while another app plays.
    if (s.external) {
        Text(stringResource(R.string.together_follow_note), style = MaterialTheme.typography.bodySmall)
    }

    // While a finger is on the slider the poll must not move it — the decision
    // is TogetherDecisions.pollMayMoveSlider, and the manager honours it; this
    // only has to report the drag boundaries.
    //
    // No slider at all for a followed app: a `MediaSession` carries no duration
    // we can trust, so the track would be a bar with no end on it — a scrubber
    // that lies about where the end is, which is worse than no scrubber. Play,
    // pause and the two skips all still work, because those need no length.
    var dragging by remember { mutableFloatStateOf(-1f) }
    val max = s.durationMs.coerceAtLeast(1L).toFloat()
    if (!s.external) Slider(
        value = if (dragging >= 0f) dragging else s.positionMs.toFloat().coerceIn(0f, max),
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
        modifier = Modifier.fillMaxWidth(),
    )

    // Back / play-pause / forward, centred, matching the desktop transport. The
    // skips go through `setState` like every other command, so they are ordered
    // by the same Lamport counter and cannot race the other side's.
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                    tint = if (micOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        TextButton(onClick = { skip(-SKIP_MS) }) { Text("−10s") }
        Button(
            onClick = { TogetherManager.setState(s.positionMs, !s.playing) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(if (s.playing) "Pause" else "Play")
        }
        TextButton(onClick = { skip(SKIP_MS) }) { Text("+10s") }
    }

    TextButton(onClick = { TogetherManager.leave() }) {
        Text(stringResource(R.string.together_leave))
    }

    // The honest limits, on screen rather than in a doc nobody reads.
    Text(stringResource(R.string.together_accuracy_note), style = MaterialTheme.typography.bodySmall)
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
    )
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
