// The rules about attachments that every frontend has to agree on, kept pure so
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
 * The largest attachment the core will accept — `MAX_MEDIA_BYTES` in
 * `comrade_core::media`.
 */
export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;

/**
 * A caption as the core will store it: trimmed, and no longer than
 * MAX_CAPTION_LENGTH.
 *
 * Applied on both sides of the preview sheet — to the text seeded into it and to
 * the text confirmed out of it — so what the sender reads back in their own
 * thread is exactly what the recipient gets.
 */
export function normalizeCaption(text) {
  return String(text ?? "")
    .trim()
    .slice(0, MAX_CAPTION_LENGTH);
}

/**
 * The caption ("tag") a new attachment *starts* with, before the sender confirms
 * it in the preview sheet.
 *
 * Telegram's rule: whatever is in the composer when you attach becomes the
 * caption, and the box is emptied. This app adds one exception — **not while a
 * reply is pending**. The core cannot carry `reply_to` on a media event, so an
 * attachment sent during a reply is not that reply; taking the text would
 * quietly consume a half-written reply and send it as a photo caption instead.
 */
export function captionForAttachment(draft, replyPending) {
  return replyPending ? "" : normalizeCaption(draft);
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

/**
 * How the preview sheet should render an attachment that has been picked but not
 * yet sent: "image" | "video" | "audio" | "file".
 *
 * The three renderers are necessarily different (an `<img>` with an object URL,
 * `Image.memory`, `BitmapFactory`), so what is shared is the *choice* — which is
 * the part that must not drift. Video is shown in a player where the platform
 * has one **that needs no file** — this one's in-memory object URL qualifies, a
 * `VideoView` pointed at a staged temp file does not, because an unsent
 * attachment must not put plaintext on disk (AUDIT S-4) — and a card everywhere
 * else. Never a still frame, which would cost a decoder none of the three
 * frontends carries.
 */
export function attachmentPreviewKind(mimeType) {
  const mime = String(mimeType ?? "")
    .trim()
    .toLowerCase();
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  return "file";
}

/**
 * "48 B" / "812 KB" / "1.5 MB" / "10 MB".
 *
 * Integer arithmetic throughout, and a trailing `.0` is dropped, so the three
 * ports round the same way instead of inheriting three float formatters.
 */
export function formatAttachmentSize(bytes) {
  const n = Number(bytes) || 0;
  if (n <= 0) return "0 B";
  if (n < 1024) return `${Math.floor(n)} B`;
  if (n < 1024 * 1024) return `${Math.floor((n + 512) / 1024)} KB`;
  const tenths = Math.floor((n * 10 + 524288) / (1024 * 1024));
  const whole = Math.floor(tenths / 10);
  const frac = tenths % 10;
  return frac === 0 ? `${whole} MB` : `${whole}.${frac} MB`;
}

/**
 * Why this file cannot be sent, or null when it can.
 *
 * Checked **before** the preview sheet opens, in every frontend. The cap is
 * enforced in the core either way, but discovering it after composing a caption
 * — which is where every frontend used to discover it — wastes the only work the
 * sender actually did.
 */
export function attachmentRejection(name, bytes) {
  const trimmed = String(name ?? "").trim();
  const subject = trimmed ? `"${trimmed}"` : "That file";
  const n = Number(bytes) || 0;
  if (n <= 0) return `${subject} is empty — there is nothing to send.`;
  if (n > MAX_ATTACHMENT_BYTES) {
    return (
      `${subject} is ${formatAttachmentSize(n)} — attachments are ` +
      `limited to ${formatAttachmentSize(MAX_ATTACHMENT_BYTES)}.`
    );
  }
  return null;
}

/**
 * Why this file cannot be handed **straight to the other device**, or null when
 * it can.
 *
 * A second function rather than a bigger number inside attachmentRejection,
 * because the two roads refuse for different reasons and one of them has no
 * ceiling at all: MAX_ATTACHMENT_BYTES is the *hosted* limit — what the core can
 * encrypt in one buffer and what a Blossom operator will take — and none of that
 * applies to bytes going device to device. What survives is the empty file, which
 * is not a size problem: a cancelled camera hands back zero bytes, and sending
 * them produces something the recipient cannot open either way.
 *
 * Callers ask `attachment_route_for_bytes` which road a file takes and then this
 * or attachmentRejection, rather than comparing against a local 10 MB — the
 * threshold belongs to the core that enforces it.
 */
export function peerToPeerAttachmentRejection(name, bytes) {
  const trimmed = String(name ?? "").trim();
  const subject = trimmed ? `"${trimmed}"` : "That file";
  const n = Number(bytes) || 0;
  if (n <= 0) return `${subject} is empty — there is nothing to send.`;
  return null;
}

/**
 * The line under the preview's heading: the file's own name and its size, or just
 * the size for a capture that has no name yet.
 *
 * The name appears *here* and only here. It is deliberately not the caption — see
 * captionForAttachment — but it is the one thing that answers "is this the file I
 * meant to pick", which is what a preview is for.
 */
export function attachmentPreviewDetail(name, bytes) {
  const trimmed = String(name ?? "").trim();
  const size = formatAttachmentSize(bytes);
  return trimmed ? `${trimmed} · ${size}` : size;
}

/**
 * The tallest the preview's picture may be, given the height of the viewport it
 * sits in.
 *
 * 52% of the viewport, never more than 420 and never less than 120. The
 * *fraction* is what keeps the caption box and Send on screen on a short window —
 * they are the two controls the sheet exists for, and neither may need a
 * scrollbar to reach. The *cap* stops a photo dominating a large window, where a
 * sheet should still read as a sheet and not as the full-screen viewer. The
 * *floor* stops a very short window shrinking the picture to something you cannot
 * recognise, which would defeat the preview entirely — below it the picture
 * scrolls instead.
 *
 * This UI applies the rule in CSS — `clamp(120px, 52vh, 420px)` on
 * `.attach-preview-media` — so nothing here calls it; it exists so the three
 * ports' numbers are pinned by one set of mirrored tests rather than by three
 * readings of three files.
 */
export function attachmentPreviewMediaHeight(viewportHeight) {
  const proportional = (Number(viewportHeight) || 0) * 0.52;
  if (proportional < 120) return 120;
  if (proportional > 420) return 420;
  return proportional;
}
