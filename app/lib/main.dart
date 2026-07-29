/// Composition root.
///
/// The only place that decides *which* backend the app talks to. Today that is
/// [FakeComradeRepository]; when `package:comrade/src/rust/api.dart` lands,
/// swap the override here for the bridge-backed [ComradeRepository] and
/// nothing else under `lib/src/` changes.
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
import 'src/state/providers.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    ProviderScope(
      overrides: <Override>[
        comradeRepositoryProvider.overrideWith((Ref ref) {
          final ComradeRepository repo = FakeComradeRepository();
          ref.onDispose(repo.dispose);
          return repo;
        }),
      ],
      child: const ComradeApp(),
    ),
  );
}
