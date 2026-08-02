/// The composer's capture-mode rules — which modes a device offers, what the
/// round button falls back to, and where the swap control goes next.
///
/// Extracted from `widgets/composer.dart` so the rules are pure and testable,
/// and so they can be pinned against the Kotlin port
/// (`android/.../ui/ComposerMode.kt`) rather than drifting from it. The widget
/// keeps the capability *probe* and the glyphs; only the decisions live here.
library;

/// What the round button does when there is no text to send.
///
/// Three, not two, because a device that can take a photo can usually also
/// record video, and a two-way mic/camera toggle leaves video unreachable.
enum ComposerCaptureMode { voice, photo, video }

/// The modes this device can actually offer, in cycle order.
///
/// Capability-gated rather than assumed: a device with no camera gets a plain
/// mic, and one with neither gets nothing to swap to — the same "no fake
/// switches" rule the settings screen states.
List<ComposerCaptureMode> availableCaptureModes({
  required bool canRecordAudio,
  required bool canTakePhoto,
  required bool canRecordVideo,
}) =>
    <ComposerCaptureMode>[
      if (canRecordAudio) ComposerCaptureMode.voice,
      if (canTakePhoto) ComposerCaptureMode.photo,
      if (canRecordVideo) ComposerCaptureMode.video,
    ];

/// [current], corrected to something this device has, or null when it has
/// nothing. A stale mode — a camera that vanished, or a default that never
/// applied — must never leave the button doing nothing when tapped.
ComposerCaptureMode? effectiveCaptureMode(
  ComposerCaptureMode? current,
  List<ComposerCaptureMode> available,
) {
  if (available.isEmpty) return null;
  if (current != null && available.contains(current)) return current;
  return available.first;
}

/// The mode the swap control would move to, or null when there is nothing to
/// swap to (fewer than two modes) — which is also the signal to hide the
/// control rather than show one that does nothing.
///
/// Wraps around, so the cycle never dead-ends on the last mode.
ComposerCaptureMode? nextCaptureMode(
  ComposerCaptureMode? current,
  List<ComposerCaptureMode> available,
) {
  if (available.length < 2) return null;
  final ComposerCaptureMode? effective =
      effectiveCaptureMode(current, available);
  if (effective == null) return null;
  return available[(available.indexOf(effective) + 1) % available.length];
}
