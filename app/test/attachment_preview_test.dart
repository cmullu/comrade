/// The confirm-before-sending sheet: what it shows, and what it returns.
///
/// The behaviour it exists for is asserted from the conversation screen
/// (`conversation_screen_test.dart`) — that a pick sends nothing until Send, that
/// backing out keeps the draft. This file pins the sheet's own rendering: a photo
/// is shown as itself, everything else as a card, and the name and size are
/// always there because that is what catches a wrong pick.
library;

import 'dart:typed_data';

import 'package:comrade/src/data/fake_comrade_repository.dart';
import 'package:comrade/src/util/attachment_caption.dart';
import 'package:comrade/src/widgets/attachment_preview.dart';
import 'package:comrade/src/widgets/media_attachment.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'helpers.dart';

/// A 64×64 PNG — the same fixture `media_attachment_test.dart` uses, for the
/// same reason: a 1×1 image decodes to nothing worth laying out.
final Uint8List _png = Uint8List.fromList(<int>[
  137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, //
  0, 0, 0, 64, 0, 0, 0, 64, 8, 2, 0, 0, 0, 37, 11, 230, //
  137, 0, 0, 0, 78, 73, 68, 65, 84, 120, 218, 237, 207, 65, 9, 0, //
  0, 8, 4, 176, 11, 118, 253, 49, 150, 17, 124, 11, 131, 21, 88, 166, //
  125, 45, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, //
  2, 151, 5, 55, 235, 1, 45, 10, 150, 103, 129, 0, 0, 0, 0, 73, //
  69, 78, 68, 174, 66, 96, 130, //
]);

PickedAttachment _pick({
  String name = 'IMG_20260731_114233.png',
  String mimeType = 'image/png',
  Uint8List? bytes,
}) =>
    PickedAttachment(
      name: name,
      mimeType: mimeType,
      bytes: bytes ?? _png,
    );

/// Open the sheet from a button, so the real `showModalBottomSheet` route is
/// exercised rather than the widget in isolation.
Future<void> openSheet(
  WidgetTester tester, {
  required PickedAttachment attachment,
  String seedCaption = '',
}) async {
  final FakeComradeRepository repo = await unlockedFake(seed: false);
  bool returned = false;

  await tester.pumpWidget(harness(
    Builder(
      builder: (BuildContext context) => TextButton(
        key: const Key('open'),
        onPressed: () async {
          await showAttachmentPreview(
            context,
            attachment: attachment,
            seedCaption: seedCaption,
          );
          returned = true;
        },
        child: const Text('open'),
      ),
    ),
    repo: repo,
  ));
  await tester.pump();
  // Image decoding runs on the real event loop, which a widget test's fake clock
  // never advances — without `runAsync` an `Image.memory` is laid out at zero
  // size and is not findable as anything.
  await tester.runAsync(() async {
    await tester.tap(find.byKey(const Key('open')));
    await tester.pump();
    await Future<void>.delayed(const Duration(milliseconds: 120));
  });
  await tester.pumpAndSettle();
  expect(returned, isFalse, reason: 'the sheet is still open');
}

void main() {
  group('what the sheet shows', () {
    testWidgets('a photo is shown as itself', (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      await openSheet(tester, attachment: _pick());

      expect(find.byKey(const Key('attachment-preview-image')), findsOneWidget);
      expect(find.byKey(const Key('attachment-preview-card')), findsNothing);
      expect(find.text('📷 Photo'), findsOneWidget);
    });

    testWidgets('the name and size are always there',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      await openSheet(tester, attachment: _pick());

      expect(
        find.text(
          attachmentPreviewDetail(
            name: 'IMG_20260731_114233.png',
            bytes: _png.length,
          ),
        ),
        findsOneWidget,
      );
    });

    testWidgets('a video is a card — no still frame, no staged file',
        (WidgetTester tester) async {
      // Flutter has no player without a plugin, and feeding one would mean
      // writing an unsent attachment's plaintext to disk (AUDIT S-4).
      setWindowSize(tester, const Size(420, 900));
      await openSheet(
        tester,
        attachment: _pick(name: 'clip.mp4', mimeType: 'video/mp4'),
      );

      expect(find.byKey(const Key('attachment-preview-card')), findsOneWidget);
      expect(find.byKey(const Key('attachment-preview-image')), findsNothing);
      expect(find.text('🎬 Video'), findsOneWidget);
    });

    testWidgets('a document is a card', (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      await openSheet(
        tester,
        attachment: _pick(name: 'notes.pdf', mimeType: 'application/pdf'),
      );

      expect(find.byKey(const Key('attachment-preview-card')), findsOneWidget);
      expect(find.text('📎 File'), findsOneWidget);
    });

    testWidgets(
        'bytes that claim to be an image and are not say so, and can '
        'still be sent', (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      await openSheet(
        tester,
        attachment: _pick(bytes: Uint8List.fromList(<int>[1, 2, 3, 4])),
      );

      expect(find.byKey(const Key('attachment-preview-card')), findsOneWidget);
      expect(find.textContaining('can still be sent'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(
                find.byKey(const Key('attachment-preview-send')))
            .onPressed,
        isNotNull,
        reason:
            'the bytes are the sender\'s to send; another device may decode',
      );
    });
  });

  group('what the sheet returns', () {
    testWidgets('Send returns the caption, normalised',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      String? captured;
      final FakeComradeRepository repo = await unlockedFake(seed: false);
      await tester.pumpWidget(harness(
        Builder(
          builder: (BuildContext context) => TextButton(
            key: const Key('open'),
            onPressed: () async => captured = await showAttachmentPreview(
              context,
              attachment: _pick(mimeType: 'application/pdf'),
              seedCaption: '',
            ),
            child: const Text('open'),
          ),
        ),
        repo: repo,
      ));
      await tester.pump();
      await tester.tap(find.byKey(const Key('open')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('attachment-preview-caption')),
        '  at the gate  ',
      );
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      expect(captured, 'at the gate');
    });

    testWidgets('a caption longer than the core keeps is cut here',
        (WidgetTester tester) async {
      setWindowSize(tester, const Size(420, 900));
      String? captured;
      final FakeComradeRepository repo = await unlockedFake(seed: false);
      await tester.pumpWidget(harness(
        Builder(
          builder: (BuildContext context) => TextButton(
            key: const Key('open'),
            onPressed: () async => captured = await showAttachmentPreview(
              context,
              attachment: _pick(mimeType: 'application/pdf'),
              seedCaption: 'x' * (maxCaptionLength + 40),
            ),
            child: const Text('open'),
          ),
        ),
        repo: repo,
      ));
      await tester.pump();
      await tester.tap(find.byKey(const Key('open')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('attachment-preview-send')));
      await tester.pumpAndSettle();

      expect(captured?.length, maxCaptionLength);
    });

    testWidgets('Cancel and the ✕ both return nothing',
        (WidgetTester tester) async {
      // Indistinguishable on purpose: every way out means "don't send this".
      for (final String key in <String>[
        'attachment-preview-cancel',
        'attachment-preview-close',
      ]) {
        setWindowSize(tester, const Size(420, 900));
        String? captured = 'not yet';
        final FakeComradeRepository repo = await unlockedFake(seed: false);
        await tester.pumpWidget(harness(
          Builder(
            builder: (BuildContext context) => TextButton(
              key: const Key('open'),
              onPressed: () async => captured = await showAttachmentPreview(
                context,
                attachment: _pick(mimeType: 'application/pdf'),
                seedCaption: 'written but not sent',
              ),
              child: const Text('open'),
            ),
          ),
          repo: repo,
        ));
        await tester.pump();
        await tester.tap(find.byKey(const Key('open')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(Key(key)));
        await tester.pumpAndSettle();

        expect(captured, isNull, reason: key);
      }
    });
  });
}
