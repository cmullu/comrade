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
/// way to point someone's upgrade at another APK.
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
  });

  const UpdateSettings.unknown()
      : currentVersion = '?',
        autoCheck = true,
        lastCheckedAt = 0,
        skippedVersion = null;

  factory UpdateSettings.fromMap(Map<Object?, Object?> map) => UpdateSettings(
        currentVersion: (map['currentVersion'] as String?) ?? '?',
        autoCheck: (map['autoCheck'] as bool?) ?? true,
        lastCheckedAt: (map['lastCheckedAt'] as int?) ?? 0,
        skippedVersion: map['skippedVersion'] as String?,
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
}

class UpdateChannel {
  UpdateChannel({MethodChannel? methods, EventChannel? state})
      : _methods = methods ?? const MethodChannel(Channels.updates),
        _state = state ?? const EventChannel(Channels.updatesState);

  final MethodChannel _methods;
  final EventChannel _state;

  /// Live check status. The native source is a `StateFlow`, so the first event
  /// is the current answer — including a finding made while no engine was
  /// attached.
  Stream<UpdateStatus> get status => _state.receiveBroadcastStream().map(
        (Object? event) => UpdateStatus.fromMap(
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

  /// Open the release page in a browser, where the APK is downloaded and
  /// installed. Comrade never installs an APK itself — see `UpdateChecker`.
  Future<void> openRelease() => _methods.invokeMethod<void>('openRelease');
}
