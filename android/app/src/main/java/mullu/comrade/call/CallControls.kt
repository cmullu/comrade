package mullu.comrade.call

/*
 * Which call controls sit in the bar, and which sit behind the ⋮.
 *
 * This exists because the bar could not hold what was being put in it. Six
 * 60dp buttons plus their 18dp gaps need ~450dp of width before padding; a
 * phone gives ~360dp. A Compose `Row` does not wrap — it lays its children out
 * in order and the ones that no longer fit simply run off the right edge —
 * and the last child was **End call**, so answering a video call produced a
 * call that could not be hung up from the call screen.
 *
 * The fix is a cap rather than a wrap: a small, fixed bar with the overflow
 * behind a ⋮, which is also what Telegram does.
 *
 * Deliberately Compose-free (and dependency-free) so it is a plain JUnit test
 * rather than an instrumented one, and mirrored by `layoutCallControls` in
 * `app/lib/src/screens/call_controls.dart` and
 * `desktop/ui/call_decisions.mjs`. "End call is always in the bar, and the bar
 * always fits" is then a test on all three frontends rather than something you
 * have to notice in a screenshot.
 */

/**
 * Every control a call screen can offer — the shared vocabulary across the
 * three frontends, each of which renders them with its own widgets.
 */
enum class CallControl {
    CAMERA,
    MIC,
    SPEAKER,
    MORE,
    HANGUP,
    SCREEN_SHARE,
    SWITCH_CAMERA,
    CHAT,
}

/** The controls split in two: what is always on screen, and what the ⋮ opens. */
data class CallControlLayout(
    /** Bar order, left to right. Always ends in [CallControl.HANGUP]. */
    val primary: List<CallControl>,
    /** Dock order, top to bottom. Empty means no ⋮ is shown at all. */
    val dock: List<CallControl>,
)

/**
 * How many controls the bar may hold. Five 56dp buttons with their gaps fit the
 * ~336dp a 360dp-wide phone leaves after padding; six do not, which is the
 * whole reason this file exists.
 */
const val MAX_PRIMARY_CALL_CONTROLS = 5

/**
 * Split the call controls into the bar and the ⋮ dock behind it.
 *
 * Ordering follows Telegram: the two things you toggle constantly (camera,
 * mic) first, then output, then the overflow, and **End call last** — on the
 * right, alone, where a thumb reaching for mute cannot find it by accident. A
 * voice call simply drops the camera and everything else keeps its place, so
 * mute does not move under your thumb when a call gains video.
 *
 * [hasAudioRoutes] and [hasCameraSwitch] are how one function serves three
 * frontends without lying about any of them: a desktop has no earpiece to route
 * audio to and no second camera to flip to, so it asks for neither and gets a
 * four-control bar rather than two dead buttons.
 */
fun layoutCallControls(
    video: Boolean,
    cameraOn: Boolean = true,
    hasAudioRoutes: Boolean = true,
    hasCameraSwitch: Boolean = true,
): CallControlLayout {
    val dock = buildList {
        add(CallControl.SCREEN_SHARE)
        // Flipping a camera that is off is meaningless, so the entry goes away
        // rather than sitting there doing nothing.
        if (video && cameraOn && hasCameraSwitch) add(CallControl.SWITCH_CAMERA)
        add(CallControl.CHAT)
    }
    val primary = buildList {
        if (video) add(CallControl.CAMERA)
        add(CallControl.MIC)
        if (hasAudioRoutes) add(CallControl.SPEAKER)
        if (dock.isNotEmpty()) add(CallControl.MORE)
        add(CallControl.HANGUP)
    }
    return CallControlLayout(primary = primary, dock = dock)
}
