/**
 * Decisions behind the task list — the desktop half of what `/task` produces.
 *
 * ## Why this is its own module
 *
 * Same reason as `chat_commands.mjs` and `focus_view.mjs`: `main.js` is DOM glue
 * and cannot be unit tested, so what *decides* something lives here under
 * `node --test`. The Android twin is `android/…/ui/TaskList.kt` and the two are
 * pinned by mirrored vectors.
 *
 * ## The rule that matters
 *
 * **The buttons a row offers must mirror `karya::may_transition` exactly.** Core
 * refuses a transition the sender has no standing to make — an assigner cannot
 * mark someone else's task done, an assignee cannot withdraw it, nothing reopens
 * — and a UI that offered those buttons anyway would be showing a control that
 * returns "that is not yours to change". Offering only what will be accepted is
 * the whole job of {@link actionsFor}, and its tests are written against the same
 * table `karya.rs`'s module header states.
 *
 * Nothing here re-derives *who* you are: `TaskDto`'s `assigned_by_me` and
 * `mine_to_do` are computed once in `comrade_ui` so three frontends do not each
 * compare npubs.
 *
 * ## Field names are snake_case here and camelCase on Android, deliberately
 *
 * Tauri serialises `TaskDto` with serde and no `rename_all`, so this side reads
 * `assigned_by_me` — the same convention as `upi_intents` and `reply_to`
 * everywhere else in `main.js`. uniffi generates Kotlin properties, so
 * `TaskList.kt` reads `assignedByMe`. The two mirror each other in *behaviour*,
 * not in spelling, and getting it wrong here fails **silently**: every row would
 * simply show no buttons, because both flags would read `undefined`.
 */

/** Mark it finished. Offered to whoever was asked. */
export const DONE = "done";
/** Say no. Offered to whoever was asked, and a first-class outcome. */
export const DECLINE = "decline";
/** Take it back. Offered to whoever asked. */
export const WITHDRAW = "withdraw";

/**
 * The state strings `karya::TaskState` serialises to. Exported so a test can
 * assert this module handles every one of them rather than silently treating an
 * unknown state as open.
 */
export const STATES = ["open", "done", "declined", "withdrawn"];

/**
 * Which buttons a row may offer, mirroring `karya::may_transition`.
 *
 * | | assigner | assignee |
 * |---|---|---|
 * | withdraw | yes | no |
 * | done | no* | yes |
 * | decline | no | yes |
 *
 * *A note to self is the exception the whole table bends around: one person
 * holds both roles, so they get all three. `Task::apply` special-cases it, and
 * so does this.
 *
 * A terminal task offers nothing — nothing reopens, because "done, no wait, not
 * done" is an argument two devices would have to arbitrate.
 */
export function actionsFor(task) {
  if (!task || task.state !== "open") return [];
  const noteToSelf = task.assignee == null && task.assigned_by_me;
  if (noteToSelf) return [DONE, DECLINE, WITHDRAW];
  const out = [];
  if (task.mine_to_do) out.push(DONE, DECLINE);
  if (task.assigned_by_me) out.push(WITHDRAW);
  return out;
}

/**
 * The state a button asks core to move a task to.
 *
 * Separate from the button's own name because the two are not the same word —
 * you press "Decline" and the task becomes `declined` — and because the *casing*
 * here is a wire contract, not a style choice: `karya::TaskState` is
 * `#[serde(rename_all = "snake_case")]`, so serde rejects `"Done"` outright.
 * Sending the capitalised form made every button on this panel return an error,
 * which is why this mapping is a tested function and not a literal in an
 * onclick.
 */
export function wireState(action) {
  return { [DONE]: "done", [DECLINE]: "declined", [WITHDRAW]: "withdrawn" }[action] || null;
}

/**
 * Open tasks first (newest first), then everything resolved.
 *
 * `tasks()` already returns newest-first, so this is a partition rather than a
 * sort — re-sorting here would be a second ordering rule to keep in step with
 * the store's.
 */
export function groupTasks(tasks) {
  const list = Array.isArray(tasks) ? tasks : [];
  return {
    open: list.filter((t) => t.state === "open"),
    resolved: list.filter((t) => t.state !== "open"),
  };
}

/**
 * The line under a task saying whose it is.
 *
 * Never a raw npub if the caller can help it: `nameFor` is the caller's own
 * display-name resolver, whatever precedence that frontend already uses for
 * every other surface — `displayName` here (published handle, then a short key),
 * `peerTitle` on Android (which also consults the alias you chose). This module
 * deliberately does not pick: a task list that named people differently from the
 * chat list beside it would read as two different address books.
 *
 * It degrades to the key rather than throwing. A list showing `npub1…` is poor;
 * a list that crashes on one unknown contact is worse.
 */
export function subtitleFor(task, nameFor) {
  if (!task) return "";
  const name = (npub) => (typeof nameFor === "function" ? nameFor(npub) : npub);
  if (task.assignee == null && task.assigned_by_me) return "yours";
  if (task.assigned_by_me) return `you asked ${name(task.assignee)}`;
  return `${name(task.assigner)} asked you`;
}

/** How a resolved task reads. Open tasks say nothing — the row is the point. */
export function stateLabel(state) {
  return { done: "Done", declined: "Declined", withdrawn: "Withdrawn" }[state] || "";
}

/**
 * What an empty list should say.
 *
 * Names the command, because a screen reachable before you know the command
 * exists is the one place to teach it — and because "no tasks" teaches nothing.
 */
export function emptyCopy() {
  return 'Nothing yet. Type "/task water the plants" in any chat — or "/task ship it @ana" to ask a comrade.';
}
