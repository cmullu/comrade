/**
 * `/play <a link>` — that it reaches core, and that core stays the one judge.
 *
 * Two kinds of test live here. Most are ordinary vectors for the classifier.
 * The last section reads `crates/comrade_core/src/together.rs` and runs **core's
 * own stream-URL vectors through this window's classifier**, in the manner of
 * `together_parity.test.mjs`: a podcast URL core accepts must not be quietly
 * re-routed into a word search here, and a hostile one must not be altered on
 * its way to the check that refuses it. Those vectors are the shared,
 * executable form of the rule — Android and Flutter can mirror the same list
 * from the same place when they build their own way in.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  COULD_NOT_PLAY,
  MEDIA_SUFFIXES,
  NOT_HTTPS,
  NOT_MEDIA,
  STREAM,
  WORDS,
  durationMsFrom,
  namesMedia,
  planStream,
  streamContent,
  streamTitle,
  streamUrlOf,
} from "./stream_link.mjs";

/** What `play_query` returns for text it could not resolve to a link. */
const UNRESOLVED = { plan: "find_locally", service: null, link: null, content: null };

const EPISODE = "https://feeds.example.com/ep/42.mp3";

// ── Telling a link from a phrase ─────────────────────────────────────────────

test("a direct media link is handed to core as a stream, not searched for", () => {
  // The whole point: before this, `/play https://…/ep.mp3` went down the
  // `ask_for_file` route and offered a file picker titled with the URL.
  const plan = planStream(EPISODE, UNRESOLVED);
  assert.equal(plan.kind, STREAM);
  assert.equal(plan.url, EPISODE);
});

test("words are words", () => {
  for (const q of ["kun faya kun", "Solaris", "the daily", "  ", "", null, undefined]) {
    assert.equal(planStream(q, UNRESOLVED).kind, WORDS, JSON.stringify(q));
  }
});

test("a colon in a title does not make it a URL", () => {
  // `re:zero` matches "scheme followed by colon" and nothing else. Requiring
  // the `//` is what keeps a search for it a search.
  for (const q of ["re:zero", "blade:runner", "mission:impossible"]) {
    assert.equal(planStream(q, UNRESOLVED).kind, WORDS, q);
  }
});

test("a link with a space in it is a phrase, not a link", () => {
  const plan = planStream("https://example.com/a.mp3 is great", UNRESOLVED);
  assert.equal(plan.kind, WORDS);
});

test("the scheme is read without regard to case, like core reads it", () => {
  // `valid_stream_url` is case-insensitive on the scheme and case-*sensitive*
  // on the path. Both halves matter: refusing this would be a mis-route, and
  // lowercasing the path would break servers that mean it.
  const plan = planStream("HTTPS://example.com/CaseSensitive/Path.MP3", UNRESOLVED);
  assert.equal(plan.kind, STREAM);
  assert.equal(plan.url, "HTTPS://example.com/CaseSensitive/Path.MP3");
});

// ── The service links this must not steal ────────────────────────────────────

test("a service link stays with the route core resolved it to", () => {
  // The trap this test exists for: `https://open.spotify.com/track/…` is a
  // perfectly valid *stream* URL by core's rules, so a classifier that looked
  // only at the scheme would start a session pointing a media element at a
  // Spotify web page. `parse_music_link` is what knows better, and it already
  // ran — so its answer decides.
  const spotify = {
    plan: "name_only",
    link: { spotify: { track_id: "4uLU6hMCjMI75M1A2tKUQC" } },
    content: null,
  };
  assert.equal(
    planStream("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC", spotify).kind,
    WORDS,
  );
});

test("a YouTube link stays with the embed route", () => {
  const youtube = {
    plan: "open_now",
    link: { youtube: { video_id: "dQw4w9WgXcQ" } },
    content: { kind: "youtube", video_id: "dQw4w9WgXcQ" },
  };
  assert.equal(planStream("https://youtu.be/dQw4w9WgXcQ", youtube).kind, WORDS);
});

test("content core already resolved is never re-read as a stream", () => {
  // `content` set means core says a session can open on it as it stands. Even
  // with no link — a shape that does not exist today — that answer wins.
  const resolved = { plan: "open_now", link: null, content: { kind: "youtube", video_id: "x" } };
  assert.equal(planStream(EPISODE, resolved).kind, WORDS);
});

test("a query core never resolved does not become a stream on a guess", () => {
  // Without `play_query`'s answer we cannot tell a podcast host from Spotify's,
  // and inventing that answer here is the duplicate parser this file refuses.
  for (const target of [null, undefined]) {
    assert.equal(planStream(EPISODE, target).kind, WORDS);
  }
});

// ── Schemes that must not travel ─────────────────────────────────────────────

test("a scheme that is not https is refused here, by name", () => {
  const cases = [
    ["http://feeds.example.com/ep.mp3", "http"],
    ["ftp://example.com/a.mp3", "ftp"],
    ["file:///etc/passwd", "file"],
    ["file:/etc/passwd", "file"],
    ["javascript:alert(1)", "javascript"],
    ["JavaScript:alert(1)", "javascript"],
    ["data:audio/mp3;base64,AAAA", "data"],
    ["blob:https://example.com/1234", "blob"],
    ["vbscript:msgbox(1)", "vbscript"],
  ];
  for (const [url, scheme] of cases) {
    const plan = planStream(url, UNRESOLVED);
    assert.equal(plan.kind, NOT_HTTPS, url);
    assert.equal(plan.scheme, scheme, url);
    assert.match(plan.message, new RegExp(`${scheme}:`), url);
    // Refused *here*, so it never reaches core and never reaches an element.
    assert.equal(plan.url, undefined, url);
  }
});

test("a refusal never echoes the link back into the sentence", () => {
  // The message lands in a toast that sets `textContent`, so this is not an
  // injection today — it is the rule that keeps it from becoming one, and it
  // stops a 2 kB URL being pasted back at the person who typed it.
  const plan = planStream("javascript:alert(document.cookie)", UNRESOLVED);
  assert.doesNotMatch(plan.message, /alert|document\.cookie/);
});

// ── A page about the thing is not the thing ──────────────────────────────────

test("a page link is refused before a session opens on it, by name", () => {
  // The flow this replaces: `/play https://example.com/episodes/42` opened a
  // session, *invited the other person to it*, pointed the element at an HTML
  // document, and failed seconds later with COULD_NOT_PLAY — after the
  // invitation had already gone out. Android has refused this at the paste
  // since §16 (core's `TogetherContent::stream`); this is desktop agreeing.
  for (const url of [
    "https://example.com/episodes/42",
    "https://example.com/show.html",
    "https://example.com/watch?file=a.mp3",
    "https://example.mp3",
  ]) {
    const plan = planStream(url, UNRESOLVED);
    assert.equal(plan.kind, NOT_MEDIA, url);
    assert.ok(plan.message, url);
    // Refused here, so nothing reaches core and nobody is invited.
    assert.equal(plan.url, undefined, url);
    // Like the scheme refusal: the URL itself never rides back in the sentence.
    assert.doesNotMatch(plan.message, /example/, url);
  }
});

test("the page-link sentence names the fix, not just the failure", () => {
  const plan = planStream("https://example.com/episodes/42", UNRESOLVED);
  assert.match(plan.message, /direct link|\.mp3/i);
});

// ── Nothing is tidied on the way to core ─────────────────────────────────────

test("the URL handed to core is byte-identical to what was typed", () => {
  // A frontend that cleaned up a URL would hand core a different string from
  // the one the person read, and `valid_stream_url`'s injection rules are
  // written against the string that reaches the element. Only the outer
  // whitespace of a paste is dropped.
  const hostile = [
    'https://example.com/a"onerror=x.mp3',
    "https://example.com/<script>.mp3",
    "https://example.com/a%2zb.mp3",
    "https://user:pass@example.com/a.mp3",
    "https://example.com@evil.test/a.mp3",
    // Suffixed so it passes the media line and reaches core — which refuses
    // the LAN address. The point here is that it reaches core *intact*.
    "https://192.168.1.1/admin.mp3",
    // A control character, not a space: a space would make this a phrase.
    // Core refuses this one; the point is that we hand it over intact.
    "https://example.com/a\u0000b.mp3",
  ];
  for (const url of hostile) {
    const plan = planStream(`  ${url}  `, UNRESOLVED);
    assert.equal(plan.kind, STREAM, url);
    assert.equal(plan.url, url, url);
  }
});

test("core is left to refuse what core refuses", () => {
  // These are all things `valid_stream_url` says no to. This window passing
  // them on is correct — the refusal comes back from `together_start` and
  // nothing has been played. What would be wrong is a second, drifting copy of
  // that check here that one day says yes where core says no.
  for (const url of ["https:///a.mp3", "https://localhost/a.mp3", "https://router/reboot.mkv"]) {
    assert.equal(planStream(url, UNRESOLVED).kind, STREAM, url);
  }
});

// ── The wire shape ───────────────────────────────────────────────────────────

test("the content sent is core's Stream shape and nothing more", () => {
  const content = streamContent(EPISODE);
  assert.deepEqual(Object.keys(content).sort(), [
    "duration_ms",
    "kind",
    "recording",
    "url",
  ]);
  assert.equal(content.kind, "stream");
  assert.equal(content.url, EPISODE);
});

test("a guessed title is never sent as if the source had said it", () => {
  // `streamTitle` reads a name off the end of a URL for our own stage. Putting
  // that guess in `recording` would print it on the other person's screen as
  // the name of the thing, which we do not know.
  const content = streamContent("https://feeds.example.com/the-daily-2026.mp3");
  assert.equal(content.recording, null);
  assert.ok(streamTitle("https://feeds.example.com/the-daily-2026.mp3"));
});

test("a length we do not know is not invented", () => {
  // Null because the element has not opened it yet, and honest rather than a
  // zero — a zero would make the other side's length warning fire on every
  // stream session.
  assert.equal(streamContent(EPISODE).duration_ms, null);
});

test("the content is JSON the command can carry", () => {
  const round = JSON.parse(JSON.stringify(streamContent(EPISODE)));
  assert.deepEqual(round, {
    kind: "stream",
    url: EPISODE,
    recording: null,
    duration_ms: null,
  });
});

// ── How long it runs ─────────────────────────────────────────────────────────

test("a length the element could not measure is zero, not a number", () => {
  // `Infinity` is what a live radio stream reports and `NaN` is what an element
  // reports before it has opened anything. Either one reaching the scrubber
  // draws a thumb at a fraction of an unknown whole, and either one reaching
  // the position report is a number core has to defend itself against.
  for (const bad of [Infinity, -Infinity, NaN, null, undefined, "", "abc", 0, -5]) {
    assert.equal(durationMsFrom(bad), 0, String(bad));
  }
});

test("a length the element did measure survives the trip", () => {
  assert.equal(durationMsFrom(12.34), 12340);
  assert.equal(durationMsFrom(3600), 3_600_000);
});

// ── Reading an invitation ────────────────────────────────────────────────────

test("an invitation carrying a stream gives up its URL", () => {
  assert.equal(streamUrlOf({ kind: "stream", url: EPISODE }), EPISODE);
});

test("no other content kind is read as a stream", () => {
  for (const content of [
    null,
    undefined,
    {},
    { kind: "local_file", duration_ms: 1000 },
    { kind: "youtube", video_id: "dQw4w9WgXcQ" },
    { kind: "service", link: { spotify: { track_id: "x" } } },
    { kind: "stream" },
    { kind: "stream", url: "" },
    { kind: "stream", url: "   " },
    { kind: "stream", url: 42 },
    { kind: "stream", url: { toString: () => EPISODE } },
  ]) {
    assert.equal(streamUrlOf(content), null, JSON.stringify(content));
  }
});

// ── The title, which is ours alone ───────────────────────────────────────────

test("a title reads like the episode rather than like a URL", () => {
  assert.equal(streamTitle("https://feeds.example.com/the-daily-2026.mp3"), "the daily 2026");
  assert.equal(streamTitle("https://archive.org/download/item/track%2001.flac"), "track 01");
});

test("a segment that names nothing falls back to the host", () => {
  // "42" on a stage tells nobody anything; "feeds.example.com" does.
  assert.equal(streamTitle("https://feeds.example.com/ep/42.mp3"), "feeds.example.com");
  assert.equal(streamTitle("https://www.example.com"), "example.com");
  assert.equal(streamTitle("https://mp3l.jamendo.com/?trackid=1214935&format=mp31"), "mp3l.jamendo.com");
});

test("a title never carries what a label cannot hold", () => {
  // Display-only, so unlike the URL this is tidied: control characters out, and
  // a bounded length so a 2 kB URL cannot become a 2 kB heading.
  const noisy = streamTitle("https://example.com/a\u0000b\u001fc.mp3");
  assert.doesNotMatch(noisy, /[\u0000-\u001f]/);
  const long = streamTitle(`https://example.com/${"a".repeat(500)}.mp3`);
  assert.ok(long.length <= 61, `title was ${long.length} long`);
});

test("a title never throws, whatever the URL is", () => {
  for (const url of [
    "",
    "   ",
    null,
    undefined,
    "https://",
    "https://example.com/%E0%A4",
    "https://example.com/%",
    "https://user:pass@example.com/x.mp3",
    `https://example.com/${"%C0".repeat(50)}`,
  ]) {
    assert.doesNotThrow(() => streamTitle(url), String(url));
  }
  // A truncated escape keeps the raw text rather than losing the title.
  assert.equal(streamTitle("https://example.com/ep%.mp3"), "ep%");
});

// ── Vocabulary (docs/TOGETHER.md §7) ─────────────────────────────────────────

test("no sentence here claims two players are synced", () => {
  const sentences = [
    COULD_NOT_PLAY,
    planStream("http://example.com/a.mp3", UNRESOLVED).message,
    planStream("javascript:alert(1)", UNRESOLVED).message,
    planStream("https://example.com/episodes/42", UNRESOLVED).message,
  ];
  for (const s of sentences) {
    assert.ok(s, "a refusal with no sentence is the bug /play already had");
    assert.doesNotMatch(s, /\bsynced\b|\bin sync\b/i, s);
  }
});

test("a failure to play says nothing is running, not that something is", () => {
  assert.doesNotMatch(COULD_NOT_PLAY, /playing|together now/i);
});

// ── Core's own vectors, run through this window ──────────────────────────────

const RUST = readFileSync(
  new URL("../../crates/comrade_core/src/together.rs", import.meta.url),
  "utf8",
);

/**
 * The string literals inside a named `#[test] fn` in the Rust source.
 *
 * Crude, and honest about it in the manner of `together_parity.test.mjs`: if
 * the test is renamed or reshaped this throws rather than silently checking an
 * empty list.
 */
function rustVectors(fnName, least) {
  const start = RUST.indexOf(`fn ${fnName}(`);
  assert.notEqual(start, -1, `could not find "fn ${fnName}" in together.rs — renamed?`);
  const body = RUST.slice(start, RUST.indexOf("\n    }", start));
  const found = [...body.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
    .map((m) => unescapeRust(m[1]))
    // The `assert!` message is a literal too, and it is the only one with a
    // `{` in it. Vectors are URLs or the empty string.
    .filter((s) => !s.includes("{"));
  assert.ok(
    found.length >= least,
    `expected at least ${least} vectors in ${fnName}, found ${found.length}`,
  );
  return found;
}

function unescapeRust(s) {
  return s.replace(/\\(.)/g, (_, c) =>
    ({ n: "\n", t: "\t", r: "\r", "0": "\u0000" })[c] ?? c,
  );
}

test("the content we build is the shape core declared", () => {
  // `together_start` takes JSON and deserialises `TogetherContent`, so a field
  // renamed in Rust is a runtime "invalid content" error rather than a build
  // failure — this window is not the one the compiler checks. The variant is
  // `#[serde(tag = "kind", rename_all = "snake_case")]`, hence `kind: "stream"`
  // beside the declared fields.
  const start = RUST.indexOf("\n    Stream {");
  assert.notEqual(start, -1, "could not find the Stream variant in together.rs — renamed?");
  const block = RUST.slice(start, RUST.indexOf("\n    },", start));
  const declared = [...block.matchAll(/^\s{8}(\w+):\s/gm)].map((m) => m[1]);
  assert.deepEqual(declared.sort(), ["duration_ms", "recording", "url"]);
  assert.deepEqual(
    Object.keys(streamContent(EPISODE)).filter((k) => k !== "kind").sort(),
    declared,
  );
});

/**
 * The vector lists of a Rust test with more than one `for url in` loop, in
 * order. Same crude honesty as [`rustVectors`]: a reshaped test throws rather
 * than silently checking nothing.
 */
function rustVectorGroups(fnName, leastGroups) {
  const start = RUST.indexOf(`fn ${fnName}(`);
  assert.notEqual(start, -1, `could not find "fn ${fnName}" in together.rs — renamed?`);
  const body = RUST.slice(start, RUST.indexOf("\n    }", start));
  const groups = body
    .split("for url in")
    .slice(1)
    .map((segment) =>
      [...segment.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
        .map((m) => unescapeRust(m[1]))
        .filter((s) => !s.includes("{")),
    );
  assert.ok(
    groups.length >= leastGroups,
    `expected at least ${leastGroups} vector groups in ${fnName}, found ${groups.length}`,
  );
  return groups;
}

test("the media-suffix list is core's, byte for byte", () => {
  // `namesMedia` is a mirror of `direct_media_url`, and this is what keeps it
  // one: an extension added in core without landing here would quietly send
  // real episodes down the search path, and this test makes that a red build.
  const declared = RUST.match(
    /const STREAM_MEDIA_SUFFIXES\s*:\s*\[&str;\s*\d+\]\s*=\s*\[([^\]]+)\]/,
  );
  assert.ok(declared, "could not find STREAM_MEDIA_SUFFIXES in together.rs — renamed?");
  const suffixes = [...declared[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual([...MEDIA_SUFFIXES], suffixes);
});

test("every URL core opens a stream on is offered to core as one", () => {
  // The mis-route this guards: desktop deciding a real podcast URL is words —
  // or, since the media-suffix line landed here, a page — and answering a
  // paste with a refusal. The vectors are the accept loop of core's own
  // `direct_media_url` test, so the two sides cannot disagree about what an
  // episode is without this going red.
  const [accepted, refused] = rustVectorGroups("only_a_url_that_names_media_becomes_a_stream", 2);
  assert.ok(accepted.length >= 4, `only ${accepted.length} accept vectors found`);
  for (const url of accepted) {
    const plan = planStream(url, UNRESOLVED);
    assert.equal(plan.kind, STREAM, url);
    assert.equal(plan.url, url, url);
  }
  // And the refuse loop: nothing core calls a non-episode may open a session
  // from here. Either this window refuses it itself (a page link, a hostile
  // scheme, a phrase) or it goes to core byte-identical for core's own refusal
  // — what it must never do is become a *different* string that passes.
  assert.ok(refused.length >= 8, `only ${refused.length} refuse vectors found`);
  let refusedAsPages = 0;
  for (const url of refused) {
    const plan = planStream(url, UNRESOLVED);
    if (plan.kind === STREAM) assert.equal(plan.url, url, url);
    if (plan.kind === NOT_MEDIA) refusedAsPages += 1;
  }
  // The page-link cases are the ones this window is entitled to answer without
  // a round trip, and they are the point of the rule — none refused here means
  // the mirror is not running.
  assert.ok(refusedAsPages >= 4, `only ${refusedAsPages} page links refused here`);
});

test("the mirror answers exactly what core's suffix rule answers", () => {
  // Shape edges the vectors above may not cover, pinned directly: the suffix
  // must sit in the path (not the host), the query string is the server's, and
  // the lowering is ASCII-only like `to_ascii_lowercase`.
  assert.equal(namesMedia("https://example.com/EPISODE.MP3"), true);
  assert.equal(namesMedia("https://example.com/ep.mp3?token=abc#t=9"), true);
  assert.equal(namesMedia("https://example.mp3"), false);
  assert.equal(namesMedia("https://example.mp3?x=1"), false);
  assert.equal(namesMedia("https://example.com/watch?file=a.mp3"), false);
  for (const suffix of MEDIA_SUFFIXES) {
    assert.equal(namesMedia(`https://example.com/a${suffix}`), true, suffix);
  }
});

test("nothing core refuses is altered on its way to the refusal", () => {
  // Whatever this window decides about a hostile vector, it must not hand core
  // a *different* string — a cleaned-up URL is one that passes the check and
  // then plays something else.
  const hostile = rustVectors("a_stream_url_refuses_everything_that_is_not_a_public_https_name", 15);
  let refusedHere = 0;
  for (const url of hostile) {
    const plan = planStream(url, UNRESOLVED);
    if (plan.kind === STREAM) assert.equal(plan.url, url, url);
    if (plan.kind === NOT_HTTPS) refusedHere += 1;
  }
  // The scheme cases — `http:`, `file:`, `javascript:`, `data:`, `ftp:` — are
  // the ones this window is entitled to answer without a round trip.
  assert.ok(refusedHere >= 5, `only ${refusedHere} hostile schemes refused here`);
});

test("a URL longer than core will take is still core's to refuse", () => {
  // `STREAM_URL_MAX_LEN` lives in one place. A length rule copied here would be
  // the copy that drifts, so the long one goes to core and comes back refused.
  const max = Number(
    RUST.match(/pub const STREAM_URL_MAX_LEN\s*:\s*usize\s*=\s*([0-9_]+)\s*;/)?.[1].replace(/_/g, ""),
  );
  assert.ok(max > 0, "could not find STREAM_URL_MAX_LEN in together.rs — renamed?");
  // Suffixed so it passes the media line here — the length is the one rule
  // this window deliberately does not copy.
  const long = `https://example.com/${"a".repeat(max)}.mp3`;
  assert.equal(planStream(long, UNRESOLVED).kind, STREAM);
});

test("the vector reader itself still works", () => {
  // Guards the guard: every assertion above would pass vacuously if this
  // stopped finding anything.
  assert.throws(() => rustVectors("a_test_that_does_not_exist", 1), /could not find/);
  assert.throws(
    () => rustVectors("an_ordinary_episode_url_is_accepted", 999),
    /expected at least/,
  );
});
