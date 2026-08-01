/// Two rules about attachments that every frontend has to agree on, kept pure
/// so they can be tested once and mirrored exactly.
///
/// Ports: `android/.../ui/AttachmentCaption.kt`, `desktop/ui/attachment_caption.mjs`.
library;

/// The longest caption the core will keep — `MAX_CAPTION_LEN` in
/// `comrade_ui::runtime`, which truncates anything longer. Applied here too so
/// the three frontends agree on the boundary rather than each discovering it
/// from a different direction.
const int maxCaptionLength = 512;

/// The caption ("tag") a new attachment is sent with.
///
/// Telegram's rule: whatever is in the composer when you attach becomes the
/// caption, and the box is emptied. This app adds one exception — **not while a
/// reply is pending**. The core cannot carry `reply_to` on a media event, so an
/// attachment sent during a reply is not that reply; taking the text would
/// quietly consume a half-written reply and send it as a photo caption instead.
/// Leaving it alone keeps the reply chip and the draft together, and the
/// attachment goes untagged rather than mistagged.
String captionForAttachment({
  required String draft,
  required bool replyPending,
}) {
  if (replyPending) return '';
  final String trimmed = draft.trim();
  return trimmed.length > maxCaptionLength
      ? trimmed.substring(0, maxCaptionLength)
      : trimmed;
}

/// Whether [captionForAttachment] would consume the composer's text — i.e.
/// whether the caller must clear the box after sending.
///
/// Separate from the caption itself so "the draft was whitespace" and "the
/// draft was taken" cannot be confused: both yield an empty caption.
bool captionConsumesDraft({
  required String draft,
  required bool replyPending,
}) =>
    !replyPending && draft.trim().isNotEmpty;

/// One line describing a media message: what a reply chip shows above the
/// composer, and what a quoted preview shows inside a bubble replying to it.
///
/// The kind is always named. A caption is *added*, never substituted for it —
/// "📷 Photo" answers "what am I replying to" even when the sender wrote
/// nothing, which is the common case for a photo.
String mediaQuoteLabel({required String mimeType, required String caption}) {
  final String kind = mediaKindLabel(mimeType);
  final String glyph = mediaKindGlyph(mimeType);
  final String trimmed = caption.trim();
  return trimmed.isEmpty ? '$glyph $kind' : '$glyph $kind · $trimmed';
}

/// "Photo" / "Video" / "Voice message" / "File" for a MIME type.
String mediaKindLabel(String mimeType) {
  final String mime = mimeType.trim().toLowerCase();
  if (mime.startsWith('image/')) return 'Photo';
  if (mime.startsWith('video/')) return 'Video';
  if (mime.startsWith('audio/')) return 'Voice message';
  return 'File';
}

/// The glyph paired with [mediaKindLabel]. Text, not an icon, because two of
/// the three consumers (the desktop SPA's quote line, Compose's `Text`) render
/// this into a plain string.
String mediaKindGlyph(String mimeType) {
  final String mime = mimeType.trim().toLowerCase();
  if (mime.startsWith('image/')) return '📷';
  if (mime.startsWith('video/')) return '🎬';
  if (mime.startsWith('audio/')) return '🎤';
  return '📎';
}

/// Whether tapping this attachment should open the full-screen viewer.
///
/// Photos and videos only: an audio clip has nothing to fill a screen with, and
/// a PDF belongs to whatever app the device has for PDFs.
bool opensFullScreen(String mimeType) {
  final String mime = mimeType.trim().toLowerCase();
  return mime.startsWith('image/') || mime.startsWith('video/');
}
