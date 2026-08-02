/// The in-app update check: whether a newer Comrade has been published, and
/// how to get it.
///
/// The rule and the network call live natively
/// (`mullu.comrade.update.UpdateCheck` / `UpdateChecker`), shared with the
/// Compose app, so the two frontends cannot disagree about what counts as an
/// upgrade — and so the check keeps working with no engine attached.
///
/// Nothing here takes a URL: the endpoint is compiled in on the native side,
/// because an update path is code execution and a settable source would be a
/// way to point someone's upgrade at another APK. The APK is fetched by a
/// foreground service — so it survives leaving the app, shows progress in the
/// notification shade, and finishes with a tappable "ready to install" — and
/// handed to the system installer, which always asks the user to confirm.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'channels.dart';

/// Where the check has got to.
sealed class UpdateStatus {
  const UpdateStatus();

  /// Parse one native state map. An unrecognised `status` reads as
  /// [UpdateUnknown] rather than throwing: a newer native side must not be able
  /// to crash an older Dart side.
  factory UpdateStatus.fromMap(Map<Object?, Object?> map) {
    final int checkedAt = (map['checkedAt'] as int?) ?? 0;
    switch (map['status'] as String?) {
      case 'checking':
        return const UpdateChecking();
      case 'upToDate':
        return UpdateUpToDate(checkedAt: checkedAt);
      case 'failed':
        return UpdateFailed(
          message: (map['message'] as String?) ?? 'Check failed',
          checkedAt: checkedAt,
        );
      case 'available':
        return UpdateAvailable(
          version: (map['version'] as String?) ?? '',
          tag: (map['tag'] as String?) ?? '',
          notes: (map['notes'] as String?) ?? '',
          pageUrl: (map['pageUrl'] as String?) ?? '',
          apkBytes: (map['apkBytes'] as int?) ?? 0,
          checkedAt: checkedAt,
        );
      default:
        return const UpdateUnknown();
    }
  }
}

/// Nothing looked yet in this process.
class UpdateUnknown extends UpdateStatus {
  const UpdateUnknown();
}

class UpdateChecking extends UpdateStatus {
  const UpdateChecking();
}

class UpdateUpToDate extends UpdateStatus {
  const UpdateUpToDate({required this.checkedAt});

  final int checkedAt;
}

/// The check itself failed — offline, rate-limited, unreadable body.
class UpdateFailed extends UpdateStatus {
  const UpdateFailed({required this.message, required this.checkedAt});

  final String message;
  final int checkedAt;
}

class UpdateAvailable extends UpdateStatus {
  const UpdateAvailable({
    required this.version,
    required this.tag,
    required this.notes,
    required this.pageUrl,
    required this.apkBytes,
    required this.checkedAt,
  });

  /// The published version, e.g. `0.0.9` — already known to be newer than this
  /// build's, because the native side only reports it when it is.
  final String version;
  final String tag;
  final String notes;
  final String pageUrl;

  /// Size of the release's APK, or 0 when the release has no single
  /// identifiable one (see `UpdateCheck.parseRelease`).
  final int apkBytes;
  final int checkedAt;
}

/// The parts of the update preference the card renders around the status.
class UpdateSettings {
  const UpdateSettings({
    required this.currentVersion,
    required this.autoCheck,
    required this.lastCheckedAt,
    this.skippedVersion,
    this.canInstall = true,
  });

  const UpdateSettings.unknown()
      : currentVersion = '?',
        autoCheck = true,
        lastCheckedAt = 0,
        skippedVersion = null,
        canInstall = true;

  factory UpdateSettings.fromMap(Map<Object?, Object?> map) => UpdateSettings(
        currentVersion: (map['currentVersion'] as String?) ?? '?',
        autoCheck: (map['autoCheck'] as bool?) ?? true,
        lastCheckedAt: (map['lastCheckedAt'] as int?) ?? 0,
        skippedVersion: map['skippedVersion'] as String?,
        canInstall: (map['canInstall'] as bool?) ?? true,
      );

  /// What this build reports as its version — what a release is compared to.
  final String currentVersion;

  /// Whether the daily check runs on its own. On by default: a sideloaded app
  /// whose users never hear that a security fix shipped is the worse failure.
  /// The card states the disclosure it costs (an IP address, to GitHub, daily).
  final bool autoCheck;

  final int lastCheckedAt;

  /// A version the user asked not to be told about again. Anything *newer* is
  /// still announced.
  final String? skippedVersion;

  /// Whether the OS will let Comrade install an APK — the per-source "install
  /// unknown apps" grant, which lives outside this app and can be revoked at any
  /// time, so it is re-read rather than remembered. Defaults to true so a
  /// platform that has no such notion (or an unanswered call) does not render a
  /// permission warning that means nothing there.
  final bool canInstall;
}

/// Where the update APK's download-and-install has got to — the Dart mirror of
/// `mullu.comrade.update.UpdateDownloadState`.
sealed class UpdateDownload {
  const UpdateDownload();

  /// Parse one native `download` map. An unrecognised state reads as
  /// [DownloadIdle] rather than throwing, for the same reason
  /// [UpdateStatus.fromMap] is forgiving.
  factory UpdateDownload.fromMap(Map<Object?, Object?> map) {
    switch (map['state'] as String?) {
      case 'downloading':
        return DownloadRunning(
          bytesRead: (map['bytesRead'] as int?) ?? 0,
          totalBytes: (map['totalBytes'] as int?) ?? 0,
        );
      case 'verifying':
        return const DownloadVerifying();
      case 'ready':
        return DownloadReady(version: (map['version'] as String?) ?? '');
      case 'installing':
        return DownloadInstalling(version: (map['version'] as String?) ?? '');
      case 'failed':
        return DownloadFailed(
          message: (map['message'] as String?) ?? 'Download failed',
        );
      default:
        return const DownloadIdle();
    }
  }
}

/// Nothing in flight — the card offers the download.
class DownloadIdle extends UpdateDownload {
  const DownloadIdle();
}

class DownloadRunning extends UpdateDownload {
  const DownloadRunning({required this.bytesRead, required this.totalBytes});

  final int bytesRead;

  /// 0 until the server (or the release asset) says — the card then shows an
  /// indeterminate bar rather than inventing a denominator.
  final int totalBytes;

  /// Whole percent, or null when the total is not known yet.
  int? get percent =>
      totalBytes > 0 ? ((bytesRead * 100) ~/ totalBytes).clamp(0, 100) : null;
}

/// Downloaded; the size, package, version and signer are being checked.
class DownloadVerifying extends UpdateDownload {
  const DownloadVerifying();
}

/// Verified and waiting in the cache — one tap from the installer.
class DownloadReady extends UpdateDownload {
  const DownloadReady({required this.version});

  final String version;
}

/// Handed to the system installer; the OS is asking the user to confirm.
class DownloadInstalling extends UpdateDownload {
  const DownloadInstalling({required this.version});

  final String version;
}

class DownloadFailed extends UpdateDownload {
  const DownloadFailed({required this.message});

  final String message;
}

/// One snapshot of both halves: whether an update exists, and where its
/// download has got to.
class UpdateState {
  const UpdateState({required this.check, required this.download});

  const UpdateState.unknown()
      : check = const UpdateUnknown(),
        download = const DownloadIdle();

  factory UpdateState.fromMap(Map<Object?, Object?> map) => UpdateState(
        check: UpdateStatus.fromMap(
          (map['check'] as Map<Object?, Object?>?) ??
              const <Object?, Object?>{},
        ),
        download: UpdateDownload.fromMap(
          (map['download'] as Map<Object?, Object?>?) ??
              const <Object?, Object?>{},
        ),
      );

  final UpdateStatus check;
  final UpdateDownload download;
}

class UpdateChannel {
  UpdateChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.updates),
        _state = state ?? const EventChannel(Channels.updatesState);

  final MethodChannel _methods;
  final EventChannel _state;

  /// Live check + download state. The native source is a combine of two
  /// `StateFlow`s, so the first event is the current answer — including a
  /// finding, or a finished download, from while no engine was attached.
  Stream<UpdateState> get updates => _state.receiveBroadcastStream().map(
        (Object? event) => UpdateState.fromMap(
            (event as Map<Object?, Object?>?) ?? const <Object?, Object?>{}),
      );

  Future<UpdateSettings> settings() async {
    final Map<Object?, Object?>? map =
        await _methods.invokeMethod<Map<Object?, Object?>>('settings');
    return map == null
        ? const UpdateSettings.unknown()
        : UpdateSettings.fromMap(map);
  }

  /// Ask for a check. Fire-and-forget: the answer arrives on [status], so this
  /// never waits on a network round-trip.
  ///
  /// Without [force] the native cadence gate applies (once a day, and not at
  /// all with automatic checking off), which is what makes this safe to call on
  /// every foreground.
  Future<void> check({bool force = false}) =>
      _methods.invokeMethod<void>('check', <String, Object?>{'force': force});

  Future<void> setAutoCheck(bool enabled) => _methods.invokeMethod<void>(
      'setAutoCheck', <String, Object?>{'enabled': enabled});

  /// Stop mentioning [version]. Not "never check again" — a later release is
  /// announced normally.
  Future<void> skip(String version) => _methods
      .invokeMethod<void>('skip', <String, Object?>{'version': version});

  Future<void> unskip() => _methods.invokeMethod<void>('unskip');

  // ── Downloading and installing ─────────────────────────────────────────────

  /// Start fetching the update APK. Returns as soon as the foreground service
  /// is up: the transfer then runs without this engine, and its progress arrives
  /// on [updates].
  Future<void> download() => _methods.invokeMethod<void>('download');

  /// Abort an in-flight download; the partial file is deleted.
  Future<void> cancelDownload() =>
      _methods.invokeMethod<void>('cancelDownload');

  /// Hand the downloaded APK to the system installer. It re-verifies first, and
  /// the OS still shows its own confirmation dialog.
  Future<void> install() => _methods.invokeMethod<void>('install');

  /// Drop a "ready to install" whose file is no longer there (the OS reaped the
  /// cache, or the install went through). Cheap; call it on resume.
  Future<void> refreshDownloadState() =>
      _methods.invokeMethod<void>('refreshDownloadState');

  /// The system screen that lets Comrade install apps. There is no in-app way to
  /// ask for that permission, so this is the whole of the flow.
  Future<void> openInstallPermissionSettings() =>
      _methods.invokeMethod<void>('openInstallPermissionSettings');

  /// Open the release page in a browser — the fallback for a release with no
  /// single APK asset, and for anyone who would rather install from there than
  /// let Comrade install apps.
  Future<void> openRelease() => _methods.invokeMethod<void>('openRelease');
}
