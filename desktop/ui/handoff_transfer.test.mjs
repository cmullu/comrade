import assert from "node:assert/strict";
import { test } from "node:test";

import { MAX_ATTACHMENT_BYTES, MAX_CAPTION_LENGTH } from "./attachment_caption.mjs";
import { chunkCount } from "./share_transfer.mjs";
import {
  MAX_DISPLAY_NAME_CHARS,
  MAX_HANDOFF_BYTES,
  MIN_OFFER_CHUNK_BYTES,
  attachmentSendPlan,
  boundedCaption,
  decodeHandoffSignal,
  decodeTogetherSignal,
  describeHandoffVerdict,
  encodeHandoffStep,
  encodeTogetherStep,
  handoffOfferRefusal,
  handoffPresencePlan,
  isContentHash,
  newTransferId,
  offerCardPlan,
  peerStepIsPlausible,
  previewRouteLine,
  progressLabel,
  safeDisplayName,
  signalIsForTransfer,
} from "./handoff_transfer.mjs";

const HASH = "a".repeat(64);

function attachment(overrides = {}) {
  const { shape: shapeOverrides, ...rest } = overrides;
  return {
    shape: {
      total_bytes: 50 * 1024 * 1024,
      chunk_bytes: 16 * 1024,
      sha256: HASH,
      duration_ms: 0,
      ...shapeOverrides,
    },
    mime_type: "video/mp4",
    file_name: "holiday.mp4",
    caption: "the last morning",
    ...rest,
  };
}

// ── The seam between the two protocols ───────────────────────────────────────

test("a transport step encodes into whichever envelope the transfer is using", () => {
  const signal = { kind: "offer", sdp: "v=0\r\n" };
  assert.deepEqual(encodeTogetherStep({ step: "transport", signal }), {
    share: "transport",
    signal,
  });
  assert.deepEqual(encodeHandoffStep({ step: "transport", signal }), {
    handoff: "transport",
    signal,
  });
});

test("every step a protocol has round-trips through its own codec", () => {
  const together = [
    { step: "ask" },
    { step: "offer", offer: { total_bytes: 1, chunk_bytes: 16384, sha256: HASH, duration_ms: 0 } },
    { step: "accept" },
    { step: "refuse", reason: { kind: "relay_forbidden" } },
    { step: "transport", signal: { kind: "answer", sdp: "s" } },
  ];
  for (const step of together) {
    assert.deepEqual(decodeTogetherSignal(encodeTogetherStep(step)), step, JSON.stringify(step));
  }
  const handoff = [
    { step: "offer", attachment: attachment() },
    { step: "accept" },
    { step: "decline" },
    { step: "refuse", reason: { kind: "path_unknown" } },
    { step: "withdraw" },
    { step: "transport", signal: { kind: "ice", candidate: "c" } },
  ];
  for (const step of handoff) {
    assert.deepEqual(decodeHandoffSignal(encodeHandoffStep(step)), step, JSON.stringify(step));
  }
});

test("a codec refuses the steps its own protocol cannot say", () => {
  // A together handover has no decline and no withdraw; putting one on the wire
  // would be a body `comrade_core::share::ShareSignal` cannot parse.
  assert.equal(encodeTogetherStep({ step: "decline" }), null);
  assert.equal(encodeTogetherStep({ step: "withdraw" }), null);
  // A handoff has no `ask` — the sender picked the file, so nobody discovers who
  // wants what.
  assert.equal(encodeHandoffStep({ step: "ask" }), null);
  // And a step whose payload is missing is not sent as an empty one.
  assert.equal(encodeTogetherStep({ step: "offer" }), null);
  assert.equal(encodeHandoffStep({ step: "offer" }), null);
  assert.equal(encodeHandoffStep({ step: "transport" }), null);
  assert.equal(encodeHandoffStep(null), null);
});

test("a step from a newer build decodes as unknown rather than as something else", () => {
  assert.deepEqual(decodeHandoffSignal({ handoff: "resume", from: 41 }), { step: "unknown" });
  assert.deepEqual(decodeHandoffSignal({}), { step: "unknown" });
  assert.deepEqual(decodeHandoffSignal(null), { step: "unknown" });
  assert.deepEqual(decodeTogetherSignal({ share: "resume" }), { step: "unknown" });
});

test("a refusal always carries a reason, so the far end is never left guessing", () => {
  assert.deepEqual(encodeHandoffStep({ step: "refuse" }), {
    handoff: "refuse",
    reason: { kind: "path_unknown" },
  });
  assert.deepEqual(encodeTogetherStep({ step: "refuse" }), {
    share: "refuse",
    reason: { kind: "path_unknown" },
  });
});

// ── Who may say what ─────────────────────────────────────────────────────────

test("a peer cannot play our own side of the transfer", () => {
  // Mirrors HandoffSignal::is_sender_only / is_receiver_only.
  assert.equal(peerStepIsPlausible("sender", "offer"), false);
  assert.equal(peerStepIsPlausible("sender", "withdraw"), false);
  assert.equal(peerStepIsPlausible("sender", "accept"), true);
  assert.equal(peerStepIsPlausible("sender", "decline"), true);
  assert.equal(peerStepIsPlausible("receiver", "accept"), false);
  assert.equal(peerStepIsPlausible("receiver", "decline"), false);
  assert.equal(peerStepIsPlausible("receiver", "offer"), true);
  assert.equal(peerStepIsPlausible("receiver", "withdraw"), true);
});

test("ice and refusals belong to neither side, because both genuinely flow both ways", () => {
  for (const role of ["sender", "receiver"]) {
    assert.equal(peerStepIsPlausible(role, "transport"), true);
    assert.equal(peerStepIsPlausible(role, "refuse"), true);
  }
});

test("a signal naming another transfer cannot reach the live one", () => {
  const live = { kind: "handoff", transferId: "abc" };
  assert.equal(signalIsForTransfer(live, "abc"), true);
  assert.equal(signalIsForTransfer(live, "abd"), false, "the id is the replay guard");
  assert.equal(signalIsForTransfer(live, ""), false);
  assert.equal(signalIsForTransfer(live, null), false);
  assert.equal(signalIsForTransfer(null, "abc"), false, "no transfer, nothing to deliver to");
  assert.equal(
    signalIsForTransfer({ kind: "together", transferId: "abc" }, "abc"),
    false,
    "a handoff signal must not steer a watch-together handover",
  );
});

// ── Peer-chosen text on its way to a screen ──────────────────────────────────

test("a peer's filename is never drawn as a path", () => {
  assert.equal(safeDisplayName("../../etc/passwd"), "passwd");
  assert.equal(safeDisplayName("C:\\Windows\\System32\\evil.dll"), "evil.dll");
  assert.equal(safeDisplayName("holiday.mp4"), "holiday.mp4");
  assert.equal(safeDisplayName("/"), "Unnamed file");
  assert.equal(safeDisplayName("../.."), "Unnamed file");
  assert.equal(safeDisplayName(""), "Unnamed file");
  assert.equal(safeDisplayName(null), "Unnamed file");
});

test("a filename cannot forge extra lines of card, or a fake extension", () => {
  assert.equal(safeDisplayName("a.mp4\nAccept: yes"), "a.mp4Accept: yes");
  assert.equal(safeDisplayName("a\u0000b.mp4"), "ab.mp4");
  // U+202E makes `holiday<RLO>gnp.exe` read as `holiday.png` where it is honoured.
  assert.equal(safeDisplayName("holiday\u202Egnp.exe"), "holidaygnp.exe");
  assert.equal(safeDisplayName("a\u200Eb\u2066c.mp4"), "abc.mp4");
});

test("a very long filename is bounded rather than allowed to push the card apart", () => {
  const drawn = safeDisplayName(`${"n".repeat(400)}.mp4`);
  assert.equal(drawn.length, MAX_DISPLAY_NAME_CHARS);
  assert.ok(drawn.endsWith("…"));
});

test("a peer's caption is bounded and stripped, because it is drawn verbatim", () => {
  assert.equal(boundedCaption("  hello  "), "hello");
  assert.equal(boundedCaption("one\ntwo"), "one two", "no forged second line");
  assert.equal(boundedCaption("a\u202Eb"), "a b");
  assert.equal(boundedCaption(null), "");
  assert.equal(boundedCaption("x".repeat(MAX_CAPTION_LENGTH + 40)).length, MAX_CAPTION_LENGTH);
});

// ── An incoming offer ────────────────────────────────────────────────────────

test("a well-formed offer is acceptable and reads as what it is", () => {
  const plan = offerCardPlan(attachment());
  assert.equal(plan.canAccept, true);
  assert.equal(plan.refusal, null);
  assert.equal(plan.kind, "Video");
  assert.equal(plan.glyph, "🎬");
  assert.equal(plan.name, "holiday.mp4");
  assert.equal(plan.sizeText, "50 MB");
  assert.equal(plan.caption, "the last morning");
});

test("an offer whose mime type shouts is still recognised", () => {
  // A peer sending IMAGE/JPEG used to miss every frontend's startsWith check.
  assert.equal(offerCardPlan(attachment({ mime_type: "IMAGE/JPEG" })).kind, "Photo");
  assert.equal(offerCardPlan(attachment({ mime_type: "" })).kind, "File");
});

test("an offer bigger than this window can hold is refused, with no Accept button", () => {
  const plan = offerCardPlan(attachment({ shape: { total_bytes: 4 * 1024 * 1024 * 1024 } }));
  assert.equal(plan.canAccept, false, "no fake affordance for a road that cannot work");
  assert.match(plan.refusal, /this window can hold/);
  assert.match(plan.refusal, /256 MB/);
  // The size is still named, so the person can see what they are being denied.
  assert.equal(plan.sizeText, "4096 MB");
});

test("the ceiling is inclusive, and one byte over it is not", () => {
  assert.equal(handoffOfferRefusal(attachment({ shape: { total_bytes: MAX_HANDOFF_BYTES } })), null);
  assert.ok(handoffOfferRefusal(attachment({ shape: { total_bytes: MAX_HANDOFF_BYTES + 1 } })));
});

test("an offer with no usable fingerprint is refused, because nothing could verify it", () => {
  for (const sha256 of ["", "zz", "a".repeat(63), "a".repeat(65), "z".repeat(64), null]) {
    assert.ok(
      handoffOfferRefusal(attachment({ shape: { sha256 } })),
      `accepted a bogus hash: ${sha256}`,
    );
  }
  // Case and surrounding space are not an attack.
  assert.equal(handoffOfferRefusal(attachment({ shape: { sha256: ` ${"B".repeat(64)} ` } })), null);
  assert.equal(isContentHash("A".repeat(64)), true);
  assert.equal(isContentHash("../../etc/passwd"), false);
});

test("a tiny chunk size is refused before it can allocate the tab to death", () => {
  // 256 MB in one-byte chunks is 256 million tracker slots.
  assert.match(handoffOfferRefusal(attachment({ shape: { chunk_bytes: 1 } })), /chunk size/);
  assert.match(handoffOfferRefusal(attachment({ shape: { chunk_bytes: 0 } })), /chunk size/);
  assert.match(
    handoffOfferRefusal(attachment({ shape: { chunk_bytes: 8 * 1024 * 1024 } })),
    /chunk size/,
  );
  assert.equal(handoffOfferRefusal(attachment({ shape: { chunk_bytes: MIN_OFFER_CHUNK_BYTES } })), null);
});

test("the two bounds together keep the tracker's allocation small", () => {
  // The pin that makes the chunk-size floor mean something: the worst case an
  // acceptable offer can ask for is tens of thousands of slots, not millions.
  const worst = chunkCount({ total_bytes: MAX_HANDOFF_BYTES, chunk_bytes: MIN_OFFER_CHUNK_BYTES });
  assert.equal(worst, 65536);
});

test("an empty or incomplete offer is refused rather than started", () => {
  assert.match(handoffOfferRefusal(attachment({ shape: { total_bytes: 0 } })), /empty/);
  assert.match(handoffOfferRefusal(attachment({ shape: { total_bytes: -1 } })), /empty/);
  assert.match(handoffOfferRefusal(attachment({ shape: { total_bytes: "lots" } })), /empty/);
  assert.match(handoffOfferRefusal({ mime_type: "video/mp4" }), /incomplete/);
  assert.match(handoffOfferRefusal(null), /incomplete/);
});

test("an offer's own text cannot escape the card", () => {
  const plan = offerCardPlan(
    attachment({ file_name: "../../.ssh/authorized_keys", caption: "yes\nAccepted" }),
  );
  assert.equal(plan.name, "authorized_keys");
  assert.equal(plan.caption, "yes Accepted");
});

// ── Sending ──────────────────────────────────────────────────────────────────

test("the road comes from the core's answer, not from a size compared here", () => {
  assert.deepEqual(attachmentSendPlan({ bytes: 4 * 1024 * 1024, route: "hosted" }), {
    road: "hosted",
    refusal: null,
  });
  assert.deepEqual(attachmentSendPlan({ bytes: 40 * 1024 * 1024, route: "peer_to_peer" }), {
    road: "peer_to_peer",
    refusal: null,
  });
});

test("no answer from the core is not quietly treated as the hosted road", () => {
  // The two roads make different promises about whether the recipient has to be
  // online, so guessing one would tell the person something untrue.
  const plan = attachmentSendPlan({ bytes: 1024, route: undefined });
  assert.equal(plan.road, null);
  assert.match(plan.refusal, /Couldn't work out/);
});

test("the shared 10 MB rule still refuses on the hosted road, and only there", () => {
  const hostedRefusal = '"a.png" is 11 MB — attachments are limited to 10 MB.';
  assert.deepEqual(attachmentSendPlan({ bytes: 11 * 1024 * 1024, route: "hosted", hostedRefusal }), {
    road: null,
    refusal: hostedRefusal,
  });
  // The same file on the peer-to-peer road is exactly what this feature exists
  // for, so the mirrored rule must not be consulted there.
  assert.deepEqual(
    attachmentSendPlan({ bytes: 11 * 1024 * 1024, route: "peer_to_peer", hostedRefusal }),
    { road: "peer_to_peer", refusal: null },
  );
});

test("an empty file is refused on either road", () => {
  for (const route of ["hosted", "peer_to_peer"]) {
    assert.match(attachmentSendPlan({ bytes: 0, route }).refusal, /empty/);
  }
});

test("sending is capped by the same ceiling as receiving, and says why", () => {
  const plan = attachmentSendPlan({ bytes: 900 * 1024 * 1024, route: "peer_to_peer" });
  assert.equal(plan.road, null);
  assert.match(plan.refusal, /fingerprint/);
  assert.match(plan.refusal, /256 MB/);
  assert.equal(attachmentSendPlan({ bytes: MAX_HANDOFF_BYTES, route: "peer_to_peer" }).road, "peer_to_peer");
});

test("the preview names the road, and what each road costs", () => {
  const hosted = previewRouteLine("hosted", 3 * 1024 * 1024);
  assert.match(hosted, /^3 MB · /);
  assert.match(hosted, /even if they're away/);
  const direct = previewRouteLine("peer_to_peer", 300 * 1024 * 1024);
  assert.match(direct, /^300 MB · /);
  assert.match(direct, /online now/);
  assert.match(direct, /direct route/);
  // No route yet: the size, and no promise either way.
  assert.equal(previewRouteLine(null, 1024), "1 KB");
});

test("a transfer id is 128 bits of hex, from the randomness it is handed", () => {
  const id = newTransferId(() => new Uint8Array(16).fill(0xab));
  assert.equal(id, "ab".repeat(16));
  assert.equal(id.length, 32);
  const real = newTransferId((n) => crypto.getRandomValues(new Uint8Array(n)));
  assert.match(real, /^[0-9a-f]{32}$/);
  assert.notEqual(real, newTransferId((n) => crypto.getRandomValues(new Uint8Array(n))));
});

// ── The two honest failures ──────────────────────────────────────────────────

test("a comrade known to be away blocks the send instead of spinning", () => {
  const plan = handoffPresencePlan({ comrade: true, online: false });
  assert.equal(plan.blocked, true);
  assert.match(plan.warning, /nothing holds it for later/);
  assert.match(plan.warning, /under 10 MB/);
});

test("a comrade who is here blocks nothing and warns about nothing", () => {
  assert.deepEqual(handoffPresencePlan({ comrade: true, online: true }), {
    blocked: false,
    warning: null,
  });
});

test("not knowing whether they are online warns, and does not block", () => {
  // Presence exists only for comrades, so for everyone else blocking would mean
  // refusing a send on a fact this device does not have.
  for (const presence of [null, undefined, { comrade: false, online: false }]) {
    const plan = handoffPresencePlan(presence);
    assert.equal(plan.blocked, false);
    assert.match(plan.warning, /If they're not at their device/);
  }
});

test("a refused path says what would work instead", () => {
  const plan = describeHandoffVerdict({ verdict: "refuse", reason: { kind: "relay_forbidden" } });
  assert.equal(plan.proceed, false);
  assert.match(plan.message, /No direct route/);
  assert.match(plan.message, /hosted road/);
  assert.match(plan.message, new RegExp(`${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB`));
});

test("an allowed path is not decorated with advice, and neither is an unsettled one", () => {
  assert.deepEqual(describeHandoffVerdict({ verdict: "allow" }), { proceed: true, message: null });
  const settling = describeHandoffVerdict({ verdict: "refuse", reason: { kind: "path_unknown" } });
  assert.equal(settling.retryable, true);
  assert.doesNotMatch(settling.message, /hosted road/);
});

test("progress reads from the tracker's fraction, and cannot read past the ends", () => {
  assert.equal(progressLabel("receiver", 0), "Receiving — 0%");
  assert.equal(progressLabel("receiver", 0.414), "Receiving — 41%");
  assert.equal(progressLabel("sender", 1), "Sending — 100%");
  assert.equal(progressLabel("sender", 1.5), "Sending — 100%");
  assert.equal(progressLabel("sender", -1), "Sending — 0%");
  assert.equal(progressLabel("sender", NaN), "Sending — 0%");
});
