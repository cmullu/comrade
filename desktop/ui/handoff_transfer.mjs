/**
 * Handing a large attachment to the other device — every decision, none of the DOM.
 *
 * `share_transfer.mjs` already holds the pump: chunking, framing, the tracker,
 * the flow-control watermarks and the path verdict. All of that is protocol- and
 * feature-agnostic and is reused here unchanged. What this file adds is the two
 * things that are *not* the pump:
 *
 *  1. **The seam between two protocols.** A `together` handover and an attachment
 *     handoff move bytes identically and say so differently: `together`'s steps
 *     ride inside `{"share":…}` scoped to a session, a handoff's inside
 *     `{"handoff":…}` scoped to a transfer id. The driver in `main.js` speaks
 *     neither — it emits abstract steps and one of the codecs below encodes them.
 *     The two codecs sit next to each other on purpose: a change to the driver
 *     cannot quietly serve one encoding and break the other, and the shipping
 *     `together` feature is not asked to trust a reading of a diff.
 *  2. **What to believe from the other end.** Every field of an incoming offer is
 *     peer-chosen — `total_bytes`, `sha256`, `file_name`, `mime_type`, `caption` —
 *     so each one is checked here, once, with tests, rather than at the call site
 *     that happens to render it.
 *
 * ## Why this window has a ceiling and Android does not
 *
 * `SubtleCrypto` has no streaming digest. Both ends therefore touch the whole
 * file in memory exactly once — the sender to fingerprint what it is offering,
 * the receiver to check that what arrived is what was offered — and there is no
 * way around it inside a webview: `crypto.subtle.digest` takes a buffer, not a
 * stream. The Tauri side has a filesystem and `sha2`, and Android stages to disk
 * under `handoff::staged_file_name`; this window has neither.
 *
 * So the desktop states a ceiling instead of pretending it has none, and the
 * alternative is *not* to spool the plaintext into the app data directory:
 * AUDIT S-4 is the rule that decrypted media must not be left on disk, and a
 * `.part` file nobody asked for is exactly that. The exit condition for the
 * ceiling is a Rust-side streaming digest writing into a destination the person
 * actually picked — a deliberate save rather than a cache, which S-4 permits and
 * which would also lift the sender's half.
 */

import {
  MAX_ATTACHMENT_BYTES,
  MAX_CAPTION_LENGTH,
  attachmentRejection,
  formatAttachmentSize,
  mediaKindGlyph,
  mediaKindLabel,
  normalizeCaption,
  peerToPeerAttachmentRejection,
} from "./attachment_caption.mjs";
import { UNSAFE_TEXT } from "./display_text.mjs";
import { describeVerdict } from "./share_transfer.mjs";

/**
 * The largest attachment this window will send or accept on the peer-to-peer
 * road — 256 MiB.
 *
 * Not a protocol limit: `comrade_core::handoff` deliberately has none, and a
 * phone staging to disk can carry a 4 GB film. This is the webview's own, and it
 * is stated rather than discovered — a 4 GB offer accepted here would allocate
 * the file twice (once as the received bytes, once as the contiguous buffer
 * `crypto.subtle.digest` needs) and take the tab down mid-transfer, which is a
 * worse answer than "no" arriving in one second.
 */
export const MAX_HANDOFF_BYTES = 256 * 1024 * 1024;

/**
 * The narrowest and widest chunk size an offer may name.
 *
 * A bound in *both* directions, and the lower one is the load-bearing half: the
 * tracker allocates one slot per chunk, so a peer offering 256 MB in one-byte
 * chunks asks this window for 256 million slots. That is an out-of-memory before
 * a single byte arrives, not a failed transfer. `comrade_core::share`'s own
 * chunk is 16 KiB; the range is wide enough that changing it is not a protocol
 * break, and narrow enough that neither end can be made to allocate absurdly.
 */
export const MIN_OFFER_CHUNK_BYTES = 4 * 1024;
export const MAX_OFFER_CHUNK_BYTES = 1024 * 1024;

/** The longest peer-chosen filename this UI will draw. */
export const MAX_DISPLAY_NAME_CHARS = 64;

// ── The seam: abstract steps, and the two encodings of them ──────────────────
//
// A step is `{ step, … }` where `step` is one of:
//   "ask" | "offer" | "accept" | "decline" | "refuse" | "withdraw" | "transport"
// plus whatever that step carries. Each codec refuses the steps its own protocol
// cannot express — `together` has no decline and no withdraw, a handoff has no
// `ask` because the sender picked the file — by returning `null`, so an
// impossible step is dropped at the boundary instead of being put on the wire as
// something the far end will not understand.

/** Encode an abstract step as a `comrade_core::share::ShareSignal`. */
export function encodeTogetherStep(step) {
  switch (step?.step) {
    case "ask":
      return { share: "ask" };
    case "offer":
      return step.offer ? { share: "offer", offer: step.offer } : null;
    case "accept":
      return { share: "accept" };
    case "refuse":
      return { share: "refuse", reason: step.reason ?? { kind: "path_unknown" } };
    case "transport":
      return step.signal ? { share: "transport", signal: step.signal } : null;
    default:
      // Including "decline" and "withdraw": a together handover has neither, and
      // inventing a spelling for them here would put a step on the wire that the
      // Rust enum cannot parse.
      return null;
  }
}

/** Encode an abstract step as a `comrade_core::handoff::HandoffSignal`. */
export function encodeHandoffStep(step) {
  switch (step?.step) {
    case "offer":
      return step.attachment ? { handoff: "offer", attachment: step.attachment } : null;
    case "accept":
      return { handoff: "accept" };
    case "decline":
      return { handoff: "decline" };
    case "refuse":
      return { handoff: "refuse", reason: step.reason ?? { kind: "path_unknown" } };
    case "withdraw":
      return { handoff: "withdraw" };
    case "transport":
      return step.signal ? { handoff: "transport", signal: step.signal } : null;
    default:
      // "ask" included: the sender picked the file, so nobody discovers who
      // wants what.
      return null;
  }
}

/** Read a `ShareSignal` off the wire as an abstract step. */
export function decodeTogetherSignal(signal) {
  switch (signal?.share) {
    case "ask":
      return { step: "ask" };
    case "offer":
      return { step: "offer", offer: signal.offer };
    case "accept":
      return { step: "accept" };
    case "refuse":
      return { step: "refuse", reason: signal.reason };
    case "transport":
      return { step: "transport", signal: signal.signal };
    default:
      return { step: "unknown" };
  }
}

/** Read a `HandoffSignal` off the wire as an abstract step. */
export function decodeHandoffSignal(signal) {
  switch (signal?.handoff) {
    case "offer":
      return { step: "offer", attachment: signal.attachment };
    case "accept":
      return { step: "accept" };
    case "decline":
      return { step: "decline" };
    case "refuse":
      return { step: "refuse", reason: signal.reason };
    case "withdraw":
      return { step: "withdraw" };
    case "transport":
      return { step: "transport", signal: signal.signal };
    default:
      // A step from a newer build. Named as unknown rather than guessed at.
      return { step: "unknown" };
  }
}

/**
 * Whether a step could legitimately have come from the peer, given the role we
 * hold in this transfer.
 *
 * The JS twin of `HandoffSignal::is_sender_only` / `is_receiver_only`, and
 * checked for the same reason: a peer sending its own `offer` for a transfer we
 * started is confused or probing, and either way the answer is to drop it.
 * `transport` and `refuse` belong to neither side — ICE genuinely flows both
 * ways, and either end's network can be the one that gave up.
 */
export function peerStepIsPlausible(localRole, step) {
  const senderOnly = step === "offer" || step === "withdraw" || step === "ask";
  const receiverOnly = step === "accept" || step === "decline";
  if (localRole === "sender") return !senderOnly;
  if (localRole === "receiver") return !receiverOnly;
  return true;
}

/**
 * Whether a signal belongs to the transfer this window is running.
 *
 * The transfer id is the replay guard (`comrade_core::handoff` mints 128 bits of
 * it for exactly that reason), so a signal naming a transfer we do not have is
 * dropped rather than allowed to revive an abandoned one — including a late
 * `accept` for a transfer the sender already withdrew.
 */
export function signalIsForTransfer(session, transferId) {
  if (!session || !transferId) return false;
  return session.kind === "handoff" && session.transferId === transferId;
}

// ── Peer-chosen text, on its way to a screen ────────────────────────────────

// The character class this file used to own now lives in `display_text.mjs`,
// because a profile page draws a peer's name and bio at heading size and needed
// the same rule. Two maintained copies of a security-relevant character class
// eventually differ, and the difference would be invisible until someone used it.

/**
 * A peer's filename, made safe to *draw*. Never safe to use as a path, and
 * nothing in this frontend has a path to use it in — the webview has no
 * filesystem, and the one place a name reaches disk is the browser's own
 * download, which does its own sanitising.
 *
 * Reduced to a last path segment anyway: a name rendered as `../../etc/passwd`
 * is a claim about where the file is going, and it is not true.
 */
export function safeDisplayName(name) {
  const flat = String(name ?? "").replace(UNSAFE_TEXT, "");
  const segments = flat.split(/[/\\]/).filter((p) => p && p !== "." && p !== "..");
  const base = (segments.pop() ?? "").trim();
  if (!base) return "Unnamed file";
  if (base.length <= MAX_DISPLAY_NAME_CHARS) return base;
  return `${base.slice(0, MAX_DISPLAY_NAME_CHARS - 1)}…`;
}

/**
 * A peer's caption, bounded to what the core would have kept and stripped of the
 * characters that would let it forge the rest of the card.
 *
 * The hosted path gets this sanitising in `comrade_ui`, which bounds and strips
 * a caption before it is ever stored. A handoff offer is handed to the frontend
 * as it arrived, so the same rule has to hold here.
 */
export function boundedCaption(text) {
  return normalizeCaption(String(text ?? "").replace(UNSAFE_TEXT, " ")).slice(0, MAX_CAPTION_LENGTH);
}

// ── An incoming offer ───────────────────────────────────────────────────────

/** Whether a `sha256` could be the digest of anything. Mirrors `staged_file_name`. */
export function isContentHash(sha256) {
  const hash = String(sha256 ?? "")
    .trim()
    .toLowerCase();
  return hash.length === 64 && /^[0-9a-f]{64}$/.test(hash);
}

/**
 * Why an offer cannot be accepted by *this* frontend, or null when it can.
 *
 * Every branch is a peer-supplied field failing a check, and the order matters
 * only in what the person is told first. A malformed offer is refused before the
 * card offers a button, because a button that cannot work is worse than no
 * button.
 */
export function handoffOfferRefusal(attachment, { ceiling = MAX_HANDOFF_BYTES } = {}) {
  const shape = attachment?.shape;
  const total = Number(shape?.total_bytes);
  const chunk = Number(shape?.chunk_bytes);
  if (!attachment || !shape) return "That offer arrived incomplete.";
  if (!Number.isSafeInteger(total) || total <= 0) return "That offer describes an empty file.";
  if (!Number.isSafeInteger(chunk) || chunk < MIN_OFFER_CHUNK_BYTES || chunk > MAX_OFFER_CHUNK_BYTES)
    return "That offer asks for a chunk size this app doesn't use.";
  if (!isContentHash(shape.sha256))
    return "That offer carries no usable fingerprint, so there'd be no way to tell if it arrived intact.";
  if (total > ceiling) {
    return (
      `${formatAttachmentSize(total)} is more than this window can hold — it has to ` +
      `fit the whole file in memory to check it arrived intact, so the desktop stops ` +
      `at ${formatAttachmentSize(ceiling)}.`
    );
  }
  return null;
}

/**
 * Everything the incoming-offer card draws, from an offer none of whose fields
 * are ours.
 *
 * `canAccept` is the whole point of returning this as data: the card must not
 * render an Accept button for an offer that cannot complete, and that decision
 * is testable here rather than inferred from whether a `disabled` attribute got
 * set in a DOM builder.
 */
export function offerCardPlan(attachment, { ceiling = MAX_HANDOFF_BYTES } = {}) {
  const refusal = handoffOfferRefusal(attachment, { ceiling });
  const mime = String(attachment?.mime_type ?? "")
    .trim()
    .toLowerCase();
  const total = Number(attachment?.shape?.total_bytes) || 0;
  return {
    canAccept: refusal === null,
    refusal,
    kind: mediaKindLabel(mime),
    glyph: mediaKindGlyph(mime),
    name: safeDisplayName(attachment?.file_name),
    sizeText: formatAttachmentSize(total),
    caption: boundedCaption(attachment?.caption),
  };
}

// ── Sending ─────────────────────────────────────────────────────────────────

/**
 * Which road a picked file takes, and whether it can go at all.
 *
 * `route` comes from the core (`attachment_route_for_bytes`) rather than from a
 * comparison here, because the threshold *is* the hosted ceiling and a frontend
 * holding its own copy of 10 MB is a frontend that disagrees the day it moves.
 *
 * **Each road's refusal comes from the mirrored trio, not from a second spelling
 * of it here.** `attachmentRejection` owns the hosted road's rules and
 * `peerToPeerAttachmentRejection` the direct road's — both of them live in
 * `attachment_caption.mjs` beside their Kotlin and Dart twins, so an empty file
 * is refused in the same words on every frontend. What this function adds is the
 * routing and the one rule that is *not* shared: `ceiling`, which exists because
 * this window has no disk (see MAX_HANDOFF_BYTES) and which must therefore never
 * migrate into the mirrored rule, where it would wrongly cap Android too.
 */
export function attachmentSendPlan({
  bytes,
  route,
  name = "",
  ceiling = MAX_HANDOFF_BYTES,
  surfaceSupportsHandoff = true,
}) {
  const n = Number(bytes) || 0;
  if (route === "peer_to_peer") {
    const shared = peerToPeerAttachmentRejection(name, n);
    if (shared) return { road: null, refusal: shared };
    // The partner panel has no transfer card and no progress, so offering the
    // road there would be a button with nowhere to report to — the same "no fake
    // affordances" rule that hides Accept on an offer this window cannot take.
    if (!surfaceSupportsHandoff) {
      return {
        road: null,
        refusal:
          `${formatAttachmentSize(n)} is too big to host, so it has to go straight to ` +
          `their device — open the conversation to send it, where the transfer can ` +
          `show you how it is going.`,
      };
    }
    if (n > ceiling) {
      return {
        road: null,
        refusal:
          `${formatAttachmentSize(n)} is more than this window can hold — it has to fit ` +
          `the whole file in memory to fingerprint it, so the desktop stops at ` +
          `${formatAttachmentSize(ceiling)}.`,
      };
    }
    return { road: "peer_to_peer", refusal: null };
  }
  if (route === "hosted") {
    const shared = attachmentRejection(name, n);
    return { road: shared ? null : "hosted", refusal: shared };
  }
  // No route from the core is not "probably hosted": the two roads have
  // different failure modes and guessing one would tell the person the wrong
  // thing about whether the recipient has to be online.
  return { road: null, refusal: "Couldn't work out how this file would be sent." };
}

/**
 * The line the preview sheet shows about the road, before anything is sent.
 *
 * Both roads get a sentence, because "encrypted and uploaded" and "straight to
 * their device, while they are at it" are different promises and the sender is
 * about to make one of them.
 */
export function previewRouteLine(route, bytes) {
  const size = formatAttachmentSize(Number(bytes) || 0);
  if (route === "hosted") {
    return `${size} · encrypted and uploaded, so it reaches them even if they're away.`;
  }
  if (route === "peer_to_peer") {
    return (
      `${size} · too big to host, so it goes straight to their device. ` +
      `They have to be online now, and there has to be a direct route between you.`
    );
  }
  return size;
}

/**
 * A fresh transfer id: 128 bits, hex. The twin of
 * `comrade_core::handoff::new_transfer_id`, and unguessable for the same reason —
 * an id a third party could predict would let them inject an `accept` and start
 * a transfer nobody asked for.
 *
 * The randomness is injected so this is testable without asserting anything
 * about a particular runtime's `crypto`.
 */
export function newTransferId(randomBytes) {
  const bytes = randomBytes(16);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * What presence means for a transfer that has no store-and-forward.
 *
 * Three answers rather than two, because "we know they are away" and "we have no
 * idea" are different: presence exists only for comrades, so for everyone else
 * the honest thing is to warn and proceed, not to block a send on a fact this
 * device does not have.
 */
export function handoffPresencePlan(presence) {
  if (presence && presence.comrade && presence.online) return { blocked: false, warning: null };
  if (presence && presence.comrade) {
    return {
      blocked: true,
      warning:
        "They're not at their device right now. A file this size goes straight " +
        "between the two of you, so nothing holds it for later — try again when " +
        "they're online, or send something under 10 MB.",
    };
  }
  return {
    blocked: false,
    warning:
      "If they're not at their device right now this won't arrive — nothing holds " +
      "a file this size for later.",
  };
}

/**
 * The path verdict, with the one thing a *handoff* can do about it that a
 * together handover cannot: fall back to the hosted road.
 *
 * `describeVerdict` already says why a path was refused. This adds the way out,
 * and only where there is one — there is no point telling someone to send a
 * smaller file while ICE is still settling.
 */
export function describeHandoffVerdict(verdict) {
  const plan = describeVerdict(verdict);
  if (plan.proceed || plan.retryable || plan.needsConsent) return plan;
  return {
    ...plan,
    message:
      `${plan.message} Anything under ${formatAttachmentSize(MAX_ATTACHMENT_BYTES)} ` +
      `takes the hosted road instead, which needs neither of you online at the same time.`,
  };
}

/** "Sending — 41%" / "Receiving — 41%", from the tracker's own fraction. */
export function progressLabel(role, fraction) {
  const pct = Math.max(0, Math.min(100, Math.round((Number(fraction) || 0) * 100)));
  return `${role === "sender" ? "Sending" : "Receiving"} — ${pct}%`;
}
