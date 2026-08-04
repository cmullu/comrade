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
/** Route to `tara_aside`, never to `send_dm`. */
export const ASIDE = "aside";
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
 * False, and deliberately a named constant rather than an inline `false`:
 * `docs/TOGETHER.md` §9 records that the commands and the decision module are in
 * place on desktop but the `<video>` element and the file picker are not, so
 * there is still no way to start a session here. When that lands, this flips and
 * `planFor` starts returning {@link PLAY} — one edit, in one place, with a test
 * already written for both sides of it.
 */
export const DESKTOP_CAN_PLAY = false;

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
      return {
        npubs,
        problem: {
          action: INCOMPLETE,
          message: `More than one contact answers to @${m.handle} — pick which one.`,
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

/**
 * Whether the composer should look like a private aside right now.
 *
 * Called on every keystroke, on the raw text rather than a parse, because it
 * must be true from the moment `@tara ` is typed — before there is anything to
 * parse. A private thing that looks like a message is how somebody sends one by
 * accident, so this is deliberately eager.
 */
export function isAsideDraft(text) {
  if (typeof text !== "string") return false;
  const t = text.trimStart();
  const lower = t.toLowerCase();
  for (const prefix of ["@tara", "/tara"]) {
    if (lower === prefix) return true;
    if (lower.startsWith(prefix) && /\s/.test(t.charAt(prefix.length))) return true;
  }
  return false;
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
