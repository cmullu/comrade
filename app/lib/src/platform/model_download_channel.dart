/// Typed wrapper over `ModelDownloadService`'s channels.
///
/// The native side is a foreground `dataSync` service whose ongoing
/// notification *is* the progress UI, so a multi-hundred-megabyte transfer
/// keeps running with the app backgrounded and the engine detached. State lives
/// in a process-wide holder rather than in the service, so a download survives
/// the screen that started it.
///
/// See `../../../android/app/src/main/kotlin/mullu/comrade/PLATFORM_CHANNELS.md`
/// §9. Everything security-relevant — the sha256 pin, the zip-slip guard, the
/// atomic staging swap — is in `ModelInstaller`, which this API cannot reach:
/// [ModelDownloadChannel.startDownload] takes a catalog **id**, never a URL, so
/// there is no way to ask it to fetch an arbitrary artifact.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// Catalog ids. Kept as constants rather than an enum because the catalog is
/// native-authoritative and may grow without this file changing.
abstract final class ModelIds {
  static const speech = 'speech';
  static const companion = 'companion';
}

/// One catalog entry.
final class ModelInfo {
  const ModelInfo({
    required this.id,
    required this.displayName,
    required this.downloadBytes,
    required this.configured,
    required this.returnTab,
  });

  final String id;
  final String displayName;
  final int downloadBytes;

  /// Whether this model has a **pinned** source (URL + sha256 + size).
  ///
  /// `false` is a deliberate, meaningful state, not a data gap: it means the
  /// artifact has not been chosen and checksummed yet, and every entry point
  /// must treat the model as *unavailable* rather than fetching something
  /// unverified. The companion model is currently unpinned on purpose. Show
  /// "not available"; never show a download offer.
  final bool configured;

  /// Which main-nav tab the "ready" notification returns to, if any.
  final String? returnTab;

  factory ModelInfo.fromMap(Map<Object?, Object?> map) => ModelInfo(
        id: map['id'] as String? ?? '',
        displayName: map['displayName'] as String? ?? '',
        downloadBytes: (map['downloadBytes'] as num?)?.toInt() ?? 0,
        configured: map['configured'] as bool? ?? false,
        returnTab: map['returnTab'] as String?,
      );
}

/// Where one model's download has got to.
sealed class ModelDownloadState {
  const ModelDownloadState();

  factory ModelDownloadState.fromMap(Map<Object?, Object?> map) =>
      switch (map['status'] as String?) {
        'downloading' => ModelDownloading(
            bytesRead: (map['bytesRead'] as num?)?.toInt() ?? 0,
            totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
          ),
        'installing' => const ModelInstalling(),
        'ready' => const ModelReady(),
        'failed' => ModelFailed(map['message'] as String? ?? 'unknown error'),
        _ => const ModelIdle(),
      };
}

/// Nothing in flight — show the download offer (if [ModelInfo.configured]).
final class ModelIdle extends ModelDownloadState {
  const ModelIdle();
}

final class ModelDownloading extends ModelDownloadState {
  const ModelDownloading({required this.bytesRead, required this.totalBytes});

  final int bytesRead;

  /// Falls back to the catalog's figure until the server reports one.
  final int totalBytes;

  /// 0.0–1.0, or null when the size is not known yet (show indeterminate).
  double? get fraction =>
      totalBytes > 0 ? (bytesRead / totalBytes).clamp(0.0, 1.0) : null;
}

/// Downloaded; checksum + extract + atomic move. Quick, but not instant.
final class ModelInstalling extends ModelDownloadState {
  const ModelInstalling();
}

/// Installed where the model's loader looks for it.
final class ModelReady extends ModelDownloadState {
  const ModelReady();
}

final class ModelFailed extends ModelDownloadState {
  const ModelFailed(this.message);

  final String message;
}

class ModelDownloadChannel {
  ModelDownloadChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.models),
        _state = state ?? const EventChannel(Channels.modelsState);

  final MethodChannel _methods;
  final EventChannel _state;

  Stream<Map<String, ModelDownloadState>>? _stream;

  /// Per-model state, keyed by catalog id.
  ///
  /// Keeps reporting real progress with the engine detached and the app
  /// backgrounded, and a reattaching UI picks the transfer up mid-flight —
  /// because the state is held process-wide, not by the service or the screen.
  Stream<Map<String, ModelDownloadState>> get state => _stream ??= _state
      .receiveBroadcastStream()
      .map((event) => (event as Map<Object?, Object?>).map(
            (k, v) => MapEntry(
              k as String,
              ModelDownloadState.fromMap(v as Map<Object?, Object?>),
            ),
          ))
      .asBroadcastStream();

  Future<List<ModelInfo>> catalog() async {
    final raw = await _methods.invokeListMethod<Object?>('catalog');
    return (raw ?? const [])
        .map((e) => ModelInfo.fromMap(e as Map<Object?, Object?>))
        .toList(growable: false);
  }

  /// Start (or re-observe) the download for [modelId].
  ///
  /// Idempotent: a second tap from another screen just re-observes. A request
  /// for a *different* model while one is in flight is reported as busy through
  /// [state] — it never relabels or resets the running transfer, and never
  /// tears it down.
  Future<void> startDownload(String modelId) =>
      _methods.invokeMethod<void>('startDownload', {'modelId': modelId});

  /// Abort an in-flight download; partial files are deleted.
  Future<void> cancelDownload(String modelId) =>
      _methods.invokeMethod<void>('cancelDownload', {'modelId': modelId});

  Future<bool> isInstalled(String modelId) async =>
      await _methods.invokeMethod<bool>('isInstalled', {'modelId': modelId}) ??
      false;

  /// Re-arm the offer when a stale in-memory `ready` no longer holds — the
  /// installed files vanished, e.g. the user cleared app storage. Without this
  /// a prompt keeps firing its ready-callback into a load that keeps failing.
  Future<void> reofferIfGone(String modelId) =>
      _methods.invokeMethod<void>('reofferIfGone', {'modelId': modelId});
}
