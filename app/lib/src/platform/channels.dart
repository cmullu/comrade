/// Channel names and error codes — the Dart mirror of
/// `android/app/src/main/kotlin/mullu/comrade/channel/ChannelNames.kt`.
///
/// Nothing outside this file spells a channel name as a literal. A typo in a
/// channel name is a silent failure — the handler simply never fires, with no
/// error anywhere — which is exactly the class of bug a shared constant kills.
///
/// See `PLATFORM_CHANNELS.md` §2 for the full inventory and §1 for the rule
/// these channels exist under: **they carry state and control, never data the
/// Rust core can serve.** Conversations, messages, the timeline, contacts, call
/// history and profiles all come from Rust over `flutter_rust_bridge`, not from
/// here.
library;

/// Method channels (control) and event channels (state).
abstract final class Channels {
  static const call = 'mullu.comrade/call';
  static const callState = 'mullu.comrade/call/state';

  static const callVideo = 'mullu.comrade/call/video';
  static const callVideoEvents = 'mullu.comrade/call/video/events';

  /// PlatformView type id for the `SurfaceViewRenderer` fallback renderer.
  /// Registered but not used by the default video widget — see
  /// `PLATFORM_CHANNELS.md` §6.3.
  static const callVideoSurfaceView = 'mullu.comrade/call/video/surface';

  /// Picture-in-picture. Not under `call/` because it is a *window* concern,
  /// not a media one: the same window mode would serve any other full-screen
  /// surface, and nothing here touches `CallManager`.
  static const pip = 'mullu.comrade/pip';
  static const pipState = 'mullu.comrade/pip/state';

  static const wakeWord = 'mullu.comrade/wakeword';
  static const wakeWordState = 'mullu.comrade/wakeword/state';

  static const relay = 'mullu.comrade/relay';
  static const relayState = 'mullu.comrade/relay/state';

  static const models = 'mullu.comrade/models';
  static const modelsState = 'mullu.comrade/models/state';

  static const recorder = 'mullu.comrade/recorder';

  static const system = 'mullu.comrade/system';
}

/// Stable `PlatformException.code` values. The `message` is for humans and may
/// change; a code may not.
abstract final class PlatformErrorCodes {
  /// A runtime permission the user refused. `details` is the denied list.
  static const permissionDenied = 'PERMISSION_DENIED';

  /// A permission-gated method was called with no Activity attached — the
  /// engine is running headless. Not retryable; it means the call came from
  /// somewhere it shouldn't have.
  static const noActivity = 'NO_ACTIVITY';

  /// `comrade_core::call::validate_turn_url` rejected the URI.
  static const illegalTurnUrl = 'ILLEGAL_TURN_URL';

  /// Anything `ComradeCore`'s `rethrowing()` wrapper produced. The message is
  /// already user-safe and never contains a credential.
  static const coreError = 'CORE_ERROR';

  /// A missing or mistyped argument — a Dart-side programming error.
  static const badArgument = 'BAD_ARGUMENT';

  /// Unknown model id, renderer id, or similar lookup miss.
  static const notFound = 'NOT_FOUND';
}
