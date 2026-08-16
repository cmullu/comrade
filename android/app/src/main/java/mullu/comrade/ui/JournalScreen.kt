package mullu.comrade.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.ComradeCore
import mullu.comrade.R
import mullu.comrade.attention.UsageStatsReader
import mullu.comrade.journal.AdoptedVideo
import mullu.comrade.journal.JOURNAL_VIDEO_MIME
import mullu.comrade.journal.JournalVideoStore
import mullu.comrade.journal.journalVideoHeading
import mullu.comrade.journal.journalVideoTitle
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
    // The entry whose share sheet is open, the people it may go to, and what
    // happened to the last send. `shareOptions == null` means "still loading
    // contacts" — distinct from "you have saved nobody", which has its own copy.
    var sharing by remember { mutableStateOf<ComradeCore.JournalEntryInfo?>(null) }
    var shareOptions by remember { mutableStateOf<List<ShareTarget>?>(null) }
    var sendingTo by remember { mutableStateOf<String?>(null) }
    var shareResult by remember { mutableStateOf<String?>(null) }
    // True while the mic tap is parked on the speech-model download dialog.
    var awaitingModel by remember { mutableStateOf(false) }
    // ── Video journal state ────────────────────────────────────────────────
    // The recording that has been made but not yet saved: the title dialog is
    // up over it, and until it resolves the file is an orphan on disk.
    var pendingVideo by remember { mutableStateOf<AdoptedVideo?>(null) }
    var savingVideo by remember { mutableStateOf(false) }
    // The entry whose recording is playing, and the one being retitled.
    var playing by remember { mutableStateOf<ComradeCore.JournalEntryInfo?>(null) }
    var renaming by remember { mutableStateOf<ComradeCore.JournalEntryInfo?>(null) }
    // Which recordings are actually on disk, by file name. A card must not
    // offer a tap that opens an empty player, and the entry alone cannot say:
    // the record and the footage are two separate deletes (AUDIT J-1).
    var playableFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Capability-gated, like the composer's capture modes: a device with no
    // camera gets no record button rather than one that fails on tap.
    val canRecordVideo = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.journal() }.getOrDefault(emptyList())
        }
        entries = loaded
        val referenced = loaded.mapNotNull { it.video?.fileName }.toSet()
        playableFiles = withContext(Dispatchers.IO) {
            referenced.filter { JournalVideoStore.fileFor(context, it) != null }.toSet()
        }
    }
    LaunchedEffect(Unit) { reload() }

    // Recordings nothing points at, cleaned up once per visit to this screen.
    //
    // Deleting a video entry is two writes — the sealed record, then the file —
    // and anything that kills the app between them leaves footage the user can
    // neither see nor remove. This is what finds it.
    //
    // The record button stays disabled until this finishes, and that is not
    // politeness: a recording is unreferenced between `adopt` and the entry
    // being saved, so a sweep overlapping a capture would delete exactly the
    // clip the user just made. Gating on `sweeping` makes "no capture during
    // the sweep" a fact rather than a race that is usually won — and the wait
    // is a directory listing and one store read, so nobody sees it.
    var sweeping by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                JournalVideoStore.discardStaleCaptures(context)
                val referenced = ComradeCore.journal().mapNotNull { it.video?.fileName }.toSet()
                JournalVideoStore.sweepOrphans(context, referenced)
            }
        }
        sweeping = false
    }

    // ── Recording a video entry ─────────────────────────────────────────────
    //
    // The camera app writes into `cache/journal-capture/` through the existing
    // FileProvider; the finished file is moved into the journal's own folder
    // under `filesDir` (JournalVideoStore), which no gallery on the phone can
    // see and no other app can open. The camera is only ever granted the one
    // file it is writing, never the directory of everything already recorded.
    var captureTarget by remember { mutableStateOf<File?>(null) }

    fun captureFinished(ok: Boolean) {
        val file = captureTarget
        captureTarget = null
        if (file == null) return
        if (!ok) {
            // Backed out of the camera. Nothing was recorded and nothing is kept.
            scope.launch { withContext(Dispatchers.IO) { file.delete() } }
            return
        }
        scope.launch {
            val adopted = withContext(Dispatchers.IO) {
                runCatching {
                    JournalVideoStore.adopt(
                        context,
                        file,
                        createdAtMs = System.currentTimeMillis(),
                        nonce = System.nanoTime(),
                    )
                }.getOrNull()
            }
            if (adopted == null) {
                error = context.getString(R.string.journal_video_capture_failed)
            } else {
                pendingVideo = adopted
            }
        }
    }

    val recordVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { ok -> captureFinished(ok) }

    fun startRecording() {
        error = null
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    JournalVideoStore.newCaptureFile(context, System.nanoTime())
                }.getOrNull()
            }
            if (target == null) {
                error = context.getString(R.string.journal_video_capture_failed)
                return@launch
            }
            captureTarget = target
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            runCatching { recordVideo.launch(uri) }.onFailure {
                // No camera app on the device. Say so rather than leaving the
                // button looking broken, and drop the file we just made.
                captureTarget = null
                withContext(Dispatchers.IO) { target.delete() }
                error = context.getString(R.string.journal_video_no_camera)
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            error = context.getString(R.string.journal_video_needs_camera)
        }
    }

    fun recordWithPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    /**
     * Keep the pending recording as an entry, under [rawTitle].
     *
     * The words in the composer come with it, so "record this, and here is what
     * I could not say to camera" is one entry rather than two — and the mood
     * chips apply to it exactly as they do to a typed entry.
     */
    fun keepRecording(rawTitle: String) {
        val video = pendingVideo ?: return
        if (savingVideo) return
        savingVideo = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ComradeCore.addJournalVideoTyped(
                        title = journalVideoTitle(rawTitle),
                        text = draft.trim(),
                        mood = mood,
                        fileName = video.fileName,
                        mime = JOURNAL_VIDEO_MIME,
                        durationMs = video.durationMs,
                        sizeBytes = video.sizeBytes,
                    )
                }
            }.onSuccess {
                pendingVideo = null
                savingVideo = false
                draft = ""
                mood = null
                reload()
            }.onFailure {
                savingVideo = false
                // The file is still there and the dialog stays up, so a locked
                // vault or a full disk costs a retry rather than the recording.
                error = it.message ?: context.getString(R.string.journal_video_capture_failed)
            }
        }
    }

    /** Throw the pending recording away — the file, not just the dialog. */
    fun discardRecording() {
        val video = pendingVideo ?: return
        if (savingVideo) return
        pendingVideo = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { JournalVideoStore.delete(context, video.fileName) }
            }
        }
    }

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
                        // Recording sits next to dictation, not behind a menu:
                        // the two are the same gesture — say it instead of
                        // typing it — and one of them being a second-class
                        // control would say the other is the real journal.
                        if (canRecordVideo) {
                            IconButton(
                                onClick = { recordWithPermission() },
                                enabled = !sweeping && pendingVideo == null && !savingVideo,
                                modifier = Modifier.testTag("journal-record"),
                            ) {
                                Icon(
                                    VideocamIcon,
                                    contentDescription = stringResource(
                                        R.string.journal_video_record,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
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
                    // Sharing made the old wording ("never posted, never
                    // uploaded", full stop) a promise the app no longer keeps
                    // on its own terms, so it says who the exception belongs
                    // to: nothing moves unless the person who wrote it moves it.
                    //
                    // Video entries put a second qualifier on it. "Sealed by
                    // your passcode" is true of what you write and NOT of the
                    // footage (AUDIT J-1), so the sentence now says which is
                    // which rather than covering both with the stronger claim.
                    // Do not shorten this back until J-1 closes.
                    Text(
                        if (canRecordVideo) {
                            "Only on this phone. What you write is sealed by your passcode; " +
                                "a recording is kept in Comrade's own folder, out of your " +
                                "gallery. Never posted, never uploaded — it reaches someone " +
                                "only if you send it to them yourself."
                        } else {
                            "Only on this phone, sealed by your passcode. Never posted, " +
                                "never uploaded — a note reaches someone only if you send " +
                                "it to them yourself."
                        },
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
                        JournalEntryCard(
                            entry,
                            videoPlayable = entry.video?.let { it.fileName in playableFiles }
                                ?: false,
                            dayLabel = day,
                            onPlay = { playing = entry },
                            onRename = { renaming = entry },
                            onShare = { sharing = entry },
                            onDelete = { confirmDelete = entry },
                        )
                    }
                }
            }
        }
    }

    sharing?.let { entry ->
        // Contacts are read per open rather than held: the list changes from
        // the Chats tab, and a stale picker is a note sent to a name that is no
        // longer the one on screen.
        LaunchedEffect(entry.id) {
            shareOptions = withContext(Dispatchers.IO) {
                runCatching {
                    shareTargets(
                        ComradeCore.contacts().map {
                            ShareCandidate(
                                npub = it.npub,
                                alias = it.alias,
                                name = it.name,
                                comrade = it.comrade,
                            )
                        },
                    )
                }.getOrDefault(emptyList())
            }
        }
        JournalShareSheet(
            targets = shareOptions,
            sendingTo = sendingTo,
            onPick = { target ->
                if (sendingTo != null) return@JournalShareSheet
                sendingTo = target.npub
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            ComradeCore.shareJournalEntryTyped(target.npub, entry.id)
                        }
                    }
                    sendingTo = null
                    shareResult = outcome.fold(
                        onSuccess = { context.getString(R.string.journal_share_sent, target.label) },
                        onFailure = {
                            context.getString(
                                R.string.journal_share_failed,
                                it.message ?: "unknown error",
                            )
                        },
                    )
                    // Close on success only: a failed send leaves the sheet up
                    // so the same note can go to the same person again without
                    // finding it in the list a second time.
                    if (outcome.isSuccess) {
                        sharing = null
                        shareOptions = null
                    }
                }
            },
            onDismiss = {
                if (sendingTo == null) {
                    sharing = null
                    shareOptions = null
                }
            },
        )
    }

    shareResult?.let { message ->
        AlertDialog(
            onDismissRequest = { shareResult = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { shareResult = null }) { Text("OK") }
            },
        )
    }

    // Name the recording just made. No dismiss-by-tapping-outside: the file is
    // on disk and unreferenced until this resolves, so both ways out are
    // deliberate — keep it, or throw it away.
    pendingVideo?.let {
        JournalVideoTitleDialog(
            initial = "",
            saving = savingVideo,
            onSave = { keepRecording(it) },
            onDismiss = { /* Answer the dialog; there is a recording waiting. */ },
            onDiscard = { discardRecording() },
        )
    }

    renaming?.let { entry ->
        JournalVideoTitleDialog(
            initial = entry.title.orEmpty(),
            saving = savingVideo,
            onSave = { raw ->
                // Guarded rather than early-returned: a second tap on a slow
                // store must not start a second write of the same title.
                if (!savingVideo) {
                    savingVideo = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                ComradeCore.setJournalEntryTitleTyped(
                                    entry.id,
                                    journalVideoTitle(raw),
                                )
                            }
                        }
                        savingVideo = false
                        renaming = null
                        reload()
                    }
                }
            },
            onDismiss = { if (!savingVideo) renaming = null },
        )
    }

    playing?.let { entry ->
        val video = entry.video
        if (video != null) {
            JournalVideoPlayerDialog(
                fileName = video.fileName,
                heading = journalVideoHeading(
                    entry.title,
                    dayLabel(entry.createdAt, System.currentTimeMillis() / 1000),
                ),
                onDismiss = { playing = null },
            )
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this entry?") },
            text = {
                Text(
                    if (entry.video != null) {
                        stringResource(R.string.journal_video_delete_body)
                    } else {
                        "It will be removed from this phone. There is no other copy."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { ComradeCore.deleteJournalEntryTyped(entry.id) }
                                // The record first, then the file. A kill in
                                // between leaves an orphaned file the sweep
                                // finds on the next open — the other order
                                // would leave an entry pointing at nothing.
                                entry.video?.let {
                                    runCatching {
                                        JournalVideoStore.delete(context, it.fileName)
                                    }
                                }
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
    videoPlayable: Boolean,
    dayLabel: String,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val video = entry.video
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
                // A titled entry leads with its title. Untitled ones draw no
                // heading at all rather than a placeholder — the vast majority
                // of typed entries have none, and a card headed "Untitled"
                // every time is noise.
                val heading = if (video != null) {
                    journalVideoHeading(entry.title, dayLabel)
                } else {
                    entry.title
                }
                heading?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("journal-entry-title"),
                    )
                }
                if (video != null) {
                    JournalVideoStrip(
                        video = video,
                        available = videoPlayable,
                        onPlay = onPlay,
                        modifier = Modifier.padding(top = 2.dp, end = 6.dp),
                    )
                }
                // A video entry with no words has nothing to draw here, and an
                // empty Text would still take a line's worth of space.
                if (entry.text.isNotBlank()) {
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (video != null) {
                IconButton(onClick = onRename, modifier = Modifier.testTag("journal-rename")) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.journal_video_rename),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            // Sharing sends the words, never the recording (the core refuses an
            // entry with none). A control that cannot work is not offered.
            if (entry.text.isNotBlank()) {
                IconButton(onClick = onShare, modifier = Modifier.testTag("journal-share")) {
                    Icon(
                        ShareIcon,
                        contentDescription = stringResource(R.string.journal_share),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
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
 * Pick the one person this note goes to.
 *
 * A dialog rather than the system share sheet, and that is the point: Android's
 * sheet would offer every app on the phone a plaintext copy of the most private
 * thing Comrade holds. The list here is Comrade's own contacts, the note travels
 * as an encrypted DM, and nothing else on the device ever sees it.
 *
 * One tap sends — there is no second "confirm" step, because the sheet was
 * opened deliberately from one entry and the row says who it goes to. The tap
 * that sends is disabled while a send is in flight, so a slow relay cannot turn
 * an impatient second tap into a second copy.
 */
@Composable
private fun JournalShareSheet(
    targets: List<ShareTarget>?,
    sendingTo: String?,
    onPick: (ShareTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.journal_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.journal_share_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    targets == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    targets.isEmpty() -> Text(
                        stringResource(R.string.journal_share_no_contacts),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // A scrolling `Column`, not a `LazyColumn`: a dialog gives
                    // its content unbounded height, which is the one thing a
                    // lazy list cannot be measured in. Contact lists are small.
                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("journal-share-targets"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        targets.forEach { target ->
                            TextButton(
                                onClick = { onPick(target) },
                                enabled = sendingTo == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(target.label, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.weight(1f))
                                    if (sendingTo == target.npub) {
                                        Text(
                                            stringResource(R.string.journal_share_sending),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = sendingTo == null) { Text("Cancel") }
        },
    )
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
