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
  const MAX_MEDIA_BYTES = 10 * 1024 * 1024; // 10 MB hard limit (Milestone 5)

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
    // Bounded memory of recently-ended call ids (see call_decisions.mjs
    // rememberEndedCall) — mirrors Android's CallManager.endedCallIds, so a
    // redelivered Offer for a call we already tore down doesn't ring again.
    endedCallIds: [],
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

  function renderMediaEl(mime, url) {
    if (mime.startsWith("image/")) return el("img", { class: "media-img", src: url, alt: "media" });
    if (mime.startsWith("audio/")) return el("audio", { controls: "", src: url });
    if (mime.startsWith("video/")) return el("video", { class: "media-img", controls: "", src: url });
    return el("a", { href: url, download: "comrade-media", text: "Download file" });
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
                    title: presenceOf(k).online ? "Online now" : "Not online",
                    "aria-label": presenceOf(k).online ? "Online now" : "Not online",
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
    state.activeContact = key;
    clearReply();
    $("#dm-input").disabled = false;
    $("#dm-attach").disabled = false;
    $("#dm-send").disabled = false;
    renderContacts();
    renderConversation();
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
        text: !presence.comrade
          ? ""
          : presence.online
            ? "online"
            : presence.peerMarkedUs
              ? "not online"
              : "waiting for them to choose you back",
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

  /** A quoted preview of the replied-to message, looked up in the open thread. */
  function quotePreview(replyToId) {
    const msgs = state.dms.get(state.activeContact) || [];
    const q = msgs.find((x) => x.id && x.id === replyToId);
    const text = q
      ? q.content || (q.media ? `📎 ${q.media.caption || "media"}` : "message")
      : "Original message";
    return el(
      "div",
      { class: "bubble-quote" },
      el("span", { class: "bubble-quote-text", text: text }),
    );
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
    const content = m.content || (m.media ? `📎 ${m.media.caption || "media"}` : "message");
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
    $("#turn-url").focus();
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

  /** Shrink the in-call overlay into the corner tile (see `.is-minimized`). */
  function minimizeCall() {
    const c = state.call;
    if (!c) return;
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
    }
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
    renderScreenShare();
    attachLocalMedia();
    attachRemoteMedia();
    $("#call-active").hidden = false;
  }

  function hideCallOverlay() {
    $("#call-active").hidden = true;
    restoreCall(); // a tile must never outlive its call
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
    $("#call-avatar").hidden = c.media === "video";
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
  function mediaBubble(m) {
    const wrap = el("div", { class: "bubble " + (m.outgoing ? "out" : "in") });
    if (m.media.caption) wrap.append(el("div", { class: "media-caption", text: m.media.caption }));

    if (m.media.objectUrl) {
      wrap.append(renderMediaEl(m.media.mime, m.media.objectUrl));
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
            m.media.objectUrl = URL.createObjectURL(base64ToBlob(out.base64, mime));
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
    return wrap;
  }

  function onIncomingMedia(p) {
    const key = p.sender || "unknown";
    const list = state.dms.get(key) || [];
    list.push({
      created_at: p.created_at,
      outgoing: false,
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

  // Encrypt + upload a selected file to `targetPubkey`, then render it locally.
  async function handleAttach(file, targetPubkey) {
    if (!file) return;
    if (!targetPubkey) {
      showToast("No recipient selected", "warn");
      return;
    }
    if (file.size > MAX_MEDIA_BYTES) {
      showToast(
        `"${file.name}" is ${(file.size / 1048576).toFixed(1)} MB — over the 10 MB limit`,
        "warn",
      );
      return;
    }
    const mime = file.type || "application/octet-stream";
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
        caption: file.name,
        base64,
      });
      // Optimistic local render straight from the picked file — no round-trip.
      const objectUrl = URL.createObjectURL(file);
      const list = state.dms.get(targetPubkey) || [];
      list.push({
        created_at: dto.created_at || nowSecs(),
        outgoing: true,
        media: { eventId: dto.event_id, mime, caption: file.name, objectUrl },
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
    for (const m of msgs) if (m.media) box.append(mediaBubble(m));
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
        } else if (p.type === "ledger_updated") {
          onLedgerUpdated(p);
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
    $("#dm-input").addEventListener("input", handleDmInput);
    $("#dm-send").addEventListener("click", handleDmSend);
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
      handleAttach(file, state.activeContact);
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

    // Call overlays (ringing + in-call controls)
    $("#ring-accept").addEventListener("click", acceptIncoming);
    $("#ring-decline").addEventListener("click", declineIncoming);
    $("#call-mute").addEventListener("click", toggleMute);
    $("#call-camera").addEventListener("click", toggleCamera);
    $("#call-screen-share").addEventListener("click", () => {
      toggleScreenShare().catch(() => {});
    });
    $("#call-chat").addEventListener("click", openChatDuringCall);
    $("#call-hangup").addEventListener("click", hangupByUser);
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
      if (!$("#modal-partner").hidden) closePartnerModal();
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
            url: "https://cdn.hackers.town/mock",
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
