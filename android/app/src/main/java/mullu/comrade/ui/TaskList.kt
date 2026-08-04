package mullu.comrade.ui

import mullu.comrade.ComradeCore
import uniffi.comrade_core.TaskState

/**
 * Decisions behind the task list — the Android half of what `/task` produces.
 *
 * Compose-free and Android-free so the whole thing is unit-testable on a plain
 * JVM, like [ComposerMode], [ChatThread] and [ChatCommands]. The desktop twin is
 * `desktop/ui/task_list.mjs`, pinned by mirrored vectors.
 *
 * ## The rule that matters
 *
 * **The buttons a row offers mirror `karya::may_transition` exactly.** Core
 * refuses a transition the caller has no standing to make — an assigner cannot
 * mark someone else's task done, an assignee cannot withdraw it, nothing reopens
 * — so a UI that offered those anyway would show a control that comes back with
 * "that is not yours to change". Offering only what will be accepted is the
 * whole job of [actionsFor].
 *
 * Nothing here re-derives *who* you are: `TaskInfo.assignedByMe` and `mineToDo`
 * are computed once in `comrade_ui` so three frontends do not each compare npubs.
 */

/** What a row can offer. */
enum class TaskAction {
    /** Mark it finished. For whoever was asked. */
    Done,

    /** Say no — a first-class outcome, not a failure. For whoever was asked. */
    Decline,

    /** Take it back. For whoever asked. */
    Withdraw,
}

/** A task list split for rendering. */
data class GroupedTasks(
    val open: List<ComradeCore.TaskInfo>,
    val resolved: List<ComradeCore.TaskInfo>,
)

object TaskList {

    /**
     * Which buttons a row may offer, mirroring `karya::may_transition`:
     *
     * | | assigner | assignee |
     * |---|---|---|
     * | withdraw | yes | no |
     * | done | no* | yes |
     * | decline | no | yes |
     *
     * *A note to self is the exception the table bends around: one person holds
     * both roles, so they get all three. `Task::apply` special-cases it and so
     * does this — refusing to let somebody delete their own private note would
     * be absurd.
     *
     * A task that has already landed offers nothing; nothing reopens.
     */
    fun actionsFor(task: ComradeCore.TaskInfo): List<TaskAction> {
        if (task.state != TaskState.OPEN) return emptyList()
        val noteToSelf = task.assignee == null && task.assignedByMe
        if (noteToSelf) {
            return listOf(TaskAction.Done, TaskAction.Decline, TaskAction.Withdraw)
        }
        return buildList {
            if (task.mineToDo) {
                add(TaskAction.Done)
                add(TaskAction.Decline)
            }
            if (task.assignedByMe) add(TaskAction.Withdraw)
        }
    }

    /**
     * Open first, resolved after, each keeping the order `tasks()` returned.
     *
     * A partition rather than a sort: the store already answers newest-first, and
     * re-sorting here would be a second ordering rule to keep in step with it.
     */
    fun group(tasks: List<ComradeCore.TaskInfo>): GroupedTasks = GroupedTasks(
        open = tasks.filter { it.state == TaskState.OPEN },
        resolved = tasks.filter { it.state != TaskState.OPEN },
    )

    /**
     * The line under a task saying whose it is.
     *
     * Never a raw npub if the caller can help it: [nameFor] is the caller's own
     * display-name resolver, so this list names people exactly the way every
     * other screen does — `TaskListScreen` passes `peerTitle`, which is the
     * alias you chose, then the handle they published, then a short key. This
     * function deliberately does not pick that order itself; a task list naming
     * people differently from the chat list would read as a second address book.
     */
    fun subtitleFor(task: ComradeCore.TaskInfo, nameFor: (String) -> String): String = when {
        task.assignee == null && task.assignedByMe -> "yours"
        task.assignedByMe -> "you asked ${nameFor(task.assignee ?: "")}"
        else -> "${nameFor(task.assigner)} asked you"
    }

    /** How a resolved task reads. An open one says nothing — the row is the point. */
    fun stateLabel(state: TaskState): String = when (state) {
        TaskState.OPEN -> ""
        TaskState.DONE -> "Done"
        TaskState.DECLINED -> "Declined"
        TaskState.WITHDRAWN -> "Withdrawn"
    }

    /**
     * What an empty list says.
     *
     * Names the command, because this screen is reachable before you know `/task`
     * exists and is therefore the one place that can teach it. "No tasks" teaches
     * nothing.
     */
    const val EMPTY_COPY: String =
        "Nothing yet. Type \"/task water the plants\" in any chat — " +
            "or \"/task ship it @ana\" to ask a comrade."
}
