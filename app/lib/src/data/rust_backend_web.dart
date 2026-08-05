/// The web stand-in for the Rust backend. See `rust_backend.dart`.
library;

import 'comrade_repository.dart';

/// Always throws, with the reason.
///
/// A browser tab cannot *be* a Comrade peer, and this is not a gap waiting on
/// effort — it is measured. `comrade_core` does not compile for
/// `wasm32-unknown-unknown` (`mio`, reached through tokio's net stack, fails),
/// and `comrade_storage` opens the vault with `redb::Database::create`, which
/// wants a real file and an exclusive lock on it. See
/// `docs/FLUTTER_WEB_MIGRATION.md` §2.
///
/// What a browser can be is a screen for a device that holds the key, which is
/// what `comrade_core::link` is for and what Phase W2 wires up here. Until then
/// this throws instead of returning something that looks like a working backend:
/// a web build that silently served demo data would be indistinguishable from a
/// signed-in session, in a product whose whole claim is that you can tell.
Future<ComradeRepository> connectRustBackend() => throw const ComradeException(
      'This browser has no Comrade core of its own. Sign in by scanning the '
      'link code with a device that holds your vault.',
    );
