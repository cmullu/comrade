package mullu.comrade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.PresenceMonitor
import mullu.comrade.R

/** The green of a live presence dot; muted grey when the peer isn't around. */
private val OnlineGreen = Color(0xFF10B981)

/**
 * A presence dot: green while the peer is online, a muted grey otherwise.
 * Drawn even when offline (rather than hidden) so "chosen, but not around"
 * and "not chosen at all" never look like the same thing.
 */
@Composable
fun PresenceDot(online: Boolean, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = if (online) {
                    OnlineGreen
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                },
                shape = CircleShape,
            ),
    )
}

/**
 * The comrades screen: choose who you want to know about, and see who is
 * around.
 *
 * Deliberately one screen for both halves — the toggles and the presence — so
 * the disclosure and its consequence are never in different places. The
 * explainer at the top is not decoration: presence is the one feature in this
 * app that tells someone else something about you, and the model (mutual, no
 * relay involved, expires on its own) is not guessable from a switch.
 */
@Composable
fun ComradesScreen(
    /** Reload trigger — the chat tick fires on every presence change. */
    chatTick: Int,
    onOpen: (peer: String, alias: String?, username: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var comrades by remember { mutableStateOf<List<ComradeCore.ComradeInfo>?>(null) }
    var contacts by remember { mutableStateOf<List<ComradeCore.ContactInfo>>(emptyList()) }
    var reloadTick by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val onlineNow by PresenceMonitor.online.collectAsState()

    LaunchedEffect(chatTick, reloadTick) {
        val (chosen, all) = withContext(Dispatchers.IO) {
            val chosen = runCatching { ComradeCore.comrades() }.getOrDefault(emptyList())
            val all = runCatching { ComradeCore.contacts() }.getOrDefault(emptyList())
            chosen to all
        }
        comrades = chosen
        contacts = all
        // Keep the app-wide dots in step with what this screen just read.
        PresenceMonitor.seed(chosen)
    }

    fun toggle(npub: String, enabled: Boolean) {
        error = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ComradeCore.setComradeTyped(npub, enabled) }
            }.onFailure { error = it.message ?: "Could not save." }
            reloadTick++
        }
    }

    val chosen = comrades
    if (chosen == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        return
    }
    val chosenKeys = chosen.map { it.npub }.toSet()
    val others = contacts.filterNot { it.npub in chosenKeys }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.comrades_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        error?.let { msg ->
            item {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (chosen.isEmpty()) {
            item {
                Text(
                    stringResource(
                        if (contacts.isEmpty()) R.string.comrades_no_contacts else R.string.comrades_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(chosen, key = { "comrade:" + it.npub }) { comrade ->
            // Prefer the live flow over the snapshot this screen loaded, so a
            // beacon arriving while it is open moves the dot immediately.
            val online = onlineNow[comrade.npub] ?: comrade.online
            ComradeRow(
                title = peerTitle(comrade.npub, comrade.alias, comrade.name),
                seed = comrade.npub,
                online = online,
                subtitle = presenceSubtitle(online, comrade.lastSeenAt, comrade.peerMarkedUs),
                checked = true,
                onCheckedChange = { toggle(comrade.npub, it) },
                onClick = { onOpen(comrade.npub, comrade.alias.ifBlank { null }, comrade.name) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
        if (others.isNotEmpty()) {
            item {
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(others, key = { "contact:" + it.npub }) { contact ->
                ComradeRow(
                    title = peerTitle(contact.npub, contact.alias, contact.name),
                    seed = contact.npub,
                    online = false,
                    subtitle = shortNpub(contact.npub),
                    showDot = false,
                    checked = false,
                    onCheckedChange = { toggle(contact.npub, it) },
                    onClick = { onOpen(contact.npub, contact.alias.ifBlank { null }, contact.name) },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

/** The presence line under a comrade's name — see [presenceLabelOf]. */
@Composable
private fun presenceSubtitle(online: Boolean, lastSeenAt: Long, peerMarkedUs: Boolean): String =
    when (presenceLabelOf(online, lastSeenAt, peerMarkedUs)) {
        PresenceLabel.Online -> stringResource(R.string.comrade_online)
        PresenceLabel.LastSeen -> stringResource(R.string.comrade_last_seen, relativeTime(lastSeenAt))
        PresenceLabel.AwaitingReciprocity -> stringResource(R.string.comrade_awaiting_reciprocity)
        PresenceLabel.NeverSeen -> stringResource(R.string.comrade_never_seen)
    }

@Composable
private fun ComradeRow(
    title: String,
    seed: String,
    online: Boolean,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    showDot: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            PeerAvatar(title, seed = seed, modifier = Modifier.clickable { onClick() })
            if (showDot) PresenceDot(online, size = 12.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .clickable { onClick() },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("comrade-toggle:$seed"),
        )
    }
}
