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
/// The same is true of the platform seams the call and media UIs declare
/// (`CallEngine`, `MediaPlaybackDelegate`, `AttachmentPicker`): each has a
/// do-nothing default that says so out loud, and each is overridden here once
/// its native half is wired up (see `lib/src/platform/`).
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'src/app.dart';
import 'src/data/comrade_repository.dart';
import 'src/data/fake_comrade_repository.dart';
import 'src/data/rust_comrade_repository.dart';
import 'src/state/providers.dart';

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

  runApp(
    ProviderScope(
      overrides: <Override>[
        comradeRepositoryProvider.overrideWith((Ref ref) {
          ref.onDispose(repository.dispose);
          return repository;
        }),
      ],
      child: const ComradeApp(),
    ),
  );
}
