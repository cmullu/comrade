/// The Telegram-shaped composer: emoji left, paper clip right, one round button
/// that is Send or the swappable mic/camera.
///
/// What these tests pin is the part that is easy to regress by accident: which
/// control is on screen for a given draft and a given set of device
/// capabilities. A composer that offers a camera on a machine without one, or
/// loses the Send button when there is text to send, is broken in a way no
/// analyzer catches.
library;

import 'dart:typed_data';

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/widgets/composer.dart';
import 'package:comrade/src/widgets/emoji_picker.dart';
import 'package:comrade/src/widgets/media_attachment.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// A picker that always yields the same small attachment.
class _FakePicker implements AttachmentPicker {
  _FakePicker({this.result});

  final PickedAttachment? result;
  int calls = 0;

  @override
  Future<PickedAttachment?> pick() async {
    calls++;
    return result;
  }
}

class _FakeCapture implements MediaCaptureDelegate {
  _FakeCapture({this.photo = true, this.video = true});

  final bool photo;
  final bool video;
  int photos = 0;
  int videos = 0;

  @override
  bool get canCapturePhoto => photo;

  @override
  bool get canCaptureVideo => video;

  @override
  Future<PickedAttachment?> capturePhoto() async {
    photos++;
    return _attachment('photo.jpg', 'image/jpeg');
  }

  @override
  Future<PickedAttachment?> captureVideo() async {
    videos++;
    return _attachment('video.mp4', 'video/mp4');
  }
}

class _FakeRecorder implements VoiceNoteRecorder {
  _FakeRecorder({this.available = true, this.note});

  @override
  final bool available;

  /// What `stop` returns; null models "too short to be a note".
  final PickedAttachment? note;

  int starts = 0;
  int stops = 0;
  int cancels = 0;

  @override
  Future<bool> start() async {
    starts++;
    return true;
  }

  @override
  Future<PickedAttachment?> stop() async {
    stops++;
    return note;
  }

  @override
  Future<void> cancel() async {
    cancels++;
  }
}

PickedAttachment _attachment(String name, String mime) => PickedAttachment(
      name: name,
      mimeType: mime,
      bytes: Uint8List.fromList(<int>[1, 2, 3]),
    );

void main() {
  late TextEditingController controller;
  late FocusNode focus;
  late List<PickedAttachment> sentAttachments;
  late int sends;

  setUp(() {
    controller = TextEditingController();
    focus = FocusNode();
    sentAttachments = <PickedAttachment>[];
    sends = 0;
  });

  tearDown(() {
    controller.dispose();
    focus.dispose();
  });

  Future<void> pumpComposer(
    WidgetTester tester, {
    AttachmentPicker? picker,
    MediaCaptureDelegate? capture,
    VoiceNoteRecorder? recorder,
  }) async {
    final FakeComradeRepository repo = await unlockedFake(seed: false);
    await tester.pumpWidget(
      harness(
        MessageComposer(
          controller: controller,
          focusNode: focus,
          onSend: () async => sends++,
          onAttachment: (PickedAttachment a) async => sentAttachments.add(a),
        ),
        repo: repo,
        extra: <Override>[
          if (picker != null)
            attachmentPickerProvider.overrideWithValue(picker),
          if (capture != null) mediaCaptureProvider.overrideWithValue(capture),
          if (recorder != null)
            voiceNoteRecorderProvider.overrideWithValue(recorder),
        ],
      ),
    );
    await tester.pump();
  }

  group('layout', () {
    testWidgets('emoji sits left of the field and the paper clip right of it',
        (WidgetTester tester) async {
      await pumpComposer(tester);

      final Offset emoji = tester.getCenter(find.byKey(const Key('dm-emoji')));
      final Offset field = tester.getCenter(find.byKey(const Key('dm-input')));
      final Offset clip = tester.getCenter(find.byKey(const Key('dm-attach')));
      final Offset action = tester.getCenter(find.byKey(const Key('dm-send')));

      expect(emoji.dx, lessThan(field.dx), reason: 'emoji is the left icon');
      expect(clip.dx, greaterThan(field.dx),
          reason: 'paper clip is on the right');
      expect(action.dx, greaterThan(clip.dx),
          reason: 'the round button is outside the field, furthest right');
    });

    testWidgets('with no recorder and no camera the round button is Send',
        (WidgetTester tester) async {
      await pumpComposer(tester);
      expect(find.byKey(const Key('dm-send')), findsOneWidget);
      expect(find.byKey(const Key('dm-record')), findsNothing);
      expect(find.byKey(const Key('dm-camera')), findsNothing);
      // Nothing to send yet, so it is disabled rather than absent — the layout
      // must not jump when the first character is typed.
      expect(
        tester.widget<IconButton>(find.byKey(const Key('dm-send'))).onPressed,
        isNull,
      );
    });

    testWidgets('typing swaps the capture button for Send, and back',
        (WidgetTester tester) async {
      await pumpComposer(tester, recorder: _FakeRecorder());
      expect(find.byKey(const Key('dm-record')), findsOneWidget);
      expect(
        find.byKey(const Key('dm-swap-capture')),
        findsNothing,
        reason: 'one way to capture is not a choice',
      );

      await tester.enterText(find.byKey(const Key('dm-input')), 'hello');
      await tester.pump();
      expect(find.byKey(const Key('dm-record')), findsNothing);
      expect(find.byKey(const Key('dm-send')), findsOneWidget);

      await tester.tap(find.byKey(const Key('dm-send')));
      await tester.pump();
      expect(sends, 1);

      // Whitespace is not a message.
      await tester.enterText(find.byKey(const Key('dm-input')), '   ');
      await tester.pump();
      expect(find.byKey(const Key('dm-record')), findsOneWidget);
    });

    testWidgets('a device with a camera but no mic shows the camera only',
        (WidgetTester tester) async {
      await pumpComposer(
        tester,
        capture: _FakeCapture(),
        recorder: _FakeRecorder(available: false),
      );
      expect(find.byKey(const Key('dm-camera')), findsOneWidget);
      expect(find.byKey(const Key('dm-record')), findsNothing);
    });
  });

  group('attach and capture', () {
    testWidgets('the paper clip sends what the picker returns',
        (WidgetTester tester) async {
      final _FakePicker picker =
          _FakePicker(result: _attachment('notes.pdf', 'application/pdf'));
      await pumpComposer(tester, picker: picker);

      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      expect(picker.calls, 1);
      expect(sentAttachments.single.name, 'notes.pdf');
    });

    testWidgets('a cancelled pick sends nothing and reports nothing',
        (WidgetTester tester) async {
      final _FakePicker picker = _FakePicker();
      await pumpComposer(tester, picker: picker);

      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();

      expect(picker.calls, 1);
      expect(sentAttachments, isEmpty);
      // Cancelling is not an error: a real picker was there, the user closed it.
      expect(find.byType(SnackBar), findsNothing);
    });

    testWidgets('with no picker at all it says so instead of failing silently',
        (WidgetTester tester) async {
      await pumpComposer(tester);
      await tester.tap(find.byKey(const Key('dm-attach')));
      await tester.pumpAndSettle();
      expect(find.textContaining('No file picker'), findsOneWidget);
    });

    testWidgets('a camera that can only record video still offers itself',
        (WidgetTester tester) async {
      // The capability flags are separate for a reason: a device can answer the
      // video-capture intent and not the photo one. The button must then be the
      // one that works, not the one it would default to.
      final _FakeCapture capture = _FakeCapture(photo: false, video: true);
      await pumpComposer(
        tester,
        capture: capture,
        recorder: _FakeRecorder(available: false),
      );

      expect(find.byKey(const Key('dm-camera')), findsNothing);
      await tester.tap(find.byKey(const Key('dm-video')));
      await tester.pumpAndSettle();

      expect(capture.photos, 0);
      expect(capture.videos, 1);
      expect(sentAttachments.single.mimeType, 'video/mp4');
    });

    testWidgets('tapping the camera captures a photo',
        (WidgetTester tester) async {
      final _FakeCapture capture = _FakeCapture();
      await pumpComposer(
        tester,
        capture: capture,
        recorder: _FakeRecorder(available: false),
      );

      await tester.tap(find.byKey(const Key('dm-camera')));
      await tester.pumpAndSettle();

      expect(capture.photos, 1);
      expect(sentAttachments.single.mimeType, 'image/jpeg');
    });

    testWidgets('the swap control cycles voice → photo → video → voice',
        (WidgetTester tester) async {
      // Three modes, not two: a device that can photograph can usually also
      // record video, and a mic/camera toggle would leave video unreachable.
      await pumpComposer(
        tester,
        capture: _FakeCapture(),
        recorder: _FakeRecorder(),
      );
      final Finder swap = find.byKey(const Key('dm-swap-capture'));

      expect(find.byKey(const Key('dm-record')), findsOneWidget);
      await tester.tap(swap);
      await tester.pump();
      expect(find.byKey(const Key('dm-camera')), findsOneWidget);

      await tester.tap(swap);
      await tester.pump();
      expect(find.byKey(const Key('dm-video')), findsOneWidget);

      await tester.tap(swap);
      await tester.pump();
      expect(find.byKey(const Key('dm-record')), findsOneWidget);
    });

    testWidgets('the swap control disappears once there is text to send',
        (WidgetTester tester) async {
      await pumpComposer(
        tester,
        capture: _FakeCapture(),
        recorder: _FakeRecorder(),
      );
      expect(find.byKey(const Key('dm-swap-capture')), findsOneWidget);

      await tester.enterText(find.byKey(const Key('dm-input')), 'hi');
      await tester.pump();

      expect(find.byKey(const Key('dm-swap-capture')), findsNothing);
      expect(find.byKey(const Key('dm-send')), findsOneWidget);
    });
  });

  group('voice notes', () {
    // While recording there is a live `Timer.periodic` driving the elapsed
    // counter, so these use explicit `pump`s: `pumpAndSettle` never settles
    // against a periodic timer and would fail on a timeout instead.
    Future<void> startRecording(WidgetTester tester) async {
      await tester.tap(find.byKey(const Key('dm-record')));
      await tester.pump(); // `start()` resolves
      await tester.pump(); // the strip is laid out
    }

    testWidgets('record, then send: the strip replaces the whole composer',
        (WidgetTester tester) async {
      final _FakeRecorder recorder =
          _FakeRecorder(note: _attachment('Voice note', 'audio/aac'));
      await pumpComposer(tester, recorder: recorder);

      await startRecording(tester);

      expect(recorder.starts, 1);
      // Nothing else is reachable while recording, deliberately.
      expect(find.byKey(const Key('dm-input')), findsNothing);
      expect(find.byKey(const Key('dm-attach')), findsNothing);
      expect(find.byKey(const Key('dm-record-elapsed')), findsOneWidget);

      // The counter is running.
      await tester.pump(const Duration(seconds: 1));
      expect(find.text('Recording  0:01'), findsOneWidget);

      await tester.tap(find.byKey(const Key('dm-record-send')));
      await tester.pumpAndSettle();

      expect(recorder.stops, 1);
      expect(recorder.cancels, 0);
      expect(sentAttachments.single.mimeType, 'audio/aac');
      expect(find.byKey(const Key('dm-input')), findsOneWidget);
    });

    testWidgets('discarding cancels the recorder and sends nothing',
        (WidgetTester tester) async {
      final _FakeRecorder recorder =
          _FakeRecorder(note: _attachment('Voice note', 'audio/aac'));
      await pumpComposer(tester, recorder: recorder);

      await startRecording(tester);
      await tester.tap(find.byKey(const Key('dm-record-cancel')));
      await tester.pumpAndSettle();

      expect(recorder.cancels, 1);
      expect(recorder.stops, 0, reason: 'a discarded note is never read');
      expect(sentAttachments, isEmpty);
    });

    testWidgets('a note too short to exist is reported, not sent',
        (WidgetTester tester) async {
      final _FakeRecorder recorder = _FakeRecorder(); // stop() -> null
      await pumpComposer(tester, recorder: recorder);

      await startRecording(tester);
      await tester.tap(find.byKey(const Key('dm-record-send')));
      await tester.pumpAndSettle();

      expect(sentAttachments, isEmpty);
      expect(find.textContaining('Too short'), findsOneWidget);
    });
  });

  group('emoji insertion', () {
    test('inserts at the caret and leaves it after the emoji', () {
      final TextEditingController c =
          TextEditingController(text: 'see you there');
      addTearDown(c.dispose);
      c.selection = const TextSelection.collapsed(offset: 3);

      insertEmoji(c, '👋');

      expect(c.text, 'see👋 you there');
      // Two UTF-16 code units for a non-BMP emoji: the caret must clear both,
      // or the next keystroke lands inside the character.
      expect(c.selection.baseOffset, 3 + '👋'.length);
    });

    test('replaces a selection rather than appending', () {
      final TextEditingController c = TextEditingController(text: 'yes no');
      addTearDown(c.dispose);
      c.selection = const TextSelection(baseOffset: 4, extentOffset: 6);

      insertEmoji(c, '✅');

      expect(c.text, 'yes ✅');
    });

    test('an invalid selection appends instead of throwing', () {
      // A field that has never been focused has selection offset -1; the naive
      // `replaceRange` would throw on it.
      final TextEditingController c = TextEditingController(text: 'hi');
      addTearDown(c.dispose);
      expect(c.selection.isValid, isFalse);

      insertEmoji(c, '🙂');

      expect(c.text, 'hi🙂');
      expect(c.selection.baseOffset, 'hi🙂'.length);
    });

    testWidgets('the emoji button opens the picker and inserts a pick',
        (WidgetTester tester) async {
      await pumpComposer(tester);
      await tester.tap(find.byKey(const Key('dm-emoji')));
      await tester.pumpAndSettle();

      expect(find.byType(EmojiPickerSheet), findsOneWidget);
      // Smileys is the group the sheet opens on.
      await tester.tap(find.byKey(const Key('emoji-😀')));
      await tester.pump();

      expect(controller.text, '😀');
      // The sheet stays open — picking three emoji in a row is the common case.
      expect(find.byType(EmojiPickerSheet), findsOneWidget);
    });
  });
}
