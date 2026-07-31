/// Composition root.
///
/// The only place that decides *which* backend the app talks to, and now the
/// only place that knows the bridge exists at all — nothing under `lib/src/`
/// outside `data/rust_comrade_repository.dart` imports `src/rust/`.
///
/// ## Choosing a backend
///
/// ```sh
/// flutter run                                   # FakeComradeRepository
/// flutter run --dart-define=COMRADE_BACKEND=rust  # the real core
/// ```
///
/// The fake is still the default, deliberately. The real backend needs a
/// `libcomrade_jni` built for the target and placed where the platform's
/// loader can find it (`jniLibs/` on Android, alongside the executable on
/// desktop); until every target ships one, defaulting to Rust would turn a
/// missing artifact into a black screen at launch. Widget tests never come
/// through here at all — they inject a repository directly (`test/helpers.dart`).
///
/// When the bridge cannot be loaded the app does **not** silently fall back to
/// the fake: `--dart-define=COMRADE_BACKEND=rust` is a statement about which
/// core you want, and quietly answering with seeded demo data instead is the
/// worst possible failure mode.
///
/// ## The platform seams
///
/// The call and media UIs declare seams (`CallEngine`, `MediaPlaybackDelegate`,
/// `AttachmentPicker`, `MediaCaptureDelegate`, `VoiceNoteRecorder`) whose
/// defaults do nothing and say so out loud. This is where the Android
/// implementations are attached — [_mediaOverrides] probes the device once at
/// startup, so the composer can gate its controls on what actually exists
/// instead of offering a camera to a machine that has none.
///
/// On a platform with no native half (desktop today) the probe answers "nothing
/// available" and every seam keeps its honest default.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'src/app.dart';
import 'src/data/comrade_repository.dart';
import 'src/data/fake_comrade_repository.dart';
import 'src/data/rust_comrade_repository.dart';
import 'src/platform/platform.dart';
import 'src/state/providers.dart';
import 'src/state/settings_providers.dart';
import 'src/widgets/media_attachment.dart';

/// `fake` (default) or `rust`. See the library doc above.
const String kBackend =
    String.fromEnvironment('COMRADE_BACKEND', defaultValue: 'fake');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Built here rather than inside the provider because the bridge needs an
  // `await` to load the library, and a Riverpod `Provider` body cannot.
  final ComradeRepository repository = kBackend == 'rust'
      ? await RustComradeRepository.connect()
      : FakeComradeRepository();

  final ComradePlatform platform = ComradePlatform();
  final List<Override> mediaOverrides = await _mediaOverrides(platform);

  runApp(
    ProviderScope(
      overrides: <Override>[
        comradeRepositoryProvider.overrideWith((Ref ref) {
          ref.onDispose(repository.dispose);
          return repository;
        }),
        screenSecurityProvider.overrideWithValue(platform.screen),
        ...mediaOverrides,
      ],
      child: const ComradeApp(),
    ),
  );
}

/// Probe the device once, then attach only the seams it can honour.
///
/// The probe is a single channel round trip at startup rather than a per-build
/// question, because the answer cannot change while the app runs — a phone does
/// not grow a camera — and because a `FutureProvider` behind the composer's
/// action button would make it flicker on every rebuild.
Future<List<Override>> _mediaOverrides(ComradePlatform platform) async {
  final MediaCapabilities caps = await platform.media.capabilities();
  return <Override>[
    if (caps.pick)
      attachmentPickerProvider
          .overrideWithValue(NativeAttachmentPicker(platform.media)),
    if (caps.canCapture)
      mediaCaptureProvider
          .overrideWithValue(NativeMediaCapture(platform.media, caps)),
    if (caps.recordVoice)
      voiceNoteRecorderProvider.overrideWithValue(
        NativeVoiceNoteRecorder(platform.recorder, available: true),
      ),
    if (caps.playAudio || caps.openExternally)
      mediaPlaybackProvider
          .overrideWithValue(NativeMediaPlayback(platform.media)),
  ];
}
