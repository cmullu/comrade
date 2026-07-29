package mullu.comrade.channel

/**
 * Every platform-channel name, in one place.
 *
 * Mirrored verbatim by `app/lib/src/platform/channels.dart`. Nothing else on
 * either side of the boundary spells a channel name as a literal — a typo in a
 * channel name is a silent failure (the handler simply never fires, with no
 * error anywhere), which is exactly the kind of bug a shared constant removes.
 *
 * Naming: `mullu.comrade/<service>` for control, `mullu.comrade/<service>/state`
 * for the observable state stream. See `../PLATFORM_CHANNELS.md` §2.
 */
internal object ChannelNames {
    const val CALL = "mullu.comrade/call"
    const val CALL_STATE = "mullu.comrade/call/state"

    const val CALL_VIDEO = "mullu.comrade/call/video"
    const val CALL_VIDEO_EVENTS = "mullu.comrade/call/video/events"

    // Not under `call/`: picture-in-picture is a *window* mode, not a media
    // concern — nothing behind it touches CallManager's media.
    const val PIP = "mullu.comrade/pip"
    const val PIP_STATE = "mullu.comrade/pip/state"

    const val WAKE_WORD = "mullu.comrade/wakeword"
    const val WAKE_WORD_STATE = "mullu.comrade/wakeword/state"

    const val RELAY = "mullu.comrade/relay"
    const val RELAY_STATE = "mullu.comrade/relay/state"

    const val MODELS = "mullu.comrade/models"
    const val MODELS_STATE = "mullu.comrade/models/state"

    const val RECORDER = "mullu.comrade/recorder"

    const val SYSTEM = "mullu.comrade/system"
}

/**
 * Stable `MethodChannel.Result.error` codes. Dart maps these to typed failures
 * (see `platform_exception.dart`); a `message` is for humans and may change, a
 * code may not.
 */
internal object ErrorCodes {
    /** A runtime permission the user refused. `details` is the denied permission list. */
    const val PERMISSION_DENIED = "PERMISSION_DENIED"

    /**
     * A permission-gated method was invoked with no Activity attached — the
     * engine is running headless. Not an error the UI can recover from by
     * retrying; it means the call arrived from somewhere it shouldn't have.
     */
    const val NO_ACTIVITY = "NO_ACTIVITY"

    /** `comrade_core::call::validate_turn_url` rejected the URI. */
    const val ILLEGAL_TURN_URL = "ILLEGAL_TURN_URL"

    /** Anything `ComradeCore`'s `rethrowing()` wrapper produced — message is already user-safe. */
    const val CORE_ERROR = "CORE_ERROR"

    /** A required argument was missing or the wrong type. A programming error on the Dart side. */
    const val BAD_ARGUMENT = "BAD_ARGUMENT"

    /** An unknown model id, renderer id, or similar lookup miss. */
    const val NOT_FOUND = "NOT_FOUND"
}
