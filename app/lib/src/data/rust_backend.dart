/// Which file even knows the FFI bridge exists, behind a conditional import.
///
/// `flutter_rust_bridge`'s generated entry point resolves
/// `frb_generated.io.dart` on the VM and `frb_generated.web.dart` in a browser.
/// Codegen currently runs with `--no-web` (`.github/workflows/flutter.yml`), so
/// the web half is not generated — which means any web compilation unit that
/// reaches `src/rust/` fails to resolve a file that does not exist. That is what
/// made `flutter build web` impossible, and it is a build error rather than a
/// runtime one, so it cannot be caught with a platform check inside `main`.
///
/// Hence the seam. `main.dart` asks for a Rust backend and never mentions the
/// bridge; the VM gets the real one, and the web build gets a version that
/// refuses out loud.
///
/// This is the seam Phase W2 of `docs/FLUTTER_WEB_MIGRATION.md` replaces: when
/// the wasm-reachable crate lands, `rust_backend_web.dart` starts returning a
/// repository that proxies to a paired host over the link protocol
/// (`comrade_core::link`) instead of throwing.
library;

export 'rust_backend_io.dart'
    if (dart.library.js_interop) 'rust_backend_web.dart';
