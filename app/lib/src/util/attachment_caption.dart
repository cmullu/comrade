/// The rules about attachments that every frontend has to agree on, kept pure
/// so they can be tested once and mirrored exactly.
///
/// Ports: `android/.../ui/AttachmentCaption.kt`, `desktop/ui/attachment_caption.mjs`.
library;

/// The longest caption the core will keep — `MAX_CAPTION_LEN` in
/// `comrade_ui::runtime`, which truncates anything longer. Applied here too so
/// the three frontends agree on the boundary rather than each discovering it
/// from a different direction.
const int maxCaptionLength = 512;

/// The largest attachment the core will accept — `MAX_MEDIA_BYTES` in
/// `comrade_core::media`.
const int maxAttachmentBytes = 10 * 1024 * 1024;

/// A caption as the core will store it: trimmed, and no longer than
/// [maxCaptionLength].
///
/// Applied on both sides of the preview sheet — to the text seeded into it and
/// to the text confirmed out of it — so what the sender reads back in their own
/// thread is exactly what the recipient gets.
String normalizeCaption(String text) {
  final String trimmed = text.trim();
  return trimmed.length > maxCaptionLength
      ? trimmed.substring(0, maxCaptionLength)
      : trimmed;
}

/// The caption ("tag") a new attachment *starts* with, before the sender
/// confirms it in the preview sheet.
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
}) =>
    replyPending ? '' : normalizeCaption(draft);

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

/// How the preview sheet should render an attachment that has been picked but
/// not yet sent.
///
/// The three renderers are necessarily different (`Image.memory`,
/// `BitmapFactory`, an `<img>` with an object URL), so what is shared is the
/// *choice* — which is the part that must not drift.
enum AttachmentPreviewKind {
  /// Show the picture itself, scaled to fit.
  image,

  /// Show it in a player where the platform has one **that needs no file** —
  /// the desktop's in-memory object URL qualifies, a `VideoView` pointed at a
  /// staged temp file does not, because an unsent attachment must not put
  /// plaintext on disk (AUDIT S-4). Everywhere else, a card. Never a still
  /// frame: extracting one costs a decoder none of the three frontends carries.
  video,

  /// A card. There is nothing to look at, and the sender just recorded or
  /// picked it, so the kind and the length are the whole story.
  audio,

  /// A card, for everything else.
  file,
}

AttachmentPreviewKind attachmentPreviewKind(String mimeType) {
  final String mime = mimeType.trim().toLowerCase();
  if (mime.startsWith('image/')) return AttachmentPreviewKind.image;
  if (mime.startsWith('video/')) return AttachmentPreviewKind.video;
  if (mime.startsWith('audio/')) return AttachmentPreviewKind.audio;
  return AttachmentPreviewKind.file;
}

/// "48 B" / "812 KB" / "1.5 MB" / "10 MB".
///
/// Integer arithmetic throughout, and a trailing `.0` is dropped, so the three
/// ports round the same way instead of inheriting three float formatters.
String formatAttachmentSize(int bytes) {
  if (bytes <= 0) return '0 B';
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes + 512) ~/ 1024} KB';
  final int tenths = (bytes * 10 + 524288) ~/ (1024 * 1024);
  final int whole = tenths ~/ 10;
  final int frac = tenths % 10;
  return frac == 0 ? '$whole MB' : '$whole.$frac MB';
}

/// Why this file cannot be sent, or null when it can.
///
/// Checked **before** the preview sheet opens, in every frontend. The cap is
/// enforced in the core either way, but discovering it after composing a
/// caption — which is where every frontend used to discover it — wastes the
/// only work the sender actually did.
String? attachmentRejection({required String name, required int bytes}) {
  final String trimmed = name.trim();
  final String subject = trimmed.isEmpty ? 'That file' : '"$trimmed"';
  if (bytes <= 0) return '$subject is empty — there is nothing to send.';
  if (bytes > maxAttachmentBytes) {
    return '$subject is ${formatAttachmentSize(bytes)} — attachments are '
        'limited to ${formatAttachmentSize(maxAttachmentBytes)}.';
  }
  return null;
}

/// The line under the preview's heading: the file's own name and its size, or
/// just the size for a capture that has no name yet.
///
/// The name appears *here* and only here. It is deliberately not the caption —
/// see [captionForAttachment] — but it is the one thing that answers "is this
/// the file I meant to pick", which is what a preview is for.
String attachmentPreviewDetail({required String name, required int bytes}) {
  final String trimmed = name.trim();
  final String size = formatAttachmentSize(bytes);
  return trimmed.isEmpty ? size : '$trimmed · $size';
}

/// The tallest the preview's picture may be, given the height of the viewport it
/// sits in.
///
/// 52% of the viewport, never more than 420 and never less than 120. The
/// *fraction* is what keeps the caption box and Send on screen on a short window —
/// they are the two controls the sheet exists for, and neither may need a
/// scrollbar to reach. The *cap* stops a photo dominating a large window, where a
/// sheet should still read as a sheet and not as the full-screen viewer. The
/// *floor* stops a very short window shrinking the picture to something you
/// cannot recognise, which would defeat the preview entirely — below it the
/// picture scrolls instead.
///
/// The desktop port spells the same rule in CSS: `clamp(120px, 52vh, 420px)`.
double attachmentPreviewMediaHeight(double viewportHeight) {
  final double proportional = viewportHeight * 0.52;
  if (proportional < 120) return 120;
  if (proportional > 420) return 420;
  return proportional;
}
