// Two rules about attachments that every frontend has to agree on, kept pure so
// they can be tested once and mirrored exactly.
//
// Ports: `app/lib/src/util/attachment_caption.dart`,
// `android/.../ui/AttachmentCaption.kt`. Same cases, same answers — a change
// here is a change in three places.

/**
 * The longest caption the core will keep — `MAX_CAPTION_LEN` in
 * `comrade_ui::runtime`, which truncates anything longer.
 */
export const MAX_CAPTION_LENGTH = 512;

/**
 * The caption ("tag") a new attachment is sent with.
 *
 * Telegram's rule: whatever is in the composer when you attach becomes the
 * caption, and the box is emptied. This app adds one exception — **not while a
 * reply is pending**. The core cannot carry `reply_to` on a media event, so an
 * attachment sent during a reply is not that reply; taking the text would
 * quietly consume a half-written reply and send it as a photo caption instead.
 */
export function captionForAttachment(draft, replyPending) {
  if (replyPending) return "";
  return String(draft ?? "")
    .trim()
    .slice(0, MAX_CAPTION_LENGTH);
}

/**
 * Whether captionForAttachment would consume the composer's text — i.e. whether
 * the caller must clear the box after sending.
 */
export function captionConsumesDraft(draft, replyPending) {
  return !replyPending && String(draft ?? "").trim().length > 0;
}

/**
 * One line describing a media message: what a reply chip shows above the
 * composer, and what a quoted preview shows inside a bubble replying to it.
 *
 * The kind is always named. A caption is *added*, never substituted for it —
 * "📷 Photo" answers "what am I replying to" even when the sender wrote
 * nothing, which is the common case for a photo.
 */
export function mediaQuoteLabel(mimeType, caption) {
  const head = `${mediaKindGlyph(mimeType)} ${mediaKindLabel(mimeType)}`;
  const trimmed = String(caption ?? "").trim();
  return trimmed ? `${head} · ${trimmed}` : head;
}

/** "Photo" / "Video" / "Voice message" / "File" for a MIME type. */
export function mediaKindLabel(mimeType) {
  const mime = String(mimeType ?? "")
    .trim()
    .toLowerCase();
  if (mime.startsWith("image/")) return "Photo";
  if (mime.startsWith("video/")) return "Video";
  if (mime.startsWith("audio/")) return "Voice message";
  return "File";
}

/** The glyph paired with mediaKindLabel. */
export function mediaKindGlyph(mimeType) {
  const mime = String(mimeType ?? "")
    .trim()
    .toLowerCase();
  if (mime.startsWith("image/")) return "📷";
  if (mime.startsWith("video/")) return "🎬";
  if (mime.startsWith("audio/")) return "🎤";
  return "📎";
}

/**
 * Whether clicking this attachment should open the full-screen viewer.
 *
 * Photos and videos only: an audio clip has nothing to fill a screen with, and
 * a document belongs to whatever app the machine has for it.
 */
export function opensFullScreen(mimeType) {
  const mime = String(mimeType ?? "")
    .trim()
    .toLowerCase();
  return mime.startsWith("image/") || mime.startsWith("video/");
}
