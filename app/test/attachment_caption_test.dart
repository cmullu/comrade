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
}
