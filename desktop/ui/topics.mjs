/**
 * Decisions behind the thread sheet and the topic list — the desktop half of
 * what `comrade_core::topic` files.
 *
 * Its twin is `android/app/src/main/java/mullu/comrade/topic/TopicDecisions.kt`
 * and the two are pinned by mirrored test vectors. Where they diverge on
 * purpose, each says so.
 *
 * ## What is *not* here
 *
 * **The slug rules, the reply graph, and which topic a thread is in.** Those
 * are `comrade_core::topic`'s answers, reached over the bridge (`topics`,
 * `threads`, `thread`, `assign_thread`). Nothing in this file re-derives a slug
 * or walks a reply chain — a second implementation of either is precisely the
 * drift `/pay` already demonstrates across four frontends.
 *
 * What *is* here is presentation: what to show, in what order, and which words
 * to use when core deliberately hands up none.
 */

/**
 * Threads with no replies are hidden by default.
 *
 * Every message is technically a thread of one, so listing them would make the
 * sheet a second copy of the conversation — which is not a thread view, it is
 * the same screen with worse ordering. Slack draws the same line.
 *
 * The exception, and why this is a flag rather than a constant: a thread has to
 * be *startable* from the sheet, and a thread that does not exist yet has no
 * replies. The desktop panel shows singletons under a topic (you filed it, so
 * you meant it) and hides them in the "all threads" view.
 */
export const HIDE_SINGLETONS = true;

/**
 * Topics in the order a list should show them: liveliest first, archived last.
 *
 * Not creation order, which is what the store returns. A conversation
 * accumulates topics and the one that just moved is the one being worked on;
 * sorting by name would put `#a-thing-from-march` above it forever.
 *
 * Archived topics sink rather than disappear even when `includeClosed` is set,
 * so the archive reads as an archive rather than as an empty list.
 */
export function visibleTopics(topics, { includeClosed = false } = {}) {
  return (topics || [])
    .filter((t) => t && (includeClosed || !t.closed))
    .slice()
    .sort((a, b) => {
      if (!!a.closed !== !!b.closed) return a.closed ? 1 : -1;
      return (b.last_activity_at || 0) - (a.last_activity_at || 0);
    });
}

/**
 * The threads a panel should list, most recently active first.
 *
 * `topicSlug` of null is every thread rather than the unfiled ones — the
 * default view is the whole conversation, and "unfiled" is asked for by name
 * (`UNFILED`) because it is a real destination in the picker, not the absence
 * of a filter.
 */
export const UNFILED = Symbol("unfiled");

export function threadsFor(threads, { topicSlug = null, includeSingletons = false } = {}) {
  return (threads || [])
    .filter((t) => t && typeof t.root_id === "string")
    .filter((t) => {
      if (topicSlug === UNFILED) return !t.topic_slug;
      if (topicSlug) return t.topic_slug === topicSlug;
      return true;
    })
    .filter((t) => includeSingletons || (t.reply_count || 0) > 0)
    .slice()
    .sort((a, b) => (b.last_at || 0) - (a.last_at || 0) || a.root_id.localeCompare(b.root_id));
}

/**
 * The line that names a thread in a list.
 *
 * Core hands up an empty `preview` for an attachment and for a root that is not
 * in this device's history, plus two booleans saying which — deliberately, so
 * the *word* is the frontend's and can be translated. This is where the desktop
 * picks its words; `labels` exists so a test can prove the branch rather than
 * the wording.
 *
 * A root that is missing is a normal state, not an error: a thread can be filed
 * before its root arrives, and a reply can quote something older than the
 * loaded window. Saying so is better than an empty row that reads as a bug.
 */
export function threadPreview(summary, labels = {}) {
  const {
    attachment = "📎 Attachment",
    missing = "The first message isn't on this device",
    empty = "(no text)",
  } = labels;
  if (!summary) return empty;
  if (summary.root_missing) return missing;
  const text = (summary.preview || "").trim();
  if (text) return summary.root_is_media ? `📎 ${text}` : text;
  return summary.root_is_media ? attachment : empty;
}

/**
 * How many of a topic's threads have something unread in them.
 *
 * Reads the threads rather than the topic row because unread is per thread and
 * the topic's own counts are about size, not attention — a topic with forty
 * read messages and one new reply should show a 1.
 */
export function unreadThreadCount(threads, topicSlug) {
  return (threads || []).filter(
    (t) => t && t.unread && (topicSlug ? t.topic_slug === topicSlug : true),
  ).length;
}

/**
 * What `/assign #topic` should do, given what the composer has selected.
 *
 * Three answers, and the middle one is the whole reason this is a function:
 *
 * - No topic named → open the picker. A bare `/assign` is the discoverable way
 *   in, not a refusal.
 * - A topic named but nothing selected → say what is missing and keep the text.
 *   The grammar cannot know which thread was meant and guessing would file the
 *   wrong conversation, which is a destructive-looking action taken on the
 *   user's behalf.
 * - Both → file it.
 *
 * `replyTarget` is the event id of whatever the composer is replying to (or the
 * message a menu was opened on). Any message in the thread is fine — core walks
 * up to the root.
 */
export const ASSIGN_OPEN_PICKER = "assign_open_picker";
export const ASSIGN_NEEDS_TARGET = "assign_needs_target";
export const ASSIGN_FILE = "assign_file";

export function planAssign(topics, replyTarget) {
  const slug = (topics || []).map((t) => t && t.slug).find(Boolean);
  if (!slug) return { action: ASSIGN_OPEN_PICKER };
  if (!replyTarget) {
    return {
      action: ASSIGN_NEEDS_TARGET,
      message: `Reply to a message first — then /assign #${slug} files that thread.`,
    };
  }
  return { action: ASSIGN_FILE, slug, messageId: replyTarget };
}
