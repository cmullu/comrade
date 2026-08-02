package mullu.comrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * The long read — the other half of `docs/ATTENTION.md` phase 2.
 *
 * One chapter-sized chunk at a time, with Back/Next, and the position
 * remembered. The design constraint is what it *lacks*: no infinite scroll,
 * no related-articles tail, no progress percentage to optimise, nothing to
 * swipe to next. Sustained reading is the exercise, so the screen offers
 * exactly one thing to do.
 *
 * **The text is always the user's own.** Comrade does not fetch a URL for
 * them, deliberately: adding an arbitrary-fetch path to the one app that
 * promises not to phone home would trade the promise for a convenience, and
 * readability extraction would drag a parser in behind it. Paste, or share
 * into the app.
 */
@Composable
fun ReaderScreen(
    onJournalNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf<ComradeCore.ReadingInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        reading = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.reading() }.getOrNull()
        }
        loaded = true
    }

    fun save() {
        val text = pasted.trim()
        if (text.isEmpty()) return
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.saveReadingTyped(title.trim(), text) }
            }.onSuccess {
                reading = it
                pasted = ""
                title = ""
            }.onFailure { error = it.message ?: "Could not save." }
        }
    }

    fun move(to: Int) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.setReadingPositionTyped(to) }.getOrNull()
            }
            if (updated != null) {
                reading = updated
                // A new chunk starts at the top; carrying the old scroll offset
                // into it would drop the reader into the middle of a sentence.
                scroll.scrollTo(0)
            }
        }
    }

    val current = reading
    if (!loaded) return

    if (current == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.reader_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.reader_paste_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.reader_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text(stringResource(R.string.reader_paste_label)) },
                minLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reader-paste"),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { save() },
                enabled = pasted.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reader-save"),
            ) { Text(stringResource(R.string.reader_save)) }
        }
        return
    }

    val position = current.position.coerceIn(0, (current.chunks.size - 1).coerceAtLeast(0))
    val atEnd = position >= current.chunks.size - 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current.title.ifBlank { stringResource(R.string.reader_title) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.reader_progress, position + 1, current.chunks.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Text(
            current.chunks.getOrElse(position) { "" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .testTag("reader-body"),
        )

        if (atEnd) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.reader_finished),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { onJournalNote(current.title) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.focus_save_note)) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { move(position - 1) },
                enabled = position > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.reader_previous)) }
            Button(
                onClick = { move(position + 1) },
                enabled = !atEnd,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reader-next"),
            ) { Text(stringResource(R.string.reader_next)) }
        }
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { ComradeCore.clearReadingTyped() } }
                    reading = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.reader_clear)) }
    }
}
