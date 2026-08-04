/// The two attachment rules every frontend copies: what a new attachment is
/// captioned with, and how an attachment reads when something quotes it.
library;

import 'package:comrade/src/util/attachment_caption.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('captionForAttachment', () {
    test('takes the composer text, trimmed', () {
      expect(
        captionForAttachment(draft: '  at the station  ', replyPending: false),
        'at the station',
      );
      expect(
        captionConsumesDraft(draft: '  at the station  ', replyPending: false),
        isTrue,
      );
    });

    test('an empty composer sends an untagged attachment', () {
      expect(captionForAttachment(draft: '   ', replyPending: false), '');
      expect(captionConsumesDraft(draft: '   ', replyPending: false), isFalse,
          reason: 'nothing was taken, so nothing should be cleared');
    });

    test('a pending reply keeps its draft — the attachment is not that reply',
        () {
      // The core cannot tag a media event as a reply, so an attachment sent
      // mid-reply is a separate message. Eating the reply text as its caption
      // would both lose the reply and mislabel the photo.
      expect(
        captionForAttachment(draft: 'yes, exactly', replyPending: true),
        '',
      );
      expect(
        captionConsumesDraft(draft: 'yes, exactly', replyPending: true),
        isFalse,
      );
    });

    test('is capped where the core caps it', () {
      final String long = 'x' * (maxCaptionLength + 40);
      expect(
        captionForAttachment(draft: long, replyPending: false).length,
        maxCaptionLength,
      );
    });
  });

  group('mediaQuoteLabel', () {
    test('names the kind even when there is no caption', () {
      expect(
        mediaQuoteLabel(mimeType: 'image/jpeg', caption: ''),
        '📷 Photo',
      );
      expect(mediaQuoteLabel(mimeType: 'video/mp4', caption: ''), '🎬 Video');
      expect(
        mediaQuoteLabel(mimeType: 'audio/aac', caption: ''),
        '🎤 Voice message',
      );
      expect(
        mediaQuoteLabel(mimeType: 'application/pdf', caption: ''),
        '📎 File',
      );
    });

    test('adds the caption to the kind rather than replacing it', () {
      expect(
        mediaQuoteLabel(mimeType: 'image/png', caption: '  the platform  '),
        '📷 Photo · the platform',
      );
    });

    test('an unknown or empty MIME type still reads as something', () {
      expect(mediaQuoteLabel(mimeType: '', caption: ''), '📎 File');
      expect(mediaQuoteLabel(mimeType: 'IMAGE/PNG', caption: ''), '📷 Photo');
    });
  });

  group('opensFullScreen', () {
    test('is photos and videos only', () {
      expect(opensFullScreen('image/webp'), isTrue);
      expect(opensFullScreen('video/mp4'), isTrue);
      // Nothing to fill a screen with, and a document belongs to whatever app
      // the device has for it.
      expect(opensFullScreen('audio/aac'), isFalse);
      expect(opensFullScreen('application/pdf'), isFalse);
    });
  });

  group('formatAttachmentSize', () {
    test('reads as a size a person would say out loud', () {
      expect(formatAttachmentSize(0), '0 B');
      expect(formatAttachmentSize(48), '48 B');
      expect(formatAttachmentSize(1024), '1 KB');
      expect(formatAttachmentSize(831488), '812 KB');
      expect(formatAttachmentSize(1572864), '1.5 MB');
      expect(formatAttachmentSize(12999999), '12.4 MB');
    });

    test('drops a trailing .0 rather than saying "10.0 MB"', () {
      expect(formatAttachmentSize(maxAttachmentBytes), '10 MB');
      expect(formatAttachmentSize(1024 * 1024), '1 MB');
    });

    test('a negative or absurd size does not produce a negative string', () {
      expect(formatAttachmentSize(-1), '0 B');
    });
  });

  group('attachmentRejection', () {
    test('a file inside the cap is not refused', () {
      expect(attachmentRejection(name: 'a.png', bytes: 1), isNull);
      expect(
        attachmentRejection(name: 'a.png', bytes: maxAttachmentBytes),
        isNull,
        reason: 'the cap is inclusive — the core accepts exactly this many',
      );
    });

    test('names the file, its size, and the limit', () {
      expect(
        attachmentRejection(name: '  holiday.mov  ', bytes: 12999999),
        '"holiday.mov" is 12.4 MB — attachments are limited to 10 MB.',
      );
    });

    test('a capture with no name still gets a sentence', () {
      expect(
        attachmentRejection(name: '', bytes: maxAttachmentBytes + 1),
        startsWith('That file is 10 MB'),
      );
    });

    test('an empty pick is refused before anything is encrypted', () {
      // A cancelled camera hands back a zero-byte placeholder; sending it
      // produces an attachment the recipient cannot open.
      expect(
        attachmentRejection(name: 'cap.jpg', bytes: 0),
        '"cap.jpg" is empty — there is nothing to send.',
      );
    });
  });

  group('attachmentPreviewKind', () {
    test('is what the preview sheet renders', () {
      expect(attachmentPreviewKind('image/png'), AttachmentPreviewKind.image);
      expect(attachmentPreviewKind('VIDEO/MP4'), AttachmentPreviewKind.video);
      expect(attachmentPreviewKind('audio/aac'), AttachmentPreviewKind.audio);
      expect(
          attachmentPreviewKind('application/pdf'), AttachmentPreviewKind.file);
      expect(attachmentPreviewKind(''), AttachmentPreviewKind.file);
    });
  });

  group('attachmentPreviewDetail', () {
    test('is the name and the size, which is how you spot a wrong pick', () {
      expect(
        attachmentPreviewDetail(
            name: 'IMG_20260731_114233.png', bytes: 1572864),
        'IMG_20260731_114233.png · 1.5 MB',
      );
    });

    test('a nameless capture shows just the size', () {
      expect(attachmentPreviewDetail(name: '  ', bytes: 48), '48 B');
    });
  });

  group('normalizeCaption', () {
    test('trims and caps, whatever the source', () {
      expect(normalizeCaption('  at the gate  '), 'at the gate');
      expect(
        normalizeCaption('x' * (maxCaptionLength + 40)).length,
        maxCaptionLength,
      );
    });
  });

  group('attachmentPreviewMediaHeight', () {
    test('is 52% of the viewport between the floor and the cap', () {
      expect(attachmentPreviewMediaHeight(600), 312);
      expect(attachmentPreviewMediaHeight(500), 260);
    });

    test('is capped, so a sheet on a big screen still reads as a sheet', () {
      expect(attachmentPreviewMediaHeight(1600), 420);
    });

    test('has a floor, so a short window still shows a recognisable picture',
        () {
      // Below this the picture scrolls rather than shrinking further — the
      // caption box and Send have already been given their room.
      expect(attachmentPreviewMediaHeight(200), 120);
      expect(attachmentPreviewMediaHeight(0), 120);
    });
  });
}
