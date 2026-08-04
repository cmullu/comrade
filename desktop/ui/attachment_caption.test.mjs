import { test } from "node:test";
import assert from "node:assert/strict";

import {
  MAX_ATTACHMENT_BYTES,
  MAX_CAPTION_LENGTH,
  attachmentPreviewDetail,
  attachmentPreviewMediaHeight,
  attachmentPreviewKind,
  attachmentRejection,
  captionConsumesDraft,
  captionForAttachment,
  formatAttachmentSize,
  mediaQuoteLabel,
  normalizeCaption,
  opensFullScreen,
  peerToPeerAttachmentRejection,
} from "./attachment_caption.mjs";

// Mirrored in `app/test/attachment_caption_test.dart` and
// `android/.../ui/AttachmentCaptionTest.kt` — same cases, same answers.

test("the composer text becomes the caption, trimmed", () => {
  assert.equal(captionForAttachment("  at the station  ", false), "at the station");
  assert.equal(captionConsumesDraft("  at the station  ", false), true);
});

test("an empty composer sends an untagged attachment", () => {
  assert.equal(captionForAttachment("   ", false), "");
  // Nothing was taken, so nothing should be cleared.
  assert.equal(captionConsumesDraft("   ", false), false);
});

test("a pending reply keeps its draft", () => {
  // The core cannot tag a media event as a reply, so an attachment sent
  // mid-reply is a separate message. Eating the reply text as its caption would
  // both lose the reply and mislabel the photo.
  assert.equal(captionForAttachment("yes, exactly", true), "");
  assert.equal(captionConsumesDraft("yes, exactly", true), false);
});

test("is capped where the core caps it", () => {
  const long = "x".repeat(MAX_CAPTION_LENGTH + 40);
  assert.equal(captionForAttachment(long, false).length, MAX_CAPTION_LENGTH);
});

test("the kind is named even with no caption", () => {
  assert.equal(mediaQuoteLabel("image/jpeg", ""), "📷 Photo");
  assert.equal(mediaQuoteLabel("video/mp4", ""), "🎬 Video");
  assert.equal(mediaQuoteLabel("audio/aac", ""), "🎤 Voice message");
  assert.equal(mediaQuoteLabel("application/pdf", ""), "📎 File");
});

test("the caption is added to the kind, not substituted for it", () => {
  assert.equal(mediaQuoteLabel("image/png", "  the platform  "), "📷 Photo · the platform");
});

test("an unknown or empty MIME type still reads as something", () => {
  assert.equal(mediaQuoteLabel("", ""), "📎 File");
  assert.equal(mediaQuoteLabel("IMAGE/PNG", ""), "📷 Photo");
  assert.equal(mediaQuoteLabel(undefined, undefined), "📎 File");
});

test("only photos and videos open full screen", () => {
  assert.equal(opensFullScreen("image/webp"), true);
  assert.equal(opensFullScreen("video/mp4"), true);
  assert.equal(opensFullScreen("audio/aac"), false);
  assert.equal(opensFullScreen("application/pdf"), false);
});

test("a size reads as one a person would say out loud", () => {
  assert.equal(formatAttachmentSize(0), "0 B");
  assert.equal(formatAttachmentSize(48), "48 B");
  assert.equal(formatAttachmentSize(1024), "1 KB");
  assert.equal(formatAttachmentSize(831488), "812 KB");
  assert.equal(formatAttachmentSize(1572864), "1.5 MB");
  assert.equal(formatAttachmentSize(12999999), "12.4 MB");
});

test("a trailing .0 is dropped rather than saying \"10.0 MB\"", () => {
  assert.equal(formatAttachmentSize(MAX_ATTACHMENT_BYTES), "10 MB");
  assert.equal(formatAttachmentSize(1024 * 1024), "1 MB");
});

test("a negative or absurd size does not produce a negative string", () => {
  assert.equal(formatAttachmentSize(-1), "0 B");
  assert.equal(formatAttachmentSize(undefined), "0 B");
});

test("a file inside the cap is not refused", () => {
  assert.equal(attachmentRejection("a.png", 1), null);
  // The cap is inclusive — the core accepts exactly this many.
  assert.equal(attachmentRejection("a.png", MAX_ATTACHMENT_BYTES), null);
});

test("a refusal names the file, its size, and the limit", () => {
  assert.equal(
    attachmentRejection("  holiday.mov  ", 12999999),
    '"holiday.mov" is 12.4 MB — attachments are limited to 10 MB.',
  );
});

test("a capture with no name still gets a sentence", () => {
  assert.ok(
    attachmentRejection("", MAX_ATTACHMENT_BYTES + 1).startsWith("That file is 10 MB"),
  );
});

test("an empty pick is refused before anything is encrypted", () => {
  // A cancelled camera hands back a zero-byte placeholder; sending it produces
  // an attachment the recipient cannot open.
  assert.equal(
    attachmentRejection("cap.jpg", 0),
    '"cap.jpg" is empty — there is nothing to send.',
  );
});

test("the other road has no ceiling to refuse against", () => {
  // The 10 MB limit is what the hosted path can encrypt and what a Blossom
  // operator will take; neither applies device to device.
  assert.equal(
    peerToPeerAttachmentRejection("holiday.mp4", MAX_ATTACHMENT_BYTES + 1),
    null,
  );
  assert.equal(
    peerToPeerAttachmentRejection("holiday.mp4", 4 * 1024 * 1024 * 1024),
    null,
  );
});

test("an empty file is still refused on the other road", () => {
  // Not a size problem: a cancelled camera hands back zero bytes, and they are
  // unopenable however they travel.
  assert.equal(
    peerToPeerAttachmentRejection("cap.jpg", 0),
    '"cap.jpg" is empty — there is nothing to send.',
  );
  assert.equal(
    peerToPeerAttachmentRejection("  ", -1),
    "That file is empty — there is nothing to send.",
  );
});

test("the preview kind is what the sheet renders", () => {
  assert.equal(attachmentPreviewKind("image/png"), "image");
  assert.equal(attachmentPreviewKind("VIDEO/MP4"), "video");
  assert.equal(attachmentPreviewKind("audio/aac"), "audio");
  assert.equal(attachmentPreviewKind("application/pdf"), "file");
  assert.equal(attachmentPreviewKind(""), "file");
});

test("the preview detail is the name and the size, which is how you spot a wrong pick", () => {
  assert.equal(
    attachmentPreviewDetail("IMG_20260731_114233.png", 1572864),
    "IMG_20260731_114233.png · 1.5 MB",
  );
  assert.equal(attachmentPreviewDetail("  ", 48), "48 B");
});

test("a caption is trimmed and capped whatever the source", () => {
  assert.equal(normalizeCaption("  at the gate  "), "at the gate");
  assert.equal(normalizeCaption("x".repeat(MAX_CAPTION_LENGTH + 40)).length, MAX_CAPTION_LENGTH);
});

test("the preview's picture is 52% of the viewport, floored and capped", () => {
  assert.equal(attachmentPreviewMediaHeight(600), 312);
  assert.equal(attachmentPreviewMediaHeight(500), 260);
  // Capped, so a sheet on a big screen still reads as a sheet.
  assert.equal(attachmentPreviewMediaHeight(1600), 420);
  // Floored, so a short window still shows a recognisable picture — below this it
  // scrolls rather than shrinking further.
  assert.equal(attachmentPreviewMediaHeight(200), 120);
  assert.equal(attachmentPreviewMediaHeight(0), 120);
});
