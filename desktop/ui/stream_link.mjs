/**
 * `/play <a link to the media itself>` — the third thing a `/play` argument can
 * be, beside words and a service link.
 *
 * ## What this decides, and what it deliberately does not
 *
 * `TogetherContent::Stream` is one public HTTPS URL that **both sides fetch for
 * themselves** — a podcast episode off its feed, an Internet Archive item. It
 * plays in the same `<video>` element a local file does, so it reports an
 * accurate position and takes any `playbackRate`, which is why it earns the fine
 * deadband where a YouTube embed or a service track cannot (`docs/TOGETHER.md`
 * §11a). Core has carried the variant, the guard and the tuning since it landed;
 * what was missing everywhere was a way to say so.
 *
 * The decision that belongs to this window is **which of two flows a typed
 * argument enters**: hand it to core as a stream, or resolve it as words to look
 * for. That is not the same question as "is this URL safe", and this file must
 * not drift into answering the second one:
 *
 *  - **`valid_stream_url` in `comrade_core::together` owns validity**, and runs
 *    on the way out *and* on the way in — HTTPS only, no credentials, no bare
 *    LAN name, no literal IP, no injection characters, bounded length. A second
 *    copy of those rules in JS would be a copy that drifts, and the one that
 *    drifts loose is the one that matters. So a URL we accept here is a
 *    *candidate*: `together_start` refuses it and says so, and nothing reaches
 *    the media element until core has agreed.
 *  - The one rule this file does apply is the **scheme**, because that is what
 *    tells a URL from a phrase at all, and because `javascript:` and `data:`
 *    must not be carried any further than the sentence that refuses them. It is
 *    narrower than core's check on purpose: narrower is safe, and the value is a
 *    sentence that names the actual problem instead of a round trip that comes
 *    back "that link isn't something Comrade will play".
 *
 * It sits between the two halves of the existing `/play`: after `play_query`,
 * whose answer it needs, and before `play_flow.mjs`'s `planPlay`, which decides
 * what to do with a route. It is a sibling rather than another export there
 * because `planPlay` answers "what does this window do about core's route" and
 * this answers "should that router be asked about this text at all".
 *
 * The other half of the split is the wire shape. `streamContent` is the only
 * place the `kind: "stream"` object is built, so a field renamed in Rust breaks
 * one tested function rather than a string literal buried in an event handler —
 * `stream_link.test.mjs` pins the shape against `together.rs` itself.
 *
 * Vocabulary throughout follows `docs/TOGETHER.md` §7: never "synced", never
 * "in sync".
 */

/** Hand it to core as a stream. `url` is the typed text, unaltered. */
export const STREAM = "stream";
/** A URL, but not one we could ever play. Refused here, with the reason. */
export const NOT_HTTPS = "not_https";
/** Not a URL at all — words for `play_query` to resolve. */
export const WORDS = "words";

/**
 * What the media element said when it could not open the URL.
 *
 * A valid HTTPS URL that is a web page rather than a file is the common miss,
 * and it is worth naming because the fix is on the person's side: find the
 * direct link. Core cannot answer this — a pure function cannot know what a
 * server will return — so this is genuinely the frontend's sentence.
 */
export const COULD_NOT_PLAY =
  "That link didn't play — it may not point straight at an audio or video file. " +
  "Nothing is running here.";

/** How long a title may get before it stops being a title. */
const TITLE_MAX = 60;

/**
 * Which flow a `/play` argument should enter.
 *
 * Takes what `play_query` made of the same text, and defers to it completely on
 * the one question that would otherwise need a second parser here: **a Spotify,
 * Apple Music or YouTube URL is an https URL too**, and `valid_stream_url` would
 * happily accept `https://open.spotify.com/track/…`. Pointing a media element at
 * a Spotify page is not a session, it is a blank rectangle — so anything core
 * recognised as a service link, or already resolved into content of its own, is
 * that route's and not this one's. `parse_music_link` stays the only thing that
 * knows which hosts those are.
 *
 * A query core has not resolved is treated the same way, for the same reason:
 * without core's answer we do not know what the link is, and guessing is how the
 * duplicate parser gets written.
 *
 * @param {string} query the raw text after `/play`
 * @param {{link?: object|null, content?: object|null}|null} target the
 *   `PlayTargetDto` `play_query` returned for that text
 * @returns {{kind: string, url?: string, title?: string|null,
 *            scheme?: string, message?: string}}
 */
export function planStream(query, target) {
  if (!target || target.link || target.content) return { kind: WORDS };

  const text = typeof query === "string" ? query.trim() : "";
  // Whitespace inside is a sentence, not an address. This also keeps
  // "kun faya kun" out of the scheme test below.
  if (!text || /\s/.test(text)) return { kind: WORDS };

  const scheme = schemeOf(text);
  if (!scheme) return { kind: WORDS };
  if (scheme === "https") {
    // Byte-identical to what was typed. A frontend that "tidied" a URL —
    // stripping a control character, encoding a space — would hand core a
    // different string from the one the person read, and core's injection rules
    // are written against the string that reaches the element. Trimming the
    // ends is the only liberty taken, and it is the one every paste needs.
    return { kind: STREAM, url: text, title: streamTitle(text) };
  }
  return {
    kind: NOT_HTTPS,
    scheme,
    // Names the scheme rather than lecturing: `http:` is a typo away from
    // working, and someone who pasted one wants to know which half was wrong.
    message:
      `A link has to be https for Comrade to open it, and that one is ${scheme}:. ` +
      "Paste the https link to the audio or video, or /play the name of something.",
  };
}

/**
 * The `TogetherContent::Stream` value to send, as core's serde shape.
 *
 * `duration_ms` is deliberately `null`. It could only be known by loading the
 * URL before inviting anyone, and this window does that the other way round —
 * core validates the URL, *then* the element is pointed at it — so at this
 * moment the length is genuinely not known. It costs almost nothing here:
 * `duration_ms` exists for the "their copy runs four seconds longer than yours"
 * warning, and both sides of a stream session are fetching the same URL, so
 * there is no disagreement for it to warn about.
 *
 * `recording` is `null` for the same kind of reason. A title read off the end of
 * the URL is a guess, and [`streamTitle`] makes it only for this window's own
 * stage; sending it would put our guess on the other person's screen as if the
 * source had said it. The URL itself is fully disclosing already.
 *
 * @param {string} url
 */
export function streamContent(url) {
  return {
    kind: "stream",
    url: typeof url === "string" ? url : "",
    recording: null,
    duration_ms: null,
  };
}

/**
 * How long the element says it runs, as a number a session can use.
 *
 * A live stream reports `Infinity` and one that has not opened reports `NaN`.
 * Neither is a length, and both would otherwise reach the scrubber and the
 * position report looking like one — so they become 0, which every reader here
 * already treats as "not known" (`player_view.seekPosition` returns no thumb
 * position for it rather than a thumb in a meaningless place).
 *
 * @param {number} seconds what a media element's `duration` reports
 */
export function durationMsFrom(seconds) {
  const secs = Number(seconds);
  if (!Number.isFinite(secs) || secs <= 0) return 0;
  return Math.round(secs * 1000);
}

/**
 * The URL out of an invitation that carries one, or `null`.
 *
 * Safe to point an element at **because core already refused every invitation
 * whose content is not admissible** — `TogetherContent::admissible` runs on the
 * receiving side before the session is created, so a `Stream` that reaches a
 * frontend has been through `valid_stream_url`. That is the guarantee this
 * function leans on, and it is written down here because it is the only reason
 * a peer-supplied string may become a `src`.
 *
 * @param {{kind?: string, url?: unknown}|null|undefined} content
 * @returns {string|null}
 */
export function streamUrlOf(content) {
  if (!content || content.kind !== "stream") return null;
  const url = typeof content.url === "string" ? content.url.trim() : "";
  return url || null;
}

/**
 * What to call it on our own stage — never what to send.
 *
 * The last path segment usually is the episode; when it is not (a bare number, a
 * URL that is all query string) the host is the more useful thing to read, and
 * both are better than "Something with Ana". Display only, so unlike the URL
 * this *is* tidied: it lands in a `textContent`, where a stray control character
 * is noise rather than a hazard.
 *
 * @param {string} url
 * @returns {string|null}
 */
export function streamTitle(url) {
  const text = typeof url === "string" ? url.trim() : "";
  if (!text) return null;
  const afterScheme = text.replace(/^[a-z][a-z0-9+.-]*:\/\//i, "");
  const authority = afterScheme.split(/[/?#]/)[0] || "";
  const host = (authority.split("@").pop() || "")
    .split(":")[0]
    .replace(/^www\./i, "");
  const path = afterScheme.slice(authority.length).split(/[?#]/)[0] || "";
  const segment = path.split("/").filter(Boolean).pop() || "";
  // A host keeps its dots: "feeds.example.com" is the name, and running it
  // through the segment tidier would take the ".com" off as if it were a file
  // extension.
  return readable(segment) || label(host) || null;
}

/** A path segment as a person would read it, or `""` if it does not read. */
function readable(raw) {
  let s = raw;
  try {
    s = decodeURIComponent(s);
  } catch {
    // A truncated `%` escape. Keep the raw text rather than dropping the title.
  }
  s = s
    // Control characters are noise in a label; the URL itself is never touched.
    // Stripped first, so a trailing one cannot hide the extension below.
    .replace(/[\u0000-\u001f\u007f]/g, "")
    .replace(/\.[a-z0-9]{1,5}$/i, "") // the extension names the format, not the thing
    .replace(/[_+-]+/g, " ");
  return label(s);
}

/** Anything at all, bounded and stripped, or `""` if it does not read as a name. */
function label(raw) {
  const s = raw
    .replace(/[\u0000-\u001f\u007f]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  // Digits alone are a file number, not a name — "42" tells nobody anything, so
  // the caller falls back to the host.
  if (!/[a-z]/i.test(s)) return "";
  return s.length > TITLE_MAX ? `${s.slice(0, TITLE_MAX).trim()}…` : s;
}

/**
 * The scheme, when the text is shaped like something a browser would open.
 *
 * `//` is required for the general case, which is what keeps `re:zero` a search
 * for an anime rather than a refused URL. The opaque schemes are listed because
 * they carry no `//` and are exactly the ones that must never be carried any
 * further — see `valid_stream_url`'s first rule.
 */
function schemeOf(text) {
  const hierarchical = /^([a-z][a-z0-9+.-]*):\/\//i.exec(text);
  if (hierarchical) return hierarchical[1].toLowerCase();
  const opaque = /^(javascript|data|blob|vbscript|file):/i.exec(text);
  return opaque ? opaque[1].toLowerCase() : null;
}
