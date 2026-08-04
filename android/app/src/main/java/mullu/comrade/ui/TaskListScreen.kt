package mullu.comrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import uniffi.comrade_core.TaskState

/**
 * Everything `/task` has produced — yours and the ones a comrade asked of you.
 *
 * The decisions are [TaskList]'s and are unit-tested there; this file is the
 * rendering. In particular **which buttons a row shows is not decided here**:
 * [TaskList.actionsFor] mirrors `karya::may_transition`, so a button that core
 * would refuse is never drawn.
 *
 * Until this screen existed, `/task water the plants` was stored and rendered by
 * nothing — the composer said "Added to your list." about a list nobody could
 * open. That was the largest gap in `docs/CHAT_ACTIONS.md` §7.
 */
@Composable
fun TaskListScreen(modifier: Modifier = Modifier) {
    var tasks by remember { mutableStateOf<List<ComradeCore.TaskInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Display names come from the saved contacts, so a row says "Ana asked you"
    // rather than an npub. Loaded once with the list; a task's counterparty is
    // someone you have a conversation with by construction.
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun reload() {
        scope.launch {
            val loadedTasks = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.tasks() }
            }
            // `peerTitle` is already the trust order this needs — the alias you
            // chose, then the handle they published, then a short key — so a row
            // never has to re-derive it or show a raw npub.
            val loadedNames = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.contacts() }.getOrDefault(emptyList())
                    .associate { it.npub to peerTitle(it.npub, it.alias, it.name) }
            }
            loadedTasks
                .onSuccess {
                    tasks = it
                    names = loadedNames
                    error = null
                }
                .onFailure { error = it.message ?: "Could not load your tasks." }
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun apply(task: ComradeCore.TaskInfo, action: TaskAction) {
        val next = when (action) {
            TaskAction.Done -> TaskState.DONE
            TaskAction.Decline -> TaskState.DECLINED
            TaskAction.Withdraw -> TaskState.WITHDRAWN
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ComradeCore.setTaskStateTyped(task.id, next) }
            }
                .onSuccess { reload() }
                // Core is the authority on whether a transition is allowed, so a
                // refusal here means the row offered something it should not
                // have — surface it rather than swallowing it.
                .onFailure { error = it.message ?: "Could not change that." }
        }
    }

    val grouped = remember(tasks) { TaskList.group(tasks) }

    Column(modifier = modifier.fillMaxSize()) {
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = TaskList.EMPTY_COPY,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(32.dp)
                        .testTag("tasks-empty"),
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize().testTag("task-list")) {
            // Stable keys, per `.claude/rules/android.md` — without one, list
            // state reattaches to the wrong row when a task resolves and moves
            // between the two groups.
            items(grouped.open, key = { it.id }) { task ->
                TaskRow(task, names, ::apply)
                HorizontalDivider()
            }
            if (grouped.resolved.isNotEmpty()) {
                item(key = "resolved-header") {
                    Text(
                        text = "Finished",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(grouped.resolved, key = { it.id }) { task ->
                    TaskRow(task, names, ::apply)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: ComradeCore.TaskInfo,
    names: Map<String, String>,
    onAction: (ComradeCore.TaskInfo, TaskAction) -> Unit,
) {
    val resolved = task.state != TaskState.OPEN
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = task.text,
            style = MaterialTheme.typography.bodyLarge,
            // A finished task reads as finished at a glance, without needing the
            // badge to be read.
            textDecoration = if (resolved) TextDecoration.LineThrough else null,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = TaskList.subtitleFor(task) { npub -> names[npub] ?: shortNpub(npub) },
                style = MaterialTheme.typography.bodySmall,
            )
            TaskList.stateLabel(task.state).takeIf { it.isNotEmpty() }?.let {
                Text(text = "· $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        val actions = TaskList.actionsFor(task)
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (action in actions) {
                    TextButton(onClick = { onAction(task, action) }) {
                        Text(
                            when (action) {
                                TaskAction.Done -> "Done"
                                TaskAction.Decline -> "Decline"
                                TaskAction.Withdraw -> "Withdraw"
                            },
                        )
                    }
                }
            }
        }
    }
}
