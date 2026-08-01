import { test } from "node:test";
import assert from "node:assert/strict";

import {
  MAX_CAPTION_LENGTH,
  captionConsumesDraft,
  captionForAttachment,
  mediaQuoteLabel,
  opensFullScreen,
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
