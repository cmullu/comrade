/// The Dart half of the update check: what the native state map means, and
/// what goes over the wire when the card asks for something.
///
/// The *rule* (what counts as newer, when to look, when to notify) is pinned on
/// the Kotlin side by `UpdateCheckTest`, because that is where it lives and where
/// both frontends read it from. What can only break here is the translation:
/// a mis-read status leaves the card claiming the wrong thing, and a mis-named
/// argument makes a button silently do nothing.
library;

import 'package:comrade/src/platform/channels.dart';
import 'package:comrade/src/platform/update_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('UpdateStatus.fromMap', () {
    test('reads every status the native side emits', () {
      expect(
        UpdateStatus.fromMap(const <Object?, Object?>{'status': 'checking'}),
        isA<UpdateChecking>(),
      );
      expect(
        UpdateStatus.fromMap(
            const <Object?, Object?>{'status': 'upToDate', 'checkedAt': 7}),
        isA<UpdateUpToDate>()
            .having((UpdateUpToDate s) => s.checkedAt, 'checkedAt', 7),
      );
      expect(
        UpdateStatus.fromMap(const <Object?, Object?>{
          'status': 'failed',
          'message': 'offline',
          'checkedAt': 3,
        }),
        isA<UpdateFailed>()
            .having((UpdateFailed s) => s.message, 'message', 'offline'),
      );

      final UpdateStatus available =
          UpdateStatus.fromMap(const <Object?, Object?>{
        'status': 'available',
        'version': '0.0.9',
        'tag': 'v0.0.9',
        'notes': 'Fixed the thing',
        'pageUrl': 'https://github.test/releases/tag/v0.0.9',
        'apkBytes': 42,
        'checkedAt': 11,
      });
      expect(
        available,
        isA<UpdateAvailable>()
            .having((UpdateAvailable s) => s.version, 'version', '0.0.9')
            .having((UpdateAvailable s) => s.apkBytes, 'apkBytes', 42)
            .having((UpdateAvailable s) => s.pageUrl, 'pageUrl',
                'https://github.test/releases/tag/v0.0.9'),
      );
    });

    test('an unknown or empty status is "nothing looked yet", not a crash', () {
      // A native side newer than this Dart side must not be able to take the
      // settings screen down with a status name it invented.
      expect(
          UpdateStatus.fromMap(const <Object?, Object?>{'status': 'sideways'}),
          isA<UpdateUnknown>());
      expect(UpdateStatus.fromMap(const <Object?, Object?>{}),
          isA<UpdateUnknown>());
    });

    test('a partial "available" payload still renders rather than throwing',
        () {
      final UpdateStatus status =
          UpdateStatus.fromMap(const <Object?, Object?>{'status': 'available'});
      expect(status, isA<UpdateAvailable>());
      expect((status as UpdateAvailable).version, '');
      expect(status.apkBytes, 0);
    });
  });

  group('UpdateState.fromMap', () {
    test('reads both halves out of one snapshot', () {
      final UpdateState state = UpdateState.fromMap(const <Object?, Object?>{
        'check': <Object?, Object?>{
          'status': 'available',
          'version': '0.0.9',
          'apkBytes': 1000,
        },
        'download': <Object?, Object?>{
          'state': 'downloading',
          'bytesRead': 250,
          'totalBytes': 1000,
        },
      });
      expect(state.check, isA<UpdateAvailable>());
      expect(state.download, isA<DownloadRunning>());
      expect((state.download as DownloadRunning).percent, 25);
    });

    test('reads every download state the service reports', () {
      UpdateDownload parse(Map<Object?, Object?> download) =>
          UpdateState.fromMap(<Object?, Object?>{'download': download})
              .download;

      expect(parse(const <Object?, Object?>{'state': 'idle'}),
          isA<DownloadIdle>());
      expect(parse(const <Object?, Object?>{'state': 'verifying'}),
          isA<DownloadVerifying>());
      expect(
        parse(const <Object?, Object?>{'state': 'ready', 'version': '0.0.9'}),
        isA<DownloadReady>()
            .having((DownloadReady d) => d.version, 'version', '0.0.9'),
      );
      expect(
        parse(const <Object?, Object?>{
          'state': 'installing',
          'version': '0.0.9'
        }),
        isA<DownloadInstalling>(),
      );
      expect(
        parse(const <Object?, Object?>{'state': 'failed', 'message': 'nope'}),
        isA<DownloadFailed>()
            .having((DownloadFailed d) => d.message, 'message', 'nope'),
      );
    });

    test('an unknown download state, or none at all, reads as idle', () {
      expect(
        UpdateState.fromMap(const <Object?, Object?>{}).download,
        isA<DownloadIdle>(),
      );
      expect(
        UpdateState.fromMap(
          const <Object?, Object?>{
            'download': <Object?, Object?>{'state': 'sideways'}
          },
        ).download,
        isA<DownloadIdle>(),
      );
    });

    test('an unknown total means no percentage rather than a wrong one', () {
      // The bar goes indeterminate; inventing 100% (or 0 of 0) would be a lie
      // about a transfer whose size the server never reported.
      const DownloadRunning running =
          DownloadRunning(bytesRead: 500, totalBytes: 0);
      expect(running.percent, isNull);
      // And a total that somehow undershoots what has arrived still clamps.
      expect(const DownloadRunning(bytesRead: 20, totalBytes: 10).percent, 100);
    });
  });

  group('UpdateSettings', () {
    test('reads the preference map, and defaults to checking automatically',
        () {
      final UpdateSettings settings =
          UpdateSettings.fromMap(const <Object?, Object?>{
        'currentVersion': '0.0.8',
        'autoCheck': false,
        'lastCheckedAt': 99,
        'skippedVersion': '0.0.9',
      });
      expect(settings.currentVersion, '0.0.8');
      expect(settings.autoCheck, isFalse);
      expect(settings.lastCheckedAt, 99);
      expect(settings.skippedVersion, '0.0.9');

      // An empty map is what a not-yet-answered call looks like; the card must
      // not read that as "automatic checking is off".
      expect(
          UpdateSettings.fromMap(const <Object?, Object?>{}).autoCheck, isTrue);
      expect(UpdateSettings.fromMap(const <Object?, Object?>{}).skippedVersion,
          isNull);
    });

    test('canInstall defaults to true where the platform never answers', () {
      // A card that warned "Comrade cannot install updates" on a platform with
      // no such concept (or before the first reply) would be inventing a
      // problem. The native side is the only thing that can say otherwise.
      expect(UpdateSettings.fromMap(const <Object?, Object?>{}).canInstall,
          isTrue);
      expect(
        UpdateSettings.fromMap(const <Object?, Object?>{'canInstall': false})
            .canInstall,
        isFalse,
      );
    });
  });

  group('UpdateChannel wire format', () {
    late List<MethodCall> calls;
    late UpdateChannel channel;

    setUp(() {
      TestWidgetsFlutterBinding.ensureInitialized();
      calls = <MethodCall>[];
      const MethodChannel methods = MethodChannel(Channels.updates);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methods, (MethodCall call) async {
        calls.add(call);
        if (call.method == 'settings') {
          return <Object?, Object?>{
            'currentVersion': '0.0.8',
            'autoCheck': true,
            'lastCheckedAt': 0,
            'skippedVersion': null,
          };
        }
        return null;
      });
      channel = UpdateChannel(methods: methods);
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
              const MethodChannel(Channels.updates), null);
    });

    test('settings() parses the native reply', () async {
      final UpdateSettings settings = await channel.settings();
      expect(settings.currentVersion, '0.0.8');
      expect(calls.single.method, 'settings');
    });

    test('check() carries the force flag the "Check now" button needs',
        () async {
      await channel.check();
      await channel.check(force: true);
      expect(calls.map((MethodCall c) => c.method), <String>['check', 'check']);
      expect(
          (calls.first.arguments as Map<Object?, Object?>)['force'], isFalse);
      expect((calls.last.arguments as Map<Object?, Object?>)['force'], isTrue);
    });

    test('the download and install calls take no arguments at all', () async {
      // Deliberate: nothing here can name a URL or a file. The native side
      // fetches the asset its own check reported, and installs the file it
      // itself wrote.
      await channel.download();
      await channel.cancelDownload();
      await channel.install();
      await channel.refreshDownloadState();
      await channel.openInstallPermissionSettings();
      expect(
        calls.map((MethodCall c) => c.method),
        <String>[
          'download',
          'cancelDownload',
          'install',
          'refreshDownloadState',
          'openInstallPermissionSettings',
        ],
      );
      expect(calls.every((MethodCall c) => c.arguments == null), isTrue);
    });

    test('skip/unskip/setAutoCheck/openRelease name their arguments', () async {
      await channel.skip('0.0.9');
      await channel.unskip();
      await channel.setAutoCheck(false);
      await channel.openRelease();
      expect(
        calls.map((MethodCall c) => c.method),
        <String>['skip', 'unskip', 'setAutoCheck', 'openRelease'],
      );
      expect((calls[0].arguments as Map<Object?, Object?>)['version'], '0.0.9');
      expect((calls[2].arguments as Map<Object?, Object?>)['enabled'], isFalse);
      // openRelease deliberately takes no URL: the native side opens the page it
      // itself reported, so nothing here can redirect an upgrade.
      expect(calls[3].arguments, isNull);
    });
  });
}
