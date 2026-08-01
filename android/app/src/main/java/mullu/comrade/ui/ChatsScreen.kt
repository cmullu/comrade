package mullu.comrade.ui

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.R
import mullu.comrade.ComradeCore
import mullu.comrade.Notifier
import mullu.comrade.PresenceMonitor
import mullu.comrade.media.VoiceRecorder

/**
 * Identity-stable avatar hues: the same key renders the same colour on every
 * device (Telegram-style), so people become recognisable at a glance.
 */
private val AvatarPalette = listOf(
    Color(0xFF6366F1), // indigo
    Color(0xFF0EA5E9), // sky
    Color(0xFF10B981), // emerald
    Color(0xFFF59E0B), // amber
    Color(0xFFEF4444), // coral
    Color(0xFF8B5CF6), // violet
    Color(0xFFEC4899), // rose
    Color(0xFF14B8A6), // teal
)

@Composable
fun PeerAvatar(
    title: String,
    modifier: Modifier = Modifier,
    seed: String = title,
    size: Dp = 46.dp,
) {
    val base = AvatarPalette[avatarColorIndex(seed, AvatarPalette.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(listOf(base.copy(alpha = 0.82f), base)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trimStart('@').take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── Chat list ────────────────────────────────────────────────────────────────

/** Tappable banner atop the chat list linking to the message-requests inbox. */
@Composable
private fun RequestsBanner(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("✉", style = MaterialTheme.typography.titleMedium)
        Text(
            "Message requests ($count)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ChatsScreen(
    chatTick: Int,
    requestTick: Int,
    onOpen: (peer: String, alias: String?, username: String?) -> Unit,
    onNewChat: () -> Unit,
    onOpenRequests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var conversations by remember { mutableStateOf<List<ComradeCore.ConversationInfo>?>(null) }
    var requestCount by remember { mutableStateOf(0) }
    val presenceNow by PresenceMonitor.presence.collectAsState()

    LaunchedEffect(chatTick) {
        conversations = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.conversations() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(chatTick, requestTick) {
        requestCount = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.messageRequestsTyped().size }.getOrDefault(0)
        }
    }

    val list = conversations
    when {
        list == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        list.isEmpty() -> Column(
            modifier = modifier.fillMaxSize(),
        ) {
            RequestsBanner(requestCount, onOpenRequests)
            EmptyChats(onNewChat)
        }
        else -> LazyColumn(modifier = modifier.fillMaxSize()) {
            item { RequestsBanner(requestCount, onOpenRequests) }
            items(list, key = { it.peer }) { convo ->
                val title = peerTitle(convo.peer, convo.alias, convo.peerName)
                // A comrade's dot follows the live flow, so a beacon arriving
                // while the list is on screen moves it without a reload.
                val online = convo.comrade && (presenceNow[convo.peer]?.online ?: convo.online)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(convo.peer, convo.alias, convo.peerName) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        PeerAvatar(title, seed = convo.peer)
                        if (convo.comrade) PresenceDot(online, size = 12.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = (if (convo.lastOutgoing) "You: " else "") + convo.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        relativeTime(convo.lastAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/** Empty-state prompt for the chat list. */
@Composable
private fun EmptyChats(onNewChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Find someone by username, or share your key so they can find you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = onNewChat) { Text("Start a chat") }
    }
}

// ── New chat (find people) ───────────────────────────────────────────────────

@Composable
fun NewChatScreen(
    onOpen: (peer: String, alias: String?, username: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ComradeCore.FoundProfile>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ComradeCore.ContactInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.contacts() }.getOrDefault(emptyList())
        }
    }

    val trimmed = query.trim()
    val isKey = trimmed.startsWith("npub1") && trimmed.length > 20

    fun search() {
        if (trimmed.isEmpty() || isKey) return
        searching = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.searchProfilesTyped(trimmed) }
            }.onSuccess {
                results = it
                searched = true
                searching = false
            }.onFailure {
                error = it.message
                searching = false
            }
        }
    }

    fun startChat(npub: String, username: String?) {
        scope.launch {
            // Pin the key only (trust-on-first-use). The published @handle is
            // cached by the search itself; an alias stays the user's to set.
            val saved = withContext(Dispatchers.IO) {
                runCatching { ComradeCore.addContactTyped(npub, "") }.getOrNull()
            }
            if (saved == null) {
                error = "That doesn't look like a valid key."
            } else {
                onOpen(saved.npub, saved.alias.ifBlank { null }, username ?: saved.name)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; searched = false },
                label = { Text("@username or npub1… key") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("newchat-query"),
            )
        }
        item {
            if (isKey) {
                Button(
                    onClick = { startChat(trimmed, null) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start chat with ${shortNpub(trimmed)}") }
            } else {
                OutlinedButton(
                    onClick = { search() },
                    enabled = trimmed.isNotEmpty() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "Searching…" else "Search") }
            }
        }
        item {
            Text(
                text = "Search asks public directory relays, so it only finds people " +
                    "who published their username. Names are not unique — always " +
                    "glance at the key. The safest way to connect is swapping " +
                    "npub keys directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (searched && results.isEmpty()) {
            item {
                Text(
                    "No one found under that name. They may not have published it — " +
                        "ask them for their npub key instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(results, key = { it.npub }) { found ->
            val title = found.name?.let { "@$it" } ?: shortNpub(found.npub)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { startChat(found.npub, found.name) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerAvatar(title, seed = found.npub)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        shortNpub(found.npub) + (found.about?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (contacts.isNotEmpty()) {
            item {
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(contacts, key = { "c:" + it.npub }) { contact ->
                val title = peerTitle(contact.npub, contact.alias, contact.name)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onOpen(contact.npub, contact.alias.ifBlank { null }, contact.name)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PeerAvatar(title, seed = contact.npub)
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            shortNpub(contact.npub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ── Conversation ─────────────────────────────────────────────────────────────

/** Delivery-status glyph shown on outgoing bubbles: ✓ sent, ✓✓ delivered/read. */
private fun statusGlyph(status: String?): String = when (status) {
    "read", "delivered" -> "✓✓"
    else -> "✓"
}

/** A conversation is a time-ordered merge of text messages and media attachments. */
private sealed interface ChatItem {
    val createdAt: Long

    /** Whether *this device* sent it — your own messages are never "unread". */
    val outgoing: Boolean

    /**
     * Stable list key. Media ids are namespaced so a media event id can never
     * collide with a message id. Also used to remember the unread boundary by
     * identity rather than by index, so a backfill of older history inserted
     * above it cannot slide the divider onto the wrong message.
     */
    val key: String

    /**
     * The nostr event id a reply points at.
     *
     * **Not** [key]: the namespacing that keeps the list keys apart would make
     * a reply address `media:abc…`, which is not an event and would never
     * resolve on the other side. A message and an attachment are both ordinary
     * events, which is why replying to media needs no core change —
     * `send_dm_reply` tags whatever id it is given.
     */
    val eventId: String

    /** One line naming this item, for a reply chip or a quoted preview. */
    val preview: String

    data class TextItem(val msg: ComradeCore.MessageInfo) : ChatItem {
        override val createdAt get() = msg.createdAt
        override val outgoing get() = msg.outgoing
        override val key get() = msg.id
        override val eventId get() = msg.id
        override val preview get() = msg.content
    }

    data class MediaItem(val info: ComradeCore.MediaMessageInfo) : ChatItem {
        override val createdAt get() = info.createdAt
        override val outgoing get() = info.outgoing
        override val key get() = "media:${info.eventId}"
        override val eventId get() = info.eventId
        override val preview get() = mediaQuoteLabel(info.mimeType, info.caption)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(
    peer: String,
    chatTick: Int,
    modifier: Modifier = Modifier,
) {
    var messages by remember { mutableStateOf<List<ComradeCore.MessageInfo>>(emptyList()) }
    var mediaItems by remember { mutableStateOf<List<ComradeCore.MediaMessageInfo>>(emptyList()) }
    // A TextFieldValue rather than a String: the emoji picker inserts at the
    // caret, which needs the selection.
    var draft by remember { mutableStateOf(TextFieldValue()) }

    /**
     * The one place the draft changes by hand, so typing and an emoji
     * insertion tell the core the same thing. It needs to know a composer
     * holds unsent text before it can ever tell a comrade one was given up on
     * (`comrade_core::nudge`) — and nothing about the text itself goes with it.
     *
     * Whitespace-only counts as empty, because [send] would not send it either.
     */
    fun editDraft(next: TextFieldValue) {
        draft = next
        if (next.text.isBlank()) ComradeCore.abandonDraft(peer) else ComradeCore.noteDraft(peer)
    }
    var emojiOpen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A reply target is any chat item, text or attachment: the `e` tag does not
    // care which kind of event it names, so "reply to that photo" needs nothing
    // from the core that "reply to that message" did not already have.
    var replyingTo by remember { mutableStateOf<ChatItem?>(null) }
    var attaching by remember { mutableStateOf(false) }
    // Voice notes: tap the action icon to record, tap again to send.
    var recording by remember { mutableStateOf(false) }
    var voiceSending by remember { mutableStateOf(false) }
    // Keyed on `peer` so switching conversations resets the scroll bookkeeping.
    var loadedOnce by remember(peer) { mutableStateOf(false) }
    var newMessagesBelow by remember(peer) { mutableStateOf(false) }
    // The unread boundary for this visit, held by item key rather than index so
    // a backfill of older history above it can't slide the divider onto the
    // wrong message. Captured once on open and deliberately *not* recomputed as
    // the watermark advances — Telegram leaves the line where you found it for
    // the rest of the visit, which is what makes it useful to read down to.
    var unreadBoundaryKey by remember(peer) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    // What this device can actually capture, probed once. Capability-gated so a
    // tablet with no camera gets a mic and nothing to swap to, rather than a
    // control that fails on tap.
    val availableModes = remember {
        availableCaptureModes(
            canRecordAudio = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            canTakePhoto = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            canRecordVideo = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
    }
    var captureMode by remember { mutableStateOf(availableModes.firstOrNull()) }
    // MediaRecorder holds the mic while active; a composition that leaves the
    // conversation mid-record (back-navigation) must not leak it.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val requestMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Granted state is re-checked on the next press; nothing to do here. */ }

    // Text and media interleaved in one time-ordered thread, like a real chat.
    // Named `chatItems`, not `items`, to avoid shadowing the LazyListScope
    // `items(...)` DSL function called with it below.
    val chatItems = remember(messages, mediaItems) {
        (messages.map(ChatItem::TextItem) + mediaItems.map(ChatItem::MediaItem))
            .sortedBy { it.createdAt }
    }
    // Quick lookup so a bubble carrying reply_to can show a quoted preview.
    // Keyed over the merged thread, not just the messages: a reply to an
    // attachment resolves to that attachment, and quoting it says what it was.
    val byId = remember(chatItems) { chatItems.associateBy { it.eventId } }

    // `chatTick` is a GLOBAL event tick — it fires for activity in any
    // conversation, and repeatedly while this one is open. So a reload must
    // not yank a reader who scrolled up in history back to the bottom:
    // auto-scroll only on first load or when they were already near it,
    // otherwise light up the jump-to-latest button instead.
    LaunchedEffect(peer, chatTick) {
        val (msgs, media) = withContext(Dispatchers.IO) {
            val msgs = runCatching { ComradeCore.messages(peer) }.getOrDefault(emptyList())
            val media = runCatching { ComradeCore.media(peer) }.getOrDefault(emptyList())
            msgs to media
        }
        val grew = msgs.size + media.size > messages.size + mediaItems.size
        val wasNearBottom = isNearBottom(
            lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
            totalCount = listState.layoutInfo.totalItemsCount,
        )
        messages = msgs
        mediaItems = media
        // The merged list this effect is about to scroll — recomputed here
        // rather than read from `chatItems`, which is derived from the state
        // set two lines up and so still holds the previous composition's value.
        val merged = (msgs.map(ChatItem::TextItem) + media.map(ChatItem::MediaItem))
            .sortedBy { it.createdAt }
        val last = merged.size - 1
        when {
            last < 0 -> {}
            !loadedOnce -> {
                // One call both records that the thread is now read and reports
                // where the reader had got to, so there is no window where the
                // answer has already been overwritten.
                val previous = withContext(Dispatchers.IO) {
                    runCatching { ComradeCore.markConversationReadTyped(peer) }.getOrDefault(0L)
                }
                val firstUnread = firstUnreadIndex(
                    createdAt = merged.map { it.createdAt },
                    outgoing = merged.map { it.outgoing },
                    lastReadAt = previous,
                )
                unreadBoundaryKey = firstUnread?.let { merged[it].key }
                // Open where they left off, not at the newest message.
                listState.scrollToItem(firstUnread ?: last)
                loadedOnce = true
            }
            grew && wasNearBottom -> {
                listState.scrollToItem(last)
                // They can see these, so they are read — receipt and watermark.
                withContext(Dispatchers.IO) {
                    runCatching { ComradeCore.markConversationReadTyped(peer) }
                }
            }
            // Scrolled up in history: deliberately *not* marked read. They have
            // not seen these, so neither the peer's receipt nor the watermark
            // should claim otherwise — the jump-to-latest button is the signal,
            // and marking read here would cost them the divider next visit.
            grew -> newMessagesBelow = true
        }
    }

    // Clear any pending notification for this peer. The read receipt itself
    // rides along with the load effect above, which needs its return value.
    LaunchedEffect(peer) { Notifier.clearForPeer(context, peer) }

    // Walking away from a half-written message is the other way to abandon it,
    // and the one the person is least likely to come back from. Keyed on `peer`
    // so switching conversations reports the thread being left, not the one
    // arriving; an empty composer makes this a no-op.
    DisposableEffect(peer) { onDispose { ComradeCore.abandonDraft(peer) } }

    fun send() {
        val text = draft.text.trim()
        if (text.isEmpty() || sending) return
        sending = true
        error = null
        val replyId = replyingTo?.eventId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.sendDmReplyTyped(peer, text, replyId) }
            }.onSuccess { sent ->
                draft = TextFieldValue()
                replyingTo = null
                sending = false
                messages = messages + sent
                scope.launch { listState.scrollToItem(messages.size + mediaItems.size - 1) }
            }.onFailure {
                sending = false
                error = it.message ?: "Could not send."
            }
        }
    }

    // Encrypt + send a picked file as an attachment (NIP-94 over the DM channel).
    //
    // The caption ("tag") is whatever is in the composer, per
    // [captionForAttachment] — Telegram's rule, minus the case where those
    // words are a half-written reply. The box is emptied only once the send has
    // actually succeeded and only if its text went along: a failed upload must
    // leave what the person typed where they typed it.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null || attaching) return@rememberLauncherForActivityResult
        attaching = true
        error = null
        val caption = captionForAttachment(draft.text, replyingTo != null)
        val consumed = captionConsumesDraft(draft.text, replyingTo != null)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read the file.")
                    if (bytes.size > 10 * 1024 * 1024) {
                        throw IllegalStateException("Attachments are limited to 10 MB.")
                    }
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    ComradeCore.sendMediaBytesTyped(peer, mime, caption, bytes)
                }
            }.onSuccess {
                attaching = false
                if (consumed) editDraft(TextFieldValue())
                // Render the real attachment inline — not a synthetic text line.
                mediaItems = mediaItems + it
                scope.launch { listState.scrollToItem(messages.size + mediaItems.size - 1) }
            }.onFailure {
                attaching = false
                error = it.message ?: "Could not send the attachment."
            }
        }
    }

    // ── Camera capture (the composer's photo/video modes) ───────────────────
    //
    // Written into `cache/capture/`, which `file_paths.xml` exposes to the
    // camera app through the existing FileProvider. A separate subdirectory
    // from `cache/media/` on purpose: that one holds decrypted attachments and
    // is swept by `purgeDecryptedMedia`, which must not race a capture that is
    // still being written.
    fun newCaptureFile(extension: String): File {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        return File(dir, "cap-${System.nanoTime()}.$extension")
    }

    fun captureUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    // Read the captured file, send it, and delete the plaintext immediately —
    // the same rule voice notes follow (AUDIT S-4): a decrypted clip must not
    // linger in the cache after the send resolves, successfully or not.
    fun sendCapturedFile(file: File, mime: String) {
        if (attaching) return
        attaching = true
        error = null
        val caption = captionForAttachment(draft.text, replyingTo != null)
        val consumed = captionConsumesDraft(draft.text, replyingTo != null)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = file.readBytes()
                        if (bytes.isEmpty()) {
                            throw IllegalStateException("The capture was empty.")
                        }
                        if (bytes.size > 10 * 1024 * 1024) {
                            throw IllegalStateException("Attachments are limited to 10 MB.")
                        }
                        ComradeCore.sendMediaBytesTyped(peer, mime, caption, bytes)
                    } finally {
                        file.delete()
                    }
                }
            }.onSuccess {
                attaching = false
                if (consumed) editDraft(TextFieldValue())
                mediaItems = mediaItems + it
                scope.launch { listState.scrollToItem(messages.size + mediaItems.size - 1) }
            }.onFailure {
                attaching = false
                error = it.message ?: "Could not send the capture."
            }
        }
    }

    // The pending capture target: the contracts hand back only success/failure,
    // not the file, so the launcher and the result have to agree out of band.
    var pendingCapture by remember { mutableStateOf<Pair<File, String>?>(null) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val pending = pendingCapture
        pendingCapture = null
        if (pending == null) return@rememberLauncherForActivityResult
        // Cancelled: drop the empty placeholder rather than sending 0 bytes.
        if (!ok) { pending.first.delete(); return@rememberLauncherForActivityResult }
        sendCapturedFile(pending.first, pending.second)
    }

    val recordVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok ->
        val pending = pendingCapture
        pendingCapture = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (!ok) { pending.first.delete(); return@rememberLauncherForActivityResult }
        sendCapturedFile(pending.first, pending.second)
    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Re-checked on the next press; nothing to do here. */ }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun launchCapture(mode: CaptureMode) {
        if (attaching) return
        if (!hasCameraPermission()) {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        when (mode) {
            CaptureMode.Photo -> {
                val file = newCaptureFile("jpg")
                pendingCapture = file to "image/jpeg"
                takePhoto.launch(captureUri(file))
            }
            CaptureMode.Video -> {
                val file = newCaptureFile("mp4")
                pendingCapture = file to "video/mp4"
                recordVideo.launch(captureUri(file))
            }
            // Voice never routes here — it has its own press-and-hold button.
            CaptureMode.Voice -> Unit
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Encrypt + send a recorded voice note, exactly like any other media
    // attachment (audio/aac over the DM channel), then wipe the plaintext clip.
    fun sendVoiceNote(file: File) {
        if (voiceSending) return
        voiceSending = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = file.readBytes()
                    // Delete the temp recording the moment the send resolves —
                    // whether it succeeds or throws (AUDIT S-4): the decrypted
                    // clip must not linger on disk after the FFI call returns.
                    try {
                        ComradeCore.sendMediaBytesTyped(peer, VoiceRecorder.MIME_TYPE, "", bytes)
                    } finally {
                        file.delete()
                    }
                }
            }.onSuccess { info ->
                voiceSending = false
                mediaItems = mediaItems + info
                scope.launch { listState.scrollToItem(messages.size + mediaItems.size - 1) }
            }.onFailure {
                voiceSending = false
                error = it.message ?: "Could not send the voice note."
            }
        }
    }

    // Tap-to-start, tap-to-send. Returns silently if the mic is unavailable or
    // the permission was refused — the press is then simply a no-op rather than
    // leaving the icon stuck in a recording pose.
    fun toggleRecording() {
        if (recording) {
            recording = false
            recorder.stop()?.let { sendVoiceNote(it) }
            return
        }
        if (!hasMicPermission()) {
            requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        if (recorder.start()) recording = true
    }


    // Whether the newest message is (nearly) on screen right now — drives
    // both the jump-to-latest button and clearing its "new" highlight.
    val atBottom by remember {
        derivedStateOf {
            isNearBottom(
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalCount = listState.layoutInfo.totalItemsCount,
            )
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) newMessagesBelow = false }
    // Reaching the bottom is the moment anything left unread has actually been
    // seen — the load effect deliberately doesn't claim that while scrolled up.
    LaunchedEffect(atBottom, chatItems.size) {
        if (loadedOnce && atBottom && chatItems.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { ComradeCore.markConversationReadTyped(peer) }
            }
        }
    }
    // Day headers compare against "now" once per data change; good enough —
    // a stale "Today" flips on the next message either way.
    val nowSecs = remember(chatItems) { System.currentTimeMillis() / 1000 }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (chatItems.isEmpty()) {
                    item {
                        Text(
                            "Messages are end-to-end encrypted with your keys. Say hi!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(
                    chatItems,
                    key = { _, item -> item.key },
                ) { index, item ->
                    // The separators live inside the message item (not as their
                    // own list items), so item indices keep matching `chatItems`
                    // and the scroll arithmetic above stays honest.
                    val prevAt = chatItems.getOrNull(index - 1)?.createdAt
                    Column(Modifier.fillMaxWidth()) {
                        if (startsNewDay(prevAt, item.createdAt)) {
                            DaySeparator(dayLabel(item.createdAt, nowSecs))
                        }
                        if (item.key == unreadBoundaryKey) {
                            UnreadSeparator(stringResource(R.string.unread_messages))
                        }
                        when (item) {
                            is ChatItem.MediaItem -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (item.info.outgoing) Arrangement.End else Arrangement.Start,
                            ) {
                                MediaAttachmentBubble(
                                    item.info,
                                    onReply = { replyingTo = item },
                                )
                            }
                            is ChatItem.TextItem -> {
                                val msg = item.msg
                                val quoted = msg.replyTo?.let { byId[it] }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {},
                                            onLongClick = { replyingTo = item },
                                        ),
                                    horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(
                                            topStart = 18.dp,
                                            topEnd = 18.dp,
                                            bottomStart = if (msg.outgoing) 18.dp else 6.dp,
                                            bottomEnd = if (msg.outgoing) 6.dp else 18.dp,
                                        ),
                                        color = if (msg.outgoing) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        tonalElevation = 1.dp,
                                        modifier = Modifier.widthIn(max = 300.dp),
                                    ) {
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                            if (quoted != null) {
                                                QuotedPreview(quoted.preview)
                                            }
                                            Text(msg.content, style = MaterialTheme.typography.bodyLarge)
                                            Row(
                                                modifier = Modifier
                                                    .align(Alignment.End)
                                                    .padding(top = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text(
                                                    clockTime(msg.createdAt),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                )
                                                if (msg.outgoing) {
                                                    Text(
                                                        statusGlyph(msg.status),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (msg.status == "read") {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.outline
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!atBottom && chatItems.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = {
                        newMessagesBelow = false
                        scope.launch { listState.scrollToItem(chatItems.size - 1) }
                    },
                    containerColor = if (newMessagesBelow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (newMessagesBelow) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .testTag("dm-jump-latest"),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (newMessagesBelow) {
                            "New messages — jump to latest"
                        } else {
                            "Jump to latest"
                        },
                    )
                }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // "Replying to…" chip above the composer.
        replyingTo?.let { r ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "↩ " + r.preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                TextButton(onClick = { replyingTo = null }) { Text("✕") }
            }
        }

        // While recording, a live "● Recording…" banner so it is unmistakable
        // that the microphone is hot.
        if (recording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Text(
                    stringResource(R.string.composer_recording),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Composer, Telegram's layout:
        //
        //   ╭────────────────────────────────╮      ╭────╮
        //   │ 🙂  Message…               📎  │  ⧉   │ 🎤 │
        //   ╰────────────────────────────────╯      ╰────╯
        //     emoji     text field     attach  swap  capture → send
        //
        // Emoji and the paper clip sit *inside* the field, which is what makes
        // the pill read as one control instead of a row of loose buttons. Only
        // the round button (and its swap) live outside, because they are the
        // ones whose meaning changes.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { editDraft(it) },
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("dm-input"),
                maxLines = 4,
                leadingIcon = {
                    IconButton(
                        onClick = { emojiOpen = true },
                        modifier = Modifier.testTag("dm-emoji"),
                    ) {
                        Icon(
                            EmojiIcon,
                            contentDescription = stringResource(R.string.composer_emoji),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (!attaching) pickMedia.launch("*/*") },
                            enabled = !attaching,
                            modifier = Modifier.testTag("dm-attach"),
                        ) {
                            if (attaching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    AttachFileIcon,
                                    contentDescription = stringResource(R.string.composer_attach),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // The one action control, inside the field. Send while
                        // there is text; otherwise the current capture mode,
                        // which a right-swipe on this same icon cycles.
                        ComposerActionIcon(
                            hasText = draft.text.isNotBlank(),
                            mode = effectiveCaptureMode(captureMode, availableModes),
                            recording = recording,
                            busy = sending || voiceSending || attaching,
                            onSend = { send() },
                            onCycle = { next -> captureMode = next },
                            nextMode = { nextCaptureMode(captureMode, availableModes) },
                            onCapture = { mode ->
                                when (mode) {
                                    CaptureMode.Voice -> toggleRecording()
                                    else -> launchCapture(mode)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    if (emojiOpen) {
        EmojiPickerSheet(
            onPick = { editDraft(insertEmoji(draft, it)) },
            onDismiss = { emojiOpen = false },
        )
    }
}

/** The glyph for a capture mode. */
private fun captureIcon(mode: CaptureMode) = when (mode) {
    CaptureMode.Voice -> MicIcon
    CaptureMode.Photo -> PhotoCameraIcon
    CaptureMode.Video -> VideocamIcon
}

/** The string resource naming what a capture mode does. */
private fun captureLabel(mode: CaptureMode): Int = when (mode) {
    CaptureMode.Voice -> R.string.composer_record_voice
    CaptureMode.Photo -> R.string.composer_take_photo
    CaptureMode.Video -> R.string.composer_record_video
}

/**
 * The composer's single action control, inside the text field.
 *
 * One icon, three jobs:
 *  * **Send**, whenever there is text — that outranks everything.
 *  * **Tap** otherwise performs the current capture mode: start/stop a voice
 *    note, or open the camera for a photo or a video.
 *  * **Swipe right** cycles the mode (voice → photo → video → voice).
 *
 * Recording is tap-to-start / tap-to-send rather than press-and-hold, because a
 * hold and a swipe cannot share one target: holding to record would have to win
 * the gesture before a swipe could be recognised, so one of the two would
 * always lose. Tap and drag compose cleanly, and it matches what the Flutter
 * composer already chose for the same reason a mouse cannot press-and-hold
 * meaningfully.
 *
 * Swiping is not discoverable on its own, and it is unavailable to anyone using
 * a screen reader or a switch device — so the mode is also exposed as a
 * semantics custom action, which is what assistive tech offers instead.
 */
@Composable
private fun ComposerActionIcon(
    hasText: Boolean,
    mode: CaptureMode?,
    recording: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onCycle: (CaptureMode) -> Unit,
    nextMode: () -> CaptureMode?,
    onCapture: (CaptureMode) -> Unit,
) {
    // Accumulated horizontal travel of the current drag. Reset on each stop, so
    // a left-then-right wobble does not add up into a mode change.
    var dragX by remember { mutableStateOf(0f) }
    val swapTo = nextMode()
    val cycleLabel = swapTo?.let {
        stringResource(R.string.composer_swap_capture, stringResource(captureLabel(it)))
    }

    val icon = when {
        hasText -> Icons.AutoMirrored.Filled.Send
        recording -> StopIcon
        mode != null -> captureIcon(mode)
        // Nothing to capture on this device: a Send that is simply inert until
        // there is text, rather than a control that fails on tap.
        else -> Icons.AutoMirrored.Filled.Send
    }
    val label = when {
        hasText -> stringResource(R.string.composer_send)
        recording -> stringResource(R.string.composer_stop_recording)
        mode != null -> stringResource(captureLabel(mode))
        else -> stringResource(R.string.composer_send)
    }
    val tint = when {
        recording -> MaterialTheme.colorScheme.error
        hasText -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val enabled = !busy || recording
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag("dm-action")
            .clip(CircleShape)
            .semantics {
                // The swipe, offered to assistive tech as a real action.
                if (!hasText && swapTo != null && cycleLabel != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(cycleLabel) { onCycle(swapTo); true },
                    )
                }
            }
            .clickable(enabled = enabled) {
                when {
                    hasText -> onSend()
                    mode != null -> onCapture(mode)
                    else -> Unit
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = !hasText && swapTo != null && !recording,
                state = rememberDraggableState { delta -> dragX += delta },
                onDragStopped = {
                    // Rightwards only, and far enough to be deliberate rather
                    // than a slip while reaching for the icon.
                    if (dragX > SWIPE_THRESHOLD_PX) nextMode()?.let(onCycle)
                    dragX = 0f
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy && !recording) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

/**
 * How far right the finger has to travel to count as a mode swipe.
 *
 * In pixels rather than dp because [androidx.compose.foundation.gestures.draggable]
 * reports raw deltas; ~64px is comfortably past touch slop on every density the
 * app targets without demanding a full swipe across the field.
 */
private const val SWIPE_THRESHOLD_PX = 64f

/** Centred "Today" / "Yesterday" / "12 Jul 2026" pill between days. */
@Composable
private fun DaySeparator(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * "Unread messages" line marking where the reader left off.
 *
 * A full-width rule rather than a day-style pill: it is a boundary through the
 * thread, not a label on one message, and it has to be findable at a glance
 * after scrolling away from it.
 */
@Composable
private fun UnreadSeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        )
    }
}

/** A small quoted line rendered above a reply's own text. */
@Composable
private fun QuotedPreview(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ── Message requests (gate strangers until accepted) ──────────────────────────

/**
 * The message-requests inbox: strangers' first DMs, gated out of the chat list.
 * Accepting shares your @handle with them and moves the thread into Chats;
 * blocking drops their future messages.
 */
@Composable
fun RequestsScreen(
    chatTick: Int,
    onOpen: (peer: String, alias: String?, username: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var requests by remember { mutableStateOf<List<ComradeCore.MessageRequestInfo>?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(chatTick, reloadTick) {
        requests = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.messageRequestsTyped() }.getOrDefault(emptyList())
        }
    }

    val list = requests
    when {
        list == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        list.isEmpty() -> Box(
            modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No message requests.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(modifier.fillMaxSize()) {
            items(list, key = { it.peer }) { req ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PeerAvatar(shortNpub(req.peer), seed = req.peer)
                        Column(Modifier.weight(1f)) {
                            Text(
                                shortNpub(req.peer),
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                req.lastMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { ComradeCore.blockConversationTyped(req.peer) }
                                    }
                                    reloadTick++
                                }
                            },
                        ) { Text("Block") }
                        Button(
                            onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { ComradeCore.acceptRequestTyped(req.peer) }
                                            .isSuccess
                                    }
                                    reloadTick++
                                    if (ok) onOpen(req.peer, null, null)
                                }
                            },
                        ) { Text("Accept") }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
