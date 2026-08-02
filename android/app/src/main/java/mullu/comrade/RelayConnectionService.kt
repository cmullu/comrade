package mullu.comrade

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mullu.comrade.attention.QuietHours
import mullu.comrade.call.CallManager
import mullu.comrade.call.CallUiState
import mullu.comrade.ui.peerTitle
import mullu.comrade.ui.shortNpub
import uniffi.comrade_ui.BridgeEvent

/**
 * Keeps the relay connection (and therefore notification delivery) alive
 * while the vault is unlocked but no Activity is visible — an accepted DM or
 * incoming call must still surface a notification 30 minutes into the
 * background, which a plain Activity-scoped coroutine cannot guarantee: once
 * nothing is visible, the process is "cached" priority and the OS can and
 * will reclaim it at any time. A foreground service (with the ongoing,
 * deliberately minimal notification Android requires for one) buys the
 * process a real priority floor for as long as this runs.
 *
 * It is **not** where the native event queue is drained. That loop lives in
 * [EventPump], which any component can hold open — this service while it
 * runs, an Activity while it is visible. The distinction matters because this
 * service is gated on a user preference: when it owned the loop, turning
 * "stay connected in the background" off stopped delivery *entirely*, even
 * with the app on screen, which is the opposite of what that setting says.
 * [EventPump] still guarantees exactly one loop no matter how many holders
 * there are, so the property this service was introduced for — an Activity
 * recreation can never duplicate listeners or notifications — still holds
 * structurally.
 *
 * ## Lifecycle
 * Started ([start]) once the vault is unlocked (see `ComradeApp`'s
 * `AppPhase.Ready` transition) — a no-op if the user has turned the feature
 * off (see [BackgroundConnectivityPreference]). Stopped ([stop]) on vault
 * lock, an explicit disconnect, or logout — today the app only has "lock
 * vault", but the same call covers whichever of those a future screen adds.
 * Starting it twice, or stopping it when not running, is harmless.
 *
 * ## Security boundary — read this before assuming more than it promises
 * This service supports **backgrounded-but-unlocked** operation: the vault's
 * decrypted key stays in the native process's memory, exactly as it would
 * with the app merely open, for as long as the OS keeps this process alive.
 * It does **not** change what happens on process death (planned or OOM-kill)
 * — the in-memory key is gone either way, same as before this service
 * existed, and the app returns to the locked/passphrase screen on next
 * launch. It does **not** implement push notifications: nothing here wakes a
 * *killed* process. Delivering a message while the process is not merely
 * backgrounded but actually dead needs a push-notification wakeup path — a
 * separate product and privacy decision (a push token identifies the device
 * to whatever relay/push-provider sends it, which is a real metadata
 * tradeoff for a privacy-first app), deliberately out of scope here.
 */
class RelayConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotified()
        // Delivery must outlive the visible app from here on.
        EventPump.acquire(applicationContext, PumpHolder.SERVICE)
        // Only ever started after an unlock, so the store is readable now.
        scope.launch { ChatEventRouter.seedFromStore() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // A visible Activity may still need the loop; the pump decides.
        EventPump.release(PumpHolder.SERVICE)
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotified() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Deliberately minimal: no peer names, no message previews, no
        // counts — just "Comrade is running", matching the notification
        // content rules the message/request/call notifications already
        // follow (see Notifier's doc comment).
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.relay_connection_notification_title))
            .setContentText(getString(R.string.relay_connection_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0xC0A1EC7

        /** Start the service — a no-op if the user has disabled the feature. */
        fun start(context: Context) {
            if (!BackgroundConnectivityPreference.isEnabled(context)) return
            context.startForegroundService(Intent(context, RelayConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayConnectionService::class.java))
        }
    }
}

/**
 * Whether the user wants [RelayConnectionService] to run at all — default
 * on, since the acceptance bar for this feature ("an accepted DM notifies
 * you 30 minutes into the background") only holds if it's running, but the
 * persistent low-priority notification and background battery use are a
 * real, visible tradeoff a user should be able to opt out of.
 */
object BackgroundConnectivityPreference {
    private const val PREFS_NAME = "comrade_prefs"
    private const val KEY_ENABLED = "background_connectivity_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/**
 * App-level (not Activity-level) home for state derived from the native
 * event stream, and for the notification-triggering side effects that must
 * keep working without a visible Activity. [RelayConnectionService.pump] is
 * the only caller of [route]; every screen instead collects the `StateFlow`s
 * below, the same pattern [MeshStatusMonitor] and
 * [mullu.comrade.call.CallManager] already use.
 */
object ChatEventRouter {
    /** Bound on the in-memory public feed (the relay stream is unbounded). */
    private const val FEED_CAP = 500

    /** Floor between peer-name refreshes; the Rust side is TTL-gated too. */
    private const val NAME_REFRESH_MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _feedItems = MutableStateFlow<List<ComradeCore.ChitthiInfo>>(emptyList())
    val feedItems: StateFlow<List<ComradeCore.ChitthiInfo>> = _feedItems.asStateFlow()
    private val seenFeedIds = HashSet<String>()

    /** Bumped whenever the DM history changed; list + open thread reload on it. */
    private val _chatTick = MutableStateFlow(0)
    val chatTick: StateFlow<Int> = _chatTick.asStateFlow()

    /**
     * Force a chat-list reload from outside the event-routing path — e.g. a
     * contact alias was edited locally, which changes chat-list titles
     * without any native event having fired.
     */
    fun bumpChatTick() {
        _chatTick.update { it + 1 }
    }

    /** Bumped when a new message request arrives; the requests list reloads on it. */
    private val _requestTick = MutableStateFlow(0)
    val requestTick: StateFlow<Int> = _requestTick.asStateFlow()

    /**
     * The peer (npub) of the conversation currently on screen, if any — set
     * by [mullu.comrade.MainActivity] so a DM notification is suppressed for
     * the thread the user is already looking at, exactly as before this
     * lived in the Activity's own pump loop.
     */
    private val _openConversationPeer = MutableStateFlow<String?>(null)
    fun setOpenConversation(peer: String?) {
        _openConversationPeer.value = peer
    }

    @Volatile private var refreshingNames = false
    @Volatile private var lastNameRefreshAt = 0L

    /** Add a freshly-arrived (or cached, on seed) Chitthi to the front of the feed, capped at [FEED_CAP]. */
    fun addChitthi(item: ComradeCore.ChitthiInfo, front: Boolean = true) {
        if (!seenFeedIds.add(item.id)) return
        _feedItems.update { current ->
            val updated = if (front) listOf(item) + current else current + item
            if (updated.size > FEED_CAP) {
                val dropped = if (front) updated.last() else updated.first()
                seenFeedIds.remove(dropped.id)
                if (front) updated.dropLast(1) else updated.drop(1)
            } else {
                updated
            }
        }
    }

    /** Offline-first seed of the cached feed, oldest-loaded-last so it renders newest-first. */
    fun seedCachedFeed(cached: List<ComradeCore.ChitthiInfo>) {
        for (item in cached.sortedByDescending { it.createdAt }) addChitthi(item, front = false)
    }

    /**
     * Fill the observable state from what the encrypted store already knows,
     * so the first frame after an unlock is right rather than empty: the
     * cached public feed, the mesh indicator, and the comrade dots (a beacon
     * that arrived before this launch may still be live).
     *
     * Called on the vault-unlocked transition and by
     * [RelayConnectionService.onStartCommand]; safe to run more than once —
     * feed items dedup by id, and the two monitors take whole snapshots.
     * Every read is `runCatching`-guarded, so a locked or busy store degrades
     * to "nothing seeded" instead of failing the caller.
     */
    suspend fun seedFromStore() {
        val cached = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.sabhaTimeline() }.getOrDefault(emptyList())
        }
        seedCachedFeed(cached)

        val initialMesh = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.meshStatusTyped() }
                .getOrDefault(ComradeCore.MeshStatus(active = false, peerCount = 0))
        }
        MeshStatusMonitor.update(initialMesh)

        val comrades = withContext(Dispatchers.IO) {
            runCatching { ComradeCore.comrades() }.getOrDefault(emptyList())
        }
        PresenceMonitor.seed(comrades)

        maybeRefreshNames()
    }

    /**
     * Fetch peers' published @handles so chats are titled by name instead of
     * key — single-flight and rate-limited (the Rust side is also
     * TTL-gated), and never awaited by [RelayConnectionService.pump], so a
     * slow relay can't stall event draining.
     */
    fun maybeRefreshNames() {
        val now = System.currentTimeMillis()
        if (refreshingNames || now - lastNameRefreshAt < NAME_REFRESH_MIN_INTERVAL_MS) return
        refreshingNames = true
        lastNameRefreshAt = now
        scope.launch {
            try {
                val changed = withContext(Dispatchers.IO) {
                    runCatching { ComradeCore.refreshPeerProfilesTyped() }.getOrDefault(0)
                }
                if (changed > 0) _chatTick.update { it + 1 }
            } finally {
                refreshingNames = false
            }
        }
    }

    /**
     * How a peer should be titled in a notification: the alias the user gave
     * them, else the @handle they published, else the shortened key — the
     * same precedence [mullu.comrade.ui.peerTitle] applies on every screen,
     * so the shade and the app never disagree about who someone is.
     *
     * Contacts first because that read is a small tree; the conversation list
     * is only consulted for a peer who was accepted but never saved (an
     * accepted message request), and both fall back to the key on any
     * failure — a notification must never be lost to a naming lookup.
     */
    fun peerLabel(peer: String): String {
        val contact = runCatching { ComradeCore.contacts() }.getOrDefault(emptyList())
            .find { it.npub == peer }
        if (contact != null) return peerTitle(peer, contact.alias, contact.name)
        val convo = runCatching { ComradeCore.conversations() }.getOrDefault(emptyList())
            .find { it.peer == peer }
        return peerTitle(peer, convo?.alias, convo?.peerName)
    }

    /**
     * Whether a message from [peer] may raise a notification — the thread on
     * screen, the per-conversation mute, and the nightly quiet window, in one
     * place so DMs and attachments cannot drift apart. The rule itself is
     * [NotificationPolicy.shouldNotifyMessage].
     */
    private fun mayNotify(context: Context, peer: String): Boolean =
        NotificationPolicy.shouldNotifyMessage(
            peer = peer,
            openConversationPeer = _openConversationPeer.value,
            muted = MutedChats.isMuted(context, peer),
            quietHours = inQuietHours(context),
        )

    /**
     * Whether Comrade's own nightly quiet window is open right now.
     *
     * Read from the local wall clock at the moment of the decision, not cached:
     * this process can outlive a whole night (the connection service is
     * deliberately long-lived), so a value resolved at startup would have the
     * window permanently wrong. See [mullu.comrade.attention.QuietHours].
     */
    private fun inQuietHours(context: Context): Boolean {
        val now = java.util.Calendar.getInstance()
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        return QuietHours.isQuietNow(context, minute)
    }

    private fun uniffi.comrade_ui.ChitthiDto.toInfo() = ComradeCore.ChitthiInfo(
        id = id,
        author = author,
        content = content,
        createdAt = createdAt.toLong(),
        replyTo = replyTo,
    )

    /** Route one drained [BridgeEvent]: update shared state and fire any notification. */
    fun route(context: Context, event: BridgeEvent) {
        when (event) {
            is BridgeEvent.IncomingChitthi -> addChitthi(event.v1.toInfo(), front = true)
            is BridgeEvent.IncomingDirectMessage -> {
                _chatTick.update { it + 1 }
                val peer = event.v1.sender
                if (mayNotify(context, peer)) {
                    Notifier.notifyMessage(
                        context,
                        peer,
                        peerLabel(peer),
                        event.v1.content.ifBlank { "New message" },
                    )
                }
            }
            is BridgeEvent.IncomingMessageRequest -> {
                _requestTick.update { it + 1 }
                // The requests bucket still fills during quiet hours — only the
                // buzz waits for morning.
                if (NotificationPolicy.shouldNotifyRequest(inQuietHours(context))) {
                    Notifier.notifyRequest(
                        context,
                        event.v1.peer,
                        event.v1.lastMessage.ifBlank { "New message request" },
                    )
                }
            }
            is BridgeEvent.IncomingMedia -> {
                _chatTick.update { it + 1 }
                val peer = event.v1.sender
                if (mayNotify(context, peer)) {
                    Notifier.notifyMessage(
                        context,
                        peer,
                        peerLabel(peer),
                        "📎 " + event.v1.caption.ifBlank { "Attachment" },
                    )
                }
            }
            is BridgeEvent.MessageStatus -> {
                _chatTick.update { it + 1 }
            }
            is BridgeEvent.PeerProfileUpdated -> {
                _chatTick.update { it + 1 }
                // A DM from an unknown key may now be nameable.
                maybeRefreshNames()
            }
            is BridgeEvent.ComradePresence -> {
                val becameOnline = PresenceMonitor.update(
                    peer = event.peer,
                    online = event.online,
                    at = event.at.toLong(),
                )
                // The chat list carries the dot too, so it has to re-read.
                _chatTick.update { it + 1 }
                if (!event.online) {
                    // They left before the user got to the notice — a shade
                    // still saying "Ana is online" would invite a call to
                    // someone who isn't there.
                    Notifier.clearComradeOnline(context, event.peer)
                } else if (
                    NotificationPolicy.shouldNotifyPresence(
                        peer = event.peer,
                        openConversationPeer = _openConversationPeer.value,
                        muted = MutedChats.isMuted(context, event.peer),
                        becameOnline = becameOnline,
                        quietHours = inQuietHours(context),
                    )
                ) {
                    // Don't tell someone their comrade is around while they
                    // are literally looking at that conversation — same rule
                    // the DM notification follows. The event's own name is
                    // the core's view at send time; fall back to a fresh
                    // store lookup so the title is a name whenever one
                    // exists at all.
                    val title = event.name?.takeIf { it.isNotBlank() } ?: peerLabel(event.peer)
                    Notifier.notifyComradeOnline(context, event.peer, title)
                }
            }
            is BridgeEvent.ComradeNudge -> {
                // Nothing to record: a nudge is not presence state, so it
                // moves no dot and advances no "last seen" — the core keeps
                // those to beacons alone. It is one notification and nothing
                // else.
                if (
                    NotificationPolicy.shouldNotifyNudge(
                        peer = event.peer,
                        openConversationPeer = _openConversationPeer.value,
                        muted = MutedChats.isMuted(context, event.peer),
                        quietHours = inQuietHours(context),
                    )
                ) {
                    val title = event.name?.takeIf { it.isNotBlank() } ?: peerLabel(event.peer)
                    Notifier.notifyComradeNudge(context, event.peer, title)
                }
            }
            is BridgeEvent.MeshStatusChanged -> MeshStatusMonitor.update(
                ComradeCore.MeshStatus(active = event.v1.active, peerCount = event.v1.peerCount.toInt()),
            )
            is BridgeEvent.IncomingCallSignal -> {
                // Feed every signal into the WebRTC layer (answers + ICE land
                // in the live PeerConnection); a fresh incoming offer returns
                // true → raise the ringing notification so a call is visible
                // even when the app isn't in the foreground.
                val freshIncoming = CallManager.onIncomingSignal(event.v1)
                if (freshIncoming) {
                    // CallManager already resolved the caller's alias/published
                    // name (the same precedence the chat list and call history
                    // use) into the ringing state's peerLabel — read it back
                    // instead of falling to the bare key here too, so the
                    // notification and the ringing screen agree.
                    val title = (CallManager.state.value as? CallUiState.Ringing)?.peerLabel
                        ?: shortNpub(event.v1.peer)
                    Notifier.notifyIncomingCall(
                        context,
                        event.v1.peer,
                        title,
                        video = event.v1.media == "video",
                    )
                }
            }
            // Sakha/ledger sync isn't wired into the Android UI yet
            // (desktop-only via Tauri commands) — drop, like before.
            is BridgeEvent.LedgerUpdated -> Unit
        }
    }
}
