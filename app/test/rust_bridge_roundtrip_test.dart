/// The one test that actually crosses the flutter_rust_bridge wire.
///
/// Everything else under `test/` runs against [FakeComradeRepository] and
/// never loads a native library. This file is the opposite: it `dlopen`s the
/// real `comrade_jni` cdylib built by
///
/// ```text
/// cargo build -p comrade_jni
/// ```
///
/// and drives Rust through the generated bindings in `lib/src/rust/`. It is
/// the only thing in the repository that proves the Dart half of the bridge
/// and the Rust half agree — symbol export and codegen being in sync are
/// static properties, and neither one says the wire actually carries values.
///
/// Three shapes of call are covered, because each one takes a different path
/// through the generated dispatcher:
///
///  * [rust.version] — `#[frb(sync)]`, so it runs *on this isolate's thread*
///    with no port round-trip at all.
///  * [rust.conversations] — a fallible `fn` on FRB's worker pool. Called
///    before any unlock, so it must surface `UiError::VaultLocked` as a thrown
///    Dart [rust.UiError] rather than unwinding across the FFI boundary.
///  * [rust.bridgeEventStream] — an `async fn` holding a `StreamSink`, driven
///    by Rust's own Tokio runtime and delivering over a Dart port.
///
/// ## Why this is not an integration test
///
/// `integration_test/` needs a device or a desktop embedder to host the
/// Flutter engine. Nothing here touches a widget, a platform channel, or a
/// frame — it is a host-side FFI test, so it belongs in `test/`, where the
/// project's `flutter test` gate actually runs it.
///
/// ## Skipping
///
/// The .so is a build artifact, not a checked-in file, so it can legitimately
/// be missing. When it is, every test here is skipped **with a reason** and a
/// banner on stderr: a bridge test that quietly passes because it never called
/// Rust is worse than no bridge test at all.
library;

import 'dart:async';
import 'dart:io';

import 'package:comrade/src/data/comrade_repository.dart';
import 'package:comrade/src/data/models.dart';
import 'package:comrade/src/data/rust_comrade_repository.dart';
import 'package:comrade/src/rust/api.dart' as rust;
import 'package:comrade/src/rust/frb_generated.dart';
import 'package:flutter_rust_bridge/flutter_rust_bridge_for_generated.dart'
    show ExternalLibrary;
import 'package:flutter_test/flutter_test.dart';

void main() {
  final _Fixture fixture = _Fixture.resolve();

  if (fixture.skipReason != null) {
    // Loud, unmissable, and on stderr so it survives `flutter test`'s
    // progress-line rewriting.
    stderr
      ..writeln('')
      ..writeln('=' * 78)
      ..writeln('SKIPPING THE flutter_rust_bridge ROUND-TRIP TESTS')
      ..writeln(fixture.skipReason)
      ..writeln('Nothing below this line exercised Rust. Build the library '
          'and re-run:')
      ..writeln('    cargo build -p comrade_jni')
      ..writeln('=' * 78)
      ..writeln('');
  }

  group('flutter_rust_bridge round trip', () {
    setUpAll(() async {
      await RustLib.init(
        externalLibrary: ExternalLibrary.open(fixture.libraryPath!),
      );
    });

    tearDownAll(() {
      RustLib.dispose();
    });

    test('sync fn: version() returns the crate version', () {
      // `#[frb(sync)]` — a plain `String`, not a `Future`. If this were async
      // the call would not compile, which is itself part of the assertion.
      final String actual = rust.version();

      expect(
        actual,
        fixture.crateVersion,
        reason: 'version() must return crates/comrade_jni/Cargo.toml\'s '
            '[package] version. A mismatch means the loaded .so is not the '
            'one this checkout builds.',
      );
    });

    test('fallible fn before unlock: rejects gracefully, does not panic',
        () async {
      // No unlock_vault() has run, so `ui.store_ref()` is None and the Rust
      // side returns `Err(UiError::VaultLocked)`. The generated code maps that
      // onto a thrown Dart `UiError` — a *value* crossing the boundary, not an
      // unwind. `PanicException` (or a hard crash) would mean the boundary
      // leaked a Rust panic.
      Object? thrown;
      try {
        await rust.conversations();
        fail('conversations() must not succeed with the vault locked');
      } on Object catch (e) {
        thrown = e;
      }

      expect(
        thrown,
        isA<rust.UiError_VaultLocked>(),
        reason: 'expected the typed UiError::VaultLocked variant; got '
            '${thrown.runtimeType}: $thrown',
      );

      // The boundary is still usable afterwards. A Rust panic that merely
      // *looked* like a clean error would usually have poisoned something by
      // now (the RwLock, the handler's thread pool, or the whole process).
      expect(rust.version(), fixture.crateVersion);
      expect(await rust.isVaultUnlocked(), isFalse);
    });

    test('stream: bridge_event_stream yields at least one event', () async {
      // `bridge_event_stream` subscribes to two `tokio::sync::broadcast`
      // channels, and broadcast receivers are not retroactive — an event sent
      // before the Rust task has subscribed is simply not delivered. So:
      // listen, give the task a moment to reach its `subscribe_events()`, and
      // only then do something that emits.
      //
      // The trigger is a workspace switch to OffGridTravel, which drives
      // `sync_saathi_lifecycle` -> `start_saathi`. Both of its outcomes emit a
      // `MeshStatusChanged`: the success path spawns a forwarder that sends
      // the opening `active: true` snapshot, and the failure path (no usable
      // socket, e.g. in a locked-down CI sandbox) sends `active: false`. That
      // makes this deterministic without a relay, a peer, or an unlocked
      // vault.
      final Completer<rust.BridgeEvent> first = Completer<rust.BridgeEvent>();
      final StreamSubscription<rust.BridgeEvent> sub =
          rust.bridgeEventStream().listen(
        (rust.BridgeEvent event) {
          if (!first.isCompleted) first.complete(event);
        },
        onError: (Object e) {
          if (!first.isCompleted) first.completeError(e);
        },
      );
      addTearDown(sub.cancel);

      await Future<void>.delayed(const Duration(milliseconds: 500));
      addTearDown(_resetToBase);

      await _emitMeshEvent(first);

      expect(
        first.isCompleted,
        isTrue,
        reason: 'no BridgeEvent arrived within ~15s of three workspace '
            'switches; the Rust stream task never reached the Dart sink',
      );

      final rust.BridgeEvent event = await first.future;
      expect(event, isA<rust.BridgeEvent_MeshStatusChanged>());

      // Read through to the nested struct: the variant tag alone would still
      // be right if the payload had been dropped or misframed.
      final rust.MeshStatusDto status =
          (event as rust.BridgeEvent_MeshStatusChanged).field0;
      expect(status.peerCount, isA<BigInt>());
      expect(status.peerCount, greaterThanOrEqualTo(BigInt.zero));
    }, timeout: const Timeout(Duration(seconds: 90)));

    // The three tests above prove the generated bindings work. This one proves
    // the thing the app actually depends on works: `RustComradeRepository`,
    // over the same live library, returning the app's own view models.
    test('RustComradeRepository drives the real core', () async {
      // RustLib is already initialised by setUpAll, so use the plain
      // constructor rather than `connect()` (which would init it twice).
      final RustComradeRepository repo = RustComradeRepository();
      addTearDown(repo.dispose);
      addTearDown(_resetToBase);

      expect(await repo.version(), fixture.crateVersion);

      // Locked: a null profile, not a throw — the interface's contract.
      expect(await repo.currentProfile(), isNull);

      // ...while a store-backed read still surfaces as the exception the
      // screens catch, with the core's own words in it.
      await expectLater(
        repo.conversations(),
        throwsA(isA<ComradeException>().having(
          (ComradeException e) => e.message,
          'message',
          'The vault is locked.',
        )),
      );

      final MeshStatus mesh = await repo.meshStatus();
      expect(mesh.peerCount, greaterThanOrEqualTo(0));

      // Watching a composer crosses the bridge and is infallible even locked —
      // a courtesy signal has no business raising an error into a text field,
      // and this is the one lane that proves the real ABI agrees.
      await repo.noteDraft('npub1nobody');
      await repo.abandonDraft('npub1nobody');

      // And the mapped, fanned-out event stream carries a real event.
      final Completer<BridgeEvent> mapped = Completer<BridgeEvent>();
      final StreamSubscription<BridgeEvent> sub =
          repo.events.listen((BridgeEvent e) {
        if (!mapped.isCompleted) mapped.complete(e);
      });
      addTearDown(sub.cancel);

      await Future<void>.delayed(const Duration(milliseconds: 500));
      await _emitMeshEvent(mapped);

      expect(
        mapped.isCompleted,
        isTrue,
        reason: 'RustComradeRepository.events never delivered an event',
      );
      expect(await mapped.future, isA<MeshStatusChanged>());
    }, timeout: const Timeout(Duration(seconds: 90)));
  }, skip: fixture.skipReason);
}

/// Toggle into the off-grid workspace until [seen] completes.
///
/// `toggle_workspace('OffGridTravel')` drives `sync_saathi_lifecycle` ->
/// `start_saathi`, and both of that function's outcomes put a
/// `MeshStatusChanged` on the critical event bus: the success path spawns a
/// forwarder that sends the opening `active: true` snapshot, the failure path
/// (no usable socket, e.g. in a locked-down CI sandbox) sends `active: false`.
/// So this needs no relay, no peer and no unlocked vault — but it does need
/// the workspace to start from Base, because Base -> OffGridTravel is the only
/// edge that starts the mesh.
Future<void> _emitMeshEvent(Completer<Object?> seen) async {
  for (int attempt = 0; attempt < 3 && !seen.isCompleted; attempt++) {
    await _resetToBase();
    await rust.toggleWorkspace(target: 'OffGridTravel');
    await Future.any<void>(<Future<void>>[
      seen.future.then((_) {}),
      Future<void>.delayed(const Duration(seconds: 5)),
    ]);
  }
}

Future<void> _resetToBase() async {
  if ((await rust.currentWorkspace()).key != 'Base') {
    await rust.toggleWorkspace(target: 'Base');
  }
}

/// Where the native library and the version to check it against live.
class _Fixture {
  const _Fixture._({
    this.libraryPath,
    this.crateVersion,
    this.skipReason,
  });

  /// Absolute path to the `comrade_jni` cdylib, or `null` when it is missing.
  final String? libraryPath;

  /// `[package] version` from `crates/comrade_jni/Cargo.toml` — the value
  /// `version()` compiles `env!("CARGO_PKG_VERSION")` down to.
  final String? crateVersion;

  /// Non-null when the tests cannot run, and printed both by the runner and
  /// by the stderr banner above.
  final String? skipReason;

  /// Overrides the discovered path — for running against a `--release` or
  /// cross-compiled build.
  static const String _envVar = 'COMRADE_JNI_LIB';

  static _Fixture resolve() {
    final Directory? root = _repoRoot();
    if (root == null) {
      return const _Fixture._(
        skipReason: 'could not locate the repository root (no ancestor of '
            'the working directory contains crates/comrade_jni/Cargo.toml)',
      );
    }

    final File manifest =
        File(_join(<String>[root.path, 'crates', 'comrade_jni', 'Cargo.toml']));
    final String? version = _packageVersion(manifest);
    if (version == null) {
      return _Fixture._(
        skipReason: 'could not read [package] version from ${manifest.path}',
      );
    }

    final String override = Platform.environment[_envVar] ?? '';
    final File cdylib = override.isNotEmpty
        ? File(override)
        : File(_join(<String>[root.path, 'target', 'debug', _libraryFileName]));

    if (!cdylib.existsSync()) {
      return _Fixture._(
        crateVersion: version,
        skipReason: '${cdylib.path} does not exist'
            '${override.isEmpty ? '' : ' (from \$$_envVar)'}.',
      );
    }

    return _Fixture._(
      libraryPath: cdylib.absolute.path,
      crateVersion: version,
    );
  }

  static String _join(List<String> parts) => parts.join(Platform.pathSeparator);

  static String get _libraryFileName {
    if (Platform.isWindows) return 'comrade_jni.dll';
    if (Platform.isMacOS) return 'libcomrade_jni.dylib';
    return 'libcomrade_jni.so';
  }

  /// Walk up from the working directory (`app/` under `flutter test`) looking
  /// for the crate, rather than hard-coding `../` — so the test still resolves
  /// when the runner is invoked from the repository root.
  static Directory? _repoRoot() {
    Directory dir = Directory.current.absolute;
    for (int depth = 0; depth < 8; depth++) {
      if (File(_join(<String>[dir.path, 'crates', 'comrade_jni', 'Cargo.toml']))
          .existsSync()) {
        return dir;
      }
      final Directory parent = dir.parent;
      if (parent.path == dir.path) break;
      dir = parent;
    }
    return null;
  }

  /// The `version = "..."` line inside `[package]`, ignoring the identically
  /// named keys under `[dependencies]`.
  static String? _packageVersion(File manifest) {
    if (!manifest.existsSync()) return null;
    bool inPackage = false;
    for (final String raw in manifest.readAsLinesSync()) {
      final String line = raw.trim();
      if (line.startsWith('[')) {
        inPackage = line == '[package]';
        continue;
      }
      if (!inPackage) continue;
      final RegExpMatch? match =
          RegExp(r'''^version\s*=\s*["']([^"']+)["']''').firstMatch(line);
      if (match != null) return match.group(1);
    }
    return null;
  }
}
