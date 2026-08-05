/**
 * Decisions behind the desktop composer's in-chat commands — the desktop half
 * of what `comrade_core::command` parses.
 *
 * ## Why this is its own module
 *
 * `main.js` is a classic script full of DOM glue and cannot be unit tested, so
 * the parts that *decide* something live here and `node --test` covers them —
 * the split `focus_view.mjs`, `together_sync.mjs` and `call_decisions.mjs`
 * already use. "It's only a couple of lines in the input handler" is exactly how
 * the rest of this UI became untestable.
 *
 * ## What is *not* here
 *
 * **The grammar.** Which words are commands, what `@handle` means, and how a
 * free-text query becomes a recording are all `comrade_core::command`'s answers,
 * reached over the Tauri bridge (`parse_chat_command`, `resolve_mentions`,
 * `play_query`). Nothing in this file re-parses composer text — a second grammar
 * is precisely the failure `/pay` already demonstrates, having drifted across
 * four implementations.
 *
 * What *is* here is the part that is genuinely the desktop's own: given a parsed
 * command, what should this window do, and what should it say — including the
 * cases where the honest answer is "not here yet".
 */

/** Nothing to do; send the text as typed. */
export const SEND = "send";
/** Route to `tara_aside`, never to `send_dm`. `/tara`. */
export const ASIDE = "aside";
/**
 * Route to `tara_in_chat` — `@tara`, which the peer sees.
 *
 * A separate action from {@link ASIDE} because the audience is different, and a
 * composer that treated them the same would either publish a private thought or
 * hide an answer the other person was waiting for.
 */
export const TARA_HERE = "tara_here";
/** Route to `assign_task`. */
export const TASK = "task";
/** Route to `offer_action`. */
export const OFFER = "offer";
/** Route to a `/play` flow. */
export const PLAY = "play";
/** Open a local screen. */
export const OPEN = "open";
/** Show the command list. */
export const HELP = "help";
/** Refuse, and say why — the command cannot work from this window. */
export const BLOCKED = "blocked";
/** Refuse, and say what is missing — the command is incomplete. */
export const INCOMPLETE = "incomplete";
/**
 * Two contacts answer to one `@handle`; ask which before doing anything.
 *
 * Not {@link INCOMPLETE}: that said "pick which one" and offered nothing to pick,
 * which is a dead end dressed as an instruction. This carries the candidates so
 * the composer can show them and re-run the same draft (see {@link withChoices}).
 */
export const CHOOSE = "choose";

/**
 * Screens this desktop build actually has.
 *
 * `breathe`, `focus`, `journal`, `read` and `tara` all exist on Android; on
 * desktop only Focus and the reader shipped (`docs/ATTENTION.md` §7,
 * `docs/FRONTEND_STRATEGY.md`'s divergence table). Listing what is *here*
 * rather than assuming parity is what lets `/journal` say "not on desktop yet"
 * instead of opening nothing and looking broken.
 */
export const DESKTOP_SCREENS = new Set(["focus", "read"]);

/**
 * Whether this window can *play* a together session.
 *
 * **True since 2026-08-05**, and the exit condition it was written against is
 * the one that was met: the `<video>` element, the file picker, and the
 * `play_query`/`play_route` commands are all in place, so `/play` can open a
 * session here rather than answering "there is no player here yet" — which had
 * become false while still being said. `desktop/ui/play_flow.mjs` decides what
 * each route does with it.
 *
 * Kept as a named constant rather than deleted: `PlayRoute::PlayEmbed` still
 * has nowhere to go in this window (no iframe — `docs/TOGETHER.md` §7 carries
 * the CSP condition), so "this window can play" remains a real question with a
 * real answer, and `planPlay` refuses that one route by name.
 */
export const DESKTOP_CAN_PLAY = true;

/**
 * What the composer should do with a parsed command.
 *
 * Takes the `ChatCommand` as the bridge delivers it (serde-tagged, so
 * `{ kind: "task", text, assignees }`) and returns
 * `{ action, message, ...payload }`. `message` is always present for the two
 * refusing actions and otherwise omitted.
 *
 * The one rule worth stating: **an unknown command never sends.** A message
 * silently delivered as `/frobnicate the thing` is a message the other person
 * gets confused by; a message silently *swallowed* is worse. So it is reported.
 */
export function planFor(command, { mentions = [] } = {}) {
  if (!command || typeof command.kind !== "string") return { action: SEND };
  switch (command.kind) {
    case "plain":
      return { action: SEND };

    case "help":
      return { action: HELP };

    case "pay":
      // `/pay` is recognised so it is not called a typo, but the composer sends
      // it as text exactly as it always has — `extract_payments` draws the chip
      // and the peer's own client detects the intent on receipt.
      return { action: SEND };

    case "ask_tara": {
      const text = (command.text || "").trim();
      if (!text) {
        return {
          action: INCOMPLETE,
          message: "Say what you want to think through — only you will see it.",
        };
      }
      return { action: ASIDE, text };
    }

    case "tara_here": {
      const text = (command.text || "").trim();
      if (!text) {
        return {
          action: INCOMPLETE,
          message: "Ask her something — you'll both see her answer.",
        };
      }
      return { action: TARA_HERE, text };
    }

    case "task": {
      const text = (command.text || "").trim();
      if (!text) {
        return { action: INCOMPLETE, message: "What needs doing?" };
      }
      const targets = resolvedTargets(command.assignees, mentions);
      if (targets.problem) return targets.problem;
      // No target is a note to self, which is the common case and needs no
      // resolution at all.
      return { action: TASK, text, peer: targets.npubs[0] ?? null };
    }

    case "offer_to": {
      const action = command.action;
      const targets = resolvedTargets(command.targets, mentions);
      if (!command.targets || command.targets.length === 0) {
        return {
          action: INCOMPLETE,
          message: "Name the comrade you want to offer this to, like @ana.",
        };
      }
      if (targets.problem) return targets.problem;
      return { action: OFFER, appAction: action, peers: targets.npubs };
    }

    case "open": {
      const key = command.action;
      if (!DESKTOP_SCREENS.has(key)) {
        return {
          action: BLOCKED,
          message: `${labelFor(key)} is on the phone app, not here yet.`,
        };
      }
      return { action: OPEN, appAction: key };
    }

    case "play":
      if (!DESKTOP_CAN_PLAY) {
        return {
          action: BLOCKED,
          message: "Listening together works on the phone app; there is no player here yet.",
        };
      }
      return { action: PLAY, query: command.query || "", service: command.service ?? null };

    case "unknown":
      return {
        action: INCOMPLETE,
        message: `There is no /${command.name} — type / to see what there is.`,
      };

    default:
      // A command this build does not know about: send nothing, say nothing
      // wrong. Reached when the Rust grammar gains a variant before this file
      // does, which is a release-skew case rather than a bug.
      return { action: SEND };
  }
}

/**
 * Turn parsed mentions into npubs, or explain why we cannot.
 *
 * The ambiguous case is the one that matters: two contacts can answer to one
 * handle, because a handle is a self-declared alias and anyone may publish any
 * name. Picking one is how a private message reaches the wrong person, so it
 * comes back as a question.
 */
function resolvedTargets(parsed, mentions) {
  const npubs = [];
  for (const m of parsed || []) {
    const match = mentions.find((r) => r.handle === m.handle);
    if (!match || (!match.npub && (!match.candidates || match.candidates.length === 0))) {
      return {
        npubs,
        problem: {
          action: INCOMPLETE,
          message: `@${m.handle} isn't in your contacts.`,
        },
      };
    }
    if (!match.npub) {
      // The answer exists; only the user has it. One handle at a time — a draft
      // naming two ambiguous handles would otherwise raise a chooser that can be
      // half-answered, and the next one is asked about on the re-run.
      return {
        npubs,
        problem: {
          action: CHOOSE,
          handle: m.handle,
          candidates: match.candidates,
          message: `Two contacts answer to @${m.handle} — which one?`,
        },
      };
    }
    npubs.push(match.npub);
  }
  return { npubs, problem: null };
}

/**
 * Every action key the bridge can send, i.e. `AppAction::key()` in Rust.
 *
 * Exported so a test can assert {@link labelFor} covers all of them. These used
 * to be spelled `breath` here and `breathe` on the wire, and because `labelFor`
 * falls back to the raw key the only symptom was a sentence reading "breathe is
 * on the phone app" — the kind of drift a `/breath/i` assertion happily passes.
 */
export const ACTION_KEYS = ["breathe", "focus", "journal", "tara", "read"];

/** Human name for an action key, for the "not here yet" sentences. */
export function labelFor(key) {
  return (
    {
      breathe: "Taking a deep breath",
      focus: "Focus sessions",
      journal: "The journal",
      tara: "Tara",
      read: "The reader",
    }[key] || key
  );
}

/**
 * The `/` picker's rows for what has been typed so far.
 *
 * Matches on the canonical name *and* every alias, so typing `/listen` finds
 * `play` — otherwise an alias that works when submitted would be invisible while
 * typing, which reads as it not existing.
 *
 * Returns `null` when the picker should be closed rather than empty: an empty
 * dropdown hanging under the composer while somebody types an ordinary message
 * containing a slash is noise.
 */
export function pickerRows(text, catalog) {
  if (typeof text !== "string" || !text.startsWith("/")) return null;
  // Once there is a space, the command has been chosen and the picker's job is
  // done — what follows is an argument.
  if (/\s/.test(text)) return null;
  const typed = text.slice(1).toLowerCase();
  const rows = (catalog || []).filter(
    (spec) =>
      spec.name.startsWith(typed) ||
      (spec.aliases || []).some((a) => a.startsWith(typed)),
  );
  return rows.length ? rows : null;
}

/**
 * The text to put in the composer when a picker row is chosen.
 *
 * A trailing space when the command takes an argument, so the caret is already
 * where the next word goes; none when it takes nothing, so pressing Enter
 * submits rather than sending `/breathe `.
 */
export function completionFor(spec) {
  if (!spec || !spec.name) return "";
  const needsArgument = Boolean(spec.argument) || Boolean(spec.takes_mention);
  return needsArgument ? `/${spec.name} ` : `/${spec.name}`;
}

/** `/tara` — nothing leaves this device. */
export const TARA_PRIVATE = "private";
/** `@tara` — the question and her answer both go to the peer. */
export const TARA_SHARED = "shared";

/**
 * Which Tara audience the raw draft implies, or `null` if she is not addressed.
 *
 * Called on every keystroke, on the raw text rather than a parse, because it must
 * be right from the moment `@tara ` is typed — before there is anything to parse.
 * The sigil is the whole difference between a private thought and a message the
 * other person reads, so the composer has to label it before Enter is pressed.
 */
export function taraDraft(text) {
  if (typeof text !== "string") return null;
  const t = text.trimStart();
  const lower = t.toLowerCase();
  for (const [prefix, audience] of [
    ["@tara", TARA_SHARED],
    ["/tara", TARA_PRIVATE],
  ]) {
    if (lower === prefix) return audience;
    if (lower.startsWith(prefix) && /\s/.test(t.charAt(prefix.length))) return audience;
  }
  return null;
}

/**
 * What a shared Tara answer carries on the wire — the mirror of
 * `comrade_core::tara::TARA_CHAT_PREFIX`.
 *
 * Only needed because a live-arriving DM reaches this frontend as the raw wire
 * body: `messages_with` already hands back `{author, content}` with the marker
 * off, but `incoming_direct_message` does not go through that DTO. Both paths
 * feed the same `state.dms`, so without this mirror a message would read one way
 * as it arrived and another way after a reload.
 */
export const TARA_CHAT_PREFIX = "Tara: ";

/**
 * Split a raw wire body into `{author, content}`, exactly as
 * `comrade_ui::split_author` does.
 *
 * **Attribution, not attestation.** Anybody can type "Tara: ", so a match means
 * the sending Comrade says this came from her — see `AUDIT.md` Q17. Style a
 * bubble with it; never trust it.
 */
export function splitAuthor(content) {
  const text = typeof content === "string" ? content : "";
  return text.startsWith(TARA_CHAT_PREFIX)
    ? { author: "tara", content: text.slice(TARA_CHAT_PREFIX.length) }
    : { author: "human", content: text };
}

/**
 * Apply the choices a user has made for ambiguous handles, so re-running the
 * same draft reaches the person they picked.
 *
 * A choice is honoured **only** when it names one of that handle's own
 * candidates. Without that check a pin left over from an earlier draft — or from
 * before a contact was removed — would silently retarget a message, which is the
 * exact failure the ambiguity exists to prevent.
 */
export function withChoices(mentions, chosen) {
  const picks = chosen || {};
  return (mentions || []).map((match) => {
    const pick = picks[match.handle];
    const offered = (match.candidates || []).some((c) => c.npub === pick);
    return !match.npub && pick && offered ? { ...match, npub: pick } : match;
  });
}

/**
 * How one candidate reads in the chooser.
 *
 * The key is not decoration: two contacts answering to one handle very often
 * share an alias too — that is *why* the handle is ambiguous — so a row showing
 * only the name would offer two identical choices. `title` is the caller's
 * resolved display name; the shortened key is what tells them apart.
 */
export function candidateLabel(title, npub) {
  const key = typeof npub === "string" && npub.length > 16
    ? `${npub.slice(0, 10)}…${npub.slice(-4)}`
    : npub || "";
  return `${title} · ${key}`;
}

/**
 * The line under the composer for a rendered offer or task bubble, so a received
 * one gets an affordance rather than reading as a bare sentence.
 *
 * Parsing is the bridge's (`comrade_core::command::parse_offer_line` /
 * `karya::parse_task_line`); this only decides whether *this* window can act on
 * the result. An offer of a screen desktop does not have is shown without a
 * button rather than hidden — the sentence is still the point, and hiding a
 * comrade's message because our build lacks a screen would be the wrong trade.
 */
export function offerAffordance(appAction) {
  if (!appAction) return null;
  return {
    label: labelFor(appAction),
    actionable: DESKTOP_SCREENS.has(appAction),
  };
}
