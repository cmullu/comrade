import { test } from "node:test";
import assert from "node:assert/strict";

import {
  ELSEWHERE,
  NOTHING,
  PICK,
  UNAVAILABLE,
  planPlay,
  playTitle,
} from "./play_flow.mjs";

const song = {
  plan: "find_locally",
  service: null,
  link: null,
  content: null,
  recording: { title: "Kun Faya Kun", artist: "A. R. Rahman", album: null, isrc: null },
};

// ── Every route ends somewhere ───────────────────────────────────────────────

test("every route this window can be handed produces an outcome", () => {
  // The property that matters more than any individual sentence: a command
  // that silently does nothing is exactly the bug /play had on desktop.
  for (const route of [
    "ask_for_file",
    "start_together",
    "open_elsewhere",
    "play_embed",
    "play_on_service",
    "nothing",
  ]) {
    const plan = planPlay(route, song);
    assert.ok(plan.kind, `${route} produced no outcome`);
    assert.ok(plan.message, `${route} said nothing`);
  }
});

test("a route this build has never heard of is refused, not guessed at", () => {
  // Reached when core gains a PlayRoute variant before this file does, which
  // is release skew rather than a bug — but it must not fall through silently.
  const plan = planPlay("teleport_into_their_speakers", song);
  assert.equal(plan.kind, NOTHING);
  assert.ok(plan.message);
});

// ── The seamless path ────────────────────────────────────────────────────────

test("a named recording goes straight to the picker, carrying what to call it", () => {
  const plan = planPlay("ask_for_file", song);
  assert.equal(plan.kind, PICK);
  assert.equal(plan.title, "Kun Faya Kun — A. R. Rahman");
  // Carried so the invitation names the song. Desktop used to send
  // `recording: null` whatever was typed, so the other person saw a blank.
  assert.equal(plan.recording.title, "Kun Faya Kun");
  assert.match(plan.message, /Kun Faya Kun/);
});

test("a query with no words still opens the picker rather than stopping", () => {
  const bare = { ...song, recording: null };
  const plan = planPlay("ask_for_file", bare);
  assert.equal(plan.kind, PICK);
  assert.equal(plan.title, null);
  assert.equal(plan.recording, null);
  assert.ok(plan.message, "the picker still needs a sentence with it");
});

test("finding it locally and being asked for it lead to the same place here", () => {
  // Desktop has no library, so `start_together` is not reachable today. It is
  // still handled, so a library search landing later needs no edit here.
  assert.equal(planPlay("start_together", song).kind, PICK);
});

// ── The honest refusals ──────────────────────────────────────────────────────

test("DRM audio says where it lives instead of pretending", () => {
  const spotify = {
    ...song,
    service: "spotify",
    link: { spotify: { id: "4uLU6hMCjMI75M1A2tKUQC" } },
  };
  const plan = planPlay("open_elsewhere", spotify);
  assert.equal(plan.kind, ELSEWHERE);
  assert.match(plan.message, /Spotify/);
  // It must still offer the route that does work.
  assert.match(plan.message, /\/play a file/);
});

test("the parsed link outranks which alias was typed", () => {
  // `/spotify <an apple music url>` — `service` is a wording hint, the link is
  // what the query turned out to be.
  const mixed = {
    ...song,
    service: "spotify",
    link: { apple_music: { id: "1440913204" } },
  };
  assert.match(planPlay("open_elsewhere", mixed).message, /Apple Music/);
});

test("a link we could not parse still gets a usable sentence", () => {
  const plan = planPlay("open_elsewhere", { ...song, service: null, link: null });
  assert.equal(plan.kind, ELSEWHERE);
  assert.ok(plan.message);
  assert.doesNotMatch(plan.message, /null|undefined/);
});

test("YouTube says it is elsewhere in the product, not impossible", () => {
  const plan = planPlay("play_embed", { ...song, service: "youtube" });
  assert.equal(plan.kind, UNAVAILABLE);
  assert.match(plan.message, /phone app/);
});

test("nothing usable asks for something usable", () => {
  const plan = planPlay("nothing", { ...song, recording: null });
  assert.equal(plan.kind, NOTHING);
  assert.match(plan.message, /\/play/);
});

test("no refusal ever claims we will play it", () => {
  for (const route of ["open_elsewhere", "play_embed", "play_on_service", "nothing"]) {
    const m = planPlay(route, song).message;
    assert.doesNotMatch(m, /I'll start it|starting|playing it now/i, route);
  }
});

// ── Titles ───────────────────────────────────────────────────────────────────

test("a title reads as a person would say it", () => {
  assert.equal(playTitle(song), "Kun Faya Kun — A. R. Rahman");
  assert.equal(
    playTitle({ recording: { title: "Solaris", artist: "" } }),
    "Solaris",
  );
});

test("a title that is only whitespace is no title", () => {
  // It becomes the other person's invitation line, so a blank must read as
  // absent rather than as a name made of spaces.
  for (const rec of [null, undefined, { title: "" }, { title: "   " }]) {
    assert.equal(playTitle({ recording: rec }), null);
  }
  assert.equal(playTitle(null), null);
});

test("an artist with nothing in it does not leave a dangling dash", () => {
  assert.equal(playTitle({ recording: { title: "Solaris", artist: "  " } }), "Solaris");
});

test("the two Spotify sentences do not read alike", () => {
  // One is "you have no account here", the other is "the account works and this
  // window has no player for it". Collapsing them would send someone to fix the
  // wrong thing — which is what the old DRM wording did.
  const spotify = { ...song, service: "spotify" };
  const refused = planPlay("open_elsewhere", spotify).message;
  const unwired = planPlay("play_on_service", spotify).message;
  assert.notEqual(refused, unwired);
  assert.match(refused, /Spotify/);
  assert.match(unwired, /Spotify/);
});

test("a refusal names the missing account, not a DRM lecture", () => {
  // The reason had been wrong since it was written: their SDKs exist precisely
  // so another app can drive playback in their client. What we lack is the
  // connection, and that is fixable — so the sentence must not imply otherwise.
  const m = planPlay("open_elsewhere", { ...song, service: "spotify" }).message;
  assert.doesNotMatch(m, /won't play|can't play|DRM/i);
});
