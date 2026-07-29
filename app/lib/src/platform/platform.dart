/// The platform layer: typed Dart wrappers over the native Android services
/// that Phase 2 of the Flutter migration deliberately keeps in Kotlin.
///
/// Import this, not the individual files.
///
/// ## What lives here, and what does not
///
/// These channels carry **state and control**. They do not carry data the Rust
/// core can serve: conversations, messages, the timeline, contacts, call
/// history and profiles all come from Rust over `flutter_rust_bridge`, against
/// the same process-global runtime the Kotlin services reach through uniffi.
/// Putting DM content on a channel would create a second copy of the store in a
/// second language.
///
/// The one near-exception is the public feed
/// ([RelayState.feed]), and only because the native event router already keeps
/// that list for dedup and its 500-item cap — Dart's copy is a projection of an
/// existing native list, not a new source of truth.
///
/// ## Why these services stayed native
///
/// Because they have to work when there is no Flutter engine. An accepted DM
/// must notify you thirty minutes into the background; an incoming call must
/// ring with the screen off; a 400 MB model download must survive the app being
/// backgrounded. Attaching an engine creates channels and detaching destroys
/// them — neither starts or stops a service. See
/// `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md` §4.
///
/// ## Status
///
/// Compiles and analyzes clean; the Kotlin side builds into a real APK. **No
/// behaviour has been verified** — nothing here has been run on a device or an
/// emulator, and there are no tests for this layer yet. The APK also does not
/// yet package `libcomrade_jni.so`, so it cannot start. See §10 of
/// `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// for the precise line between what was checked and what was not.
library;

import 'call_channel.dart';
import 'media_channel.dart';
import 'model_download_channel.dart';
import 'pip_channel.dart';
import 'relay_channel.dart';
import 'screen_channel.dart';
import 'system_channel.dart';
import 'voice_recorder_channel.dart';
import 'wake_word_channel.dart';

export 'call_channel.dart';
export 'call_video.dart';
export 'channels.dart';
export 'media_channel.dart';
export 'model_download_channel.dart';
export 'pip_channel.dart';
export 'relay_channel.dart';
export 'screen_channel.dart';
export 'system_channel.dart';
export 'voice_recorder_channel.dart';
export 'wake_word_channel.dart';

/// One construction point for the whole platform layer.
///
/// Deliberately a plain object with no dependency-injection opinion — the state
/// layer (Riverpod providers, owned elsewhere) wraps this rather than this
/// reaching into it. Each wrapper accepts injected channels in its constructor
/// so tests can substitute fakes without a running engine.
class ComradePlatform {
  ComradePlatform({
    CallChannel? call,
    PipChannel? pip,
    WakeWordChannel? wakeWord,
    RelayChannel? relay,
    ModelDownloadChannel? models,
    VoiceRecorderChannel? recorder,
    MediaChannel? media,
    ScreenSecurityChannel? screen,
    SystemChannel? system,
  })  : call = call ?? CallChannel(),
        pip = pip ?? PipChannel(),
        wakeWord = wakeWord ?? WakeWordChannel(),
        relay = relay ?? RelayChannel(),
        models = models ?? ModelDownloadChannel(),
        recorder = recorder ?? VoiceRecorderChannel(),
        media = media ?? MediaChannel(),
        screen = screen ?? ScreenSecurityChannel(),
        system = system ?? SystemChannel();

  final CallChannel call;
  final PipChannel pip;
  final WakeWordChannel wakeWord;
  final RelayChannel relay;
  final ModelDownloadChannel models;
  final VoiceRecorderChannel recorder;
  final MediaChannel media;
  final ScreenSecurityChannel screen;
  final SystemChannel system;

  void dispose() => system.dispose();
}
