package mullu.comrade

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import mullu.comrade.channel.CallChannel
import mullu.comrade.channel.CallVideoChannel
import mullu.comrade.channel.CallVideoViewFactory
import mullu.comrade.channel.ModelDownloadChannel
import mullu.comrade.channel.PermissionBridge
import mullu.comrade.channel.PipChannel
import mullu.comrade.channel.RelayConnectionChannel
import mullu.comrade.channel.SystemChannel
import mullu.comrade.channel.VoiceRecorderChannel
import mullu.comrade.channel.WakeWordChannel

/**
 * Registers every Comrade platform channel with a [io.flutter.embedding.engine.FlutterEngine].
 *
 * One plugin rather than seven so the shared pieces — the [PermissionBridge]
 * (whose request codes must not collide) and the teardown order — have a single
 * owner. See `channel/../PLATFORM_CHANNELS.md` §2 for the channel inventory.
 *
 * ## Attach / detach is not "start / stop"
 *
 * This is the load-bearing distinction of Phase 2. Attaching creates channels;
 * detaching destroys them. Neither starts or stops a *service*. When
 * [onDetachedFromEngine] runs:
 *
 *  - `RelayConnectionService` keeps draining `ComradeCore.pollEvent()` and keeps
 *    raising notifications;
 *  - `CallManager` keeps its `PeerConnection`, its audio focus, and its media;
 *  - `CallService` keeps the process alive with its ongoing-call notification;
 *  - `WakeWordService` keeps listening;
 *  - `ModelDownloadService` keeps downloading;
 *  - [CallStateReactor] keeps ringing the phone.
 *
 * The only things released are the channels themselves and the video textures,
 * which belong to the engine's `TextureRegistry` and have no surface to draw to
 * anyway (§6.4).
 */
class ComradePlugin : FlutterPlugin, ActivityAware {

    private val permissions = PermissionBridge()

    private var call: CallChannel? = null
    private var callVideo: CallVideoChannel? = null
    private var pip: PipChannel? = null
    private var wakeWord: WakeWordChannel? = null
    private var relay: RelayConnectionChannel? = null
    private var models: ModelDownloadChannel? = null
    private var recorder: VoiceRecorderChannel? = null
    private var system: SystemChannel? = null

    /**
     * Exposed so `MainActivity` can hand a notification's
     * `AppNavigation.EXTRA_OPEN_TAB` to Dart (the one Kotlin → Dart direction in
     * the whole contract; fire-and-forget, stashed if no engine is attached).
     */
    internal val systemChannel: SystemChannel? get() = system

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val messenger = binding.binaryMessenger
        val context = binding.applicationContext

        call = CallChannel(messenger, context, permissions)
        callVideo = CallVideoChannel(messenger, binding.textureRegistry)
        pip = PipChannel(messenger)
        wakeWord = WakeWordChannel(messenger, context, permissions)
        relay = RelayConnectionChannel(messenger, context)
        models = ModelDownloadChannel(messenger, context)
        recorder = VoiceRecorderChannel(messenger, context, permissions)
        system = SystemChannel(messenger, context, permissions)

        // The SurfaceViewRenderer fallback for call video. Registered so it is
        // available, not used by the default widget — see CallVideoPlatformView.
        binding.platformViewRegistry.registerViewFactory(
            CallVideoViewFactory.VIEW_TYPE,
            CallVideoViewFactory(),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Order matters only in that the video channel must release its
        // textures before the engine's TextureRegistry goes away.
        callVideo?.dispose()
        call?.dispose()
        pip?.dispose()
        wakeWord?.dispose()
        relay?.dispose()
        models?.dispose()
        recorder?.dispose()
        system?.dispose()

        callVideo = null
        call = null
        pip = null
        wakeWord = null
        relay = null
        models = null
        recorder = null
        system = null
    }

    // ── ActivityAware: runtime permissions need a real Activity ──────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        permissions.activity = binding.activity
        pip?.activity = binding.activity
        binding.addRequestPermissionsResultListener(permissions)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    /**
     * Drops the Activity **and fails every pending permission request**. A
     * callback resumed against a dead Activity would, in the worst case,
     * accept a call that ended minutes ago.
     */
    override fun onDetachedFromActivity() {
        permissions.detachActivity()
        pip?.activity = null
    }
}
