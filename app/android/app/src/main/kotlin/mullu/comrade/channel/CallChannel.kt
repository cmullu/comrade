package mullu.comrade.channel

import android.Manifest
import android.content.Context
import android.os.Build
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mullu.comrade.ComradeCore
import mullu.comrade.call.AudioRoute
import mullu.comrade.call.CallManager
import mullu.comrade.call.CallQuality
import mullu.comrade.call.CallUiState
import uniffi.comrade_core.CallMediaKind

/**
 * Control + state for the call service (`CallManager`, `CallService`, `Ringer`).
 *
 * A thin, typed facade over `CallManager`'s existing public API — it adds no
 * state of its own and holds no session knowledge. Everything hard about calls
 * (the WebRTC state machine, glare resolution, ICE restart, media recovery,
 * audio focus, the `MicHolderSet` discipline) stays exactly where it already
 * works, in the 2,039 lines of `CallManager` this phase deliberately does not
 * touch. See `../PLATFORM_CHANNELS.md` §5.
 *
 * Note what is *absent*: nothing here reaches `ComradeCore.pollEvent()`, and
 * nothing here is called from a WebRTC thread. The channel only observes
 * `StateFlow`s (safe from any thread) and calls already-`@Synchronized` public
 * methods from a coroutine.
 */
internal class CallChannel(
    messenger: BinaryMessenger,
    private val appContext: Context,
    private val permissions: PermissionBridge,
) : MethodChannel.MethodCallHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val methods = MethodChannel(messenger, ChannelNames.CALL)
        .also { it.setMethodCallHandler(this) }

    private val state = EventChannelRelay(messenger, ChannelNames.CALL_STATE) { callStateFlow() }

    // ── State ────────────────────────────────────────────────────────────────

    /** The five non-phase flows, bundled so the outer `combine` stays typed. */
    private data class Controls(
        val muted: Boolean,
        val cameraOn: Boolean,
        val quality: CallQuality,
        val route: AudioRoute,
        val available: List<AudioRoute>,
    )

    /**
     * All seven of `CallManager`'s observable flows, combined into one
     * conflated snapshot.
     *
     * Combined rather than published as seven channels so Dart always sees a
     * *coherent* picture: a mute toggle and a phase transition that happen in
     * the same instant arrive together, not as two frames where the UI briefly
     * shows a muted Idle call. `combine` also gives the snapshot-on-subscribe
     * behaviour the relay contract depends on, since every input is a
     * `StateFlow` with a current value.
     *
     * Nested as 5 + 3 rather than one 7-way `combine` so both calls hit the
     * *typed* overloads. The vararg overload would work too, but it hands the
     * lambda an `Array<Any?>` and seven unchecked casts — and this file cannot
     * be compiled here, so the version the type checker can verify wins.
     */
    private fun callStateFlow(): Flow<Any?> {
        val controls = combine(
            CallManager.muted,
            CallManager.cameraOn,
            CallManager.connectionQuality,
            CallManager.audioRoute,
            CallManager.availableRoutes,
        ) { muted, cameraOn, quality, route, available ->
            Controls(muted, cameraOn, quality, route, available)
        }
        return combine(CallManager.state, controls, CallManager.sasEmojis) { phase, c, sas ->
            encodeState(
                state = phase,
                muted = c.muted,
                cameraOn = c.cameraOn,
                quality = c.quality,
                route = c.route,
                available = c.available,
                sas = sas,
            )
        }
    }

    private fun encodeState(
        state: CallUiState,
        muted: Boolean,
        cameraOn: Boolean,
        quality: CallQuality,
        route: AudioRoute,
        available: List<AudioRoute>,
        sas: List<String>?,
    ): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "muted" to muted,
            "cameraOn" to cameraOn,
            "quality" to quality.name.lowercase(),
            "audioRoute" to route.wireName(),
            "availableRoutes" to available.map { it.wireName() },
            // Deliberately nullable on the wire: null means "cannot verify"
            // (no SDP fingerprint to derive from), which is a real, displayable
            // state — an honest "no code", never a fabricated one. Dart must
            // not coerce it to an empty list. See ComradeCore.kt:491-498.
            "sasEmojis" to sas,
        )
        when (state) {
            is CallUiState.Idle -> base["phase"] = "idle"
            is CallUiState.Ringing -> base.putAll(
                mapOf(
                    "phase" to "ringing",
                    "peer" to state.peer,
                    "peerLabel" to state.peerLabel,
                    "video" to state.video,
                    "incoming" to state.incoming,
                    "remoteRinging" to state.remoteRinging,
                ),
            )
            is CallUiState.Connecting -> base.putAll(
                mapOf(
                    "phase" to "connecting",
                    "peer" to state.peer,
                    "peerLabel" to state.peerLabel,
                    "video" to state.video,
                    "incoming" to state.incoming,
                ),
            )
            is CallUiState.Active -> base.putAll(
                mapOf(
                    "phase" to "active",
                    "peer" to state.peer,
                    "peerLabel" to state.peerLabel,
                    "video" to state.video,
                    "incoming" to state.incoming,
                    "connectedAtMs" to state.connectedAtMs,
                ),
            )
            is CallUiState.Ended -> base.putAll(
                mapOf(
                    "phase" to "ended",
                    "peer" to state.peer,
                    "peerLabel" to state.peerLabel,
                    "video" to state.video,
                    "incoming" to state.incoming,
                    "outcome" to state.outcome,
                ),
            )
        }
        return base
    }

    // ── Control ──────────────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "placeCall" -> placeCall(call, result)
                "accept" -> accept(result)
                "reject" -> {
                    CallManager.reject()
                    result.postSuccess()
                }
                "hangup" -> {
                    CallManager.hangup()
                    result.postSuccess()
                }
                // Fire-and-forget: the resulting state arrives on the state
                // channel. A method that returned it would give Dart two clocks.
                "toggleMute" -> {
                    CallManager.toggleMute()
                    result.postSuccess()
                }
                "toggleCamera" -> {
                    CallManager.toggleCamera()
                    result.postSuccess()
                }
                "switchCamera" -> {
                    CallManager.switchCamera()
                    result.postSuccess()
                }
                "cycleAudioRoute" -> {
                    CallManager.cycleAudioRoute()
                    result.postSuccess()
                }
                "setAudioRoute" -> setAudioRoute(call.requireString("route"), result)
                "testTurnConnectivity" -> testTurn(call, result)
                "turnServerStatus" -> scope.replyOnIo(result) {
                    val s = ComradeCore.turnServerStatusTyped()
                    mapOf("configured" to s.configured, "url" to s.url)
                }
                "setTurnServer" -> setTurnServer(call, result)
                "callSas" -> scope.replyOnIo(result) {
                    ComradeCore.callSasTyped(call.requireString("localSdp"), call.requireString("remoteSdp"))
                }
                else -> result.postNotImplemented()
            }
        } catch (e: MissingArgument) {
            result.postError(ErrorCodes.BAD_ARGUMENT, e.message)
        }
    }

    private fun placeCall(call: MethodCall, result: MethodChannel.Result) {
        val peer = call.requireString("peer")
        val peerLabel = call.argument<String>("peerLabel").orEmpty().ifBlank { peer }
        val video = call.requireBool("video")
        withCallPermissions(video, result) {
            CallManager.startOutgoingCall(
                appContext,
                peer,
                peerLabel,
                if (video) CallMediaKind.VIDEO else CallMediaKind.AUDIO,
            )
            result.postSuccess()
        }
    }

    private fun accept(result: MethodChannel.Result) {
        // The ringing state is the only place the media kind is known at accept
        // time — the same read MainActivity's Accept button did.
        val ringing = CallManager.state.value as? CallUiState.Ringing
        if (ringing == null) {
            // Nothing to accept: the call ended (or was rejected) between the
            // tap and this handler. Not an error — succeed silently, exactly as
            // CallManager.accept()'s own `session ?: return` does.
            result.postSuccess()
            return
        }
        withCallPermissions(ringing.video, result) {
            CallManager.accept(appContext)
            result.postSuccess()
        }
    }

    /**
     * Mic, plus camera for video — the deferred-action shape from
     * `MainActivity.withCallPermissions`, preserved. See [PermissionBridge].
     */
    private fun withCallPermissions(video: Boolean, result: MethodChannel.Result, action: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request call permissions")
            return
        }
        permissions.ensure(needed) { granted, denied ->
            if (granted) action() else result.postError(ErrorCodes.PERMISSION_DENIED, "call permission denied", denied)
        }
    }

    /**
     * AUDIT COMMS-06, preserved exactly: `BLUETOOTH_CONNECT` is requested only
     * when the user explicitly picks the Bluetooth route — never at startup —
     * and **denial calls [CallManager.onBluetoothPermissionDenied]**, which
     * drops Bluetooth from `availableRoutes` for the rest of the call so the
     * UI can explain the speaker fallback.
     *
     * The one behaviour that must not come back is the pre-COMMS-06 one:
     * silently doing nothing.
     */
    private fun setAudioRoute(name: String, result: MethodChannel.Result) {
        val route = AudioRoute.entries.firstOrNull { it.wireName() == name }
        if (route == null) {
            result.postError(ErrorCodes.BAD_ARGUMENT, "unknown audio route: $name")
            return
        }
        if (route != AudioRoute.BLUETOOTH || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            CallManager.setAudioRoute(route)
            result.postSuccess()
            return
        }
        if (permissions.activity == null) {
            result.postError(ErrorCodes.NO_ACTIVITY, "no Activity attached to request BLUETOOTH_CONNECT")
            return
        }
        permissions.ensure(listOf(Manifest.permission.BLUETOOTH_CONNECT)) { granted, denied ->
            if (granted) {
                CallManager.setAudioRoute(route)
                result.postSuccess()
            } else {
                CallManager.onBluetoothPermissionDenied()
                result.postError(ErrorCodes.PERMISSION_DENIED, "bluetooth permission denied", denied)
            }
        }
    }

    /**
     * Off the main thread and bounded: `testTurnConnectivity` gathers ICE
     * against a throwaway RELAY-only `PeerConnection` and can legitimately take
     * its full timeout (default 8 s). Safe mid-call — it never touches the live
     * session (`CallManager.kt:786-795`).
     */
    private fun testTurn(call: MethodCall, result: MethodChannel.Result) {
        val timeoutMs = (call.argument<Number>("timeoutMs"))?.toLong() ?: 8_000L
        scope.launch(Dispatchers.IO) {
            val diagnostic = CallManager.testTurnConnectivity(appContext, timeoutMs)
            result.postSuccess(
                when (diagnostic) {
                    CallManager.TurnDiagnostic.NO_SERVER_CONFIGURED -> "noServer"
                    CallManager.TurnDiagnostic.RELAY_AVAILABLE -> "relayAvailable"
                    CallManager.TurnDiagnostic.RELAY_UNAVAILABLE -> "relayUnavailable"
                },
            )
        }
    }

    /**
     * Write-only, matching the existing nsec-masking discipline: the credential
     * goes in and is never read back (`turnServerStatus` returns the URL only).
     * Nothing here logs it.
     */
    private fun setTurnServer(call: MethodCall, result: MethodChannel.Result) {
        val url = call.requireString("url")
        val username = call.argument<String>("username").orEmpty()
        val credential = call.argument<String>("credential").orEmpty()
        scope.replyOnIo(result, errorCode = ErrorCodes.ILLEGAL_TURN_URL) {
            ComradeCore.setTurnServerTyped(url, username, credential)
            null
        }
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        state.dispose()
        scope.cancel()
    }
}

/** Lower-camel wire name for an [AudioRoute] — `EARPIECE` → `"earpiece"`. */
private fun AudioRoute.wireName(): String = name.lowercase()
