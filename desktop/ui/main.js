/* ============================================================================
 * Comrade desktop frontend
 *
 * A no-build, vanilla-JS SPA that drives the Tauri "Command & Event Bridge".
 * `withGlobalTauri: true` (tauri.conf.json) exposes window.__TAURI__, so we use
 * window.__TAURI__.core.invoke + window.__TAURI__.event.listen directly.
 *
 * Progressive disclosure:
 *   vault door  ->  base workspace (Sabha | Vault)  ->  modality overlays
 *                                                       (Travel mesh / Couple)
 *
 * Every backend call goes through safeInvoke(), which surfaces errors as toasts
 * (Milestone 5) instead of failing silently in the console.
 * ========================================================================== */

(() => {
  "use strict";

  const STORE_PATH = "comrade-data";
  const EVENT_CHANNEL = "comrade://event";
  // The 10 MB hard limit (Milestone 5) now lives in `attachment_caption.mjs` as
  // MAX_ATTACHMENT_BYTES, next to the message shown when a file exceeds it — one
  // copy, shared with the Flutter app and Android, tested in all three.

  // ── Backend access (real Tauri, or a dev mock for browser preview) ────────
  const TAURI = window.__TAURI__;
  const hasTauri = !!(TAURI && TAURI.core && TAURI.event);
  const backend = hasTauri
    ? {
        invoke: (cmd, args) => TAURI.core.invoke(cmd, args),
        listen: (event, cb) => TAURI.event.listen(event, cb),
      }
    : mockBackend();

  // ── Call decision helpers (desktop/ui/call_decisions.mjs) ──────────────────
  // This file is a plain classic script (index.html loads it as
  // `<script src="main.js">`, no type="module"), so a static `import`
  // statement isn't available here. Dynamic `import()` is spec'd to work
  // from classic scripts too (it isn't restricted to module scripts), so we
  // kick the load off once, at parse time — long before any call signal can
  // plausibly arrive — and every call site below awaits this same cached
  // promise. That's a smaller, safer diff than flipping main.js to
  // type="module" (which would also change the whole file to always-strict
  // semantics and defer-by-default execution timing) for a change that only
  // needs to reuse a couple of pure functions from another file.
  const callDecisionsReady = import("./call_decisions.mjs");

  /**
   * The same module, cached for the handful of call sites that cannot await —
   * a `pointermove` handler dragging the call tile has to answer within the
   * frame. Null only in the milliseconds before the import resolves, long
   * before any call exists to minimise.
   */
  let callDecisions = null;
  callDecisionsReady.then((m) => {
    callDecisions = m;
  }).catch(() => {});

  // ── Draft reports (desktop/ui/draft_reports.mjs) ───────────────────────────
  // Which of "there is unsent text here" / "it is gone" a composer edit or a
  // conversation switch has to report, for `comrade_core::nudge`. Pure and
  // tested there; loaded through the same cached dynamic import as the two
  // modules above — see the note on `callDecisionsReady` for why this file
  // cannot use a static `import`.
  let draftReports = null;
  import("./draft_reports.mjs")
    .then((m) => {
      draftReports = m;
    })
    .catch(() => {});

  // ── Attachment rules (desktop/ui/attachment_caption.mjs) ───────────────────
  // What a new attachment is captioned with, and how one reads when something
  // quotes it. Mirrored in the Flutter app and on Android — same cases, same
  // answers, pinned by three copies of one test. Loaded the same way as the
  // modules above; `attachmentCaptionReady` is for the call sites that can
  // await (sending), the handle for the ones that cannot (rendering a bubble).
  let attachmentCaption = null;
  const attachmentCaptionReady = import("./attachment_caption.mjs");
  attachmentCaptionReady
    .then((m) => {
      attachmentCaption = m;
    })
    .catch(() => {});

  // Degraded, not wrong: the window where the handle is still null closes long
  // before a vault is unlocked and a thread is on screen, and a bubble drawn in
  // it says "Attachment" rather than naming the kind. Nothing is mislabelled.
  const mediaQuoteLabel = (mime, caption) =>
    attachmentCaption ? attachmentCaption.mediaQuoteLabel(mime, caption) : "Attachment";
  const mediaKindLabel = (mime) =>
    attachmentCaption ? attachmentCaption.mediaKindLabel(mime) : "Attachment";
  const opensFullScreen = (mime) =>
    attachmentCaption ? attachmentCaption.opensFullScreen(mime) : false;

  // ── Chat thread rules (desktop/ui/chat_thread.mjs) ─────────────────────────
  // Where tapping a reply's quote goes, and how long the arrival flashes.
  // Mirrored in the Flutter app and on Android. Loaded the same way as the
  // modules above; both call sites run only after a thread is on screen, so the
  // module has long since resolved.
  let chatThread = null;
  import("./chat_thread.mjs")
    .then((m) => {
      chatThread = m;
    })
    .catch(() => {});

  // Degraded, not wrong: before the handle resolves a quote simply is not
  // tappable, which is exactly what it did before this existed.
  const quoteScrollTargetId = (msgs, replyToId) =>
    chatThread ? chatThread.quoteScrollTargetId(msgs, replyToId) : null;

  // ── Focus view decisions (desktop/ui/focus_view.mjs) ───────────────────────
  // Countdown formatting, which duration chip is selected, and where the
  // reader is. Loaded the same way as the modules above. Every call site here
  // is inside a click handler or a render that runs after unlock, so the
  // module has long since resolved; `focusReady` is awaited once before the
  // first paint of the tab so there is no null window at all.
  let focusView = null;
  const focusReady = import("./focus_view.mjs");
  focusReady
    .then((m) => {
      focusView = m;
    })
    .catch(() => {});

  // ── Tiny DOM helpers ──────────────────────────────────────────────────────
  const $ = (sel) => document.querySelector(sel);

  /** Build an element. Dynamic text always goes through textContent (no XSS). */
  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null) continue;
      if (k === "class") node.className = v;
      else if (k === "text") node.textContent = v;
      else if (k.startsWith("on") && typeof v === "function")
        node.addEventListener(k.slice(2).toLowerCase(), v);
      else node.setAttribute(k, v);
    }
    for (const c of children.flat()) {
      if (c == null) continue;
      node.append(c.nodeType ? c : document.createTextNode(String(c)));
    }
    return node;
  }

  const nowSecs = () => Math.floor(Date.now() / 1000);

  /**
   * Resolve a stable on-disk location for the encrypted vault. In a packaged
   * app the cwd is unpredictable, so prefer the OS app-data dir when Tauri's
   * path API is exposed; otherwise fall back to a cwd-relative folder.
   */
  async function resolveStorePath() {
    try {
      if (TAURI && TAURI.path && TAURI.path.appDataDir && TAURI.path.join) {
        const base = await TAURI.path.appDataDir();
        return await TAURI.path.join(base, "comrade-vault");
      }
    } catch {
      /* fall through to the relative default */
    }
    return STORE_PATH;
  }

  function shortNpub(s) {
    s = String(s || "");
    return s.length > 18 ? `${s.slice(0, 11)}…${s.slice(-5)}` : s;
  }

  function relTime(secs) {
    if (!secs) return "just now";
    const d = nowSecs() - Number(secs);
    if (d < 45) return "just now";
    if (d < 3600) return `${Math.floor(d / 60)}m ago`;
    if (d < 86400) return `${Math.floor(d / 3600)}h ago`;
    return new Date(Number(secs) * 1000).toLocaleString();
  }

  function errText(e) {
    if (typeof e === "string") return e;
    if (e && e.message) return e.message;
    try {
      return JSON.stringify(e);
    } catch {
      return String(e);
    }
  }

  function debounce(fn, ms) {
    let t;
    return (...a) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...a), ms);
    };
  }

  function setBusy(btn, busy) {
    if (!btn) return;
    btn.disabled = busy;
    btn.classList.toggle("is-busy", busy);
    const sp = btn.querySelector(".spinner");
    if (sp) sp.hidden = !busy;
  }

  // ── Toasts (Milestone 5) ──────────────────────────────────────────────────
  function showToast(message, type = "info", title) {
    const icons = { error: "⛔", success: "✓", info: "ℹ", warn: "⚠" };
    const toast = el(
      "div",
      { class: `toast ${type}`, role: "status" },
      el("span", { class: "toast-icon", text: icons[type] || "ℹ" }),
      el(
        "div",
        { class: "toast-body" },
        title ? el("div", { class: "toast-title", text: title }) : null,
        el("div", { text: message }),
      ),
    );
    $("#toasts").append(toast);
    const ttl = type === "error" ? 6500 : 3500;
    setTimeout(() => {
      toast.classList.add("leaving");
      setTimeout(() => toast.remove(), 250);
    }, ttl);
  }

  /** Single funnel for IPC: try/catch with an error toast, then rethrow. */
  async function safeInvoke(cmd, args, opts = {}) {
    try {
      return await backend.invoke(cmd, args);
    } catch (e) {
      if (!opts.silent) showToast(errText(e), "error", "Backend error");
      throw e;
    }
  }

  // ── App state ─────────────────────────────────────────────────────────────
  const state = {
    identity: null,
    workspace: null,
    chitthis: [],
    seenChitthi: new Set(),
    // peer pubkey -> [{ id?, content?, media?, created_at, outgoing, upi, status?, reply_to? }]
    dms: new Map(),
    activeContact: null,
    coupleRole: "sakha",
    partnerNpub: null,
    // Milestone 6: comms
    requests: [], // pending stranger DMs: [{ peer, last_message, last_at }]
    peerNames: new Map(), // peer pubkey -> published display handle
    // Comrade presence: peer pubkey -> { comrade, online, lastSeenAt, peerMarkedUs }.
    // Only ever populated for peers the user chose (and, for `peerMarkedUs`,
    // whether they chose back) — see docs/PRESENCE.md.
    presence: new Map(),
    replyTo: null, // { id, content, outgoing } while composing a reply
    call: null, // active call session (see newCallState)
    // Watch/listen together (docs/TOGETHER.md): the local file, its object URL,
    // the live session id, and the echo suppressor that keeps a remote seek
    // from being re-broadcast as a local one.
    together: null,
    // A file handover in progress, when only one side has what is playing.
    // Its own RTCPeerConnection, never the call's — see newShareState.
    share: null,
    // Bounded memory of recently-ended call ids (see call_decisions.mjs
    // rememberEndedCall) — mirrors Android's CallManager.endedCallIds, so a
    // redelivered Offer for a call we already tore down doesn't ring again.
    endedCallIds: [],
    // Attention practice (docs/ATTENTION.md phase 2). `presets`/`suggested`
    // are the engine's, never this file's; `chosen` is only what the user
    // clicked, resolved against the presets by focus_view.chosenPreset.
    focus: {
      presets: [],
      suggested: 0,
      chosen: null,
      prompt: "",
      active: null,
      history: [],
      reflection: null,
      reading: null,
      tick: null,
    },
  };

  // Prefer a peer's published handle over the raw npub when we have one.
  function displayName(peer) {
    const n = state.peerNames.get(peer);
    return n ? n : shortNpub(peer);
  }

  // ── Media helpers ─────────────────────────────────────────────────────────
  function fileToBase64(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => {
        const s = String(r.result);
        resolve(s.slice(s.indexOf(",") + 1)); // strip "data:...;base64,"
      };
      r.onerror = () => reject(r.error);
      r.readAsDataURL(file);
    });
  }

  function base64ToBlob(b64, mime) {
    const bin = atob(b64);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return new Blob([arr], { type: mime || "application/octet-stream" });
  }

  // ── Decrypted-media object URLs, bounded and revocable ────────────────────
  //
  // An object URL pins its Blob — the *decrypted* attachment — in memory until
  // it is revoked, and this UI never revoked one: every attachment viewed in a
  // session stayed in the webview's heap for the life of the process, and
  // re-unlocking as a different identity left the previous one's plaintext
  // behind. Android bounded the same cache at 24 bitmaps and purged it on
  // backgrounding; this is the desktop equivalent.
  //
  // The eviction policy lives in media_cache.mjs (with node tests) for the same
  // reason the call decisions do: the rule is testable, the DOM glue is not.
  // Loaded through the same cached dynamic import as call_decisions.mjs — see
  // its note above for why this file cannot use a static `import`.
  const MEDIA_URL_CAPACITY = 8;
  const mediaCacheReady = import("./media_cache.mjs").then(
    ({ createBlobUrlCache }) =>
      createBlobUrlCache({
        create: (blob) => URL.createObjectURL(blob),
        revoke: (url) => URL.revokeObjectURL(url),
        capacity: MEDIA_URL_CAPACITY,
        // Forget the bubble's own reference, so a re-view re-fetches instead of
        // rendering a dead `blob:` link.
        onEvict: (eventId) => {
          for (const msgs of state.dms.values()) {
            for (const m of msgs) {
              if (m.media && m.media.eventId === eventId) m.media.objectUrl = null;
            }
          }
        },
      }),
  );

  /** Mint (or reuse) the object URL for one attachment. */
  async function mediaUrlFor(eventId, blob) {
    return (await mediaCacheReady).get(eventId, blob);
  }

  /** Drop every decrypted attachment this window is holding — the deliberate
   *  version of "the app should not be sitting on plaintext". */
  async function revokeAllMediaUrls() {
    (await mediaCacheReady).clear();
  }

  function renderMediaEl(mime, url, caption = "") {
    if (mime.startsWith("image/")) {
      return el("img", {
        class: "media-img media-zoomable",
        src: url,
        alt: caption || "Photo attachment",
        title: "Click to view full screen",
        onClick: () => openLightbox(mime, url, caption),
      });
    }
    if (mime.startsWith("audio/")) return el("audio", { controls: "", src: url });
    if (mime.startsWith("video/")) {
      const wrap = el("div", { class: "media-video" });
      wrap.append(el("video", { class: "media-img", controls: "", src: url }));
      wrap.append(
        el("button", {
          class: "btn btn-ghost btn-sm",
          text: "⛶ Full screen",
          onClick: () => openLightbox(mime, url, caption),
        }),
      );
      return wrap;
    }
    return el("a", { href: url, download: "comrade-media", text: "Download file" });
  }

  // A photo or a video, full screen: black backdrop, the caption if there is
  // one, and a way out (the ✕, a click on the backdrop, or Escape). Same
  // decision as `MediaViewerDialog` on Android and `media_viewer.dart` in the
  // Flutter app — including the black backdrop, which is not decoration: a
  // photo is judged against black and the chrome must not tint it.
  //
  // The object URL is the one the bubble already holds, so opening this costs
  // no second fetch and `revokeAllMediaUrls` still drops exactly one copy.
  function openLightbox(mime, url, caption) {
    if (!opensFullScreen(mime)) return;
    const existing = $("#media-lightbox");
    if (existing) existing.remove();

    const close = () => {
      overlay.remove();
      document.removeEventListener("keydown", onKey);
    };
    const onKey = (e) => {
      if (e.key === "Escape") close();
    };

    const inner = mime.startsWith("image/")
      ? el("img", { class: "lightbox-media", src: url, alt: caption || "Photo attachment" })
      : el("video", { class: "lightbox-media", controls: "", autoplay: "", src: url });

    const overlay = el(
      "div",
      {
        id: "media-lightbox",
        class: "lightbox",
        // Only a click on the backdrop itself closes — one on the photo must
        // not, or zooming in on a detail becomes a game of not missing.
        onClick: (e) => {
          if (e.target === overlay) close();
        },
      },
      el(
        "div",
        { class: "lightbox-bar" },
        el("button", {
          class: "btn btn-ghost btn-sm",
          id: "lightbox-close",
          text: "✕",
          title: "Close",
          onClick: close,
        }),
        el("span", { class: "lightbox-kind", text: mediaKindLabel(mime) }),
      ),
      inner,
      caption ? el("div", { class: "lightbox-caption", text: caption }) : null,
    );
    document.addEventListener("keydown", onKey);
    document.body.append(overlay);
  }

  // The last look before an attachment leaves the machine.
  //
  // Telegram's shape: pick a file and you get the file itself, a caption box, and
  // two ways out. This UI had none of it — a pick went straight to encrypt and
  // upload, so the first sight of what had been chosen was in one's own thread,
  // already sent. Resolves to the confirmed caption, or `null` for every way of
  // backing out: the ✕, Cancel, Escape and a click on the backdrop are all "don't
  // send this" and none of them should be distinguishable from the caller.
  //
  // The object URL is created here and revoked on close, so an abandoned preview
  // leaves nothing behind — and unlike the Android and Flutter ports, a video
  // really does play, because a blob URL is in memory and needs no file on disk
  // (AUDIT S-4).
  async function openAttachmentPreview(file, seedCaption) {
    // Awaited here rather than read off the module-level handle: this is the one
    // call site that needs *every* rule at once, and awaiting removes any
    // question of whether the handle has been assigned yet.
    const rules = await attachmentCaptionReady;
    const { attachmentPreviewKind, attachmentPreviewDetail, normalizeCaption } = rules;
    const mime = file.type || "application/octet-stream";
    const url = URL.createObjectURL(file);

    return new Promise((resolve) => {
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        URL.revokeObjectURL(url);
        overlay.remove();
        document.removeEventListener("keydown", onKey);
        resolve(value);
      };
      const onKey = (e) => {
        if (e.key === "Escape") finish(null);
      };

      const caption = el("textarea", {
        id: "attachment-preview-caption",
        class: "attach-preview-caption",
        rows: "2",
        placeholder: "Add a caption…",
        maxlength: String(rules.MAX_CAPTION_LENGTH),
      });
      caption.value = seedCaption;
      // Enter sends, Shift+Enter is a newline — the same bargain the DM box makes.
      caption.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          finish(normalizeCaption(caption.value));
        }
      });

      const kind = attachmentPreviewKind(mime);
      let media;
      if (kind === "image") {
        media = el("img", { class: "attach-preview-media", src: url, alt: file.name || "Photo" });
      } else if (kind === "video") {
        media = el("video", { class: "attach-preview-media", controls: "", src: url });
      } else {
        media = el(
          "div",
          { class: "attach-preview-card" },
          el("div", { class: "attach-preview-glyph", text: rules.mediaKindGlyph(mime) }),
          el("div", { text: rules.mediaKindLabel(mime) }),
        );
      }

      const overlay = el(
        "div",
        {
          id: "attachment-preview",
          class: "lightbox lightbox-scrim",
          // Only a click on the backdrop itself: one on the photo or in the
          // caption box must not throw away what is being written.
          onClick: (e) => {
            if (e.target === overlay) finish(null);
          },
        },
        el(
          "div",
          { class: "attach-preview" },
          el(
            "div",
            { class: "attach-preview-head" },
            el(
              "div",
              { class: "attach-preview-titles" },
              el("div", { class: "attach-preview-kind", text: rules.mediaQuoteLabel(mime, "") }),
              el("div", {
                // The one place the machine's own filename belongs: it answers
                // "is this the file I meant", and it is deliberately not what
                // gets sent as the caption.
                class: "attach-preview-detail",
                id: "attachment-preview-detail",
                text: attachmentPreviewDetail(file.name, file.size),
              }),
            ),
            el("button", {
              // `icon-btn`, the same control the reply chip cancels with — a
              // bordered `btn-sm` reads as heavy as Send in a two-line header.
              class: "icon-btn",
              id: "attachment-preview-close",
              text: "✕",
              title: "Discard",
              onClick: () => finish(null),
            }),
          ),
          // Only the picture scrolls. The heading, the caption box and the two
          // buttons stay put, because on a short window they are the parts that
          // must not go looking for a scrollbar.
          el("div", { class: "attach-preview-body" }, media),
          caption,
          el(
            "div",
            { class: "attach-preview-actions" },
            el("button", {
              class: "btn btn-ghost",
              id: "attachment-preview-cancel",
              text: "Cancel",
              onClick: () => finish(null),
            }),
            el("button", {
              class: "btn btn-primary",
              id: "attachment-preview-send",
              text: "Send",
              onClick: () => finish(normalizeCaption(caption.value)),
            }),
          ),
        ),
      );
      document.addEventListener("keydown", onKey);
      document.body.append(overlay);
    });
  }

  // ── Screen + theme management (progressive disclosure) ────────────────────
  function setScreen(name) {
    document.body.dataset.screen = name;
    $("#screen-vault").hidden = name !== "vault";
    $("#screen-app").hidden = name !== "app";
    $("#screen-couple").hidden = name !== "couple";
  }

  function themeClass(key) {
    switch (key) {
      case "OffGridTravel":
        return "theme-travel";
      case "CoupleSandboxSakha":
        return "theme-couple-sakha";
      case "CoupleSandboxSakhi":
        return "theme-couple-sakhi";
      default:
        return "theme-base";
    }
  }

  function setPill(node, on) {
    node.classList.toggle("on", !!on);
    node.classList.toggle("off", !on);
  }

  /** Apply a WorkspaceDto: re-theme, update indicators, pick the right screen. */
  function applyWorkspace(ws) {
    if (!ws) return;
    state.workspace = ws;
    document.body.className = themeClass(ws.key);

    $("#ws-badge").textContent = ws.mesh_active
      ? "Off-Grid"
      : ws.couple_sandbox
        ? "Couples"
        : "Base";
    setPill($("#pill-relays"), ws.relay_connected);
    setPill($("#pill-mesh"), ws.mesh_active);
    $("#travel-toggle").checked = !!ws.mesh_active;

    if (ws.couple_sandbox) {
      $("#couple-role").textContent = ws.key.endsWith("Sakhi") ? "Sakhi" : "Sakha";
      setScreen("couple");
      // Pull the authoritative pairing state (partner key, ledger form
      // enablement, ledger content) — fire-and-forget so entering the
      // screen never blocks on it.
      refreshSakhaStatus().catch(() => {});
    } else {
      setScreen("app");
    }
  }

  // ── Milestone 1: Vault initialization ─────────────────────────────────────
  async function handleUnlock(e) {
    e.preventDefault();
    const pass = $("#passphrase").value.trim();
    if (!pass) {
      showToast("Enter a passphrase to continue", "warn");
      return;
    }
    const btn = $("#unlock-btn");
    setBusy(btn, true);
    try {
      const path = await resolveStorePath();
      const id = await safeInvoke("unlock_comrade_vault", {
        path,
        passphrase: pass,
      });
      state.identity = id;
      $("#identity-npub").textContent = shortNpub(id.npub);
      $("#passphrase").value = "";
      // A fresh unlock may be a different identity in the same window: nothing
      // decrypted for the previous one may still be renderable.
      await revokeAllMediaUrls();
      // The together file is a separate object URL on purpose (an LRU eviction
      // mid-session would revoke it and kill playback), so it is revoked here
      // rather than by `revokeAllMediaUrls`.
      endShare();
      if (state.together?.objectUrl) URL.revokeObjectURL(state.together.objectUrl);
      state.together = null;
      onTogetherOver();
      // A different identity has a different practice: drop the previous
      // one's session, history and half-read text rather than leaving them on
      // screen under the new npub.
      stopFocusTick();
      state.focus.active = null;
      state.focus.history = [];
      state.focus.reading = null;
      state.focus.reflection = null;
      state.focus.chosen = null;
      showToast(`Vault unlocked · ${shortNpub(id.npub)}`, "success");

      const ws = await safeInvoke("current_workspace", undefined, {
        silent: true,
      }).catch(() => ({
        key: "Base",
        label: "Base",
        relay_connected: true,
        mesh_active: false,
        couple_sandbox: false,
      }));
      applyWorkspace(ws);
      await loadTimeline();
      await loadConversations();
      await loadRequests();
      await loadComrades();
      // Cheap, and it means a session left running in a previous launch is
      // resolved (completed or lapsed) the moment the vault opens, not
      // whenever the user happens to visit the tab.
      await loadFocus();
    } catch {
      /* error already toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  // ── Milestone 2: Sabha timeline ───────────────────────────────────────────
  async function loadTimeline() {
    const loading = $("#sabha-loading");
    const empty = $("#sabha-empty");
    const feed = $("#sabha-feed");
    empty.hidden = true;
    feed.hidden = true;
    loading.hidden = false;
    try {
      const items = await safeInvoke("fetch_sabha_timeline");
      state.chitthis = Array.isArray(items) ? items : [];
      state.seenChitthi = new Set(state.chitthis.map((c) => c.id));
      renderFeed();
    } catch {
      /* toasted */
    } finally {
      loading.hidden = true;
    }
  }

  function chitthiCard(c, isNew = false) {
    return el(
      "article",
      { class: "chitthi" + (isNew ? " is-new" : "") },
      el(
        "div",
        { class: "chitthi-meta" },
        el("span", { class: "chitthi-author", text: shortNpub(c.author || "anon") }),
        el("span", { class: "chitthi-time", text: relTime(c.created_at) }),
      ),
      el("div", { class: "chitthi-body", text: c.content || "" }),
      c.reply_to
        ? el("div", {
            class: "chitthi-reply",
            text: `↳ reply to ${String(c.reply_to).slice(0, 12)}…`,
          })
        : null,
    );
  }

  function renderFeed() {
    const feed = $("#sabha-feed");
    const empty = $("#sabha-empty");
    feed.innerHTML = "";
    if (!state.chitthis.length) {
      empty.hidden = false;
      feed.hidden = true;
      return;
    }
    empty.hidden = true;
    feed.hidden = false;
    for (const c of state.chitthis) feed.append(chitthiCard(c));
  }

  /** Milestone 3: seamlessly prepend a freshly received/sent Chitthi. */
  function prependChitthi(c, isNew = false) {
    if (c.id && state.seenChitthi.has(c.id)) return;
    if (c.id) state.seenChitthi.add(c.id);
    state.chitthis.unshift(c);
    $("#sabha-empty").hidden = true;
    const feed = $("#sabha-feed");
    feed.hidden = false;
    feed.prepend(chitthiCard(c, isNew));
  }

  async function handleBroadcast() {
    const input = $("#chitthi-input");
    const content = input.value.trim();
    if (!content) {
      showToast("Write a Chitthi first", "warn");
      return;
    }
    const btn = $("#broadcast-btn");
    setBusy(btn, true);
    try {
      const id = await safeInvoke("broadcast_chitthi", { content, replyTo: null });
      input.value = "";
      updateCount();
      showToast("Chitthi broadcast to Sabha", "success");
      prependChitthi(
        {
          id,
          author: state.identity ? state.identity.npub : "you",
          content,
          created_at: nowSecs(),
          reply_to: null,
        },
        true,
      );
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  function updateCount() {
    const v = $("#chitthi-input").value;
    $("#chitthi-count").textContent = `${v.length} / 2000`;
  }

  // ── Tabs ──────────────────────────────────────────────────────────────────
  function switchTab(name) {
    for (const t of document.querySelectorAll(".tab")) {
      const on = t.dataset.tab === name;
      t.classList.toggle("is-active", on);
      t.setAttribute("aria-selected", on ? "true" : "false");
    }
    $("#view-sabha").hidden = name !== "sabha";
    $("#view-vault").hidden = name !== "vault";
    $("#view-focus").hidden = name !== "focus";
    // The countdown only has to tick while it is being looked at; a session
    // left running behind another tab is still authoritative in the engine,
    // which is where the remaining time comes from on the next paint.
    if (name === "focus") loadFocus();
    else stopFocusTick();
  }

  // ── Milestone 2/3: Vault DMs ──────────────────────────────────────────────
  function onIncomingDm(p) {
    const key = p.sender || "unknown";
    const list = state.dms.get(key) || [];
    list.push({
      id: p.id,
      content: p.content || "",
      created_at: p.created_at,
      outgoing: false,
      upi: p.upi_intents || [],
      reply_to: p.reply_to || null,
    });
    state.dms.set(key, list);
    renderContacts();
    if (state.activeContact === key) renderConversation();
    showToast(`New encrypted DM from ${shortNpub(key)}`, "info");
  }

  function renderContacts() {
    const list = $("#contact-list");
    const empty = $("#contacts-empty");
    list.innerHTML = "";
    const keys = [...state.dms.keys()];
    empty.hidden = keys.length > 0;
    for (const k of keys) {
      const msgs = state.dms.get(k);
      const last = msgs[msgs.length - 1];
      list.append(
        el(
          "li",
          {
            class: "contact" + (k === state.activeContact ? " is-active" : ""),
            onClick: () => selectContact(k),
          },
          el(
            "div",
            { class: "contact-title" },
            ...(presenceOf(k).comrade
              ? [
                  el("span", {
                    class: "presence-dot" + (presenceOf(k).online ? " is-online" : ""),
                    title: presenceLabel(presenceOf(k)),
                    "aria-label": presenceLabel(presenceOf(k)),
                  }),
                ]
              : []),
            el("span", { class: "contact-name", text: displayName(k) }),
          ),
          el("span", {
            class: "contact-last",
            text: last
              ? last.content ||
                (last.media ? `📎 ${last.media.caption || "media"}` : "")
              : "",
          }),
        ),
      );
    }
  }

  /** Seed the contact list from the persisted offline history (chat list). */
  async function loadConversations() {
    let convos;
    try {
      convos = await safeInvoke("conversations", undefined, { silent: true });
    } catch {
      return; // older backend without the command — live events still work
    }
    for (const c of convos || []) {
      if (c.comrade) {
        const prev = presenceOf(c.peer);
        state.presence.set(c.peer, { ...prev, comrade: true, online: !!c.online });
      }
      if (!state.dms.has(c.peer)) {
        state.dms.set(c.peer, [
          { content: c.last_message, created_at: c.last_at, outgoing: !!c.last_outgoing, upi: [] },
        ]);
      }
    }
    renderContacts();
  }

  function selectContact(key) {
    // Leaving a conversation with text still in the box abandons that draft,
    // and the text left behind belongs to whoever is on screen next — both
    // decided in draft_reports.mjs, where the re-attribution is tested.
    if (draftReports) {
      performDraftReports(
        draftReports.switchReports(state.activeContact, key, $("#dm-input").value),
      );
    }
    state.activeContact = key;
    clearReply();
    $("#dm-input").disabled = false;
    $("#dm-attach").disabled = false;
    $("#dm-send").disabled = false;
    renderContacts();
    renderConversation();
    showTogetherPanel();
    // Opening a conversation clears its unread state and sends read receipts.
    safeInvoke("mark_conversation_read", { peer: key }, { silent: true }).catch(() => {});
    // Pull the full persisted thread — text history plus persisted media
    // history — and merge in any live media bubbles from this session ahead
    // of their persisted duplicate (a live one may already hold a decrypted
    // objectUrl, which a freshly-fetched persisted row never does).
    Promise.all([
      safeInvoke("messages_with", { peer: key }, { silent: true }).catch(() => []),
      safeInvoke("media_with", { peer: key }, { silent: true }).catch(() => []),
    ]).then(([msgs, mediaHistory]) => {
      if (state.activeContact !== key) return;
      const texts = Array.isArray(msgs) ? msgs : [];
      const liveMedia = (state.dms.get(key) || []).filter((m) => m.media);
      const seenEventIds = new Set(liveMedia.map((m) => m.media.eventId));
      const persistedMedia = (Array.isArray(mediaHistory) ? mediaHistory : [])
        .filter((m) => !seenEventIds.has(m.event_id))
        .map((m) => ({
          created_at: m.created_at,
          outgoing: !!m.outgoing,
          // `id` is the NIP-94 event id: what a reply's `e` tag names, and
          // what quotePreview looks an original up by. Without it an
          // attachment is unrepliable and unquotable — it was, until now.
          id: m.event_id,
          media: { eventId: m.event_id, mime: m.mime_type, caption: m.caption },
        }));
      if (!texts.length && !liveMedia.length && !persistedMedia.length) return;
      const merged = texts
        .map((m) => ({
          id: m.id,
          content: m.content,
          created_at: m.created_at,
          outgoing: !!m.outgoing,
          upi: [],
          status: m.status || null,
          reply_to: m.reply_to || null,
        }))
        .concat(liveMedia)
        .concat(persistedMedia)
        .sort((a, b) => a.created_at - b.created_at);
      state.dms.set(key, merged);
      renderConversation();
    });
  }

  /** Send the composed DM to the active contact (real end-to-end send). */
  async function handleDmSend() {
    const input = $("#dm-input");
    const content = input.value.trim();
    if (!content) return;
    if (!state.activeContact) {
      showToast("Select a conversation first", "warn");
      return;
    }
    const btn = $("#dm-send");
    const replyTo = state.replyTo;
    setBusy(btn, true);
    try {
      const msg = replyTo
        ? await safeInvoke("send_dm_reply", {
            target: state.activeContact,
            content,
            replyTo: replyTo.id,
          })
        : await safeInvoke("send_dm", {
            target: state.activeContact,
            content,
          });
      input.value = "";
      const preview = $("#dm-upi-preview");
      preview.hidden = true;
      preview.innerHTML = "";
      clearReply();
      const list = state.dms.get(state.activeContact) || [];
      list.push({
        id: msg.id,
        content: msg.content,
        created_at: msg.created_at,
        outgoing: true,
        upi: [],
        status: msg.status || "sent",
        reply_to: msg.reply_to || (replyTo ? replyTo.id : null),
      });
      state.dms.set(state.activeContact, list);
      renderContacts();
      renderConversation();
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  function renderConversation() {
    const log = $("#chat-log");
    const head = $("#chat-header");
    log.innerHTML = "";
    head.innerHTML = "";
    if (!state.activeContact) {
      head.append(el("span", { class: "muted", text: "Select a conversation" }));
      return;
    }
    const peer = state.activeContact;
    const presence = presenceOf(peer);
    head.append(
      el("span", { class: "chat-peer mono", text: displayName(peer) }),
      el("span", {
        class: "chat-presence" + (presence.online ? " is-online" : ""),
        // Honest about the mutual model: a comrade who hasn't chosen back
        // will never show as online, and the header says why rather than
        // leaving a grey dot to be misread as "they're ignoring me".
        text: presenceLabel(presence),
      }),
      el(
        "div",
        { class: "chat-actions" },
        el("button", {
          class: "icon-btn" + (presence.comrade ? " is-on" : ""),
          title: presence.comrade
            ? "Remove as comrade (they stop seeing you online)"
            : "Make a comrade (they see you online; you see them once they choose you back)",
          "aria-label": presence.comrade ? "Remove as comrade" : "Make a comrade",
          text: presence.comrade ? "★" : "☆",
          onClick: () => toggleComrade(peer),
        }),
        el("button", {
          class: "icon-btn",
          title: "Voice call",
          "aria-label": "Start voice call",
          text: "📞",
          onClick: () => startCall(peer, "audio"),
        }),
        el("button", {
          class: "icon-btn",
          title: "Video call",
          "aria-label": "Start video call",
          text: "🎥",
          onClick: () => startCall(peer, "video"),
        }),
      ),
    );
    const msgs = state.dms.get(state.activeContact) || [];
    for (const m of msgs) {
      log.append(m.media ? mediaBubble(m) : textBubble(m));
      if (m.upi && m.upi.length) {
        for (const i of m.upi)
          log.append(
            el("div", {
              class: "upi-chip",
              text: `₹${Number(i.amount_inr).toFixed(2)} → ${i.vpa}`,
            }),
          );
      }
    }
    log.scrollTop = log.scrollHeight;
  }

  function textBubble(m) {
    const wrap = el("div", { class: "bubble " + (m.outgoing ? "out" : "in") });
    // The anchor a quote tap scrolls to. Only messages a relay has confirmed
    // have an id, and only those can be a reply target in the first place.
    if (m.id) wrap.dataset.msgId = m.id;
    if (m.reply_to) wrap.append(quotePreview(m.reply_to));
    wrap.append(el("span", { class: "bubble-text", text: m.content }));
    wrap.append(
      el(
        "div",
        { class: "bubble-meta" },
        el("span", { class: "bubble-time", text: relTime(m.created_at) }),
        m.outgoing && m.status ? statusTick(m.status) : null,
      ),
    );
    // A reply is only addressable if we know the target message's event id.
    if (m.id) wrap.append(replyButton(m));
    return wrap;
  }

  // ── Milestone 6: replies, receipts, requests, calls ───────────────────────

  /**
   * A quoted preview of the replied-to message, looked up in the open thread.
   *
   * Tappable when the original is in the thread, so a reply can be followed back
   * to what it answers. When it is not — history older than what is loaded — the
   * quote still says "Original message" but is inert: offering a tap that cannot
   * work is worse than not offering one.
   */
  function quotePreview(replyToId) {
    const msgs = state.dms.get(state.activeContact) || [];
    const q = msgs.find((x) => x.id && x.id === replyToId);
    const text = q
      ? q.content ||
        (q.media ? mediaQuoteLabel(q.media.mime, q.media.caption) : "message")
      : "Original message";
    const targetId = quoteScrollTargetId(msgs, replyToId);
    if (!targetId) {
      return el(
        "div",
        { class: "bubble-quote" },
        el("span", { class: "bubble-quote-text", text: text }),
      );
    }
    const node = el(
      "button",
      {
        class: "bubble-quote bubble-quote-link",
        type: "button",
        title: "Go to the quoted message",
        "aria-label": "Go to the quoted message",
      },
      el("span", { class: "bubble-quote-text", text: text }),
    );
    node.addEventListener("click", (e) => {
      // The bubble itself has handlers; a quote tap means "go there", not
      // "act on this message".
      e.stopPropagation();
      goToQuoted(targetId);
    });
    return node;
  }

  /**
   * Scroll the thread to the message with event id [targetId] and flash it.
   *
   * The flash is not decoration. The scroll lands the target somewhere in a
   * screenful of other messages and says nothing about which one it was; without
   * the highlight the jump reads as the thread having lost your place.
   */
  function goToQuoted(targetId) {
    const log = $("#dm-log");
    if (!log) return;
    const target = log.querySelector(`[data-msg-id="${CSS.escape(targetId)}"]`);
    if (!target) return;
    target.scrollIntoView({ behavior: "smooth", block: "center" });
    // Clear any flash still running, so tapping through a chain of replies keeps
    // exactly one message highlighted — the one you are actually on.
    for (const prev of log.querySelectorAll(".bubble-jumped")) {
      prev.classList.remove("bubble-jumped");
    }
    target.classList.add("bubble-jumped");
    const ms = chatThread ? chatThread.QUOTE_HIGHLIGHT_MS : 1400;
    setTimeout(() => target.classList.remove("bubble-jumped"), ms);
  }

  /** Delivery-status ticks for an outgoing bubble. */
  function statusTick(status) {
    const glyph = status === "sent" ? "✓" : "✓✓";
    return el("span", {
      class: "bubble-status" + (status === "read" ? " read" : ""),
      title: status,
      text: glyph,
    });
  }

  function replyButton(m) {
    return el("button", {
      class: "bubble-reply",
      title: "Reply",
      "aria-label": "Reply to this message",
      text: "↩",
      onClick: (e) => {
        e.stopPropagation();
        setReply(m);
      },
    });
  }

  function setReply(m) {
    if (!m || !m.id) return;
    const content =
      m.content || (m.media ? mediaQuoteLabel(m.media.mime, m.media.caption) : "message");
    state.replyTo = { id: m.id, content, outgoing: !!m.outgoing };
    $("#dm-reply-text").textContent = content;
    $("#dm-reply-chip").hidden = false;
    const input = $("#dm-input");
    if (!input.disabled) input.focus();
  }

  function clearReply() {
    state.replyTo = null;
    const chip = $("#dm-reply-chip");
    if (chip) chip.hidden = true;
  }

  // ── Delivered / read receipts ──────────────────────────────────────────────
  const STATUS_RANK = { sent: 1, delivered: 2, read: 3 };

  function onMessageStatus(p) {
    const list = state.dms.get(p.peer);
    if (!list) return;
    const ids = new Set(p.message_ids || []);
    const next = p.status;
    let changed = false;
    for (const m of list) {
      if (!m.outgoing || !m.id || !ids.has(m.id)) continue;
      // Never regress a status (a late "delivered" must not undo "read").
      if ((STATUS_RANK[next] || 0) >= (STATUS_RANK[m.status] || 0)) {
        m.status = next;
        changed = true;
      }
    }
    if (changed && state.activeContact === p.peer) renderConversation();
  }

  function onPeerProfileUpdated(p) {
    if (!p.peer) return;
    if (p.name) state.peerNames.set(p.peer, p.name);
    else state.peerNames.delete(p.peer);
    renderContacts();
    renderRequests();
    if (state.activeContact === p.peer) renderConversation();
  }

  // ── Comrades (chosen-peer presence) ───────────────────────────────────────

  function presenceOf(peer) {
    return (
      state.presence.get(peer) || { comrade: false, online: false, lastSeenAt: 0, peerMarkedUs: false }
    );
  }

  /**
   * How a peer's presence reads, in the same vocabulary the phone uses
   * (see `android/.../DisplayName.kt` lastSeenOf): "online" while they are,
   * a relative sighting while it is fresh, a wall clock once it isn't, a date
   * beyond that — and an honest explanation when there is nothing to show.
   * Returns "" for a peer who isn't a comrade: we know nothing about them and
   * must not imply otherwise.
   */
  function presenceLabel(presence) {
    if (!presence.comrade) return "";
    if (presence.online) return "online";
    if (!presence.lastSeenAt) {
      return presence.peerMarkedUs ? "last seen recently" : "waiting for them to choose you back";
    }
    const seen = new Date(presence.lastSeenAt * 1000);
    const ageSecs = Math.max(0, nowSecs() - presence.lastSeenAt);
    if (ageSecs < 60) return "last seen just now";
    if (ageSecs < 3600) {
      const mins = Math.floor(ageSecs / 60);
      return `last seen ${mins} minute${mins === 1 ? "" : "s"} ago`;
    }
    const time = seen.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    const today = new Date();
    const sameDay = (a, b) => a.toDateString() === b.toDateString();
    const yesterday = new Date(today.getTime() - 86_400_000);
    if (sameDay(seen, today)) return `last seen at ${time}`;
    if (sameDay(seen, yesterday)) return `last seen yesterday at ${time}`;
    if (ageSecs < 7 * 86_400) {
      return `last seen ${seen.toLocaleDateString(undefined, { weekday: "long" })} at ${time}`;
    }
    const sameYear = seen.getFullYear() === today.getFullYear();
    const date = seen.toLocaleDateString(undefined, {
      day: "numeric",
      month: "short",
      ...(sameYear ? {} : { year: "numeric" }),
    });
    return `last seen ${date}`;
  }

  /** Load who was chosen as a comrade, and what their last beacon said. */
  async function loadComrades() {
    let rows;
    try {
      rows = await safeInvoke("comrades", undefined, { silent: true });
    } catch {
      return; // older backend without the command
    }
    state.presence = new Map(
      (Array.isArray(rows) ? rows : []).map((c) => [
        c.npub,
        {
          comrade: true,
          online: !!c.online,
          lastSeenAt: c.last_seen_at || 0,
          peerMarkedUs: !!c.peer_marked_us,
        },
      ]),
    );
    renderContacts();
    if (state.activeContact) renderConversation();
  }

  /** Choose (or un-choose) a peer as a comrade. */
  async function toggleComrade(peer) {
    const wasComrade = presenceOf(peer).comrade;
    try {
      await safeInvoke("set_comrade", { npub: peer, comrade: !wasComrade });
    } catch {
      return; // safeInvoke already surfaced the error
    }
    showToast(
      wasComrade
        ? `${displayName(peer)} is no longer a comrade — they stop seeing you online.`
        : `${displayName(peer)} is a comrade. They see you online, and you'll see them once they choose you back.`,
      "info",
    );
    await loadComrades();
  }

  /**
   * Perform draft reports decided by `draft_reports.mjs`. Silent and
   * fire-and-forget: the core treats a missed report as "no nudge", which is
   * the harmless direction, and nothing here is worth a toast.
   */
  function performDraftReports(reports) {
    for (const { command, peer } of reports) {
      safeInvoke(command, { peer }, { silent: true }).catch(() => {});
    }
  }

  /**
   * Tell the core whether this composer holds unsent text — the only input the
   * nudge feature takes (`comrade_core::nudge`). Never the text itself, and
   * nothing at all until the draft is abandoned *and* the peer is a comrade.
   *
   * Deliberately outside the debounced UPI preview below: what the core needs
   * is the edge, and a trailing debounce would swallow "the box is empty again"
   * behind the next keystroke.
   */
  function reportDraftEdit() {
    if (!draftReports) return;
    performDraftReports(
      draftReports.editReports(state.activeContact, $("#dm-input").value),
    );
  }

  /**
   * A comrade wrote something for us and gave up on it. One toast, and no
   * record kept: a nudge is not presence, so it moves no dot and advances no
   * "last seen" — the core keeps those to beacons.
   */
  function onComradeNudge(p) {
    if (!p.peer) return;
    showToast(`${p.name || displayName(p.peer)} is online — they might need you`, "info");
  }

  /** A comrade came online, went offline, or their claim aged out. */
  function onComradePresence(p) {
    if (!p.peer) return;
    const prev = presenceOf(p.peer);
    state.presence.set(p.peer, {
      comrade: true,
      online: !!p.online,
      lastSeenAt: p.at || prev.lastSeenAt,
      peerMarkedUs: true,
    });
    if (p.online && !prev.online) {
      showToast(`${p.name || displayName(p.peer)} is online`, "info");
    }
    renderContacts();
    if (state.activeContact === p.peer) renderConversation();
  }

  // ── Message requests (stranger DMs awaiting accept/block) ──────────────────
  async function loadRequests() {
    let reqs;
    try {
      reqs = await safeInvoke("message_requests", undefined, { silent: true });
    } catch {
      return; // older backend without the command
    }
    state.requests = Array.isArray(reqs) ? reqs : [];
    renderRequests();
  }

  function renderRequests() {
    const section = $("#requests-section");
    if (!section) return;
    const list = $("#requests-list");
    const count = $("#requests-count");
    list.innerHTML = "";
    const reqs = state.requests || [];
    section.hidden = reqs.length === 0;
    count.textContent = reqs.length ? String(reqs.length) : "";
    for (const r of reqs) {
      list.append(
        el(
          "li",
          { class: "request" },
          el(
            "div",
            { class: "request-info" },
            el("span", { class: "request-name mono", text: displayName(r.peer) }),
            el("span", { class: "request-last", text: r.last_message || "" }),
          ),
          el(
            "div",
            { class: "request-actions" },
            el("button", {
              class: "btn btn-primary btn-sm",
              text: "Accept",
              onClick: () => acceptRequest(r.peer),
            }),
            el("button", {
              class: "btn btn-ghost btn-sm",
              text: "Block",
              onClick: () => blockRequest(r.peer),
            }),
          ),
        ),
      );
    }
  }

  async function acceptRequest(peer) {
    try {
      await safeInvoke("accept_request", { peer });
    } catch {
      return; // toasted
    }
    state.requests = (state.requests || []).filter((r) => r.peer !== peer);
    renderRequests();
    showToast(`Request from ${shortNpub(peer)} accepted`, "success");
    loadRequests().catch(() => {});
    loadConversations().catch(() => {});
  }

  async function blockRequest(peer) {
    try {
      await safeInvoke("block_conversation", { peer });
    } catch {
      return; // toasted
    }
    state.requests = (state.requests || []).filter((r) => r.peer !== peer);
    renderRequests();
    showToast(`${shortNpub(peer)} blocked`, "info");
    loadRequests().catch(() => {});
    loadConversations().catch(() => {});
  }

  function onIncomingMessageRequest(p) {
    const rec = { peer: p.peer, last_message: p.last_message, last_at: p.last_at };
    const i = (state.requests || []).findIndex((r) => r.peer === p.peer);
    if (i >= 0) state.requests[i] = rec;
    else state.requests.unshift(rec);
    renderRequests();
    showToast(`New message request from ${shortNpub(p.peer)}`, "info");
  }

  // ── TURN relay (call settings) ─────────────────────────────────────────────
  function openTurnModal() {
    $("#modal-turn").hidden = false;
    loadSharePolicy();
    $("#turn-url").focus();
  }

  /** The 50 MB in the "small files only" option, as bytes. */
  const SHARE_SMALL_LIMIT = 50 * 1024 * 1024;

  /**
   * Show the stored policy. Read from core rather than remembered here, so the
   * control cannot drift from the rule actually being enforced.
   */
  async function loadSharePolicy() {
    const select = $("#share-policy");
    if (!select) return;
    let kind = "direct_only";
    try {
      const raw = await safeInvoke("share_relay_policy", {}, { silent: true });
      kind = JSON.parse(raw)?.kind ?? "direct_only";
    } catch {
      /* unreadable or unreachable shows as the safe one, which is also what
         core falls back to enforcing */
    }
    select.value = kind;
  }

  async function handleSharePolicyChange() {
    const kind = $("#share-policy").value;
    const policy =
      kind === "under_bytes" ? { kind, limit: SHARE_SMALL_LIMIT } : { kind };
    try {
      await safeInvoke("set_share_relay_policy", { policyJson: JSON.stringify(policy) });
    } catch {
      // The toast has already said why. Re-read instead of trusting the click:
      // a save that failed must leave the control showing what is actually
      // enforced, not what someone selected.
      await loadSharePolicy();
    }
  }
  function closeTurnModal() {
    $("#modal-turn").hidden = true;
  }
  async function handleSaveTurn() {
    const url = $("#turn-url").value.trim();
    const username = $("#turn-username").value.trim();
    const credential = $("#turn-credential").value.trim();
    const btn = $("#turn-save");
    setBusy(btn, true);
    try {
      await safeInvoke("set_turn_server", { url, username, credential });
      showToast(url ? "TURN relay saved" : "TURN relay cleared", "success");
      closeTurnModal();
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  // ── WebRTC 1:1 voice / video calls ─────────────────────────────────────────
  //
  // Signaling rides the E2E DM channel: we hand a CallSignal JSON string to
  // `send_call_signal`, and receive the peer's signals as `incoming_call_signal`
  // events. WebRTC itself (getUserMedia + RTCPeerConnection) runs in the webview.
  // One call at a time; `state.call` holds the whole session.

  function callSupported() {
    return !!(
      navigator.mediaDevices &&
      navigator.mediaDevices.getUserMedia &&
      window.RTCPeerConnection
    );
  }

  // Mirror of Android's CONNECT_TIMEOUT_MS (CallManager.kt:112): once a side is
  // in the connecting phase (has an answer in hand / has sent its answer), fail
  // the call honestly if ICE never reaches "connected" within this window. This
  // is the backstop the callee's WAIT-on-failed relies on — Android's callee
  // wait is only safe because this 30s timer sits under it. NOT the 45s ring
  // timeout (a separate, deferred WP): this covers the connect phase only.
  const CONNECT_TIMEOUT_MS = 30_000;

  function newCallState(base) {
    return Object.assign(
      {
        callId: null,
        peer: null,
        media: "audio",
        incoming: false,
        phase: "calling", // calling | ringing | connecting | connected
        pc: null,
        localStream: null,
        remoteStream: null,
        offerSdp: null, // buffered offer (callee) until Accept
        pendingIce: [], // remote candidates buffered until remoteDescription set
        remoteSet: false,
        // Caller-only STUN->TURN fallback one-shot: set true the first (and
        // only) time we widen to TURN and re-offer with an ICE restart, so a
        // second pre-connect "failed" is terminal instead of looping. Mirrors
        // Android's Session.triedTurn (CallManager.kt:1069-1073). See WP3.
        triedTurn: false,
        connected: false,
        startedAt: null, // connect time (unix secs) that drives the timer
        initAt: nowSecs(), // call-initiation time; the log's started_at fallback
        timerId: null,
        statsId: null, // getStats poll driving the signal bars (startStatsPolling)
        connectTimeoutId: null, // connect-phase timeout handle (see armConnectTimeout)
        muted: false,
        // The user's own camera choice, kept separate from videoSuspended
        // ("nothing is displaying this") so returning to a visible window
        // never switches a deliberately-off camera back on.
        cameraOn: true,
        videoSuspended: false,
        // Rolling framesDecoded state behind the "Video paused" caption; owned
        // here because decideRemoteVideoPaused is pure.
        videoPauseState: { lastFrames: null, stalledPolls: 0, paused: false },
        // Shrunk into the corner tile by the chat button (see minimizeCall).
        // Every call starts full screen.
        minimized: false,
        // Screen sharing, and the display stream behind it. Kept apart from
        // localStream on purpose: the visibility rule that stops the camera
        // when nothing is displaying it must never touch the screen track.
        screenSharing: false,
        screenStream: null,
        ended: false,
      },
      base,
    );
  }

  // Map ice-server DTOs -> RTCIceServer, dropping null auth fields.
  function normalizeIce(list) {
    return (list || [])
      .map((s) => {
        const o = { urls: s.urls };
        if (s.username != null) o.username = s.username;
        if (s.credential != null) o.credential = s.credential;
        return o;
      })
      .filter((o) => o.urls && o.urls.length);
  }

  async function sendSignal(sig) {
    const c = state.call;
    if (!c) return;
    try {
      await safeInvoke(
        "send_call_signal",
        {
          peer: c.peer,
          callId: c.callId,
          media: c.media,
          signalJson: JSON.stringify(sig),
        },
        { silent: true },
      );
    } catch {
      /* ICE loss is tolerable; a dropped offer/answer fails the call cleanly */
    }
  }

  // Shared peer-connection setup for both the caller and the accepting callee.
  async function setupPeer(iceServers) {
    const c = state.call;
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: c.media === "video",
      });
    } catch (e) {
      showToast(`Microphone/camera unavailable — ${errText(e)}`, "error");
      await finishCall({
        sendHangup: true,
        reason: c.incoming ? "declined" : "failed",
        outcome: "failed",
      });
      return false;
    }
    let pc;
    try {
      pc = new RTCPeerConnection({ iceServers: normalizeIce(iceServers) });
    } catch (e) {
      showToast(`Could not start WebRTC — ${errText(e)}`, "error");
      stream.getTracks().forEach((t) => t.stop());
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
      return false;
    }
    c.localStream = stream;
    c.pc = pc;
    c.remoteStream = new MediaStream();
    for (const track of stream.getTracks()) pc.addTrack(track, stream);

    pc.onicecandidate = (ev) => {
      if (!ev.candidate) return; // null == end-of-candidates
      sendSignal({
        kind: "ice",
        candidate: ev.candidate.candidate,
        sdp_mid: ev.candidate.sdpMid == null ? undefined : ev.candidate.sdpMid,
        sdp_m_line_index:
          ev.candidate.sdpMLineIndex == null ? undefined : ev.candidate.sdpMLineIndex,
      });
    };
    pc.ontrack = (ev) => {
      if (ev.streams && ev.streams[0]) c.remoteStream = ev.streams[0];
      else c.remoteStream.addTrack(ev.track);
      attachRemoteMedia();
    };
    pc.onconnectionstatechange = () => {
      const st = pc.connectionState;
      if (st === "connected") {
        onCallConnected();
        return;
      }
      // Every other state routes through the pure decision table
      // (call_decisions.decideConnectionStateAction), which folds Android's
      // decideConnectionStateAction + tryTurnFallbackOrFail (WP3). `c` is the
      // session this pc belongs to, so a late event can't act on a different
      // call. Fire-and-forget: the handler is async only because the caller's
      // TURN fallback awaits.
      onConnectionStateChanged(c, st);
    };

    attachLocalMedia();
    attachRemoteMedia();
    return true;
  }

  // React to a non-"connected" peer-connection state change. Replaces the old
  // hardcoded "failed -> finishCall" (which broke the caller's CGNAT case: no
  // TURN fallback, no ICE restart) with Android's proven decision table.
  async function onConnectionStateChanged(c, st) {
    // Bail if this event is for a call that has since ended or been replaced.
    if (!c || c.ended || state.call !== c) return;
    const { decideConnectionStateAction, CONNECTION_ACTION } = await callDecisionsReady;
    const action = decideConnectionStateAction({
      connectionState: st,
      hasConnectedBefore: c.connected, // set once in onCallConnected, never cleared
      isCaller: !c.incoming,
      triedTurn: c.triedTurn,
    });
    // Re-check liveness across the await above.
    if (c.ended || state.call !== c) return;
    switch (action) {
      case CONNECTION_ACTION.RESTART_WITH_TURN:
        await tryTurnFallback(c);
        break;
      // Post-connect "failed" (RECOVER_NOW): desktop has no media-recovery
      // countdown yet — that is deferred beyond WP3 (the pure function still
      // returns Android's RECOVER_NOW so the conformance suite stays honest),
      // so we map it to the same terminal treatment desktop always gave a
      // "failed" connection. FAIL is the caller's already-tried-TURN terminal.
      case CONNECTION_ACTION.RECOVER_NOW:
      case CONNECTION_ACTION.FAIL:
        await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
        break;
      // WAIT: callee pre-connect "failed" — wait for the caller's rescue
      // re-offer (Android has no callee TURN retry; CallManager.kt:1065-1068).
      // RECOVER_AFTER_GRACE: post-connect "disconnected" — desktop already
      // tolerated "disconnected" as transient (ICE restart), so keep ignoring
      // it here rather than starting a countdown (deferred, as above). NONE:
      // nothing to do.
      case CONNECTION_ACTION.WAIT:
      case CONNECTION_ACTION.RECOVER_AFTER_GRACE:
      case CONNECTION_ACTION.NONE:
      default:
        break;
    }
  }

  // Caller-only STUN->TURN fallback: the direct/STUN path failed to connect, so
  // widen to STUN+TURN and restart ICE with a fresh offer. That re-offer is a
  // same-call_id offer the callee answers as a renegotiation (WP1), and the
  // second answer comes back into applyRemoteAnswer (accepted via decideAnswer,
  // not a one-shot flag). Reuses the EXISTING pc: no getUserMedia re-run, no new
  // pc, no duration timer touched. Mirrors Android's tryTurnFallbackOrFail
  // (CallManager.kt:1063-1097). One-shot via c.triedTurn.
  async function tryTurnFallback(c) {
    if (!c || !c.pc || c.ended) return;
    // Set the one-shot BEFORE any await, so a second pre-connect "failed" during
    // the async gap below decides FAIL (terminal) rather than a restart loop.
    c.triedTurn = true;
    setCallStatusText("Connecting…"); // subtle status: fall back to "connecting"
    let widened = [];
    try {
      widened =
        (await safeInvoke("call_ice_servers_for", { strategy: "stun_and_turn" }, { silent: true })) ||
        [];
    } catch {
      // If we can't widen, the re-offer below still runs on whatever we have and
      // the next "failed" is terminal (triedTurn is already set).
    }
    // Re-check liveness after the await.
    if (!c.pc || c.ended || state.call !== c) return;
    try {
      c.pc.setConfiguration({ iceServers: normalizeIce(widened) });
      // Buffer any new-generation remote ICE until the fresh answer's
      // setRemoteDescription runs — mirrors Android setting s.remoteSet = false
      // before the re-offer (CallManager.kt:1086). applyRemoteAnswer sets
      // remoteSet = true and flushes pendingIce when the second answer lands.
      c.remoteSet = false;
      const offer = await c.pc.createOffer({ iceRestart: true });
      await c.pc.setLocalDescription(offer);
      await sendSignal({ kind: "offer", sdp: offer.sdp });
    } catch (e) {
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
    }
  }

  // Caller: place the call, negotiate locally, and send the offer.
  async function startCall(peer, media) {
    if (!peer) {
      showToast("Select a conversation first", "warn");
      return;
    }
    if (state.call) {
      showToast("You're already in a call", "warn");
      return;
    }
    if (!callSupported()) {
      showToast("Calling isn't available in this environment", "error");
      return;
    }
    let session;
    try {
      session = await safeInvoke("place_call", { peer, media });
    } catch {
      return; // toasted
    }
    state.call = newCallState({
      callId: session.call_id,
      peer: session.peer || peer,
      media: session.media || media,
      incoming: false,
      phase: "calling",
    });
    showCallOverlay();
    setCallStatusText("Calling…");
    const ok = await setupPeer(session.ice_servers || []);
    if (!ok) return; // setupPeer handled cleanup
    try {
      const offer = await state.call.pc.createOffer();
      await state.call.pc.setLocalDescription(offer);
      await sendSignal({ kind: "offer", sdp: offer.sdp });
    } catch (e) {
      showToast(`Could not start the call — ${errText(e)}`, "error");
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
    }
  }

  // Callee: an offer arrived. Depending on call_decisions.decideOfferDisposition
  // this either rings fresh (no call yet), renegotiates the existing pc (a
  // same-call_id re-offer — e.g. the caller's STUN->TURN ICE-restart
  // fallback), silently no-ops a duplicate/ended call_id, or auto-rejects as
  // busy (a genuinely different call_id). See call_decisions.mjs for the
  // pure decision this mirrors from Android's CallManager.
  async function handleIncomingOffer(p, sig) {
    const { decideOfferDisposition, OFFER_DISPOSITION, isEndedCallId } = await callDecisionsReady;
    const c = state.call;
    const disposition = decideOfferDisposition({
      hasCall: !!c,
      sameCallId: !!c && c.callId === p.call_id,
      hasPc: !!c && !!c.pc,
      isEndedCallId: isEndedCallId(state.endedCallIds, p.call_id),
    });

    if (disposition === OFFER_DISPOSITION.ENDED_NOOP) {
      // Redelivered offer for a call we already tore down (relay
      // at-least-once delivery, or a backfill re-scan) — drop silently,
      // don't ring again.
      console.log(`call ${p.call_id}: ignoring offer for an already-ended call`);
      return;
    }

    if (disposition === OFFER_DISPOSITION.RENEGOTIATE) {
      // Same call_id re-offer on a live pc (the P0 fix: this used to be
      // answered `busy`, which broke an Android caller's STUN->TURN
      // ICE-restart fallback). Answer on the existing pc — do NOT touch
      // getUserMedia, do NOT recreate the pc, do NOT reset the duration
      // timer or any UI state.
      try {
        await c.pc.setRemoteDescription({ type: "offer", sdp: sig.sdp });
        const answer = await c.pc.createAnswer();
        await c.pc.setLocalDescription(answer);
        await sendSignal({ kind: "answer", sdp: answer.sdp });
      } catch (e) {
        showToast(`Could not renegotiate the call — ${errText(e)}`, "error");
      }
      return;
    }

    if (disposition === OFFER_DISPOSITION.DUPLICATE_NOOP) {
      // Same call_id redelivered while still ringing, pre-accept (no pc
      // yet) — drop silently; re-ringing would only restart the ring state.
      return;
    }

    if (disposition === OFFER_DISPOSITION.BUSY) {
      // Genuinely busy on a different call: politely reject the new caller
      // and log the missed attempt.
      try {
        await safeInvoke(
          "send_call_signal",
          {
            peer: p.peer,
            callId: p.call_id,
            media: p.media,
            signalJson: JSON.stringify({ kind: "busy" }),
          },
          { silent: true },
        );
      } catch {
        /* best-effort */
      }
      logCall(p.peer, p.call_id, p.media, true, "busy", nowSecs(), 0);
      return;
    }

    // NEW_INCOMING: no live call — ring as usual (happy path, unchanged).
    if (!callSupported()) {
      try {
        await safeInvoke(
          "hangup_call",
          { peer: p.peer, callId: p.call_id, media: p.media, reason: "failed" },
          { silent: true },
        );
      } catch {
        /* best-effort */
      }
      return;
    }
    state.call = newCallState({
      callId: p.call_id,
      peer: p.peer,
      media: p.media,
      incoming: true,
      phase: "ringing",
      offerSdp: sig.sdp,
    });
    sendSignal({ kind: "ringing" }); // best-effort, not awaited
    showRingingOverlay();
    showToast(
      `Incoming ${p.media === "video" ? "video" : "voice"} call from ${shortNpub(p.peer)}`,
      "info",
    );
  }

  async function acceptIncoming() {
    const c = state.call;
    if (!c || !c.incoming || c.pc) return; // only valid from the ringing phase
    hideRingingOverlay();
    c.phase = "connecting";
    showCallOverlay();
    setCallStatusText("Connecting…");
    let ice = [];
    try {
      ice = (await safeInvoke("call_ice_servers", undefined, { silent: true })) || [];
    } catch {
      /* fall back to host-only candidates */
    }
    const ok = await setupPeer(ice);
    if (!ok) return;
    try {
      await c.pc.setRemoteDescription({ type: "offer", sdp: c.offerSdp });
      c.remoteSet = true;
      await flushPendingIce();
      const answer = await c.pc.createAnswer();
      await c.pc.setLocalDescription(answer);
      await sendSignal({ kind: "answer", sdp: answer.sdp });
      // Answer sent — now fail honestly if ICE never connects, so the call
      // can't hang on "Connecting…" forever (mirrors Android arming
      // CONNECT_TIMEOUT_MS right after the callee's answer, CallManager.kt:510).
      // This is the backstop under the callee's WAIT-on-failed: if the caller
      // dies mid-fallback or its hangup never arrives, this timer ends the call.
      armConnectTimeout(c);
    } catch (e) {
      showToast(`Could not answer the call — ${errText(e)}`, "error");
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
    }
  }

  function declineIncoming() {
    if (!state.call) return;
    finishCall({ sendHangup: true, reason: "declined", outcome: "declined" });
  }

  function hangupByUser() {
    const c = state.call;
    if (!c) return;
    const wasConnected = c.connected;
    finishCall({
      sendHangup: true,
      reason: wasConnected ? "normal" : c.incoming ? "declined" : "cancelled",
      outcome: wasConnected ? "connected" : c.incoming ? "declined" : "cancelled",
    });
  }

  // Route a non-offer signal (answer/ice/ringing/busy/hangup) to the live call.
  function onCallSignal(p) {
    const sig = p.signal || {};
    const kind = sig.kind;
    if (kind === "offer") {
      handleIncomingOffer(p, sig);
      return;
    }
    const c = state.call;
    if (!c || c.callId !== p.call_id) return; // stray, or for a call we've ended
    if (kind === "answer") {
      applyRemoteAnswer(sig.sdp);
    } else if (kind === "ice") {
      addRemoteIce(sig);
    } else if (kind === "ringing") {
      if (!c.connected) setCallStatusText("Ringing…");
    } else if (kind === "busy") {
      showToast(`${displayName(c.peer)} is busy`, "warn");
      finishCall({ sendHangup: false, reason: "busy", outcome: "busy" });
    } else if (kind === "hangup") {
      const reason = sig.reason || "normal";
      const outcome = remoteHangupOutcome(c, reason);
      showToast(
        outcome === "declined" ? `${displayName(c.peer)} declined the call` : "Call ended",
        "info",
      );
      finishCall({ sendHangup: false, reason, outcome });
    }
  }

  async function applyRemoteAnswer(sdp) {
    const c = state.call;
    if (!c || !c.pc) return;
    // Apply an answer only while this pc is still holding our own unanswered
    // local offer ("have-local-offer") — mirrors Android's decideAnswer
    // (CallManager.kt:1648). This is what lets the STUN->TURN fallback's SECOND
    // answer apply (after the ICE-restart re-offer's setLocalDescription the pc
    // is back in "have-local-offer"), while a redelivered duplicate answer that
    // arrives once the pc has settled to "stable" is ignored instead of
    // throwing in setRemoteDescription and tearing the live call down. Keying
    // off signalingState (not a one-shot flag like c.remoteSet) is deliberate:
    // a latch would drop the legitimate second answer and hang the fallback.
    const { decideAnswer, ANSWER_DECISION } = await callDecisionsReady;
    if (decideAnswer(c.pc.signalingState) !== ANSWER_DECISION.APPLY) return;
    try {
      await c.pc.setRemoteDescription({ type: "answer", sdp });
      c.remoteSet = true;
      await flushPendingIce();
      // Answer in hand — (re)arm the connect timeout (mirrors Android,
      // CallManager.kt:1019). Re-arming here is also what gives the STUN->TURN
      // fallback's SECOND answer a fresh window; tryTurnFallback deliberately
      // does NOT touch the timer, so the original window rides through the
      // re-offer exactly as Android's does, and this re-arm starts a fresh 30s
      // for the relayed attempt.
      armConnectTimeout(c);
      if (!c.connected) setCallStatusText("Connecting…");
    } catch (e) {
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
    }
  }

  async function addRemoteIce(sig) {
    const c = state.call;
    if (!c) return;
    const cand = {
      candidate: sig.candidate,
      sdpMid: sig.sdp_mid == null ? null : sig.sdp_mid,
      sdpMLineIndex: sig.sdp_m_line_index == null ? null : sig.sdp_m_line_index,
    };
    // Buffer until the remote description exists (also covers the ring phase).
    if (!c.pc || !c.remoteSet) {
      c.pendingIce.push(cand);
      return;
    }
    try {
      await c.pc.addIceCandidate(cand);
    } catch {
      /* a rejected candidate shouldn't kill the call */
    }
  }

  async function flushPendingIce() {
    const c = state.call;
    if (!c || !c.pc) return;
    const queued = c.pendingIce.splice(0);
    for (const cand of queued) {
      try {
        await c.pc.addIceCandidate(cand);
      } catch {
        /* ignore */
      }
    }
  }

  function remoteHangupOutcome(c, reason) {
    if (c.connected) return "connected";
    if (c.incoming) return "missed"; // caller cancelled before we answered
    if (reason === "declined") return "declined";
    if (reason === "busy") return "busy";
    if (reason === "missed") return "missed";
    return "cancelled";
  }

  function onCallConnected() {
    const c = state.call;
    if (!c) return;
    if (!c.connected) {
      c.connected = true;
      c.phase = "connected";
      c.startedAt = nowSecs();
      // Connected — the connect timeout no longer applies (mirrors CallManager.kt:1103).
      clearConnectTimeout(c);
      startDurationTimer();
    }
    // Start (or restart) the stats poll that drives the signal-strength bars
    // and the "Video paused" caption. Restarting on every connected transition
    // — not just the first — mirrors Android's onConnected calling
    // startStatsPolling unconditionally (CallManager.kt), so a mid-call
    // ICE-restart reconnect samples the fresh path instead of carrying a stale
    // reading, and re-baselines the frame counter that the restart resets.
    startStatsPolling(c);
  }

  // ── Connection quality + remote video pause (the signal bars) ────────────
  //
  // This replaced the 4-emoji SAS row. The SAS was an out-of-band
  // man-in-the-middle check on the media path, and it was near-redundant here:
  // Comrade's SDP rides the NIP-44 gift-wrapped DM channel, so both sides'
  // DTLS fingerprints are already authenticated by the peer's Nostr key before
  // a call is answered. `comrade_core::call::derive_sas` and the Tauri
  // `call_sas` command still exist and are still tested — nothing in the UI
  // surfaces them. What people actually need mid-call is signal strength.
  //
  // Both readings come off one `getStats()` poll every STATS_POLL_MS, matching
  // Android's cadence and thresholds; the classification itself is pure and
  // lives in call_decisions.mjs so the two frontends cannot drift.

  const STATS_POLL_MS = 2000;

  /** Reset the indicator to "nothing measured yet" and hide the paused caption. */
  function resetCallQuality() {
    renderSignal(null);
    renderVideoPaused(false);
  }

  /**
   * Poll `getStats()` for as long as this call is the live one. Every read is
   * guarded on liveness both before and after the await, exactly as
   * the SAS derivation used to be: the call can end while a poll is in flight.
   */
  function startStatsPolling(c) {
    stopStatsPolling(c);
    c.videoPauseState = { lastFrames: null, stalledPolls: 0, paused: false };
    const poll = async () => {
      if (!c || c.ended || state.call !== c || !c.pc) return;
      let report = null;
      try {
        report = await c.pc.getStats();
      } catch {
        return; // a getStats failure degrades this poll, nothing more
      }
      if (!c || c.ended || state.call !== c) return;
      const {
        classifyCallQuality,
        remoteVideoFramesDecoded,
        decideRemoteVideoPaused,
      } = await callDecisionsReady;
      if (!c || c.ended || state.call !== c) return;
      // An RTCStatsReport is iterable over its stat objects, which is exactly
      // what the pure classifier takes.
      const stats = Array.from(report.values ? report.values() : report);
      renderSignal(classifyCallQuality(stats));
      if (c.media === "video") {
        c.videoPauseState = decideRemoteVideoPaused({
          frames: remoteVideoFramesDecoded(stats),
          ...c.videoPauseState,
        });
        renderVideoPaused(c.videoPauseState.paused);
      }
    };
    poll();
    c.statsId = setInterval(poll, STATS_POLL_MS);
  }

  function stopStatsPolling(c) {
    const call = c || state.call;
    if (call && call.statsId) {
      clearInterval(call.statsId);
      call.statsId = null;
    }
  }

  /**
   * Paint the bars. `null` means "no call / nothing measured", which hides the
   * row entirely; an `unknown` reading shows the row with zero bars lit —
   * honest about having no number rather than inventing one.
   */
  function renderSignal(quality) {
    const row = $("#call-signal");
    if (!row) return;
    if (quality == null) {
      row.hidden = true;
      row.removeAttribute("data-quality");
      $("#call-signal-label").textContent = "";
      row.querySelector(".call-bars").dataset.filled = "0";
      return;
    }
    Promise.resolve(callDecisionsReady).then(({ signalBarsFor, signalLabelFor }) => {
      const live = $("#call-signal");
      if (!live || !state.call || state.call.ended) return;
      live.hidden = false;
      live.dataset.quality = quality;
      live.querySelector(".call-bars").dataset.filled = String(signalBarsFor(quality));
      $("#call-signal-label").textContent = signalLabelFor(quality) || "";
    });
  }

  /** Show/hide the "Video paused" cover over the remote frame. */
  function renderVideoPaused(paused) {
    const node = $("#call-video-paused");
    if (node) node.hidden = !paused;
  }

  // Best-effort call-log write (never surfaces its own error).
  function logCall(peer, callId, media, incoming, outcome, startedAt, durationSecs) {
    safeInvoke(
      "log_call",
      { peer, callId, media, incoming, outcome, startedAt, durationSecs },
      { silent: true },
    ).catch(() => {});
  }

  // The single call terminator: optionally signal hangup, log, stop media, hide.
  async function finishCall({ sendHangup, reason, outcome }) {
    const c = state.call;
    if (!c || c.ended) return;
    c.ended = true;
    // Cancel the connect timeout up front so it can never fire into a *later*
    // call (zombie timer) — mirrors Android clearing timeoutJob in endWith
    // (CallManager.kt:1247). The c.ended guard above + shouldConnectTimeoutFire
    // together also make the timeout-vs-hangup race a no-op either way.
    clearConnectTimeout(c);
    // Remember this call_id as ended so a redelivered terminal Offer
    // (relay at-least-once delivery, or a backfill re-scan) doesn't ring
    // again — see call_decisions.mjs rememberEndedCall, mirroring Android's
    // CallManager.endedCallIds/rememberEnded.
    const { rememberEndedCall } = await callDecisionsReady;
    state.endedCallIds = rememberEndedCall(state.endedCallIds, c.callId);
    stopDurationTimer();
    stopStatsPolling(c);
    const duration = c.startedAt ? Math.max(0, nowSecs() - c.startedAt) : 0;
    if (sendHangup) {
      try {
        await safeInvoke(
          "hangup_call",
          { peer: c.peer, callId: c.callId, media: c.media, reason },
          { silent: true },
        );
      } catch {
        /* best-effort */
      }
    }
    logCall(
      c.peer,
      c.callId,
      c.media,
      c.incoming,
      outcome,
      c.startedAt || c.initAt || nowSecs(),
      duration,
    );
    teardownMedia(c);
    hideCallOverlay();
    hideRingingOverlay();
    state.call = null;
  }

  function teardownMedia(c) {
    try {
      if (c.pc) {
        c.pc.onicecandidate = null;
        c.pc.ontrack = null;
        c.pc.onconnectionstatechange = null;
        c.pc.close();
      }
    } catch {
      /* ignore */
    }
    try {
      if (c.localStream) c.localStream.getTracks().forEach((t) => t.stop());
      // A screen capture that outlived its call would keep the OS's "sharing
      // your screen" indicator up with nothing on the other end.
      if (c.screenStream) c.screenStream.getTracks().forEach((t) => t.stop());
      c.screenStream = null;
      c.screenSharing = false;
    } catch {
      /* ignore */
    }
    try {
      $("#call-remote-video").srcObject = null;
      $("#call-local-video").srcObject = null;
    } catch {
      /* ignore */
    }
  }

  function toggleMute() {
    const c = state.call;
    if (!c || !c.localStream) return;
    c.muted = !c.muted;
    for (const t of c.localStream.getAudioTracks()) t.enabled = !c.muted;
    const btn = $("#call-mute");
    // The glyph stays the same and a slash is drawn over it (see
    // .call-btn.is-off in styles.css) — the same explicitness the Flutter and
    // Compose SlashedIcon gives, rather than swapping 🎙 for 🔇.
    btn.classList.toggle("is-off", c.muted);
    btn.classList.toggle("is-muted", c.muted); // kept: existing styling hook
    btn.title = c.muted ? "Unmute microphone" : "Mute microphone";
    btn.setAttribute("aria-label", btn.title);
  }

  /**
   * Turn the local camera off/on mid-call.
   *
   * Disabling the track stops frames reaching the peer (their UI shows "Video
   * paused"); `stop()`ing it would release the hardware but cannot be undone
   * without renegotiating, so this mirrors Android's `toggleCamera`, which
   * disables the track and stops the *capturer* while keeping the sender.
   */
  function toggleCamera() {
    const c = state.call;
    if (!c || !c.localStream || c.media !== "video") return;
    c.cameraOn = c.cameraOn === false ? true : false;
    for (const t of c.localStream.getVideoTracks()) t.enabled = c.cameraOn;
    const btn = $("#call-camera");
    btn.classList.toggle("is-off", !c.cameraOn);
    btn.title = c.cameraOn ? "Turn camera off" : "Turn camera on";
    btn.setAttribute("aria-label", btn.title);
    // Our own preview should agree with what we are sending.
    $("#call-local-video").hidden = !c.cameraOn;
  }

  // ── Picture-in-picture ────────────────────────────────────────────────────
  //
  // The desktop equivalent of Android's PipController: the chat button gets the
  // call out of the way of the conversation. There is no OS-level PiP for a
  // Tauri window, but the webview gives us PiP on the remote <video> element
  // itself, which is the same idea and survives the window being backgrounded.
  // Every path degrades quietly — a webview without the API just leaves the
  // call full screen, which is a usable outcome, not an error.

  function openChatDuringCall() {
    const c = state.call;
    if (!c) return;
    // Open the thread first, so the call shrinks *onto* it. selectContact also
    // marks it read and pulls the history, which is exactly what opening the
    // conversation by hand would do.
    switchTab("vault");
    selectContact(c.peer);
    // Shrink our own overlay — deliberately NOT browser PiP on the <video>.
    // That was the bug: PiP moves the picture into its own window but leaves
    // this opaque full-screen overlay in place, so the conversation stayed
    // hidden behind it and the button read as "minimise the video, open
    // nothing". Only our own window can show the call and the thread together.
    minimizeCall();
  }

  // ── The ⋮ dock ─────────────────────────────────────────────────────────────
  //
  // The bar holds camera, mic, ⋮ and End; everything else is in here. The split
  // itself is `layoutCallControls` in call_decisions.mjs — this is only the
  // open/shut of the panel that holds the second half.

  function toggleCallDock() {
    const dock = $("#call-dock");
    if (dock.hidden) openCallDock();
    else closeCallDock();
  }

  function openCallDock() {
    const dock = $("#call-dock");
    dock.hidden = false;
    $("#call-more").setAttribute("aria-expanded", "true");
    // Move focus in, so the dock is usable from the keyboard and Escape has
    // somewhere obvious to return from.
    const first = dock.querySelector(".call-dock-item:not([hidden])");
    if (first) first.focus();
  }

  function closeCallDock() {
    const dock = $("#call-dock");
    if (dock.hidden) return;
    dock.hidden = true;
    $("#call-more").setAttribute("aria-expanded", "false");
  }

  /** Shrink the in-call overlay into the corner tile (see `.is-minimized`). */
  function minimizeCall() {
    const c = state.call;
    if (!c) return;
    // A dock left open would be wider than the tile it hangs off.
    closeCallDock();
    c.minimized = true;
    const el = $("#call-active");
    el.classList.add("is-minimized");
    placeTile(tilePosition());
  }

  /** Back to the full-screen call — the tile was clicked, or the call ended. */
  function restoreCall() {
    const c = state.call;
    if (c) c.minimized = false;
    const el = $("#call-active");
    el.classList.remove("is-minimized");
    // Drop the inline position so the overlay goes back to filling the window.
    el.style.left = "";
    el.style.top = "";
  }

  // ── Dragging the minimised tile ────────────────────────────────────────────
  //
  // Telegram's behaviour: the tile goes wherever you put it, never off screen,
  // and lets go by flying to the nearer side edge. Kept in `state.tile` (not on
  // the call) so a second call reuses where you last parked it.

  const TILE_MARGIN = 12;

  function tileSize() {
    const el = $("#call-active");
    return { w: el.offsetWidth || 148, h: el.offsetHeight || 208 };
  }

  /** The stored position, defaulting to the top-right corner. */
  function tilePosition() {
    const { w } = tileSize();
    if (!state.tile) state.tile = { x: window.innerWidth - w - TILE_MARGIN, y: TILE_MARGIN };
    return state.tile;
  }

  /** Geometry shared with the tested pure module — never duplicated here. */
  function tileBox() {
    const { w, h } = tileSize();
    return {
      tileWidth: w,
      tileHeight: h,
      windowWidth: window.innerWidth,
      windowHeight: window.innerHeight,
      margin: TILE_MARGIN,
    };
  }

  function clampTile(x, y) {
    if (!callDecisions) return { x, y };
    return callDecisions.clampTilePosition({ x, y, ...tileBox() });
  }

  function placeTile(pos) {
    const clamped = clampTile(pos.x, pos.y);
    state.tile = clamped;
    const el = $("#call-active");
    el.style.left = `${clamped.x}px`;
    el.style.top = `${clamped.y}px`;
  }

  /** Fly to whichever side edge the tile's centre ended up nearer. */
  function snapTile() {
    if (!callDecisions) return;
    const pos = tilePosition();
    placeTile(callDecisions.snapTileToEdge({ x: pos.x, y: pos.y, ...tileBox() }));
  }

  /**
   * Pointer-drag the tile. Uses pointer capture so a fast drag that outruns the
   * cursor doesn't drop the gesture, and only counts as a drag past a small
   * threshold — otherwise every click-to-restore would be a one-pixel move.
   */
  function installTileDragging() {
    const el = $("#call-active");
    let origin = null;

    el.addEventListener("pointerdown", (e) => {
      const c = state.call;
      if (!c || !c.minimized) return;
      if (e.target.closest(".call-btn")) return; // the two controls keep their clicks
      const pos = tilePosition();
      origin = { px: e.clientX, py: e.clientY, x: pos.x, y: pos.y, moved: false };
      el.setPointerCapture(e.pointerId);
    });

    el.addEventListener("pointermove", (e) => {
      if (!origin) return;
      const dx = e.clientX - origin.px;
      const dy = e.clientY - origin.py;
      if (!origin.moved && Math.hypot(dx, dy) < 4) return;
      origin.moved = true;
      el.classList.add("is-dragging"); // suppress the snap transition mid-drag
      placeTile({ x: origin.x + dx, y: origin.y + dy });
    });

    const end = (e) => {
      if (!origin) return;
      const moved = origin.moved;
      origin = null;
      el.classList.remove("is-dragging");
      if (e.pointerId != null && el.hasPointerCapture(e.pointerId)) {
        el.releasePointerCapture(e.pointerId);
      }
      // A drag still ends with a `click`, and that click must not be read as
      // "restore the call" — parking the tile somewhere would always reopen it.
      state.tileDragged = moved;
      if (moved) snapTile();
    };
    el.addEventListener("pointerup", end);
    el.addEventListener("pointercancel", end);

    // A resized window must not strand the tile off screen.
    window.addEventListener("resize", () => {
      const c = state.call;
      if (c && c.minimized) placeTile(tilePosition());
    });
  }

  // ── Screen sharing ─────────────────────────────────────────────────────────
  //
  // Available on **voice calls as well as video ones**, which is what makes it
  // worth having: a voice call that starts sharing grows a picture it did not
  // have. The two cases take different paths through WebRTC, and the difference
  // is the whole complexity of this section:
  //
  //   * A **video call** already has a video sender, so swapping the camera
  //     track for the screen track with `replaceTrack` needs no renegotiation
  //     at all — the peer just starts seeing a different picture.
  //   * A **voice call** has no video sender, so the track has to be added and
  //     the call renegotiated with a fresh offer. That path reuses the same
  //     same-call_id re-offer the STUN→TURN fallback already relies on
  //     (`decideOfferDisposition` → RENEGOTIATE), so the peer handles it with
  //     code that is already exercised.
  //
  // Note what deliberately does *not* apply here: `applyVideoVisibility`'s
  // "stop sending video nobody is displaying" rule. It only ever touches
  // `c.localStream`'s camera tracks, never the screen track — sharing your
  // screen and then switching to the window you are sharing is the normal way
  // to use this, and suspending capture then would defeat it entirely.

  async function toggleScreenShare() {
    const c = state.call;
    if (!c || !c.pc || c.ended) return;
    if (c.screenSharing) await stopScreenShare();
    else await startScreenShare();
  }

  async function startScreenShare() {
    const c = state.call;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      showToast("This build can't capture the screen", "warn");
      return;
    }
    let stream;
    try {
      stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
    } catch {
      // Dismissing the picker is a decision, not a failure — say nothing.
      return;
    }
    // Re-check liveness after the await: the call may have ended while the
    // picker was open.
    if (!c || c.ended || state.call !== c || !c.pc) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    const track = stream.getVideoTracks()[0];
    if (!track) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    c.screenStream = stream;
    c.screenSharing = true;
    // The browser draws its own "Stop sharing" control, which ends the track
    // behind our back. Without this the UI would keep claiming to share.
    track.addEventListener("ended", () => {
      stopScreenShare().catch(() => {});
    });

    const sender = videoSender(c);
    try {
      if (sender) {
        await sender.replaceTrack(track);
      } else {
        c.pc.addTrack(track, stream);
        await renegotiate(c);
      }
    } catch (e) {
      showToast(`Couldn't share the screen — ${errText(e)}`, "error");
      await stopScreenShare();
      return;
    }
    renderScreenShare();
    attachLocalMedia();
  }

  async function stopScreenShare() {
    const c = state.call;
    if (!c || !c.screenSharing) return;
    c.screenSharing = false;
    const stream = c.screenStream;
    c.screenStream = null;

    if (c.pc && !c.ended) {
      const sender = videoSender(c);
      try {
        if (c.media === "video") {
          // Put the camera back where the screen was — same sender, so again
          // no renegotiation.
          const camera = c.localStream ? c.localStream.getVideoTracks()[0] : null;
          if (sender) await sender.replaceTrack(camera || null);
        } else if (sender) {
          // A voice call has no camera to go back to: drop the video entirely
          // and renegotiate, so the peer's stage goes away rather than freezing
          // on the last frame of a screen that is no longer shared.
          c.pc.removeTrack(sender);
          await renegotiate(c);
        }
      } catch {
        /* the call is ending, or already gone — nothing useful to do */
      }
    }
    if (stream) stream.getTracks().forEach((t) => t.stop());
    renderScreenShare();
    attachLocalMedia();
  }

  /** The peer connection's video sender, if it has one. */
  function videoSender(c) {
    if (!c.pc) return null;
    return c.pc.getSenders().find((s) => s.track && s.track.kind === "video") || null;
  }

  /**
   * Offer again on the existing call id, for a change that alters the media
   * shape (adding or removing the screen track on a voice call).
   *
   * Same shape as the ICE-restart re-offer above, minus `iceRestart` — the
   * transport is fine, only the tracks changed.
   */
  async function renegotiate(c) {
    if (!c.pc || c.ended || state.call !== c) return;
    const offer = await c.pc.createOffer();
    await c.pc.setLocalDescription(offer);
    if (c.ended || state.call !== c) return;
    await sendSignal({ kind: "offer", sdp: offer.sdp });
  }

  /** Paint the screen-share button and the stage's "you are sharing" state. */
  function renderScreenShare() {
    const c = state.call;
    const sharing = !!(c && c.screenSharing);
    const btn = $("#call-screen-share");
    if (btn) {
      btn.classList.toggle("is-on", sharing);
      btn.title = sharing ? "Stop sharing your screen" : "Share your screen";
      btn.setAttribute("aria-label", btn.title);
      const label = $("#call-screen-share-label");
      if (label) label.textContent = sharing ? "Stop sharing" : "Share screen";
    }
    // Sharing is the one dock item whose state matters while the dock is shut,
    // so it also lights the ⋮ that hides it.
    const more = $("#call-more");
    if (more) more.classList.toggle("is-on", sharing);
    // A voice call that is sharing has a picture to show, so the stage stops
    // being just an avatar.
    const avatar = $("#call-avatar");
    if (avatar && c) avatar.hidden = c.media === "video" || sharing;
  }

  // No `enterPictureInPicture` here on purpose. The webview can PiP the remote
  // <video> element, and the chat button used to do exactly that — but PiP moves
  // the picture into a window of its own and leaves this overlay covering the
  // app, so the conversation stayed hidden. `minimizeCall` is the answer to
  // "get out of the way of the chat". The user can still PiP the video with the
  // webview's own control, which is why the exit below stays: whatever put us in
  // PiP, a call that ends must not leave a floating window behind.

  function exitPictureInPicture() {
    if (!document.pictureInPictureElement) return;
    document.exitPictureInPicture().catch(() => {});
  }

  /**
   * Stop sending video the moment nothing is displaying it, and resume when it
   * is again — the browser twin of `CallManager.setVideoCaptureSuspended`.
   *
   * A PiP window *is* something displaying the call, so it does not suspend.
   * `cameraOn` is the user's own choice and is kept separate: a call that was
   * backgrounded with the camera deliberately off does not come back on.
   */
  async function applyVideoVisibility() {
    const c = state.call;
    if (!c || c.media !== "video" || !c.localStream) return;
    const { shouldSendLocalVideo } = await callDecisionsReady;
    if (!c || c.ended || state.call !== c) return;
    const inPip = !!document.pictureInPictureElement;
    c.videoSuspended = document.hidden && !inPip;
    const shouldSend = shouldSendLocalVideo({
      documentHidden: document.hidden,
      inPictureInPicture: inPip,
      cameraOn: c.cameraOn,
    });
    for (const t of c.localStream.getVideoTracks()) t.enabled = shouldSend;
  }

  // ── Call overlay / media-element plumbing ──────────────────────────────────
  function showCallOverlay() {
    const c = state.call;
    if (!c) return;
    $("#call-peer").textContent = displayName(c.peer);
    $("#call-media-label").textContent = c.media === "video" ? "Video call" : "Voice call";
    $("#call-timer").hidden = true;
    resetCallQuality(); // no reading yet — four empty bars, no caption
    // The camera button only exists on a video call; screen share exists on
    // both, which is the point of it.
    $("#call-camera").hidden = c.media !== "video";
    closeCallDock(); // a new call starts with the overflow shut
    renderScreenShare();
    attachLocalMedia();
    attachRemoteMedia();
    $("#call-active").hidden = false;
  }

  function hideCallOverlay() {
    $("#call-active").hidden = true;
    restoreCall(); // a tile must never outlive its call
    closeCallDock(); // nor an open dock
    resetCallQuality();
    exitPictureInPicture();
    const mb = $("#call-mute");
    mb.classList.remove("is-off");
    mb.title = "Mute microphone";
    const cb = $("#call-camera");
    cb.classList.remove("is-off");
    cb.hidden = true;
    const sb = $("#call-screen-share");
    if (sb) sb.classList.remove("is-on");
    $("#call-more").classList.remove("is-on");
  }

  function showRingingOverlay() {
    const c = state.call;
    if (!c) return;
    $("#ring-peer").textContent = displayName(c.peer);
    $("#ring-media").textContent =
      c.media === "video" ? "Incoming video call" : "Incoming voice call";
    $("#call-ring").hidden = false;
  }

  function hideRingingOverlay() {
    $("#call-ring").hidden = true;
  }

  function attachLocalMedia() {
    const c = state.call;
    if (!c) return;
    const lv = $("#call-local-video");
    // While sharing, the self-view shows what the peer is actually receiving —
    // the screen, not the camera — so you can see what you are giving away.
    const source = c.screenSharing && c.screenStream
      ? c.screenStream
      : c.media === "video"
        ? c.localStream
        : null;
    if (source) {
      lv.srcObject = source;
      // A mirrored self-view is right for a camera and wrong for a screen:
      // mirrored text is unreadable, and it is not what the peer sees.
      lv.classList.toggle("is-screen", !!c.screenSharing);
      lv.hidden = false;
      lv.play().catch(() => {}); // autoplay attr isn't always honored in a webview
    } else {
      lv.srcObject = null;
      lv.classList.remove("is-screen");
      lv.hidden = true;
    }
  }

  function attachRemoteMedia() {
    const c = state.call;
    if (!c) return;
    // One <video> carries remote audio+video; on a voice call it just plays
    // audio while the avatar covers the empty frame.
    if (c.remoteStream) {
      const rv = $("#call-remote-video");
      rv.srcObject = c.remoteStream;
      rv.play().catch(() => {}); // best-effort; user gesture (the call) already occurred
    }
    // A voice call that the peer is sharing a screen on does have a picture,
    // so the avatar must get out of its way.
    $("#call-avatar").hidden = c.media === "video" || !!c.screenSharing;
    applyRemoteFit();
  }

  /**
   * Fill the stage with the remote frame — unless filling would throw most of
   * the picture away, which is what a shared 16:9 screen on a narrow window
   * does. Then letterbox instead.
   *
   * Driven by geometry rather than by "is the peer sharing a screen", which
   * would need a wire-format field the protocol does not have. See
   * `shouldLetterbox` for why a third is the threshold.
   */
  function applyRemoteFit() {
    const rv = $("#call-remote-video");
    if (!rv || !callDecisions) return;
    rv.classList.toggle(
      "is-letterboxed",
      callDecisions.shouldLetterbox({
        frameWidth: rv.videoWidth,
        frameHeight: rv.videoHeight,
        boxWidth: rv.clientWidth,
        boxHeight: rv.clientHeight,
      }),
    );
  }

  function setCallStatusText(text) {
    const node = $("#call-status");
    if (text == null) {
      node.hidden = true;
      return;
    }
    node.hidden = false;
    node.textContent = text;
  }

  function startDurationTimer() {
    stopDurationTimer();
    const timerEl = $("#call-timer");
    timerEl.hidden = false;
    setCallStatusText(null);
    const tick = () => {
      const c = state.call;
      if (!c || !c.startedAt) return;
      const s = Math.max(0, nowSecs() - c.startedAt);
      const mm = String(Math.floor(s / 60)).padStart(2, "0");
      const ss = String(s % 60).padStart(2, "0");
      timerEl.textContent = `${mm}:${ss}`;
    };
    tick();
    if (state.call) state.call.timerId = setInterval(tick, 1000);
  }

  function stopDurationTimer() {
    if (state.call && state.call.timerId) {
      clearInterval(state.call.timerId);
      state.call.timerId = null;
    }
  }

  // Connect-phase timeout — the desktop mirror of Android's armTimeout +
  // CONNECT_TIMEOUT_MS (CallManager.kt:1581-1592, 112). Armed by the caller when
  // the answer applies (applyRemoteAnswer) and by the callee once its answer is
  // sent (acceptIncoming); cleared on connect (onCallConnected) and teardown
  // (finishCall). armConnectTimeout cancels any prior timer first, so re-arming
  // on the fallback's second answer just replaces the window — matching
  // Android's armTimeout, which does `timeoutJob?.cancel()` before re-launching.
  function armConnectTimeout(c) {
    if (!c) return;
    clearConnectTimeout(c);
    c.connectTimeoutId = setTimeout(async () => {
      c.connectTimeoutId = null; // fired — no longer pending
      const { shouldConnectTimeoutFire } = await callDecisionsReady;
      // Only end the call if it's still current, un-ended, and never connected
      // (mirrors the guard in Android's armTimeout body, CallManager.kt:1586).
      if (
        !shouldConnectTimeoutFire({
          isCurrentCall: state.call === c,
          ended: c.ended,
          connected: c.connected,
        })
      )
        return;
      // Same terminal shape the FAIL connection-state action uses.
      await finishCall({ sendHangup: true, reason: "failed", outcome: "failed" });
    }, CONNECT_TIMEOUT_MS);
  }

  function clearConnectTimeout(c) {
    if (c && c.connectTimeoutId) {
      clearTimeout(c.connectTimeoutId);
      c.connectTimeoutId = null;
    }
  }

  // A media bubble: renders inline if we already hold an object URL (our own
  // sent media), otherwise a Download button that fetches + decrypts on click.
  // `repliable` is false in the couple panel: that view has no composer and no
  // reply chip, so a button there would set a reply target nothing can show.
  function mediaBubble(m, { repliable = true } = {}) {
    const wrap = el("div", { class: "bubble " + (m.outgoing ? "out" : "in") });
    // Same anchor as a text bubble: replying to an attachment is expressible, so
    // jumping back to one has to be too.
    if (m.id) wrap.dataset.msgId = m.id;
    if (m.media.caption) wrap.append(el("div", { class: "media-caption", text: m.media.caption }));

    if (m.media.objectUrl) {
      wrap.append(renderMediaEl(m.media.mime, m.media.objectUrl, m.media.caption));
    } else {
      const btn = el("button", { class: "btn btn-ghost btn-sm", text: "⬇ Download & view" });
      btn.addEventListener("click", async () => {
        // Dedupe: a re-render can hand out a fresh button for the same message
        // while a download is in flight — guard so we never fetch (and mint an
        // object URL) twice for one blob.
        if (m.media.objectUrl || m.media.loading) return;
        m.media.loading = true;
        btn.disabled = true;
        btn.textContent = "Decrypting…";
        try {
          const out = await safeInvoke("download_and_decrypt_media", {
            eventId: m.media.eventId,
          });
          const mime = out.mime_type || m.media.mime;
          if (!m.media.objectUrl) {
            // Through the bounded cache: an object URL pins the decrypted blob
            // until it is revoked, so they cannot simply accumulate.
            m.media.objectUrl = await mediaUrlFor(
              m.media.eventId,
              base64ToBlob(out.base64, mime),
            );
          }
          // Re-render from state (not replaceChild on a possibly-detached node)
          // so the inline media lands in the live DOM for whichever screen shows it.
          renderConversation();
          renderCoupleMedia();
        } catch {
          btn.disabled = false;
          btn.textContent = "⬇ Retry";
        } finally {
          m.media.loading = false;
        }
      });
      wrap.append(btn);
    }
    wrap.append(el("span", { class: "bubble-time", text: relTime(m.created_at) }));
    // An attachment is an event like any other, so it is repliable like any
    // other. It was not, only because media rows carried no `id`.
    if (m.id && repliable) wrap.append(replyButton(m));
    return wrap;
  }

  function onIncomingMedia(p) {
    const key = p.sender || "unknown";
    const list = state.dms.get(key) || [];
    list.push({
      created_at: p.created_at,
      outgoing: false,
      id: p.event_id,
      media: { eventId: p.event_id, mime: p.mime_type, caption: p.caption },
    });
    state.dms.set(key, list);
    renderContacts();
    if (state.activeContact === key) renderConversation();
    // The backend normalises the sender to bech32 npub, matching the partner
    // npub the couple panel is keyed by — repaint it when partner media lands.
    if (key === state.partnerNpub) renderCoupleMedia();
    showToast(`New encrypted media from ${shortNpub(key)}`, "info");
  }

  // Confirm, encrypt and upload a selected file to `targetPubkey`, then render it
  // locally.
  //
  // Three steps, and the split between them is the point: refuse what cannot be
  // sent, let the sender look at what can, then upload. It used to be one step —
  // a pick went straight to the uploader — so the first sight of what had been
  // chosen was in one's own thread, already sent.
  //
  // The caption ("tag") the sheet opens with is whatever is in the composer, per
  // `attachment_caption.mjs` — Telegram's rule, minus the case where those words
  // are a half-written reply. It used to be the *file's name*, which is noise in
  // the recipient's thread at best and at worst the one piece of local filesystem
  // vocabulary the sender never chose to share.
  //
  // `composer` is the input whose text seeds that caption — the DM box in the
  // conversation, and nothing at all in the couple panel, which has no composer
  // of its own and must not reach across screens for the DM one's draft.
  async function handleAttach(file, targetPubkey, { composer = null } = {}) {
    if (!file) return;
    if (!targetPubkey) {
      showToast("No recipient selected", "warn");
      return;
    }
    const { captionForAttachment, captionConsumesDraft, attachmentRejection } =
      await attachmentCaptionReady;
    // Before the preview, not after the upload: composing a caption for a file
    // that was never going to fit is the one bit of work worth not wasting.
    const refusal = attachmentRejection(file.name, file.size);
    if (refusal) {
      showToast(refusal, "warn");
      return;
    }
    const mime = file.type || "application/octet-stream";
    const replyPending = !!state.replyTo;
    const draft = composer ? composer.value : "";
    const consumed = captionConsumesDraft(draft, replyPending);
    const caption = await openAttachmentPreview(
      file,
      captionForAttachment(draft, replyPending),
    );
    // Backed out. Nothing was read, nothing was encrypted, and the draft is
    // still where they left it.
    if (caption === null) return;

    let base64;
    try {
      base64 = await fileToBase64(file);
    } catch {
      showToast("Could not read the file", "error");
      return;
    }
    showToast("Encrypting & uploading…", "info");
    try {
      const dto = await safeInvoke("send_media_bytes", {
        targetPubkey,
        mimeType: mime,
        caption,
        base64,
      });
      // Cleared only now, and only if its text went along: a failed upload must
      // leave what the person typed where they typed it.
      if (consumed && composer) {
        composer.value = "";
        // The box is empty again, and the core is told so — the same edge a
        // manual clear reports (`comrade_core::nudge`).
        reportDraftEdit();
      }
      // Optimistic local render straight from the picked file — no round-trip.
      const objectUrl = URL.createObjectURL(file);
      const list = state.dms.get(targetPubkey) || [];
      list.push({
        created_at: dto.created_at || nowSecs(),
        outgoing: true,
        id: dto.event_id,
        media: { eventId: dto.event_id, mime, caption, objectUrl },
      });
      state.dms.set(targetPubkey, list);
      if (state.activeContact === targetPubkey) {
        renderContacts();
        renderConversation();
      } else if (document.body.dataset.screen === "app") {
        selectContact(targetPubkey);
      }
      renderCoupleMedia();
      showToast("Encrypted media sent", "success");
    } catch {
      /* toasted */
    }
  }

  function renderCoupleMedia() {
    const box = $("#couple-media");
    if (!box || !state.partnerNpub) return;
    box.innerHTML = "";
    const msgs = state.dms.get(state.partnerNpub) || [];
    for (const m of msgs) if (m.media) box.append(mediaBubble(m, { repliable: false }));
    box.scrollTop = box.scrollHeight;
  }

  // Live UPI /pay detection in the DM composer (real extract_payments command).
  const handleDmInput = debounce(async () => {
    const text = $("#dm-input").value;
    const preview = $("#dm-upi-preview");
    if (!text.includes("/pay")) {
      preview.hidden = true;
      preview.innerHTML = "";
      return;
    }
    try {
      const intents = await safeInvoke("extract_payments", { text }, { silent: true });
      preview.innerHTML = "";
      if (intents && intents.length) {
        preview.hidden = false;
        for (const i of intents)
          preview.append(
            el("div", {
              class: "upi-chip",
              text: `Detected: ₹${Number(i.amount_inr).toFixed(2)} → ${i.vpa}`,
            }),
          );
      } else {
        preview.hidden = true;
      }
    } catch {
      preview.hidden = true;
    }
  }, 250);

  // ── Milestone 4: Travel / Off-Grid toggle ─────────────────────────────────
  async function handleTravel(e) {
    const want = e.target.checked;
    const target = want ? "OffGridTravel" : "Base";
    try {
      const ws = await safeInvoke("toggle_app_workspace", { target });
      applyWorkspace(ws);
      // Honest copy: switching workspace only changes the app's mode today —
      // engine disconnect/mesh start-up is not wired yet (AUDIT A1 / M2-4).
      showToast(
        want
          ? "Off-Grid / Travel mode enabled (relay disconnect not yet implemented)"
          : "Back in Base mode",
        "info",
      );
    } catch {
      e.target.checked = !want; // revert the switch on a blocked transition
    }
  }

  // ── Milestone 4: Partner Portal — real Sakha/Sakhi pairing handshake ──────
  //
  // Pairing is a genuine Diffie-Hellman key exchange (`pair_sakha`, backed by
  // `SakhaEngine::pair_with`) between two Nostr public keys — not a client-side
  // token check. A completed pairing is persisted on the backend and survives
  // a relaunch, so a returning couple gets a "Continue" shortcut instead of
  // being asked to paste each other's keys again every session.

  /** Opening the portal decides which face to show: the pairing form (first
   * time, or pairing with someone new) or the "already paired" shortcut. */
  async function openPartnerModal() {
    $("#modal-partner").hidden = false;
    let status = null;
    try {
      status = await safeInvoke("sakha_status", undefined, { silent: true });
    } catch {
      /* vault locked or an older backend without the command — show the form */
    }
    if (status && status.paired) {
      showPairExisting(status);
    } else {
      showPairForm();
    }
  }

  function closePartnerModal() {
    $("#modal-partner").hidden = true;
  }

  function showPairExisting(status) {
    $("#pair-existing-npub").textContent = shortNpub(status.partner_npub || "");
    $("#pair-existing-role").textContent = status.role === "sakhi" ? "Sakhi" : "Sakha";
    $("#pair-existing").hidden = false;
    $("#pair-form").hidden = true;
  }

  function showPairForm() {
    $("#pair-existing").hidden = true;
    $("#pair-form").hidden = false;
    $("#pair-payload").focus();
  }

  /** Re-enter the sandbox as an already-paired partner — no new handshake. */
  async function handlePairContinue() {
    const btn = $("#pair-continue");
    setBusy(btn, true);
    try {
      const status = await safeInvoke("sakha_status", undefined, { silent: true });
      const role = status.role === "sakhi" ? "sakhi" : "sakha";
      const target = role === "sakhi" ? "CoupleSandboxSakhi" : "CoupleSandboxSakha";
      const ws = await safeInvoke("toggle_app_workspace", { target });
      state.coupleRole = role;
      closePartnerModal();
      applyWorkspace(ws);
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  /** Perform the real pairing handshake, then enter the sandbox. */
  async function handlePair() {
    const payload = $("#pair-payload").value.trim();
    const role = (document.querySelector("input[name=pair-role]:checked") || {}).value || "sakha";
    if (!/^npub1[0-9a-z]+$/i.test(payload)) {
      showToast("Enter your partner's npub public key", "warn");
      return;
    }
    const btn = $("#pair-submit");
    setBusy(btn, true);
    try {
      await safeInvoke("pair_sakha", { partnerPubkey: payload, role });
      const target = role === "sakhi" ? "CoupleSandboxSakhi" : "CoupleSandboxSakha";
      const ws = await safeInvoke("toggle_app_workspace", { target });
      state.coupleRole = role;
      $("#pair-payload").value = "";
      closePartnerModal();
      applyWorkspace(ws);
      showToast("Paired — your shared ledger is ready", "success");
    } catch {
      /* e.g. an invalid key, or blocked because Travel mode is active —
         toasted already */
    } finally {
      setBusy(btn, false);
    }
  }

  async function exitCouple() {
    try {
      const ws = await safeInvoke("toggle_app_workspace", { target: "Base" });
      applyWorkspace(ws);
    } catch {
      /* toasted */
    }
  }

  // ── Hisab-Kitab shared ledger: pairing status, entries, live sync ────────

  function setLedgerFormEnabled(enabled) {
    for (const id of ["ledger-desc", "ledger-amount", "ledger-paid-by", "ledger-add-btn"]) {
      $(`#${id}`).disabled = !enabled;
    }
  }

  function renderLedgerText(text) {
    $("#ledger-status").textContent =
      text && text.trim() ? text : "No entries yet — add the first one below.";
  }

  /** Pull the authoritative pairing state and refresh everything it drives:
   * the partner key (couple media), the entry form's enabled state, and the
   * ledger content itself. Called whenever the Couple Sandbox screen opens. */
  async function refreshSakhaStatus() {
    let status;
    try {
      status = await safeInvoke("sakha_status", undefined, { silent: true });
    } catch {
      return; // older backend without the command, or vault locked
    }
    state.partnerNpub = status.partner_npub || null;
    $("#couple-attach").disabled = !state.partnerNpub;
    setLedgerFormEnabled(!!status.paired);
    renderCoupleMedia();
    if (status.paired) await loadLedger();
    else $("#ledger-status").textContent = "Not yet paired.";
  }

  async function loadLedger() {
    try {
      renderLedgerText(await safeInvoke("sakha_read_ledger", undefined, { silent: true }));
    } catch {
      /* leave whatever was already shown */
    }
  }

  async function handleAddLedgerEntry(e) {
    e.preventDefault();
    const description = $("#ledger-desc").value.trim();
    const paidBy = $("#ledger-paid-by").value.trim();
    const amountInr = parseFloat($("#ledger-amount").value);
    if (!description || !paidBy || !Number.isFinite(amountInr) || amountInr < 0) {
      showToast("Fill in what it was for, the amount, and who paid", "warn");
      return;
    }
    const btn = $("#ledger-add-btn");
    setBusy(btn, true);
    try {
      renderLedgerText(await safeInvoke("sakha_add_entry", { description, amountInr, paidBy }));
      $("#ledger-desc").value = "";
      $("#ledger-amount").value = "";
      $("#ledger-paid-by").value = "";
      $("#ledger-desc").focus();
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  /** The partner pushed a ledger update over the sync channel — refresh live. */
  function onLedgerUpdated(p) {
    renderLedgerText(p.ledger || "");
    showToast("Your partner updated the shared ledger", "info");
  }

  async function handleSyncLedger() {
    const btn = $("#sync-ledger-btn");
    setBusy(btn, true);
    try {
      await safeInvoke("sync_ledger");
      showToast("Hisab-Kitab ledger synced to your partner", "success");
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  // ── Focus: attention practice (docs/ATTENTION.md phase 2) ─────────────────
  //
  // Sessions and the long read, both strictly local — nothing on this tab
  // touches a relay or the mesh. The engine owns every judgement: which
  // durations exist, which one to suggest, whether a session that outlived its
  // plan completed or lapsed, and how the text is chunked. This file draws the
  // answers and reports the clicks.
  //
  // There is no usage mirror here. It is fed by Android's UsageStatsManager
  // and the store is per-device, so a desktop panel could only ever read zero
  // — and a panel that always says zero is a worse answer than no panel.

  async function loadFocus() {
    if (!state.identity) return; // vault still locked; the tab paints on unlock
    if (!focusView) await focusReady.catch(() => {});
    try {
      const [presets, suggested, prompt, active, history, reading] = await Promise.all([
        safeInvoke("focus_presets", undefined, { silent: true }).catch(() => []),
        safeInvoke("suggested_focus_minutes"),
        safeInvoke("focus_prompt"),
        safeInvoke("active_focus_session"),
        safeInvoke("focus_sessions"),
        safeInvoke("reading"),
      ]);
      state.focus.presets = Array.isArray(presets) ? presets : [];
      state.focus.suggested = suggested;
      state.focus.prompt = prompt || "";
      state.focus.active = active || null;
      // Only finished sessions are history; the running one has its own card.
      state.focus.history = (Array.isArray(history) ? history : []).filter((s) => s.outcome);
      state.focus.reading = reading || null;
      renderFocus();
      renderReader();
    } catch {
      /* toasted */
    }
  }

  function renderFocus() {
    if (!focusView) return;
    const f = state.focus;
    const running = f.active;

    $("#focus-idle").hidden = !!running;
    $("#focus-running").hidden = !running;

    if (running) {
      $("#focus-running-intent").textContent =
        (running.intent || "").trim() || "This block, on one thing.";
      $("#focus-clock").textContent = focusView.formatCountdown(running.remaining_secs);
      startFocusTick();
    } else {
      stopFocusTick();
      $("#focus-prompt").textContent = f.prompt;
      const selected = focusView.chosenPreset(f.presets, f.suggested, f.chosen);
      const row = $("#focus-presets");
      row.replaceChildren(
        ...f.presets.map((m) =>
          el("button", {
            class: "chip",
            type: "button",
            text: `${m}m`,
            "aria-pressed": m === selected ? "true" : "false",
            onClick: () => {
              state.focus.chosen = m;
              renderFocus();
            },
          }),
        ),
      );
      // "From your own sessions" is only true once there are some. On a first
      // run the suggestion is the ladder's floor, and saying otherwise would
      // credit the user with a history they do not have.
      $("#focus-suggestion").textContent = !selected
        ? ""
        : f.history.length === 0
          ? `Starting at ${f.suggested}m. Longer blocks open up as you use them.`
          : `Suggested: ${f.suggested}m, from your own sessions.`;
      $("#focus-start").disabled = selected == null;
    }

    const reflection = $("#focus-reflection");
    reflection.textContent = f.reflection || "";
    reflection.hidden = !f.reflection;

    const list = $("#focus-history");
    list.replaceChildren(
      ...f.history.slice(0, 10).map((s) => el("li", { text: focusView.historyLine(s) })),
    );
    $("#focus-history-empty").hidden = f.history.length > 0;
  }

  /**
   * Re-read the running session once a second.
   *
   * The remaining time is not counted down locally: the engine is the
   * authority, and it is also what decides that a session which outlived its
   * plan plus the grace window has *lapsed* rather than completed. Asking it
   * every second is how that resolution reaches the screen at all — a local
   * timer would happily count into the negatives on a machine that slept.
   */
  function startFocusTick() {
    if (state.focus.tick) return;
    state.focus.tick = setInterval(async () => {
      // Nothing to repaint while the tab is off screen — behind another tab,
      // behind the Couple overlay, or behind a relocked vault door. The
      // session keeps running in the engine either way; this is only the clock.
      if (document.body.dataset.screen !== "app" || $("#view-focus").hidden) return;
      let active = null;
      try {
        active = await safeInvoke("active_focus_session", undefined, { silent: true });
      } catch {
        return; // transient; the next tick tries again
      }
      if (!active) {
        // It ended on its own — reload so the outcome shows up in the history
        // instead of the session simply vanishing.
        state.focus.active = null;
        await loadFocus();
        return;
      }
      state.focus.active = active;
      if (focusView) $("#focus-clock").textContent = focusView.formatCountdown(active.remaining_secs);
    }, 1000);
  }

  function stopFocusTick() {
    if (!state.focus.tick) return;
    clearInterval(state.focus.tick);
    state.focus.tick = null;
  }

  async function handleFocusStart() {
    if (!focusView) return;
    const minutes = focusView.chosenPreset(
      state.focus.presets,
      state.focus.suggested,
      state.focus.chosen,
    );
    if (minutes == null) return;
    const btn = $("#focus-start");
    setBusy(btn, true);
    try {
      const started = await safeInvoke("start_focus_session", {
        intent: $("#focus-intent").value.trim(),
        plannedMinutes: minutes,
      });
      state.focus.active = started;
      state.focus.reflection = null;
      $("#focus-intent").value = "";
      renderFocus();
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  /**
   * End the running session.
   *
   * `completed` is what the *user* claims; the engine may still record a lapse
   * if nobody was present for the block, and the reflection line is asked for
   * by the outcome it actually stored rather than the one requested. Letting
   * the claim win would flatter the ladder that reads this history, which
   * would make the practice it measures fictional.
   */
  async function handleFocusFinish(completed) {
    stopFocusTick();
    try {
      const finished = await safeInvoke("finish_focus_session", { completed });
      state.focus.active = null;
      state.focus.reflection = finished?.outcome
        ? await safeInvoke("focus_reflection", { outcome: finished.outcome }, { silent: true }).catch(
            () => null,
          )
        : null;
      await loadFocus();
    } catch {
      await loadFocus();
    }
  }

  // ── Long read ─────────────────────────────────────────────────────────────

  function renderReader() {
    if (!focusView) return;
    const r = state.focus.reading;
    $("#reader-compose").hidden = !!r;
    $("#reader-open").hidden = !r;
    if (!r) return;

    const nav = focusView.readerNav(r.position, r.chunks.length);
    $("#reader-open-title").textContent = r.title || "Long read";
    $("#reader-chunk").textContent = r.chunks[nav.position] || "";
    $("#reader-progress").textContent = nav.label;
    $("#reader-prev").disabled = !nav.canPrev;
    $("#reader-next").disabled = !nav.canNext;
    $("#reader-finished").hidden = !nav.atEnd;
  }

  async function handleReaderSave() {
    const text = $("#reader-text").value;
    if (!text.trim()) {
      showToast("Paste something to read first", "warn");
      return;
    }
    const btn = $("#reader-save");
    setBusy(btn, true);
    try {
      state.focus.reading = await safeInvoke("save_reading", {
        title: $("#reader-title").value.trim(),
        text,
      });
      $("#reader-title").value = "";
      $("#reader-text").value = "";
      renderReader();
    } catch {
      /* toasted */
    } finally {
      setBusy(btn, false);
    }
  }

  async function handleReaderStep(delta) {
    if (!focusView) return;
    const r = state.focus.reading;
    if (!r) return;
    const to = focusView.stepReader(r.position, r.chunks.length, delta);
    // null means the position did not change — every step is a write into the
    // encrypted store, so a click at either end must not become one.
    if (to == null) return;
    // Paint immediately and let the engine's clamped answer overwrite it; the
    // reader should not wait on a disk write to turn the page.
    state.focus.reading = { ...r, position: to };
    renderReader();
    try {
      const updated = await safeInvoke("set_reading_position", { position: to });
      if (updated) {
        state.focus.reading = updated;
        renderReader();
      }
    } catch {
      /* toasted; the optimistic position stands until the next load */
    }
  }

  async function handleReaderClear() {
    try {
      await safeInvoke("clear_reading");
      state.focus.reading = null;
      renderReader();
    } catch {
      /* toasted */
    }
  }

  // ── Watch/listen together ─────────────────────────────────────────────────
  //
  // Each side plays its own copy; Comrade only keeps the two clocks together.
  // All the arithmetic — the clock filter, the drift verdict, the command
  // arbitration — is `comrade_core::together`, and the echo suppression that
  // stops a remote seek being re-broadcast as a local one is
  // `together_sync.mjs`. What is left here is the DOM.

  const togetherSyncReady = import("./together_sync.mjs");

  const $together = {
    panel: () => $("#together-panel"),
    status: () => $("#together-status"),
    player: () => $("#together-player"),
    invite: () => $("#together-invite"),
    join: () => $("#together-join"),
    leave: () => $("#together-leave"),
    shareStatus: () => $("#together-share-status"),
  };

  function setTogetherStatus(text) {
    const el = $together.status();
    if (el) el.textContent = text;
  }

  function showTogetherPanel() {
    const panel = $together.panel();
    if (panel) panel.hidden = !state.activeContact;
  }

  /** Load a local file into the player and remember how long it runs. */
  async function handleTogetherPick(file) {
    if (!file) return;
    const player = $together.player();
    if (!player) return;
    // One object URL, revoked on replace. Deliberately not the capacity-8 LRU
    // in media_cache.mjs: an eviction mid-session would revoke this URL and
    // kill playback.
    if (state.together?.objectUrl) URL.revokeObjectURL(state.together.objectUrl);
    const objectUrl = URL.createObjectURL(file);
    const { createEchoSuppressor } = await togetherSyncReady;
    state.together = Object.assign(state.together || {}, {
      file,
      objectUrl,
      durationMs: 0,
      suppressor: state.together?.suppressor || createEchoSuppressor({ now: () => performance.now() }),
    });
    player.src = objectUrl;
    player.hidden = false;
    await new Promise((resolve) => {
      player.onloadedmetadata = resolve;
      setTimeout(resolve, 5000); // a file we cannot measure is still playable
    });
    state.together.durationMs = Math.round((player.duration || 0) * 1000) || 0;
    const invite = $together.invite();
    if (invite) invite.disabled = !state.activeContact;
    setTogetherStatus("Ready to invite");
  }

  async function handleTogetherInvite() {
    if (!state.activeContact || !state.together?.file) return;
    try {
      const session = await safeInvoke("together_start", {
        peer: state.activeContact,
        contentJson: JSON.stringify({
          kind: "local_file",
          duration_ms: state.together.durationMs,
          recording: null,
        }),
      });
      state.together.sessionId = session.session_id;
      state.together.weLead = true;
      setTogetherStatus("Invited — waiting for them");
      $together.leave().hidden = false;
    } catch {
      /* toasted */
    }
  }

  async function handleTogetherJoin() {
    try {
      await safeInvoke("together_join", {});
      $together.join().hidden = true;
      $together.leave().hidden = false;
      setTogetherStatus("Together");
      // We have no copy of what they are playing: say so, which is what starts
      // the handover. If we *do* have one, the person picks it themselves.
      if (!state.together?.file) {
        setShareStatus("Asking them to send it…");
        await sendShareSignal({ share: "ask" });
      }
    } catch {
      /* toasted */
    }
  }

  /**
   * They invited us. The session already exists in the runtime — joining is the
   * user gesture, which is also what unlocks programmatic `play()` in a
   * webview, so it is a button rather than an automatic yes.
   */
  async function onTogetherInvited(p) {
    const { createEchoSuppressor } = await togetherSyncReady;
    state.together = Object.assign(state.together || {}, {
      sessionId: p.session_id,
      weLead: false,
      peerDurationMs: p.content?.duration_ms ?? 0,
      suppressor: state.together?.suppressor || createEchoSuppressor({ now: () => performance.now() }),
    });
    showTogetherPanel();
    $together.join().hidden = false;
    setTogetherStatus(
      state.together.file
        ? "They want to watch together"
        : "They want to watch together — you don't have it, so they can send it",
    );
  }

  async function handleTogetherLeave() {
    endShare();
    try {
      await safeInvoke("together_end", {});
    } catch {
      /* toasted */
    }
    onTogetherOver();
  }

  function onTogetherOver() {
    const player = $together.player();
    if (player) player.pause();
    if (state.together) state.together.sessionId = null;
    $together.join().hidden = true;
    $together.leave().hidden = true;
    setTogetherStatus("Not in a session");
  }

  /**
   * Run the operations a verdict or a command produced, arming the echo
   * suppressor for each one *before* touching the element.
   *
   * The order matters and is the bug this design exists to prevent: assigning
   * `currentTime` fires `seeked` asynchronously, so a latch set after the
   * assignment is already too late, and assigning a value the element already
   * holds fires no event at all — which is why entries expire on a deadline
   * rather than waiting to be popped.
   */
  function runTogetherPlan(plan) {
    const player = $together.player();
    if (!player || !state.together?.suppressor || !plan) return;
    for (const entry of plan.expect || []) state.together.suppressor.expect(entry);
    for (const op of plan.ops || []) {
      switch (op.kind) {
        case "seek":
          player.currentTime = op.secs;
          break;
        case "play":
          player.play().catch(() => {});
          break;
        case "pause":
          player.pause();
          break;
        case "rate":
          player.playbackRate = op.value;
          break;
        default:
          break;
      }
    }
  }

  /** A remote play/pause/seek won the ordering: apply it without echoing it. */
  async function onTogetherCommand(p) {
    if (!state.together) return;
    const { planApply } = await togetherSyncReady;
    const player = $together.player();
    if (!player || !state.together) return;
    const apply = () => {
      const plan = planApply(
        { kind: "adopt", pos_ms: p.pos_ms, playing: p.playing },
        { playing: !player.paused, positionSecs: player.currentTime },
      );
      runTogetherPlan(plan);
    };
    // Non-zero only on a transport fast enough to schedule ahead; over a relay
    // `pos_ms` already carries the flight time and this is zero.
    if (p.apply_in_ms > 0) setTimeout(apply, p.apply_in_ms);
    else apply();
  }

  /** A drift verdict arrived. Only the follower gets these — core decides. */
  async function onTogetherCorrection(p) {
    if (!state.together) return;
    const { planApply } = await togetherSyncReady;
    const player = $together.player();
    if (!player || !state.together) return;
    const plan = planApply(p.verdict, {
      playing: !player.paused,
      positionSecs: player.currentTime,
    });
    runTogetherPlan(plan);
    setTogetherStatus("catching up…");
  }

  /** Our own player moved. Send it only if the person did it, not if we did. */
  async function onTogetherLocalEvent(type) {
    if (!state.together?.sessionId || !state.together.suppressor) return;
    const { classifyLocalEvent } = await togetherSyncReady;
    const player = $together.player();
    if (!player || !state.together?.sessionId) return;
    const { emit } = classifyLocalEvent(
      { type, positionSecs: player.currentTime, playing: !player.paused },
      state.together.suppressor,
    );
    if (!emit) return; // our own doing, echoed back by the element
    await safeInvoke(
      "together_set_state",
      {
        posMs: Math.round(emit.positionSecs * 1000),
        playing: emit.playing,
        effectiveInMs: 0,
      },
      { silent: true },
    ).catch(() => {});
  }

  /** Feed the runtime our playhead so the drift verdict has something true. */
  function reportTogetherPosition() {
    const player = $together.player();
    if (!player || !state.together?.sessionId) return;
    safeInvoke(
      "together_report_position",
      {
        posMs: Math.round(player.currentTime * 1000),
        playing: !player.paused,
        // A browser cannot ask its audio stack how far behind the speaker is.
        // Zero is honest — "unmeasured" — and costs only the accuracy it
        // cannot supply.
        outputLatencyMs: 0,
      },
      { silent: true },
    ).catch(() => {});
  }

  // ── Handing the file over ─────────────────────────────────────────────────
  //
  // `together` assumes both people already have what they are playing. When
  // only one does, the one who has it sends it — over a **separate**
  // `RTCPeerConnection` from any call, for two reasons that both matter:
  //
  // - *Congestion.* One connection means one SCTP association and one
  //   congestion controller, where a multi-gigabyte push and a voice stream
  //   compete and the voice loses. Two connections cost one extra ICE
  //   negotiation and buy complete isolation: a call cannot be degraded by a
  //   transfer it knows nothing about.
  // - *Policy.* The transfer connection is built from its own ICE list
  //   (`share_ice_servers`, which drops TURN under a direct-only policy), so a
  //   relay candidate is never gathered. The call keeps its TURN fallback,
  //   because a relayed *call* is a few tens of kilobits and entirely
  //   reasonable while a relayed film is not.
  //
  // The negotiation rides the together control channel, so it inherits the
  // acceptance gate, the sixty-second age gate and the session scoping. The
  // sender offers, because the sender is the side that opens the data channel.

  const shareTransferReady = import("./share_transfer.mjs");

  /** How many chunks to have in flight before asking for the next window. */
  const SHARE_REQUEST_WINDOW = 64;

  function newShareState(base) {
    return Object.assign(
      {
        role: null, // "sender" | "receiver"
        peer: null,
        sessionId: null,
        offer: null, // the ShareOffer both sides agree on
        file: null, // sender: the File being read
        tracker: null, // receiver: which chunks have landed
        parts: null, // receiver: the bytes, by chunk index
        pc: null,
        channel: null,
        pump: null,
        pendingIce: [],
        // Sender: the half-open range the receiver last asked for. On the
        // session rather than in a closure so it survives the gap between the
        // channel opening and the policy verdict letting the pump start.
        cursor: null,
        remoteSet: false,
        judged: false,
        objectUrl: null,
        done: false,
      },
      base,
    );
  }

  async function sendShareSignal(signal) {
    try {
      await safeInvoke("together_share", { signalJson: JSON.stringify(signal) }, { silent: true });
    } catch {
      // A lost negotiation step fails the transfer, not the session — the
      // watch-together part keeps working with whatever each side already has.
    }
  }

  function endShare({ toast } = {}) {
    const s = state.share;
    if (!s) return;
    state.share = null;
    try {
      s.pump?.stop();
      s.channel?.close();
      s.pc?.close();
    } catch {
      /* already torn down */
    }
    if (s.objectUrl) URL.revokeObjectURL(s.objectUrl);
    // A question about a transfer that no longer exists must not stay on
    // screen — answering it would act on a session that is already gone.
    s.awaitingConsent = false;
    renderShareConsent(s, "");
    if (toast) showToast(toast, "warn");
  }

  /**
   * Build the transfer connection. Deliberately not `setupPeer`: that one
   * attaches microphone and camera tracks and is owned by `state.call`.
   */
  async function newTransferPeer() {
    const servers = (await safeInvoke("share_ice_servers", {}, { silent: true })) || [];
    return new RTCPeerConnection({ iceServers: normalizeIce(servers) });
  }

  /** Trickle our candidates for the *transfer* connection, not the call's. */
  function wireTransferIce(pc) {
    pc.onicecandidate = (ev) => {
      if (!ev.candidate) return;
      sendShareSignal({
        share: "transport",
        signal: {
          kind: "ice",
          candidate: ev.candidate.candidate,
          sdp_mid: ev.candidate.sdpMid == null ? undefined : ev.candidate.sdpMid,
          sdp_m_line_index:
            ev.candidate.sdpMLineIndex == null ? undefined : ev.candidate.sdpMLineIndex,
        },
      });
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "connected") judgeSharePath();
      else if (pc.connectionState === "failed") {
        endShare({ toast: "Couldn't open a route to send the file." });
      }
    };
  }

  /**
   * Inspect the path ICE actually chose and ask core whether it may carry this.
   *
   * The second line of the policy, after the structural one: even with no TURN
   * configured a peer-reflexive path can turn out to be relayed at the far end,
   * and `share_transfer_verdict` classifies a pair as relayed if *either* end
   * is. An unsettled report reads as unknown, which is refused and retried
   * rather than waved through.
   */
  async function judgeSharePath() {
    const s = state.share;
    if (!s || s.judged || !s.pc || !s.offer) return;
    const { selectedPairTypes, describeVerdict } = await shareTransferReady;
    if (state.share !== s) return;
    let types = null;
    try {
      types = selectedPairTypes(await s.pc.getStats());
    } catch {
      /* treated as unknown below */
    }
    if (state.share !== s) return;
    // Caught rather than propagated: this runs from a connectionstatechange
    // handler, where a rejection is an unhandled one and the retry below never
    // happens — the transfer would sit at zero with nothing said. An absent
    // verdict describes as "cannot work out how to send this", which refuses.
    let verdict = null;
    try {
      verdict = await safeInvoke(
        "share_transfer_verdict",
        {
          localCandidateType: types?.local || "",
          remoteCandidateType: types?.remote || "",
          totalBytes: s.offer.total_bytes,
          // Only ever true after the person has answered the question below.
          // Core treats this as an answer, not an override: it can turn
          // needs_consent into allow and can do nothing else.
          consentGranted: s.consentGranted === true,
        },
        { silent: true },
      );
    } catch {
      /* handled by describeVerdict(null) below */
    }
    if (state.share !== s) return;
    const plan = describeVerdict(verdict);
    if (plan.retryable) {
      // ICE has not settled. Look again shortly rather than failing a transfer
      // that was about to be fine.
      setTimeout(() => judgeSharePath(), 1000);
      return;
    }
    if (plan.needsConsent) {
      // The policy wants a person to agree to this specific transfer. Ask, and
      // do nothing at all until they answer — no bytes move, and no refusal is
      // sent, because neither has been decided yet.
      askShareConsent(s, plan.message);
      return;
    }
    if (!plan.proceed) {
      // Tell them why, and tell the other side too — they are looking at a
      // progress bar that would otherwise sit at zero forever.
      sendShareSignal({ share: "refuse", reason: verdict?.reason ?? { kind: "path_unknown" } });
      endShare({ toast: plan.message });
      return;
    }
    s.judged = true;
    setShareStatus(`Sending over a ${verdict.path === "host" ? "local" : "direct"} connection…`);
    if (s.role === "sender") startShareSending();
  }

  /**
   * Put the relay question on screen and wait. Nothing moves until it is
   * answered — declining is a real outcome, not a timeout.
   *
   * The prompt is rendered rather than `confirm()`ed because a modal
   * `confirm` blocks the event loop, and this connection is live: the data
   * channel, the ICE trickle and the session's own heartbeat all stop while a
   * native dialog is up.
   */
  function askShareConsent(s, message) {
    s.awaitingConsent = true;
    setShareStatus(message);
    renderShareConsent(s, message);
  }

  function renderShareConsent(s, message) {
    const host = document.getElementById("share-consent");
    if (!host) return;
    host.innerHTML = "";
    if (!s?.awaitingConsent) {
      host.hidden = true;
      return;
    }
    host.hidden = false;
    const text = document.createElement("p");
    text.className = "share-consent-text";
    text.textContent = message;
    const yes = document.createElement("button");
    yes.className = "btn-primary";
    yes.textContent = "Send it anyway";
    yes.onclick = () => {
      if (state.share !== s) return;
      s.consentGranted = true;
      s.awaitingConsent = false;
      renderShareConsent(s, "");
      // Re-ask rather than assume: the path may have changed while the
      // question was on screen, and the answer we want is the one for the
      // route we actually have now.
      judgeSharePath();
    };
    const no = document.createElement("button");
    no.className = "btn-ghost";
    no.textContent = "Don't send";
    no.onclick = () => {
      if (state.share !== s) return;
      s.awaitingConsent = false;
      renderShareConsent(s, "");
      sendShareSignal({ share: "refuse", reason: { kind: "relay_forbidden" } });
      endShare({ toast: "Didn't send it." });
    };
    const row = document.createElement("div");
    row.className = "share-consent-actions";
    row.append(yes, no);
    host.append(text, row);
  }

  /** Sender: read the requested ranges off disk and push them, with backpressure. */
  async function startShareSending() {
    const s = state.share;
    if (!s || !s.channel || s.pump) return;
    const { CHUNK_BYTES, chunkRange, createTransferPump, frameChunk } = await shareTransferReady;
    if (state.share !== s || !s.channel) return;

    // Reading is async and the pump is synchronous, so a small read-ahead sits
    // between them: the pump takes only what is already in hand, and a refill
    // kicks it again. Without this the pump would either block or busy-wait.
    const ready = [];
    let reading = false;
    async function refill() {
      const cursor = s.cursor;
      if (reading || !cursor || state.share !== s) return;
      reading = true;
      try {
        while (cursor && cursor.next < cursor.end && ready.length < SHARE_REQUEST_WINDOW) {
          const index = cursor.next;
          const range = chunkRange(s.offer, index);
          if (!range) break;
          const slice = s.file.slice(range[0], range[0] + range[1]);
          const bytes = new Uint8Array(await slice.arrayBuffer());
          if (state.share !== s) return;
          ready.push(frameChunk(index, bytes));
          cursor.next += 1;
        }
      } catch (e) {
        endShare({ toast: `Couldn't read the file — ${errText(e)}` });
        return;
      } finally {
        reading = false;
      }
      s.pump?.kick();
    }

    s.pump = createTransferPump({
      channel: s.channel,
      chunkBytes: CHUNK_BYTES,
      nextChunks: (budget) => {
        const batch = ready.splice(0, budget);
        refill();
        return batch;
      },
      isDone: () => s.done,
      onError: (e) => endShare({ toast: `The transfer stopped — ${errText(e)}` }),
    });
    refill();
  }

  /** Receiver: bank a chunk, keep the request window topped up, play when we can. */
  async function onShareChunk(data) {
    const s = state.share;
    if (!s || !s.tracker) return;
    const { chunkFrameFits, parseChunkFrame } = await shareTransferReady;
    if (state.share !== s) return;
    const frame = parseChunkFrame(data);
    // A peer can put anything on this channel. A wrong index writes bytes into
    // the wrong place and a wrong length shifts everything after it, so both
    // are checked here rather than left for the hash at the very end.
    if (!frame || !chunkFrameFits(s.offer, frame.index, frame.payload.byteLength)) return;
    if (!s.tracker.accept(frame.index)) return;
    s.parts[frame.index] = frame.payload;

    setShareStatus(`Receiving — ${Math.round(s.tracker.fraction() * 100)}%`);
    if (s.tracker.isComplete()) {
      finishShareReceive();
      return;
    }
    // Ask for the next window as the current one drains, anchored at the
    // playhead so a seek costs one request rather than a re-download.
    const posMs = Math.round(($together.player()?.currentTime || 0) * 1000);
    const req = s.tracker.nextRequest(posMs, SHARE_REQUEST_WINDOW);
    if (req && s.channel?.readyState === "open") {
      s.channel.send(JSON.stringify(req));
    }
  }

  async function finishShareReceive() {
    const s = state.share;
    if (!s || s.done) return;
    s.done = true;
    const blob = new Blob(s.parts.filter(Boolean), { type: "application/octet-stream" });
    // Integrity, once, at the end: SubtleCrypto has no streaming digest, so a
    // per-chunk hash is not available to a webview. This is why the framing
    // checks above exist — they catch the failures a whole-file hash would only
    // report after the whole file.
    try {
      const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
      const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
      if (hex.toLowerCase() !== String(s.offer.sha256).toLowerCase()) {
        endShare({ toast: "The file that arrived isn't the one that was sent." });
        return;
      }
    } catch (e) {
      endShare({ toast: `Couldn't verify the file — ${errText(e)}` });
      return;
    }
    if (state.share !== s) return;
    s.objectUrl = URL.createObjectURL(blob);
    const player = $together.player();
    if (player) {
      player.src = s.objectUrl;
      player.hidden = false;
    }
    setShareStatus("Ready — you both have it now.");
    s.pump?.stop();
  }

  function setShareStatus(text) {
    const el = $together.shareStatus();
    if (el) el.textContent = text;
  }

  /** Sender: offer what we have, once they say they don't have it. */
  async function offerShare(file, durationMs) {
    const s = state.share;
    if (!s || s.role !== "sender" || !file) return;
    const { CHUNK_BYTES } = await shareTransferReady;
    let sha256 = "";
    try {
      const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
      sha256 = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    } catch (e) {
      endShare({ toast: `Couldn't read the file — ${errText(e)}` });
      return;
    }
    if (state.share !== s) return;
    s.file = file;
    s.offer = {
      total_bytes: file.size,
      chunk_bytes: CHUNK_BYTES,
      sha256,
      duration_ms: durationMs || 0,
    };
    setShareStatus("Waiting for them to accept…");
    await sendShareSignal({ share: "offer", offer: s.offer });
  }

  /** Sender: they accepted, so build the connection and open the channel. */
  async function beginShareNegotiation() {
    const s = state.share;
    if (!s || s.role !== "sender" || s.pc) return;
    try {
      s.pc = await newTransferPeer();
    } catch (e) {
      endShare({ toast: `Couldn't start the transfer — ${errText(e)}` });
      return;
    }
    if (state.share !== s) {
      s.pc.close();
      return;
    }
    wireTransferIce(s.pc);
    // Ordered and reliable: the receiver asks for ranges and expects them
    // whole. Unreliable delivery would mean re-implementing retransmission on
    // top of a stack that already has it.
    s.channel = s.pc.createDataChannel("comrade-share", { ordered: true });
    s.channel.binaryType = "arraybuffer";
    // Installed here rather than alongside the pump: the receiver asks as soon
    // as the channel opens, which is *while* the policy check is still running.
    // A handler attached after the verdict would miss that first request, and
    // nothing re-sends it — the transfer would sit at zero forever.
    s.channel.onmessage = (ev) => {
      try {
        const req = JSON.parse(typeof ev.data === "string" ? ev.data : "");
        if (Number.isInteger(req?.from) && Number.isInteger(req?.count)) {
          s.cursor = { next: req.from, end: req.from + req.count };
          s.pump?.kick();
        }
      } catch {
        /* not a request; the sender has nothing else to read on this channel */
      }
    };
    try {
      const offer = await s.pc.createOffer();
      await s.pc.setLocalDescription(offer);
      await sendShareSignal({ share: "transport", signal: { kind: "offer", sdp: offer.sdp } });
    } catch (e) {
      endShare({ toast: `Couldn't start the transfer — ${errText(e)}` });
    }
  }

  /** Both sides: one step of the negotiation arrived over the session channel. */
  async function onShareTransport(signal) {
    const s = state.share;
    if (!s) return;
    try {
      if (signal.kind === "offer") {
        if (!s.pc) {
          s.pc = await newTransferPeer();
          if (state.share !== s) {
            s.pc.close();
            return;
          }
          wireTransferIce(s.pc);
          s.pc.ondatachannel = (ev) => {
            s.channel = ev.channel;
            s.channel.binaryType = "arraybuffer";
            s.channel.onmessage = (m) => onShareChunk(m.data);
            const askForTheFirstWindow = () => {
              const req = s.tracker?.nextRequest(0, SHARE_REQUEST_WINDOW);
              if (req && s.channel?.readyState === "open") s.channel.send(JSON.stringify(req));
            };
            s.channel.onopen = askForTheFirstWindow;
            // A channel handed over already open fires no `onopen` at all.
            if (s.channel.readyState === "open") askForTheFirstWindow();
          };
        }
        await s.pc.setRemoteDescription({ type: "offer", sdp: signal.sdp });
        s.remoteSet = true;
        await flushShareIce();
        const answer = await s.pc.createAnswer();
        await s.pc.setLocalDescription(answer);
        await sendShareSignal({ share: "transport", signal: { kind: "answer", sdp: answer.sdp } });
      } else if (signal.kind === "answer") {
        if (!s.pc) return;
        await s.pc.setRemoteDescription({ type: "answer", sdp: signal.sdp });
        s.remoteSet = true;
        await flushShareIce();
      } else if (signal.kind === "ice") {
        const candidate = {
          candidate: signal.candidate,
          sdpMid: signal.sdp_mid ?? null,
          sdpMLineIndex: signal.sdp_m_line_index ?? null,
        };
        // Buffer until the remote description exists, exactly as the call path
        // does — an early candidate is dropped by the browser otherwise.
        if (!s.remoteSet) s.pendingIce.push(candidate);
        else await s.pc?.addIceCandidate(candidate);
      }
    } catch (e) {
      endShare({ toast: `The transfer couldn't connect — ${errText(e)}` });
    }
  }

  async function flushShareIce() {
    const s = state.share;
    if (!s || !s.pc) return;
    const queued = s.pendingIce.splice(0);
    for (const c of queued) {
      try {
        await s.pc.addIceCandidate(c);
      } catch {
        /* a candidate we cannot use is not a failed transfer */
      }
    }
  }

  /** The one entry point: a share signal arrived inside the together session. */
  async function onTogetherShare(p) {
    const signal = p && p.signal;
    if (!signal) return;
    switch (signal.share) {
      case "ask": {
        // They don't have it. Offer ours if we picked a local file.
        if (!state.together?.file) return;
        state.share = newShareState({
          role: "sender",
          peer: p.peer,
          sessionId: p.session_id,
        });
        await offerShare(state.together.file, state.together.durationMs);
        break;
      }
      case "offer": {
        const { createTracker } = await shareTransferReady;
        state.share = newShareState({
          role: "receiver",
          peer: p.peer,
          sessionId: p.session_id,
          offer: signal.offer,
        });
        state.share.tracker = createTracker(signal.offer);
        state.share.parts = new Array(state.share.tracker.chunkCount).fill(null);
        setShareStatus(`They can send it — ${Math.round(signal.offer.total_bytes / 1048576)} MB.`);
        await sendShareSignal({ share: "accept" });
        break;
      }
      case "accept":
        await beginShareNegotiation();
        break;
      case "refuse": {
        const { describeVerdict } = await shareTransferReady;
        const plan = describeVerdict({ verdict: "refuse", reason: signal.reason });
        endShare({ toast: plan.message });
        break;
      }
      case "transport":
        await onShareTransport(signal.signal);
        break;
      default:
        // An unknown step from a newer build: ignore rather than guess.
        break;
    }
  }

  // ── Milestone 3: real-time event wiring ───────────────────────────────────
  async function wireEvents() {
    try {
      await backend.listen(EVENT_CHANNEL, (evt) => {
        const p = evt && evt.payload;
        if (!p || !p.type) return;
        if (p.type === "incoming_chitthi") {
          prependChitthi(
            {
              id: p.id,
              author: p.author,
              content: p.content,
              created_at: p.created_at,
              reply_to: p.reply_to,
            },
            true,
          );
        } else if (p.type === "incoming_direct_message") {
          onIncomingDm(p);
        } else if (p.type === "incoming_media") {
          onIncomingMedia(p);
        } else if (p.type === "incoming_call_signal") {
          onCallSignal(p);
        } else if (p.type === "incoming_message_request") {
          onIncomingMessageRequest(p);
        } else if (p.type === "message_status") {
          onMessageStatus(p);
        } else if (p.type === "peer_profile_updated") {
          onPeerProfileUpdated(p);
        } else if (p.type === "comrade_presence") {
          onComradePresence(p);
        } else if (p.type === "comrade_nudge") {
          onComradeNudge(p);
        } else if (p.type === "ledger_updated") {
          onLedgerUpdated(p);
        } else if (p.type === "together_invited") {
          onTogetherInvited(p);
        } else if (p.type === "together_joined") {
          setTogetherStatus("Together");
        } else if (p.type === "together_command") {
          onTogetherCommand(p);
        } else if (p.type === "together_correction") {
          onTogetherCorrection(p);
        } else if (p.type === "together_ended") {
          endShare();
          onTogetherOver();
          showToast(p.by_peer ? "They left the session" : "Session ended", "info");
        } else if (p.type === "together_share") {
          onTogetherShare(p);
        }
      });
    } catch (e) {
      showToast(`Could not subscribe to live events: ${errText(e)}`, "warn");
    }
  }

  // ── Wiring ────────────────────────────────────────────────────────────────
  function init() {
    if (!hasTauri) $("#preview-banner").hidden = false;

    $("#vault-form").addEventListener("submit", handleUnlock);
    $("#toggle-reveal").addEventListener("click", () => {
      const i = $("#passphrase");
      i.type = i.type === "password" ? "text" : "password";
    });

    $("#identity-chip").addEventListener("click", async () => {
      if (!state.identity) return;
      try {
        await navigator.clipboard.writeText(state.identity.npub);
        showToast("npub copied to clipboard", "success");
      } catch {
        showToast("Clipboard unavailable", "error");
      }
    });

    for (const t of document.querySelectorAll(".tab"))
      t.addEventListener("click", () => switchTab(t.dataset.tab));

    $("#chitthi-input").addEventListener("input", updateCount);
    $("#broadcast-btn").addEventListener("click", handleBroadcast);

    // Focus (attention practice — all local)
    $("#focus-start").addEventListener("click", handleFocusStart);
    $("#focus-done").addEventListener("click", () => handleFocusFinish(true));
    $("#focus-stop").addEventListener("click", () => handleFocusFinish(false));
    $("#reader-save").addEventListener("click", handleReaderSave);
    $("#reader-next").addEventListener("click", () => handleReaderStep(1));
    $("#reader-prev").addEventListener("click", () => handleReaderStep(-1));
    $("#reader-clear").addEventListener("click", handleReaderClear);
    $("#dm-input").addEventListener("input", (e) => {
      reportDraftEdit();
      handleDmInput(e);
    });
    $("#dm-send").addEventListener("click", handleDmSend);

    // Watch/listen together. The player's own events are the only source of
    // "the person did something" — `timeupdate` is deliberately not among them
    // (it fires four times a second and says nothing anyone chose), and
    // `ratechange` is never signalled because a rate trim is a local
    // correction, not news.
    $("#together-pick").addEventListener("click", () => $("#together-file").click());
    $("#together-file").addEventListener("change", (e) => handleTogetherPick(e.target.files?.[0]));
    $("#together-invite").addEventListener("click", handleTogetherInvite);
    $("#together-join").addEventListener("click", handleTogetherJoin);
    $("#together-leave").addEventListener("click", handleTogetherLeave);
    for (const type of ["play", "pause", "seeked", "ended"]) {
      $("#together-player").addEventListener(type, () => onTogetherLocalEvent(type));
    }
    // Feed the runtime our playhead on a slow timer. Not a producer on the
    // event bus: the runtime only emits when the drift verdict is not `hold`.
    setInterval(reportTogetherPosition, 1000);
    $("#dm-input").addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleDmSend();
      }
    });

    // Media attachments (Vault + Couple sandbox)
    $("#dm-attach").addEventListener("click", () => $("#dm-file").click());
    $("#dm-file").addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      handleAttach(file, state.activeContact, { composer: $("#dm-input") });
      e.target.value = "";
    });
    $("#couple-attach").addEventListener("click", () => $("#couple-file").click());
    $("#couple-file").addEventListener("change", (e) => {
      const file = e.target.files && e.target.files[0];
      handleAttach(file, state.partnerNpub);
      e.target.value = "";
    });

    $("#travel-toggle").addEventListener("change", handleTravel);
    $("#partner-btn").addEventListener("click", openPartnerModal);
    $("#partner-cancel").addEventListener("click", closePartnerModal);
    $("#pair-submit").addEventListener("click", handlePair);
    $("#pair-continue").addEventListener("click", handlePairContinue);
    $("#pair-again").addEventListener("click", showPairForm);
    $("#couple-exit").addEventListener("click", exitCouple);
    $("#sync-ledger-btn").addEventListener("click", handleSyncLedger);
    $("#ledger-entry-form").addEventListener("submit", handleAddLedgerEntry);

    // Reply chip + message requests + call settings (Milestone 6)
    $("#dm-reply-cancel").addEventListener("click", clearReply);
    $("#call-settings-btn").addEventListener("click", openTurnModal);
    $("#turn-cancel").addEventListener("click", closeTurnModal);
    $("#turn-save").addEventListener("click", handleSaveTurn);
    $("#share-policy").addEventListener("change", handleSharePolicyChange);

    // Call overlays (ringing + in-call controls)
    $("#ring-accept").addEventListener("click", acceptIncoming);
    $("#ring-decline").addEventListener("click", declineIncoming);
    $("#call-mute").addEventListener("click", toggleMute);
    $("#call-camera").addEventListener("click", toggleCamera);
    $("#call-more").addEventListener("click", toggleCallDock);
    $("#call-screen-share").addEventListener("click", () => {
      closeCallDock();
      toggleScreenShare().catch(() => {});
    });
    $("#call-chat").addEventListener("click", () => {
      closeCallDock();
      openChatDuringCall();
    });
    $("#call-hangup").addEventListener("click", hangupByUser);
    // Anywhere outside the dock shuts it — including the call stage, whose own
    // click handler is unaffected because this only ever closes.
    document.addEventListener("click", (e) => {
      if ($("#call-dock").hidden) return;
      if (e.target.closest("#call-dock") || e.target.closest("#call-more")) return;
      closeCallDock();
    });
    // Clicking the minimised tile restores the call — but not when the click
    // was meant for one of the two controls the tile still shows.
    $("#call-active").addEventListener("click", (e) => {
      const c = state.call;
      if (!c || !c.minimized) return;
      if (e.target.closest(".call-btn")) return;
      // The click that ends a drag is not a request to restore.
      if (state.tileDragged) {
        state.tileDragged = false;
        return;
      }
      restoreCall();
    });
    installTileDragging();
    // Nothing displaying the call means nothing should be captured for it.
    document.addEventListener("visibilitychange", applyVideoVisibility);
    // The frame's own dimensions only exist once metadata has loaded, and they
    // change when the peer rotates or starts sharing a screen; the box changes
    // when the window does or the tile is minimised.
    $("#call-remote-video").addEventListener("loadedmetadata", applyRemoteFit);
    $("#call-remote-video").addEventListener("resize", applyRemoteFit);
    window.addEventListener("resize", applyRemoteFit);
    $("#call-remote-video").addEventListener("leavepictureinpicture", applyVideoVisibility);
    $("#call-remote-video").addEventListener("enterpictureinpicture", applyVideoVisibility);

    $("#modal-partner").addEventListener("click", (e) => {
      if (e.target === $("#modal-partner")) closePartnerModal();
    });
    $("#modal-turn").addEventListener("click", (e) => {
      if (e.target === $("#modal-turn")) closeTurnModal();
    });
    document.addEventListener("keydown", (e) => {
      if (e.key !== "Escape") return;
      // Innermost thing first: an open call dock is above both modals.
      if (!$("#call-dock").hidden) {
        closeCallDock();
        $("#call-more").focus();
      } else if (!$("#modal-partner").hidden) closePartnerModal();
      else if (!$("#modal-turn").hidden) closeTurnModal();
    });

    wireEvents();
    renderContacts();
    renderConversation();
    setScreen("vault");
    $("#passphrase").focus();
  }

  // ── Dev mock backend (used only when running outside the Tauri shell) ──────
  function mockBackend() {
    const listeners = {};
    const wsOf = (key) => ({
      key,
      label: key,
      active: true,
      relay_connected: key !== "OffGridTravel",
      mesh_active: key === "OffGridTravel",
      couple_sandbox: key.startsWith("CoupleSandbox"),
    });
    let ws = wsOf("Base");
    const delay = (ms) => new Promise((r) => setTimeout(r, ms));
    const re = /\/pay\s+(\d+(?:\.\d{1,2})?)\s+to\s+([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)/gi;
    const ICE_DEMO = [
      { urls: ["stun:stun.l.google.com:19302"], username: null, credential: null },
    ];
    // A demo message request so the Requests UI is visible in browser preview;
    // accept/block splice it so the interaction feels real without a backend.
    let mockRequests = [
      {
        peer: "npub1stranger00000000000000000000000000000000000000000",
        last_message: "Hey, saw your Chitthi — mind if we chat? (mock)",
        // mockBackend() runs at module-init time (`const backend = hasTauri ?
        // … : mockBackend()` above), before the `nowSecs` const further down
        // this same scope is initialized — calling it here would be a
        // temporal-dead-zone ReferenceError, so compute the timestamp inline.
        last_at: Math.floor(Date.now() / 1000) - 300,
      },
    ];
    // Local Sakha/Sakhi pairing + ledger state, so the pairing modal and the
    // Couple Sandbox behave believably in browser preview.
    let mockSakha = { paired: false, partnerNpub: null, role: null, ledger: "" };
    // Focus practice state for browser preview (see the `focus_*` cases below).
    const mockFocus = { active: null, sessions: [], reading: null };

    const invoke = async (cmd, args = {}) => {
      await delay(120);
      switch (cmd) {
        case "unlock_comrade_vault":
          return { npub: "npub1mockdev0identity00000000000000000000000000000000", has_secret: true };
        case "current_identity":
          return { npub: "npub1mockdev0identity00000000000000000000000000000000", has_secret: true };
        case "current_workspace":
          return ws;
        case "toggle_app_workspace":
        case "switch_workspace":
          ws = wsOf(args.target || args.key || "Base");
          return ws;
        case "back":
          ws = wsOf("Base");
          return ws;
        case "fetch_sabha_timeline":
          return [
            { id: "demo1", author: "npub1alice000000000000000000000000000000000000000000", content: "Namaste from the Sabha! (mock)", created_at: nowSecs() - 600, reply_to: null },
            { id: "demo2", author: "npub1bob0000000000000000000000000000000000000000000000", content: "Off-grid travel mode is wild.", created_at: nowSecs() - 90, reply_to: "demo1" },
          ];
        case "broadcast_chitthi":
          return "mock_" + Date.now();
        case "send_dm":
          return {
            id: "mockdm_" + Date.now(),
            peer: args.target,
            content: args.content,
            created_at: nowSecs(),
            outgoing: true,
          };
        case "conversations":
        case "messages_with":
        case "media_with":
        case "list_contacts":
        case "comrades":
          return [];
        case "peer_presence":
          return null;
        case "set_comrade":
          return { npub: args.npub, alias: "", name: null, comrade: !!args.comrade };
        case "announce_presence":
          return 0;
        case "current_profile":
          return { npub: "npub1mockdev0identity00000000000000000000000000000000", username: "mockuser" };
        case "extract_payments": {
          const out = [];
          let m;
          re.lastIndex = 0;
          while ((m = re.exec(args.text || "")) !== null)
            out.push({ amount_inr: parseFloat(m[1]), vpa: m[2], uri: `upi://pay?pa=${m[2]}&am=${m[1]}` });
          return out;
        }
        case "pair_sakha":
          mockSakha.paired = true;
          mockSakha.partnerNpub = args.partnerPubkey;
          mockSakha.role = args.role === "sakhi" ? "sakhi" : "sakha";
          return { paired: true, partner_npub: mockSakha.partnerNpub, role: mockSakha.role };
        case "sakha_status":
          return mockSakha.paired
            ? { paired: true, partner_npub: mockSakha.partnerNpub, role: mockSakha.role }
            : { paired: false, partner_npub: null, role: null };
        case "sakha_add_entry": {
          if (!mockSakha.paired) throw "not paired with a partner yet";
          const line = `[mock] ${args.description} | ₹${Number(args.amountInr).toFixed(2)} | paid by ${args.paidBy}`;
          mockSakha.ledger = mockSakha.ledger ? `${mockSakha.ledger}\n${line}` : line;
          return mockSakha.ledger;
        }
        case "sakha_read_ledger":
          return mockSakha.ledger;
        case "sync_ledger":
          if (!mockSakha.paired) throw "no shared secret available — pairing handshake incomplete";
          return "mockledgersync_" + Date.now();
        case "send_media_bytes":
          return {
            event_id: "mockmedia_" + Date.now(),
            // `example.invalid` on purpose: this is the dev mock, so the URL is
            // never fetched, and naming a *real* host here is how a dead one
            // ends up looking load-bearing. (It did: the previous value was the
            // media host that broke every attachment.)
            url: "https://blob.example.invalid/mock",
            mime_type: args.mimeType,
            caption: args.caption || "",
            sender: "npub1mockdev0identity00000000000000000000000000000000",
            created_at: nowSecs(),
            size: 0,
          };
        case "download_and_decrypt_media":
          // 1×1 transparent PNG so the preview can render an <img>.
          return {
            mime_type: "image/png",
            base64:
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
          };
        // ── Milestone 6: replies / receipts / requests / calls ──────────────
        case "send_dm_reply":
          return {
            id: "mockdm_" + Date.now(),
            peer: args.target,
            content: args.content,
            created_at: nowSecs(),
            outgoing: true,
            status: "sent",
            reply_to: args.replyTo || null,
          };
        case "mark_conversation_read":
          return null;
        case "message_requests":
          return mockRequests.slice();
        case "accept_request":
          mockRequests = mockRequests.filter((r) => r.peer !== args.peer);
          return null;
        case "block_conversation":
          mockRequests = mockRequests.filter((r) => r.peer !== args.peer);
          return null;
        case "call_ice_servers":
          return ICE_DEMO.slice();
        case "call_ice_servers_for":
          // Browser preview has no TURN relay configured, so both the
          // "stun_only" and "stun_and_turn" strategies resolve to the same
          // STUN-only demo list — matching the real runtime when no relay is
          // set (the widened list equals the STUN-only one). Keeps WP3's caller
          // TURN-fallback path from throwing "unknown command" in preview.
          return ICE_DEMO.slice();
        case "set_turn_server":
          return null;
        case "place_call":
          return {
            call_id: "mockcall_" + Date.now(),
            peer: args.peer,
            media: args.media,
            ice_servers: ICE_DEMO.slice(),
          };
        case "send_call_signal":
        case "hangup_call":
          return null;
        case "log_call":
          return {
            id: "mockrec_" + Date.now(),
            peer: args.peer,
            media: args.media,
            incoming: !!args.incoming,
            outcome: args.outcome,
            started_at: args.startedAt || nowSecs(),
            duration_secs: args.durationSecs || 0,
          };
        case "call_history":
          return [];

        // ── Focus (attention practice) ──────────────────────────────────────
        // Enough state to exercise the tab in browser preview: the mock keeps
        // one session and one read, so start → countdown → finish and
        // paste → page → close both behave. The ladder is not modelled — the
        // engine owns it, and a mock that guessed at it would be a second
        // opinion about the one thing this UI must not have an opinion on.
        case "focus_presets":
          return [25, 45, 90];
        case "suggested_focus_minutes":
          return 25;
        case "focus_prompt":
          return "Name one thing. The rest can wait. (mock)";
        case "focus_reflection":
          return args.outcome === "completed"
            ? "You gave it the whole block. What came of it? (mock)"
            : "Noted, and that's all. (mock)";
        case "active_focus_session":
          if (!mockFocus.active) return null;
          mockFocus.active.remaining_secs = Math.max(
            0,
            mockFocus.active.planned_minutes * 60 - (nowSecs() - mockFocus.active.started_at),
          );
          return mockFocus.active;
        case "start_focus_session":
          mockFocus.active = {
            id: "mockfocus_" + Date.now(),
            intent: args.intent || "",
            planned_minutes: args.plannedMinutes,
            started_at: nowSecs(),
            ended_at: null,
            outcome: null,
            remaining_secs: args.plannedMinutes * 60,
          };
          return mockFocus.active;
        case "finish_focus_session": {
          const done = mockFocus.active;
          if (!done) return null;
          done.outcome = args.completed ? "completed" : "abandoned";
          done.ended_at = nowSecs();
          done.remaining_secs = 0;
          mockFocus.sessions.unshift(done);
          mockFocus.active = null;
          return done;
        }
        case "focus_sessions":
          return mockFocus.sessions.slice();
        case "save_reading":
          mockFocus.reading = {
            title: args.title || "",
            // A stand-in for `attention::chunk_reading`, which splits on
            // paragraph boundaries. Same shape, not the same algorithm.
            chunks: String(args.text)
              .split(/\n{2,}/)
              .filter((c) => c.trim()),
            position: 0,
          };
          return mockFocus.reading;
        case "reading":
          return mockFocus.reading;
        case "set_reading_position":
          if (!mockFocus.reading) return null;
          mockFocus.reading.position = Math.min(
            Math.max(0, args.position),
            mockFocus.reading.chunks.length - 1,
          );
          return mockFocus.reading;
        case "clear_reading": {
          const had = !!mockFocus.reading;
          mockFocus.reading = null;
          return had;
        }

        default:
          throw `mock backend: unknown command '${cmd}'`;
      }
    };

    const listen = async (event, cb) => {
      (listeners[event] = listeners[event] || []).push(cb);
      return () => {};
    };
    // Manual event injection for design/QA: window.__comradeEmit({type:'incoming_chitthi', ...})
    window.__comradeEmit = (payload) =>
      (listeners[EVENT_CHANNEL] || []).forEach((cb) => cb({ payload }));

    return { invoke, listen };
  }

  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", init);
  else init();
})();
