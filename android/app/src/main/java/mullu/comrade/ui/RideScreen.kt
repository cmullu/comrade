package mullu.comrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.ride.RideDecisions
import mullu.comrade.ride.RideSignals
import mullu.comrade.together.TogetherManager

/**
 * Ride mode — the two-seat screen (`docs/RIDE.md`).
 *
 * Deliberately thin. Every rule worth arguing about lives in
 * [RideDecisions], which is pure and JVM-tested: which card a glance gets,
 * when it ages out, what is spoken, what buzzes, which music controls a seat
 * is offered and which composer it draws. This file turns those answers into
 * widgets and nothing more — the `TogetherDecisions`/`TogetherScreen` division,
 * for the reason `CLAUDE.md` gives: the tested half is the only half this
 * sandbox checks before CI.
 *
 * The card is recomputed each recomposition rather than stored, exactly like
 * `UiState.Live`'s drift readout, because whether a signal may still be shown
 * depends on how old it is *at the moment the screen draws*.
 */
@Composable
fun RideScreen(
    peers: List<ComradeCore.ComradeInfo>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var role by remember { mutableStateOf<RideDecisions.Role?>(null) }
    var peer by remember { mutableStateOf<ComradeCore.ComradeInfo?>(null) }

    val board by RideSignals.board.collectAsState()
    val together by TogetherManager.state.collectAsState()

    // A clock the card can age against. Signals arrive on the event pump, not
    // on a tick, so without this a card would sit at "Left in 400 m" until the
    // next unrelated recomposition — the staleness rule would be written and
    // never applied.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Speech and haptics arm only while this screen is open, and only for the
    // seat it was opened as — see `RideSignals.setActive`.
    val activeRole = role
    DisposableEffect(activeRole) {
        if (activeRole != null) RideSignals.setActive(context, activeRole)
        onDispose { RideSignals.setInactive() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.ride_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.ride_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val chosenRole = role
        val chosenPeer = peer
        if (chosenRole == null || chosenPeer == null) {
            RideSetup(
                peers = peers,
                role = role,
                peer = peer,
                onRole = { role = it },
                onPeer = { peer = it },
            )
            Text(
                stringResource(R.string.ride_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        GlanceCard(
            card = RideDecisions.card(nowMs, board.quick, board.route),
            driving = chosenRole == RideDecisions.Role.Driver,
        )

        MusicControls(
            controls = RideDecisions.controlsFor(chosenRole),
            live = together as? TogetherManager.UiState.Live,
            onPlayPause = { playing, posMs -> TogetherManager.setState(posMs, !playing) },
            onNext = { TogetherManager.skipForward(context) },
            onPrevious = { TogetherManager.skipBack(context) },
        )

        HorizontalDivider()

        if (RideDecisions.mayComposeQuick(chosenRole)) {
            PhraseGrid(
                onSend = { phrase ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { ComradeCore.rideSendQuick(chosenPeer.npub, phrase) }
                        }
                    }
                },
            )
        }

        if (RideDecisions.mayComposeRoute(chosenRole)) {
            RouteComposer(
                onSend = { maneuver, distanceM, note ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                ComradeCore.rideSendRoute(
                                    chosenPeer.npub,
                                    maneuver,
                                    distanceM,
                                    note,
                                )
                            }
                        }
                    }
                },
            )
        }

        TextButton(
            onClick = { role = null },
            modifier = Modifier.testTag("ride-change-seat"),
        ) {
            Text(stringResource(R.string.ride_change_seat))
        }
    }
}

/** Choosing a seat and a person — once, before anything else is drawn. */
@Composable
private fun RideSetup(
    peers: List<ComradeCore.ComradeInfo>,
    role: RideDecisions.Role?,
    peer: ComradeCore.ComradeInfo?,
    onRole: (RideDecisions.Role) -> Unit,
    onPeer: (ComradeCore.ComradeInfo) -> Unit,
) {
    Text(stringResource(R.string.ride_seat), style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SeatButton(
            label = stringResource(R.string.ride_seat_driver),
            note = stringResource(R.string.ride_seat_driver_note),
            selected = role == RideDecisions.Role.Driver,
            testTag = "ride-seat-driver",
            onClick = { onRole(RideDecisions.Role.Driver) },
            modifier = Modifier.weight(1f),
        )
        SeatButton(
            label = stringResource(R.string.ride_seat_pillion),
            note = stringResource(R.string.ride_seat_pillion_note),
            selected = role == RideDecisions.Role.Pillion,
            testTag = "ride-seat-pillion",
            onClick = { onRole(RideDecisions.Role.Pillion) },
            modifier = Modifier.weight(1f),
        )
    }

    Text(stringResource(R.string.ride_who_with), style = MaterialTheme.typography.titleMedium)
    peers.forEach { candidate ->
        FilterChip(
            selected = peer?.npub == candidate.npub,
            onClick = { onPeer(candidate) },
            label = {
                Text(
                    candidate.alias.ifBlank { candidate.name.orEmpty() }
                        .ifBlank { shortNpubOrRaw(candidate.npub) },
                )
            },
            modifier = Modifier.testTag("ride-peer-${candidate.npub}"),
        )
    }
}

@Composable
private fun SeatButton(
    label: String,
    note: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.testTag(testTag)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.testTag(testTag)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The one card. Big, and blank when there is nothing to say — a driver reading
 * a dimmed, crossed-out or reassuring-but-stale instruction is the failure this
 * whole surface exists to avoid.
 */
@Composable
private fun GlanceCard(card: RideDecisions.Card?, driving: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .testTag("ride-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (card == null) {
                Text(
                    stringResource(R.string.ride_nothing_yet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (driving) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ride_driver_note),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            card.glyph?.let { glyph ->
                Text(glyph, fontSize = 56.sp, textAlign = TextAlign.Center)
            }
            Text(
                card.headline,
                fontSize = if (card.urgency == "urgent") 40.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("ride-card-headline"),
            )
            card.detail?.let { detail ->
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The music, as much of it as this seat may have. Nothing here starts a
 * session: the Together tab owns that, and a ride screen that could also pick
 * a track would be a second way in to the thing §16 made one way in.
 */
@Composable
private fun MusicControls(
    controls: RideDecisions.Controls,
    live: TogetherManager.UiState.Live?,
    onPlayPause: (Boolean, Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Text(stringResource(R.string.ride_music), style = MaterialTheme.typography.titleMedium)
    if (live == null) {
        Text(
            stringResource(R.string.ride_music_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (controls.previous) {
            OutlinedButton(
                onClick = onPrevious,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .testTag("ride-previous"),
            ) {
                Text(stringResource(R.string.ride_previous))
            }
        }
        if (controls.playPause) {
            Button(
                onClick = { onPlayPause(live.playing, live.positionMs) },
                modifier = Modifier
                    .weight(2f)
                    .height(64.dp)
                    .testTag("ride-play-pause"),
            ) {
                Text(
                    stringResource(
                        if (live.playing) R.string.ride_pause else R.string.ride_play,
                    ),
                    fontSize = 20.sp,
                )
            }
        }
        if (controls.next) {
            OutlinedButton(
                onClick = onNext,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .testTag("ride-next"),
            ) {
                Text(stringResource(R.string.ride_next))
            }
        }
    }
}

/** Five buttons, glove-sized, in the order `RideDecisions.PHRASES` fixes. */
@Composable
private fun PhraseGrid(onSend: (String) -> Unit) {
    Text(stringResource(R.string.ride_say), style = MaterialTheme.typography.titleMedium)
    RideDecisions.PHRASES.chunked(2).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            row.forEach { phrase ->
                Button(
                    onClick = { onSend(phrase) },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .testTag("ride-phrase-$phrase"),
                ) {
                    Text(RideDecisions.phraseText(phrase) ?: phrase)
                }
            }
            // A trailing odd button keeps its width rather than stretching to
            // fill the row, so the grid stays a grid.
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** The pillion's composer: a maneuver, optionally how far, optionally where. */
@Composable
private fun RouteComposer(onSend: (String, Long?, String?) -> Unit) {
    var maneuver by remember { mutableStateOf<String?>(null) }
    var distanceM by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }

    Text(stringResource(R.string.ride_suggest), style = MaterialTheme.typography.titleMedium)
    RideDecisions.MANEUVERS.chunked(3).forEach { row ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            row.forEach { candidate ->
                FilterChip(
                    selected = maneuver == candidate,
                    onClick = { maneuver = candidate },
                    label = {
                        Text(
                            listOfNotNull(
                                RideDecisions.maneuverGlyph(candidate),
                                RideDecisions.maneuverText(candidate),
                            ).joinToString(" "),
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ride-maneuver-$candidate"),
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }

    Text(stringResource(R.string.ride_distance), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RideDecisions.DISTANCES_M.forEach { metres ->
            FilterChip(
                selected = distanceM == metres,
                // Tapping the chosen chip again clears it: "left" with no
                // distance is a real thing to say, and a chip that could only
                // ever be set would make it unsayable.
                onClick = { distanceM = if (distanceM == metres) null else metres },
                label = { Text(RideDecisions.distanceText(metres)) },
                modifier = Modifier.testTag("ride-distance-$metres"),
            )
        }
    }

    OutlinedTextField(
        value = note,
        // Capped here as well as in core, which *refuses* an over-long note
        // rather than truncating it — so without this the send would simply
        // fail at the length the composer let someone type.
        onValueChange = { if (it.length <= RideDecisions.NOTE_MAX_CHARS) note = it },
        label = { Text(stringResource(R.string.ride_landmark)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ride-note"),
    )

    Button(
        onClick = {
            maneuver?.let { chosen ->
                onSend(chosen, distanceM, note.trim().ifEmpty { null })
                note = ""
            }
        },
        enabled = maneuver != null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ride-send-route"),
    ) {
        Text(stringResource(R.string.ride_send))
    }
}

/** A readable stand-in when a comrade has neither alias nor published name. */
private fun shortNpubOrRaw(npub: String): String =
    if (npub.length <= 16) npub else "${npub.take(10)}…${npub.takeLast(4)}"
