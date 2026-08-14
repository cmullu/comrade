package mullu.comrade.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.attention.ShelfDecisions
import mullu.comrade.attention.ShelfDecisions.RowAction
import mullu.comrade.attention.ShelfDecisions.RowFact

/**
 * The reading shelf — everything saved to read, in one place.
 *
 * ## What this replaced, and why
 *
 * There used to be a paste box here. It asked the person who wanted to read
 * something properly to go and fetch the text of it, by hand, one article at a
 * time — and the articles people mean to read properly are *already saved*, in
 * Instagram's bookmarks, in X's, in Facebook's saves, in a browser's reading
 * list. Asking for them again was asking someone to do the collecting twice, and
 * the feature went unused because that is a fair thing to refuse.
 *
 * So the shelf fills itself two ways, neither of which needs an account, an API
 * key or a network:
 *
 * * **the share sheet** — share a page or a text selection into Comrade from any
 *   app and it lands here (`MainActivity`'s `ACTION_SEND` handling →
 *   `ComradeCore.saveSharedTyped`).
 * * **an export archive** — the file Instagram, X, Facebook and Reddit are
 *   obliged to hand over. Picked with `ACTION_OPEN_DOCUMENT`, read on this phone,
 *   parsed by `comrade_core::library::import_saves`, sent nowhere.
 *
 * ## The boundary this screen has to state out loud
 *
 * **Nothing here fetches a URL.** An imported bookmark is a title and a link, so
 * a row with no text cannot be read in the reader — it offers the link and a way
 * to paste the article in. [ShelfDecisions.rowActions] holds that rule, with
 * tests, because the failure mode of getting it wrong is an empty reader, which
 * reads as a broken app rather than as a deliberate limit.
 *
 * And an import is a **snapshot, not a sync**: something saved on Instagram
 * tomorrow will not appear here until the next export or the next share. The copy
 * says so (`R.string.library_import_hint`) rather than leaving it to be
 * discovered.
 */
@Composable
fun LibraryScreen(
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<ComradeCore.SavedItemInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Which bookmark is having its text pasted in, if any. A row-level editor
    // rather than a screen: the person doing this has the article in a clipboard
    // and one row in mind, and a navigation away from the shelf to come back to
    // it is the friction that makes them not bother.
    var addingTextTo by remember { mutableStateOf<ComradeCore.SavedItemInfo?>(null) }
    var pastedText by remember { mutableStateOf("") }

    suspend fun reload() {
        val fresh = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.libraryItems() }
        }
        fresh.onSuccess { items = it }.onFailure { error = it.message }
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    // The document picker. `OpenDocument` rather than `GetContent`: an export
    // archive is a file the user goes and finds, and this is the contract that
    // can see a Downloads folder.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = context.getString(R.string.library_import_reading)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // Read here, in the app, and hand the *text* across the FFI.
                    // A command that took a path would be an arbitrary-file-read
                    // primitive on the Rust side, which is a much larger thing to
                    // add than an import button.
                    val payload = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("could not open that file")
                    ComradeCore.importSavesTyped(payload)
                }
            }
            outcome
                .onSuccess { report ->
                    status = importSummary(context, report)
                    reload()
                }
                .onFailure {
                    status = null
                    error = it.message ?: context.getString(R.string.library_import_failed)
                }
        }
    }

    fun save() {
        val body = input
        if (!ShelfDecisions.worthSaving(body)) return
        error = null
        scope.launch {
            withContext(Dispatchers.IO) {
                // Whether this is a link, an article or a scrap is the engine's
                // call (`library::parse_shared`) — the same call the share sheet
                // and desktop both go through, so no two surfaces can disagree
                // about what a paste means.
                runCatching { ComradeCore.saveSharedTyped(null, body) }
            }
                .onSuccess { saved ->
                    if (saved == null) {
                        error = context.getString(R.string.library_nothing_to_save)
                    } else {
                        input = ""
                    }
                    reload()
                }
                .onFailure { error = it.message }
        }
    }

    fun open(item: ComradeCore.SavedItemInfo) {
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.openSavedItemTyped(item.id) }.getOrNull()
            }
            if (opened != null) {
                onRead()
            } else {
                // Not a failure: this row is a link, and Comrade will not go and
                // get the page behind it.
                error = context.getString(R.string.library_link_only_explainer)
            }
        }
    }

    fun remove(item: ComradeCore.SavedItemInfo) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ComradeCore.deleteSavedItemTyped(item.id) } }
            reload()
        }
    }

    fun openLink(url: String?) {
        val target = url?.takeIf { it.isNotBlank() } ?: return
        // Handed to whatever the person's own browser is. An in-app webview would
        // make Comrade the thing that fetched the page, which is the line this
        // whole shelf is built not to cross.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error = context.getString(R.string.library_no_browser) }
    }

    fun attachText(item: ComradeCore.SavedItemInfo) {
        val text = pastedText
        if (text.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ComradeCore.saveTextTyped(item.title, text, item.url) }
            }
                .onSuccess {
                    // The bookmark's row is replaced by the readable one rather
                    // than sitting next to it: two rows for one article is the
                    // duplication the whole de-duplicating engine exists to avoid.
                    withContext(Dispatchers.IO) {
                        runCatching { ComradeCore.deleteSavedItemTyped(item.id) }
                    }
                    addingTextTo = null
                    pastedText = ""
                    reload()
                }
                .onFailure { error = it.message }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    // Translucent over the ambient layer — see `FocusAmbient`.
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.library_add_label)) },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("library-input"),
                    )
                    Button(
                        onClick = { save() },
                        enabled = ShelfDecisions.worthSaving(input),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("library-save"),
                    ) { Text(stringResource(R.string.library_save)) }

                    Text(
                        stringResource(R.string.library_import_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { pickFile.launch(IMPORT_MIME_TYPES) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("library-import"),
                    ) { Text(stringResource(R.string.library_import)) }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.library_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (loaded && items.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(items, key = { it.id }) { saved ->
            SavedRow(
                item = saved,
                onAction = { action ->
                    when (action) {
                        RowAction.READ, RowAction.RESUME -> open(saved)
                        RowAction.OPEN_LINK -> openLink(saved.url)
                        RowAction.ADD_TEXT -> {
                            addingTextTo = saved
                            pastedText = ""
                        }
                        RowAction.REMOVE -> remove(saved)
                    }
                },
                isAddingText = addingTextTo?.id == saved.id,
                pastedText = pastedText,
                onPastedTextChange = { pastedText = it },
                onAttachText = { attachText(saved) },
                onCancelAddText = {
                    addingTextTo = null
                    pastedText = ""
                },
            )
        }
    }
}

/**
 * What the document picker will accept.
 *
 * Every export archive's real type, plus `text/plain` for a hand-made list of
 * links. `application/octet-stream` is in there because X's `bookmarks.js` and
 * several providers' JSON arrive with no useful MIME type at all — a picker that
 * greyed out the very file the copy just told the user to choose is worse than one
 * that accepts a file it then reports as unreadable.
 */
private val IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "text/javascript",
    "application/javascript",
    "text/csv",
    "text/tab-separated-values",
    "text/html",
    "text/plain",
    "application/octet-stream",
)

/** One shelf row: title, the facts about it, and only the actions it can honour. */
@Composable
private fun SavedRow(
    item: ComradeCore.SavedItemInfo,
    onAction: (RowAction) -> Unit,
    isAddingText: Boolean,
    pastedText: String,
    onPastedTextChange: (String) -> Unit,
    onAttachText: () -> Unit,
    onCancelAddText: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = ShelfDecisions.rowTitle(item.title, item.url)
            Text(
                title.ifEmpty { stringResource(R.string.library_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // A finished piece is struck through rather than hidden or moved:
                // it stays findable, and nothing counts how many there are.
                textDecoration = if (item.finishedAt != null) TextDecoration.LineThrough else null,
                color = if (item.finishedAt != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    Color.Unspecified
                },
            )
            Text(
                rowFactsLine(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (isAddingText) {
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = onPastedTextChange,
                    label = { Text(stringResource(R.string.library_paste_label)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAttachText,
                        enabled = pastedText.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.library_paste_save)) }
                    OutlinedButton(onClick = onCancelAddText, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.close))
                    }
                }
                return@Column
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The action list is `ShelfDecisions`', not this composable's —
                // which is what keeps "a bookmark is never offered a reader" a
                // tested rule rather than a `when` inside a UI file.
                ShelfDecisions.rowActions(
                    hasText = item.hasText,
                    hasUrl = item.url != null,
                    position = item.position,
                    finished = item.finishedAt != null,
                ).forEach { action ->
                    TextButton(onClick = { onAction(action) }) {
                        Text(stringResource(actionLabel(action)))
                    }
                }
            }
        }
    }
}

private fun actionLabel(action: RowAction): Int = when (action) {
    RowAction.READ -> R.string.library_read
    RowAction.RESUME -> R.string.library_resume
    RowAction.OPEN_LINK -> R.string.library_open_link
    RowAction.ADD_TEXT -> R.string.library_add_text
    RowAction.REMOVE -> R.string.library_remove
}

/** The `Instagram · ~7 min · open` line, assembled from the decided facts. */
@Composable
private fun rowFactsLine(item: ComradeCore.SavedItemInfo): String =
    ShelfDecisions.rowFacts(
        hasText = item.hasText,
        estimatedMinutes = item.estimatedMinutes,
        finished = item.finishedAt != null,
        isOpen = item.isOpen,
    ).joinToString(" · ") { fact ->
        when (fact) {
            RowFact.SOURCE -> stringResource(sourceLabel(item.source))
            RowFact.READING_TIME ->
                stringResource(R.string.library_reading_time, item.estimatedMinutes)
            RowFact.LINK_ONLY -> stringResource(R.string.library_link_only)
            RowFact.OPEN -> stringResource(R.string.library_open_now)
            RowFact.FINISHED -> stringResource(R.string.library_finished)
        }
    }

/**
 * Where a save came from, in the words a person reads.
 *
 * An unrecognised key reads as the generic label rather than throwing: the value
 * comes off disk and may have been written by a newer build, and losing a whole
 * row because its provenance label is from the future would be the worse failure.
 * `focus_view.mjs`'s `sourceLabel` holds the same mapping for desktop.
 */
internal fun sourceLabel(source: String): Int = when (source) {
    "instagram" -> R.string.library_source_instagram
    "twitter" -> R.string.library_source_twitter
    "facebook" -> R.string.library_source_facebook
    "reddit" -> R.string.library_source_reddit
    "youtube" -> R.string.library_source_youtube
    "readlater" -> R.string.library_source_readlater
    "pasted" -> R.string.library_source_pasted
    "web" -> R.string.library_source_web
    else -> R.string.library_source_saved
}

/**
 * What to say after an import, in one sentence.
 *
 * Every number the engine counted gets said. "Added 412" alone is not an honest
 * answer to a file with 900 rows in it — the person who chose that file will
 * assume the other 488 are lost, and the two facts that stop them assuming it
 * (they were already here; we could not read them) are exactly the ones a
 * cheerful one-number summary drops.
 */
private fun importSummary(
    context: android.content.Context,
    report: ComradeCore.ImportReportInfo,
): String = buildString {
    append(context.getString(R.string.library_import_added, report.added))
    if (report.duplicates > 0) {
        append(" ")
        append(context.getString(R.string.library_import_duplicates, report.duplicates))
    }
    if (report.skipped > 0) {
        append(" ")
        append(context.getString(R.string.library_import_skipped, report.skipped))
    }
    if (report.truncated) {
        append(" ")
        append(context.getString(R.string.library_import_truncated))
    }
    if (report.fellBack) {
        append(" ")
        append(context.getString(R.string.library_import_fell_back))
    }
}
