package mullu.comrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.attention.ScrollSitting

/**
 * The public feed (Sabha): broadcast short posts to open relays and watch the
 * live public stream. Unlike Chats, nothing here is private — the composer
 * says so explicitly.
 *
 * ## The calm-feed contract (`docs/ATTENTION.md` phase 0)
 *
 * This screen is bound by a rule the rest of the app now depends on: it is
 * **strictly chronological**, has no ranking, no autoplay, no engagement
 * counters, and no recommendation tail. That was already true by accident;
 * it is now the contract, because an app built to repair attention cannot
 * ship the mechanism that erodes it.
 *
 * The one addition is a *gentle stop*: after ten unbroken minutes here, one
 * inline card offers somewhere better to be. It does not block, it is
 * dismissible for the sitting, and nothing about the sitting is recorded.
 * The rule lives in [ScrollSitting] so it can be tested.
 */
@Composable
fun FeedScreen(
    feedItems: List<ComradeCore.ChitthiInfo>,
    onPosted: (ComradeCore.ChitthiInfo) -> Unit,
    modifier: Modifier = Modifier,
    /** Where "write instead" goes; null hides that action (e.g. in tests). */
    onOpenJournal: (() -> Unit)? = null,
    /** Where "take a breath" goes; null hides that action. */
    onOpenBreathing: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // ── Gentle stop ───────────────────────────────────────────────────────────
    // Sitting state is remembered across recomposition but deliberately NOT
    // across process death or navigation away for longer than the reset window:
    // a "you've been scrolling" card that survived a night would be both wrong
    // and preachy. Nothing here is persisted or counted.
    val sitting = rememberSaveable { mutableLongStateOf(0L) }
    val leftAt = rememberSaveable { mutableLongStateOf(0L) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var elapsedTick by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        sitting.longValue = ScrollSitting.resumeOrRestart(
            previousStart = sitting.longValue,
            leftAt = leftAt.longValue,
            now = System.currentTimeMillis(),
        )
        // A fresh sitting earns a fresh offer; a resumed one keeps the
        // dismissal, so bouncing to a chat and back cannot re-nag.
        if (sitting.longValue > leftAt.longValue) dismissed = false
        onDispose { leftAt.longValue = System.currentTimeMillis() }
    }

    // One coarse tick a while before the threshold, not a per-second timer:
    // this only needs to notice a ten-minute boundary, and a screen that woke
    // up every second to check whether the user had been there too long would
    // be its own small irony.
    LaunchedEffect(sitting.longValue, dismissed) {
        if (dismissed) return@LaunchedEffect
        while (true) {
            elapsedTick = System.currentTimeMillis()
            delay(SITTING_CHECK_INTERVAL_MS)
        }
    }

    val offerStop = ScrollSitting.shouldOffer(
        sittingStartedAt = sitting.longValue,
        now = elapsedTick,
        dismissed = dismissed,
    )

    fun post() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.broadcastChitthiTyped(text) }
            }.onSuccess { id ->
                busy = false
                draft = ""
                onPosted(
                    ComradeCore.ChitthiInfo(
                        id = id,
                        author = "you",
                        content = text,
                        createdAt = System.currentTimeMillis() / 1000,
                        replyTo = null,
                    ),
                )
            }.onFailure {
                busy = false
                error = it.message ?: "Could not post."
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Share something publicly…") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("feed-input"),
                        maxLines = 4,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Public — anyone on the network can read this.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Button(
                            onClick = { post() },
                            enabled = draft.isNotBlank() && !busy,
                            modifier = Modifier.testTag("feed-post"),
                        ) { Text(if (busy) "Posting…" else "Post") }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (offerStop) {
            item(key = "gentle-stop") {
                GentleStopCard(
                    onOpenJournal = onOpenJournal,
                    onOpenBreathing = onOpenBreathing,
                    onDismiss = { dismissed = true },
                )
            }
        }

        if (feedItems.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Live public posts stream in as the relays deliver them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        // Strictly chronological, exactly as delivered — the calm-feed contract.
        // No ranking pass belongs here, ever.
        items(feedItems, key = { it.id }) { post ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (post.author == "you") "You" else shortNpub(post.author),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = if (post.author == "you") null else FontFamily.Monospace,
                        )
                        Text(
                            relativeTime(post.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        post.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * How often the sitting clock is re-read while the feed is on screen. Coarse
 * on purpose — see the comment at its use site.
 */
private const val SITTING_CHECK_INTERVAL_MS = 20_000L

/**
 * The gentle stop: one inline card, after ten unbroken minutes on the feed.
 *
 * Every word here is deliberate. It does not say how long the user has been
 * scrolling (a number invites a score), it does not say they should stop (it
 * offers somewhere to go instead), and it carries no streak, count, or
 * consequence. `docs/ATTENTION.md` gate 3: no guilt loops.
 */
@Composable
private fun GentleStopCard(
    onOpenJournal: (() -> Unit)?,
    onOpenBreathing: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .testTag("gentle-stop"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.gentle_stop_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.gentle_stop_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onOpenJournal?.let {
                    Button(
                        onClick = { onDismiss(); it() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gentle-stop-journal"),
                    ) { Text(stringResource(R.string.gentle_stop_write)) }
                }
                onOpenBreathing?.let {
                    OutlinedButton(
                        onClick = { onDismiss(); it() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.gentle_stop_breathe)) }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("gentle-stop-dismiss"),
            ) { Text(stringResource(R.string.gentle_stop_keep_reading)) }
        }
    }
}
