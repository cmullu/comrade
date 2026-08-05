/// The real Rust backend, on any target with `dart:ffi`. See `rust_backend.dart`.
library;

import 'comrade_repository.dart';
import 'rust_comrade_repository.dart';

/// Load `libcomrade_jni` and connect to the process-global `ComradeRuntime`.
///
/// Throws rather than falling back to the fake if the library cannot be loaded —
/// `--dart-define=COMRADE_BACKEND=rust` is a statement about which core you
/// want, and quietly answering with seeded demo data instead is the worst
/// possible failure mode. `main.dart` says the same thing at more length.
Future<ComradeRepository> connectRustBackend() =>
    RustComradeRepository.connect();
