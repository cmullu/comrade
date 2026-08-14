package mullu.comrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R

/**
 * Long reads — the reading library (`docs/ATTENTION.md` phase 2, extended
 * 2026-08-14 from one pasted text to a library of saved articles).
 *
 * One place for everything the user chose to read properly, wherever it was
 * found: the system share sheet is the way in from other apps — share an
 * article out of any feed and it lands here, with its source labelled from
 * the first link in the shared text — and paste still works for everything
 * else. Each read keeps its own place.
 *
 * The reading view keeps the original constraint set: one chapter-sized chunk
 * at a time, no infinite scroll, no related-articles tail. Sustained reading
 * is the exercise, so the screen offers exactly one thing to do.
 *
 * **The text is always the user's own.** Comrade does not fetch a URL for
 * them, deliberately: adding an arbitrary-fetch path to the one app that
 * promises not to phone home would trade the promise for a convenience. The
 * source label is derived offline from the shared text
 * (`comrade_core::attention::reading_source`) — the URL is read for its host
 * and nothing else.
 */
@Composable
fun ReaderScreen(
    onJournalNote: (String) -> Unit,
    /** Text handed in by the system share sheet, if that is how we got here. */
    sharedTitle: String? = null,
    sharedText: String? = null,
    /** Called once the shared text has been taken into the compose fields. */
    onSharedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var reads by remember { mutableStateOf<List<ComradeCore.SavedReadSummaryInfo>>(emptyList()) }
    var open by remember { mutableStateOf<ComradeCore.SavedReadInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    suspend fun reloadList() {
        reads = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.savedReads() }.getOrDefault(emptyList())
        }
        loaded = true
    }
    LaunchedEffect(Unit) { reloadList() }

    // A share-sheet arrival prefills the compose box rather than saving
    // silently: the user sees exactly what another app handed over before it
    // enters the vault, and an accidental share costs one Back press.
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            title = sharedTitle.orEmpty()
            pasted = sharedText
            open = null
            onSharedConsumed()
        }
    }

    fun save() {
        val text = pasted.trim()
        if (text.isEmpty()) return
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.saveReadTyped(title.trim(), text) }
            }.onSuccess {
                // Saving opens the read: whoever just added an article is the
                // person who wants to start it.
                open = it
                pasted = ""
                title = ""
                reloadList()
            }.onFailure { error = it.message ?: "Could not save." }
        }
    }

    fun openRead(id: String) {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.openSavedReadTyped(id) }.getOrNull()
            }
            if (read != null) open = read else reloadList()
        }
    }

    fun move(read: ComradeCore.SavedReadInfo, to: Int) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.setSavedReadPositionTyped(read.id, to) }.getOrNull()
            }
            if (updated != null) {
                open = updated
                // A new chunk starts at the top; carrying the old scroll offset
                // into it would drop the reader into the middle of a sentence.
                scroll.scrollTo(0)
            }
        }
    }

    if (!loaded) return
    val current = open

    if (current == null) {
        // ── The library ────────────────────────────────────────────────────
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (reads.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.reader_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(reads, key = { it.id }) { row ->
                OutlinedCard(
                    onClick = { openRead(row.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader-row"),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            // An untitled share leans on its source: an
                            // "instagram.com" row beats a generic word.
                            row.title.ifBlank {
                                row.source.ifBlank { stringResource(R.string.reader_title) }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                // …and then the meta line must not repeat it.
                                if (row.title.isBlank()) "" else row.source,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                stringResource(
                                    R.string.reader_progress,
                                    row.position + 1,
                                    row.chunkCount.coerceAtLeast(1),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            // ── Add one: paste (the share sheet fills this in too) ─────────
            item {
                Text(
                    stringResource(R.string.reader_add),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.reader_paste_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.reader_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(R.string.reader_paste_label)) },
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader-paste"),
                )
            }
            error?.let {
                item {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(
                    onClick = { save() },
                    enabled = pasted.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reader-save"),
                ) { Text(stringResource(R.string.reader_save)) }
            }
        }
        return
    }

    // ── One read open ────────────────────────────────────────────────────────
    val position = current.position.coerceIn(0, (current.chunks.size - 1).coerceAtLeast(0))
    val atEnd = position >= current.chunks.size - 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = {
                open = null
                scope.launch { reloadList() }
            },
        ) { Text(stringResource(R.string.reader_back_to_list)) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.title.ifBlank {
                        current.source.ifBlank { stringResource(R.string.reader_title) }
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current.title.isNotBlank() && current.source.isNotBlank()) {
                    Text(
                        current.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
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
                onClick = { move(current, position - 1) },
                enabled = position > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.reader_previous)) }
            Button(
                onClick = { move(current, position + 1) },
                enabled = !atEnd,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reader-next"),
            ) { Text(stringResource(R.string.reader_next)) }
        }
        TextButton(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ComradeCore.deleteSavedReadTyped(current.id) }
                    }
                    open = null
                    reloadList()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.reader_remove)) }
    }
}
