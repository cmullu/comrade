/// Which call controls sit in the bar, and which sit behind the ⋮.
///
/// This exists because the bar could not hold what was being put in it. Six
/// 60dp buttons plus their gaps need ~490dp of width; a phone gives ~360dp. A
/// `Row` does not wrap — it runs off the right edge, taking its last children
/// with it — and the last child was **End call**, so answering a video call
/// produced a call you could not hang up from the call screen. (A `Wrap`, which
/// this screen used, does not clip, but it reflows into a second row that
/// shoves the whole bar up over the picture, and the button you reach for is
/// somewhere new on every call.)
///
/// The fix is a cap rather than a wrap: a small, fixed bar with the overflow
/// behind a ⋮, which is also what Telegram does.
///
/// Pure, and mirrored by `layoutCallControls` in `desktop/ui/call_decisions.mjs`
/// and `android/.../call/CallControls.kt`, so "End call is always in the bar,
/// and the bar always fits" is a test on all three frontends rather than
/// something you have to notice in a screenshot.
library;

/// Every control a call screen can offer — the shared vocabulary across the
/// three frontends, each of which renders them with its own widgets.
enum CallControl {
  camera,
  mic,
  speaker,
  more,
  hangup,
  screenShare,
  switchCamera,
  chat,
}

/// How many controls the bar may hold. Five 56dp buttons with their gaps fit
/// the ~336dp a 360dp-wide phone leaves after padding; six do not, which is the
/// whole reason this file exists.
const int kMaxPrimaryCallControls = 5;

/// The controls split in two: what is always on screen, and what the ⋮ opens.
class CallControlLayout {
  const CallControlLayout({required this.primary, required this.dock});

  /// Bar order, left to right. Always ends in [CallControl.hangup].
  final List<CallControl> primary;

  /// Dock order, top to bottom. Empty means no ⋮ is shown at all.
  final List<CallControl> dock;
}

/// Split the call controls into the bar and the ⋮ dock behind it.
///
/// Ordering follows Telegram: the two things you toggle constantly (camera,
/// mic) first, then output, then the overflow, and **End call last** — on the
/// right, alone, where a thumb reaching for mute cannot find it by accident.
/// A voice call simply drops the camera and everything else keeps its place,
/// so mute does not move under your thumb when a call gains video.
///
/// The capability flags are how one function serves three frontends without
/// lying about any of them: a desktop has no earpiece to route audio to and no
/// second camera to flip to, so it asks for neither and gets a four-control bar
/// rather than two dead buttons.
CallControlLayout layoutCallControls({
  required bool video,
  bool cameraOn = true,
  bool hasAudioRoutes = true,
  bool hasCameraSwitch = true,
}) {
  final List<CallControl> dock = <CallControl>[
    CallControl.screenShare,
    // Flipping a camera that is off is meaningless, so the entry goes away
    // rather than sitting there doing nothing.
    if (video && cameraOn && hasCameraSwitch) CallControl.switchCamera,
    CallControl.chat,
  ];

  final List<CallControl> primary = <CallControl>[
    if (video) CallControl.camera,
    CallControl.mic,
    if (hasAudioRoutes) CallControl.speaker,
    if (dock.isNotEmpty) CallControl.more,
    CallControl.hangup,
  ];

  return CallControlLayout(primary: primary, dock: dock);
}
