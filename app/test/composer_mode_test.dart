import 'package:comrade/src/util/composer_mode.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pins the composer's capture-mode rules: what a device offers, what the round
/// button falls back to, and where the swap control goes next.
///
/// Mirrors `ComposerModeTest.kt` so the two frontends cycle in the same order.
void main() {
  group('availableCaptureModes', () {
    test('follows capability, in cycle order', () {
      expect(
        availableCaptureModes(
          canRecordAudio: true,
          canTakePhoto: true,
          canRecordVideo: true,
        ),
        <ComposerCaptureMode>[
          ComposerCaptureMode.voice,
          ComposerCaptureMode.photo,
          ComposerCaptureMode.video,
        ],
      );
      expect(
        availableCaptureModes(
          canRecordAudio: true,
          canTakePhoto: false,
          canRecordVideo: false,
        ),
        <ComposerCaptureMode>[ComposerCaptureMode.voice],
      );
      expect(
        availableCaptureModes(
          canRecordAudio: false,
          canTakePhoto: true,
          canRecordVideo: true,
        ),
        <ComposerCaptureMode>[
          ComposerCaptureMode.photo,
          ComposerCaptureMode.video,
        ],
      );
    });

    test('a device with no recorder and no camera offers nothing', () {
      final List<ComposerCaptureMode> none = availableCaptureModes(
        canRecordAudio: false,
        canTakePhoto: false,
        canRecordVideo: false,
      );
      expect(none, isEmpty);
      // No mode at all → the caller shows a plain Send rather than a control
      // that fails on tap.
      expect(effectiveCaptureMode(ComposerCaptureMode.voice, none), isNull);
      expect(nextCaptureMode(ComposerCaptureMode.voice, none), isNull);
    });
  });

  group('effectiveCaptureMode', () {
    test('a stale mode falls back to something the device has', () {
      const List<ComposerCaptureMode> cameraOnly = <ComposerCaptureMode>[
        ComposerCaptureMode.photo,
        ComposerCaptureMode.video,
      ];
      expect(
        effectiveCaptureMode(ComposerCaptureMode.voice, cameraOnly),
        ComposerCaptureMode.photo,
      );
      expect(
        effectiveCaptureMode(ComposerCaptureMode.video, cameraOnly),
        ComposerCaptureMode.video,
      );
      expect(effectiveCaptureMode(null, cameraOnly), ComposerCaptureMode.photo);
    });
  });

  group('nextCaptureMode', () {
    const List<ComposerCaptureMode> all = <ComposerCaptureMode>[
      ComposerCaptureMode.voice,
      ComposerCaptureMode.photo,
      ComposerCaptureMode.video,
    ];

    test('names the mode it moves to, and wraps around', () {
      expect(
        nextCaptureMode(ComposerCaptureMode.voice, all),
        ComposerCaptureMode.photo,
      );
      expect(
        nextCaptureMode(ComposerCaptureMode.photo, all),
        ComposerCaptureMode.video,
      );
      // Wraps, so the cycle never dead-ends on the last mode.
      expect(
        nextCaptureMode(ComposerCaptureMode.video, all),
        ComposerCaptureMode.voice,
      );
    });

    test('there is nothing to swap to with one mode', () {
      // The caller hides the control entirely rather than showing a button that
      // swaps a mode for itself.
      expect(
        nextCaptureMode(ComposerCaptureMode.voice,
            <ComposerCaptureMode>[ComposerCaptureMode.voice]),
        isNull,
      );
    });

    test('swapping from a stale mode still advances', () {
      expect(
        nextCaptureMode(ComposerCaptureMode.voice, <ComposerCaptureMode>[
          ComposerCaptureMode.photo,
          ComposerCaptureMode.video,
        ]),
        ComposerCaptureMode.video,
      );
    });

    test('cycling every mode returns to the start', () {
      ComposerCaptureMode? mode = ComposerCaptureMode.voice;
      for (int i = 0; i < all.length; i++) {
        mode = nextCaptureMode(mode, all);
      }
      expect(mode, ComposerCaptureMode.voice);
    });
  });
}
