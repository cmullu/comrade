package mullu.comrade.ui

/**
 * What the composer's round button does when there is no text to send.
 *
 * Three, not two, because a device that can take a photo can usually also
 * record video, and a two-way mic/camera toggle leaves video unreachable.
 *
 * The Dart port is `app/lib/src/util/composer_mode.dart`; the cycle order and
 * the fallback rules must agree, so both are pinned by mirrored tests.
 */
enum class CaptureMode { Voice, Photo, Video }

/**
 * The modes this device can actually offer, in cycle order.
 *
 * Capability-gated rather than assumed: a device with no camera gets a plain
 * mic, and one with neither gets nothing to swap to — the same "no fake
 * switches" rule the settings screen states.
 */
fun availableCaptureModes(
    canRecordAudio: Boolean,
    canTakePhoto: Boolean,
    canRecordVideo: Boolean,
): List<CaptureMode> = buildList {
    if (canRecordAudio) add(CaptureMode.Voice)
    if (canTakePhoto) add(CaptureMode.Photo)
    if (canRecordVideo) add(CaptureMode.Video)
}

/**
 * [current], corrected to something this device has, or null when it has
 * nothing. A stale mode — a default that never applied on this hardware — must
 * never leave the button doing nothing when tapped.
 */
fun effectiveCaptureMode(current: CaptureMode?, available: List<CaptureMode>): CaptureMode? = when {
    available.isEmpty() -> null
    current != null && current in available -> current
    else -> available.first()
}

/**
 * The mode the swap control would move to, or null when there is nothing to
 * swap to (fewer than two modes) — which is also the signal to hide the
 * control rather than show one that does nothing.
 *
 * Wraps around, so the cycle is reachable in one direction only and never
 * dead-ends on the last mode.
 */
fun nextCaptureMode(current: CaptureMode?, available: List<CaptureMode>): CaptureMode? {
    if (available.size < 2) return null
    val effective = effectiveCaptureMode(current, available) ?: return null
    return available[(available.indexOf(effective) + 1) % available.size]
}
