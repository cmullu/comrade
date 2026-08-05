package mullu.comrade.ui

import mullu.comrade.ComradeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.comrade_core.TaskState

/**
 * Mirrors `desktop/ui/task_list.test.mjs` case for case, so the two frontends
 * cannot disagree about which buttons a task offers — and neither can disagree
 * with `karya::may_transition`, which is the table both are written against.
 */
class TaskListTest {

    private fun task(
        state: TaskState = TaskState.OPEN,
        assignee: String? = "npub1bina",
        assignedByMe: Boolean = true,
        mineToDo: Boolean = false,
        id: String = "t1",
        createdAt: Long = 100,
    ) = ComradeCore.TaskInfo(
        id = id,
        text = "get some work done",
        assigner = "npub1ana",
        assignee = assignee,
        createdAt = createdAt,
        updatedAt = createdAt,
        state = state,
        assignedByMe = assignedByMe,
        mineToDo = mineToDo,
    )

    private fun noteToSelf(state: TaskState = TaskState.OPEN) =
        task(state = state, assignee = null, assignedByMe = true, mineToDo = true)

    // ── The buttons must mirror karya::may_transition ─────────────────────────

    @Test
    fun whoeverWasAskedMayFinishOrDeclineAndMayNotWithdraw() {
        assertEquals(
            listOf(TaskAction.Done, TaskAction.Decline),
            TaskList.actionsFor(task(assignedByMe = false, mineToDo = true)),
        )
    }

    @Test
    fun whoeverAskedMayWithdrawAndMayNotFinishItForThem() {
        // The rule that stops an assigner ticking off someone else's work.
        assertEquals(
            listOf(TaskAction.Withdraw),
            TaskList.actionsFor(task(assignedByMe = true, mineToDo = false)),
        )
    }

    @Test
    fun aNoteToSelfHoldsBothPowersWithdrawIncluded() {
        // The exception the whole table bends around — deleting your own private
        // note is not somebody else's business. Task::apply special-cases it too.
        assertEquals(
            listOf(TaskAction.Done, TaskAction.Decline, TaskAction.Withdraw),
            TaskList.actionsFor(noteToSelf()),
        )
    }

    @Test
    fun nothingIsOfferedOnATaskThatHasAlreadyLanded() {
        // Nothing reopens, so a terminal row is a record and not a control.
        for (state in listOf(TaskState.DONE, TaskState.DECLINED, TaskState.WITHDRAWN)) {
            assertEquals("$state", emptyList<TaskAction>(), TaskList.actionsFor(task(state = state)))
            assertEquals(
                "self/$state",
                emptyList<TaskAction>(),
                TaskList.actionsFor(noteToSelf(state)),
            )
        }
    }

    @Test
    fun aRowNobodyIsPartyToOffersNothing() {
        // Should not arrive — tasks() only returns rows this device is party to —
        // but offering a button core would refuse is the failure to avoid.
        assertEquals(
            emptyList<TaskAction>(),
            TaskList.actionsFor(task(assignedByMe = false, mineToDo = false)),
        )
    }

    @Test
    fun everyStateThisBuildKnowsIsHandledExplicitly() {
        // A state treated as open by accident would show buttons on a finished
        // task.
        for (state in TaskState.entries) {
            val actions = TaskList.actionsFor(
                task(state = state, assignedByMe = false, mineToDo = true),
            )
            if (state == TaskState.OPEN) {
                assertTrue("$state", actions.isNotEmpty())
            } else {
                assertEquals("$state", emptyList<TaskAction>(), actions)
            }
        }
    }

    // ── Grouping ─────────────────────────────────────────────────────────────

    @Test
    fun openFirstResolvedAfterEachKeepingTheStoresOrder() {
        val list = listOf(
            task(id = "a", state = TaskState.OPEN, createdAt = 30),
            task(id = "b", state = TaskState.DONE, createdAt = 20),
            task(id = "c", state = TaskState.OPEN, createdAt = 10),
        )
        val grouped = TaskList.group(list)
        assertEquals(listOf("a", "c"), grouped.open.map { it.id })
        assertEquals(listOf("b"), grouped.resolved.map { it.id })
    }

    @Test
    fun anEmptyListGroupsWithoutThrowing() {
        val grouped = TaskList.group(emptyList())
        assertTrue(grouped.open.isEmpty() && grouped.resolved.isEmpty())
    }

    // ── Subtitles ────────────────────────────────────────────────────────────

    @Test
    fun aSubtitleNamesAPersonNeverAKey() {
        val nameFor = { npub: String ->
            mapOf("npub1ana" to "Ana", "npub1bina" to "Bina")[npub] ?: npub
        }
        assertEquals("you asked Bina", TaskList.subtitleFor(task(), nameFor))
        assertEquals(
            "Ana asked you",
            TaskList.subtitleFor(task(assignedByMe = false, mineToDo = true), nameFor),
        )
        assertEquals("yours", TaskList.subtitleFor(noteToSelf(), nameFor))
    }

    // ── Copy ─────────────────────────────────────────────────────────────────

    @Test
    fun resolvedStatesReadAsWordsAndOpenSaysNothing() {
        assertEquals("Done", TaskList.stateLabel(TaskState.DONE))
        assertEquals("Declined", TaskList.stateLabel(TaskState.DECLINED))
        assertEquals("Withdrawn", TaskList.stateLabel(TaskState.WITHDRAWN))
        assertEquals("", TaskList.stateLabel(TaskState.OPEN))
    }

    @Test
    fun theEmptyStateTeachesTheCommandThatFillsIt() {
        // This screen is reachable before you know /task exists, so "no tasks"
        // would waste the one place that can teach it.
        assertTrue(TaskList.EMPTY_COPY.contains("/task"))
        assertTrue(TaskList.EMPTY_COPY.contains("@ana"))
    }
}
