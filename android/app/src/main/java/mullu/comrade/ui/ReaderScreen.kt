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
 * The long read — one chapter-sized chunk at a time.
 *
 * The design constraint is what it *lacks*: no infinite scroll, no
 * related-articles tail, no progress percentage to optimise, nothing to swipe to
 * next. Sustained reading is the exercise, so the screen offers exactly one thing
 * to do.
 *
 * **The text is still always the user's own**, and that has not changed with the
 * shelf: Comrade does not fetch a URL for anyone. What changed is where the text
 * comes *from* — it used to be pasted here, one article at a time, and it now
 * comes off `LibraryScreen`'s shelf, filled by the share sheet or by an import of
 * the user's own platform export. This screen shows whichever row is open
 * (`ComradeRuntime::reading`) and nothing else; there is no paste box here any
 * more, because a reader with a compose field in it is two screens.
 */
@Composable
fun ReaderScreen(
    onJournalNote: (String) -> Unit,
    onBackToShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf<ComradeCore.ReadingInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        reading = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.reading() }.getOrNull()
        }
        loaded = true
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

    // Nothing open. This is reachable — the row was removed on the shelf while
    // the reader was in the back stack — and the honest answer is a way back
    // rather than an empty page.
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
                stringResource(R.string.reader_nothing_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBackToShelf, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.library_title))
            }
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
        // Where it came from, said once and quietly. It matters for a shared
        // selection whose title is a guess: "from Instagram" is often the only
        // thing that identifies which save this is.
        Text(
            stringResource(sourceLabel(current.source)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

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
        // Closes the reader, keeping the article and the position on the shelf.
        // The old wording here was "Close this read" over a call that *deleted*
        // the only saved text, which was true when there was only one. Deleting
        // is now a shelf-row action, where the thing being deleted is visible.
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { ComradeCore.closeReadingTyped() } }
                    reading = null
                    onBackToShelf()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.reader_close)) }
    }
}
