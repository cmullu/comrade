package mullu.comrade.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.attention.UsageStatsReader
import mullu.comrade.voice.OneShotRecognizer
import mullu.comrade.voice.VoiceModelMissingException
import mullu.comrade.voice.VoskModel

/** Self-reported mood markers, low → high. Stored as the emoji itself. */
private val Moods = listOf("😞", "😕", "😐", "🙂", "😄")

/**
 * The private journal — wellbeing pillar #1. Everything written here stays on
 * this device, sealed inside the encrypted store; nothing is ever published
 * to a relay. Supports typing or on-device Vosk dictation.
 *
 * Also carries the attention **mirror** ([MirrorCard], `docs/ATTENTION.md`
 * phase 1). That is deliberate placement rather than convenience: a screen-time
 * number is only useful next to somewhere to put what you make of it, and a
 * dashboard people open to feel bad about themselves is the thing this pillar
 * is treating. The mirror is opt-in and absent entirely until the user grants
 * usage access.
 */
@Composable
fun JournalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ComradeCore.JournalEntryInfo>?>(null) }
    // Mirror state. `mirrorTick` re-reads after returning from the system
    // permission screen or changing the marked-app list.
    var mirrorTick by remember { mutableStateOf(0) }
    var hasUsageAccess by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<ComradeCore.AttentionSummaryInfo?>(null) }
    var pickingApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var doomApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Coming back from the system usage-access screen must not leave the
    // invitation showing when the grant has just been made.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) mirrorTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Read usage, record today's rollup, and re-read the summary — all off the
    // main thread (queryEvents walks a day of events, and the record call
    // writes to the encrypted store).
    LaunchedEffect(mirrorTick) {
        val access = UsageStatsReader.hasAccess(context)
        hasUsageAccess = access
        if (!access) {
            summary = null
            return@LaunchedEffect
        }
        summary = withContext(Dispatchers.IO) {
            runCatching {
                val marked = ComradeCore.doomApps()
                doomApps = marked.toSet()
                UsageStatsReader.todayRollup(context, marked.toSet())?.let { rollup ->
                    ComradeCore.recordAttentionDayTyped(
                        date = UsageStatsReader.today(),
                        screenMinutes = rollup.screenMinutes,
                        pickups = rollup.pickups,
                        doomMinutes = rollup.doomMinutes,
                    )
                }
                ComradeCore.attentionSummaryTyped(UsageStatsReader.today())
            }.getOrNull()
        }
    }
    var draft by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ComradeCore.JournalEntryInfo?>(null) }
    // True while the mic tap is parked on the speech-model download dialog.
    var awaitingModel by remember { mutableStateOf(false) }

    suspend fun reload() {
        entries = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.journal() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun save() {
        val text = draft.trim()
        if (text.isEmpty() || saving) return
        saving = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ComradeCore.addJournalEntryTyped(text, mood) }
            }.onSuccess {
                draft = ""
                mood = null
                saving = false
                reload()
            }.onFailure {
                saving = false
                error = it.message ?: "Could not save."
            }
        }
    }

    fun dictate() {
        if (listening) return
        listening = true
        error = null
        OneShotRecognizer(context).listen(
            onText = { heard ->
                listening = false
                if (heard.isNotBlank()) draft = (draft.trim() + " " + heard).trim()
            },
            onError = {
                listening = false
                // Backstop: the model vanished between the gate below and
                // listening — offer the download rather than a dead end.
                if (it is VoiceModelMissingException) {
                    awaitingModel = true
                } else {
                    error = "Voice unavailable: ${it.message}"
                }
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) dictate() else error = "Microphone permission is needed to dictate."
    }

    fun dictateWithPermission() {
        // Dictation needs the offline model first — offer the one-time
        // download (no permission needed for that), then the mic permission.
        if (!VoskModel.isAvailable(context)) {
            awaitingModel = true
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) dictate() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (awaitingModel) {
        VoiceModelDownloadDialog(
            onReady = {
                awaitingModel = false
                dictateWithPermission()
            },
            onDismiss = { awaitingModel = false },
        )
    }

    if (pickingApps) {
        DoomAppPicker(
            apps = installedApps,
            selected = doomApps,
            onToggle = { pkg ->
                val updated = doomApps.toMutableSet().apply {
                    if (!add(pkg)) remove(pkg)
                }
                doomApps = updated
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { ComradeCore.setDoomAppsTyped(updated.toList()) }
                    }
                    // The marked-minutes line changes with the list.
                    mirrorTick++
                }
            },
            onDismiss = { pickingApps = false },
        )
    }

    val list = entries
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("What's on your mind?") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("journal-input"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Moods.forEach { m ->
                            FilterChip(
                                selected = mood == m,
                                onClick = { mood = if (mood == m) null else m },
                                label = { Text(m) },
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = { dictateWithPermission() },
                            enabled = !listening,
                            modifier = Modifier.testTag("journal-mic"),
                        ) {
                            Icon(
                                MicIcon,
                                contentDescription = "Dictate",
                                tint = if (listening) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        if (listening) {
                            Text(
                                "Listening…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { save() },
                            enabled = draft.isNotBlank() && !saving,
                            modifier = Modifier.testTag("journal-save"),
                        ) { Text(if (saving) "Saving…" else "Save") }
                    }
                    Text(
                        "Only on this phone, sealed by your passcode. Never posted, " +
                            "never uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // The mirror sits under the composer: the number is context for
        // writing, not the point of the screen.
        item(key = "mirror") {
            MirrorCard(
                summary = summary,
                hasAccess = hasUsageAccess,
                onEnable = {
                    runCatching { context.startActivity(UsageStatsReader.accessSettingsIntent()) }
                        .onFailure { error = "Couldn't open the system settings screen." }
                },
                onPickApps = {
                    scope.launch {
                        installedApps = withContext(Dispatchers.IO) { launchableApps(context) }
                        pickingApps = true
                    }
                },
            )
        }

        when {
            list == null -> item {
                CircularProgressIndicator(
                    Modifier
                        .padding(top = 24.dp)
                        .size(28.dp),
                )
            }
            list.isEmpty() -> item {
                Text(
                    "Nothing yet. A line a day is plenty — write or dictate " +
                        "whatever is on your mind.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
            }
            else -> {
                val now = System.currentTimeMillis() / 1000
                list.groupBy { dayLabel(it.createdAt, now) }.forEach { (day, dayEntries) ->
                    item(key = "day:$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(dayEntries, key = { it.id }) { entry ->
                        JournalEntryCard(entry, onDelete = { confirmDelete = entry })
                    }
                }
            }
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("It will be removed from this phone. There is no other copy.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { ComradeCore.deleteJournalEntryTyped(entry.id) }
                            }
                            reload()
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun JournalEntryCard(
    entry: ComradeCore.JournalEntryInfo,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entry.mood?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(
                        relativeTime(entry.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete entry",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Installed, launchable apps as `(packageName, label)`, alphabetically —
 * the candidate list for the doom-app picker.
 *
 * Deliberately only apps with a launcher entry: system services and libraries
 * are not things anyone chooses to open, so listing them would bury the handful
 * of apps the question is actually about. Comrade's own package is excluded for
 * the same reason [UsageMirror] excludes it from the figures.
 *
 * Reads the package manager, so callers must be off the main thread.
 */
internal fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != context.packageName }
            .distinct()
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                pkg to label
            }
            .sortedBy { it.second.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
