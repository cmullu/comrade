/**
 * `/play` on desktop: what one command should actually do.
 *
 * ## Why this is a module
 *
 * The gesture is meant to be one step — type `/play kun faya kun` in the
 * conversation and be listening together — and the reason it was not is that
 * every piece existed except the sentence connecting them. `play_query` and
 * `play_route` have been registered Tauri commands the whole time; the window
 * simply answered "there is no player here yet" and stopped.
 *
 * What is genuinely a decision, and therefore lives here rather than in the
 * click handler:
 *
 *  - **A route is not an instruction.** `PlayRoute` says what is possible given
 *    the query and this device's library; it does not say what this *window*
 *    should do about it, and desktop's answer differs from Android's because
 *    desktop has no library to search. Every route still needs an outcome — a
 *    command that silently does nothing is the bug this replaces.
 *  - **What each refusal says.** Two of the five routes cannot end in playback
 *    here, and `docs/PRESENCE.md` §5 applies: say what is true and what to do
 *    next, never imply a capability we lack.
 *
 * The rule the whole file exists to keep: **every route returns something, and
 * nothing here ever claims we can play what we cannot.**
 */

/** Open the file picker, then invite as soon as a file is chosen. */
export const PICK = "pick";
/** We know what they mean and cannot play it; say where it lives. */
export const ELSEWHERE = "elsewhere";
/** This window cannot do it, though another frontend can. */
export const UNAVAILABLE = "unavailable";
/** Nothing usable in what they typed. */
export const NOTHING = "nothing";

/**
 * A readable name for what was asked for, or `null` if the query named nothing.
 *
 * Used as the session title, so it is what the *other* person sees in the
 * invitation. Their side otherwise reads "wants to watch  with you" with a hole
 * in it, because desktop was sending `recording: null` regardless of what was
 * typed.
 *
 * @param {{recording?: {title?: string, artist?: string}|null}|null} target
 * @returns {string|null}
 */
export function playTitle(target) {
  const rec = target?.recording;
  const title = (rec?.title || "").trim();
  if (!title) return null;
  const artist = (rec?.artist || "").trim();
  return artist ? `${title} — ${artist}` : title;
}

/**
 * What this window should do about a resolved `/play`.
 *
 * @param {string} route a `PlayRoute`, snake_case as it crosses the boundary
 * @param {object|null} target the `PlayTargetDto` the route was derived from
 * @returns {{kind: string, message?: string, title?: string|null,
 *            recording?: object|null}}
 */
export function planPlay(route, target) {
  const title = playTitle(target);
  const service = serviceLabel(target);

  switch (route) {
    // Desktop has no library to search, so this is the common route for a
    // query that named a recording: we know what they want and have to be
    // handed it. The picker *is* the answer, not an error before it.
    case "ask_for_file":
      return {
        kind: PICK,
        title,
        recording: target?.recording ?? null,
        message: title
          ? `Pick your copy of ${title} and I'll start it with them.`
          : "Pick the file and I'll start it with them.",
      };

    // Android reaches this by finding the file itself. Desktop cannot yet, but
    // the route is still handled rather than falling through to a default —
    // when a library search lands here it must not need this file changed.
    case "start_together":
      return {
        kind: PICK,
        title,
        recording: target?.recording ?? null,
        message: title
          ? `Pick your copy of ${title} and I'll start it with them.`
          : "Pick the file and I'll start it with them.",
      };

    // No account connected here that could drive it. Reworded 2026-08-08: the
    // old sentence said the service "won't play in here", which named the wrong
    // reason. Decoding their bytes is out and always will be, but driving
    // playback inside their own client on this listener's own subscription is
    // precisely what their SDKs exist for — see `docs/TOGETHER.md` §11. What is
    // missing is the connection, and that is a thing that can be fixed.
    case "open_elsewhere":
      return {
        kind: ELSEWHERE,
        title,
        message: service
          ? `No ${service} account connected here. Open it there — or /play a file you both have.`
          : "I can't play that one here. /play a file you both have and I'll sync it.",
      };

    // The account is connected and the track is drivable, so this is a real
    // session on this listener's own subscription. No player is wired to it in
    // this window yet, and the sentence says which half is missing rather than
    // implying the whole feature is absent.
    case "play_on_service":
      return {
        kind: UNAVAILABLE,
        title,
        message: service
          ? `${service} together isn't wired into this window yet.`
          : "That service isn't wired into this window yet.",
      };

    // A YouTube embed is drivable in a webview, and this window has no iframe
    // for it yet (`docs/TOGETHER.md` §7 carries the CSP condition it must meet).
    // Named precisely: "not here" rather than "cannot", because the phone can.
    case "play_embed":
      return {
        kind: UNAVAILABLE,
        title,
        message: "YouTube together is on the phone app, not this window yet.",
      };

    case "nothing":
      return {
        kind: NOTHING,
        message: "Say what to play — /play and the name of it, or a link.",
      };

    // An unrecognised route means core gained a variant this file has not
    // learned. Refuse in a way that is true regardless of what it was, rather
    // than guessing at a behaviour.
    default:
      return {
        kind: NOTHING,
        message: "I didn't understand what to play.",
      };
  }
}

/**
 * Where a link came from, for a sentence that has to name it.
 *
 * Prefers the parsed link over the wording hint: `service` records which alias
 * was typed (`/spotify …`), which is a hint about phrasing and not a claim
 * about what the query turned out to be.
 *
 * @param {{link?: object|null, service?: string|null}|null} target
 */
function serviceLabel(target) {
  const link = target?.link;
  if (link && typeof link === "object") {
    const kind = Object.keys(link)[0] || "";
    const named = LINK_NAMES[kind];
    if (named) return named;
  }
  return SERVICE_NAMES[target?.service] || null;
}

/** Mirrors `MusicLink::service` in `comrade_core::together`. */
const LINK_NAMES = Object.freeze({
  spotify: "Spotify",
  apple_music: "Apple Music",
  youtube: "YouTube",
});

/** Mirrors `MusicService::label` in `comrade_core::command`. */
const SERVICE_NAMES = Object.freeze({
  spotify: "Spotify",
  apple_music: "Apple Music",
  youtube: "YouTube",
});
