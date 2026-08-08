package mullu.comrade.together

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log

/**
 * Reaching whatever the phone is already playing, whatever app that is.
 *
 * `docs/TOGETHER.md` §13. Android publishes every app's media session
 * system-wide, so one implementation reaches Spotify, YouTube Music, a podcast
 * app, VLC and whatever else is installed — with no OAuth, no client id and no
 * vendored binary. It is the posture a car head unit or a watch companion takes,
 * and Comrade never learns what the other app is or where its bytes came from.
 *
 * **Every decision in here lives in [MediaSessionDecisions]**, which has no
 * Android imports and therefore runs under `kotlinc` in the sandbox. This file
 * is the boundary: it reads the framework's types and flattens them to the
 * strings and booleans that file takes, and it is the *only* place the
 * `PlaybackState.STATE_*` ints are mentioned. That is not tidiness — it is what
 * keeps the decisions checkable on the frontend that cannot otherwise be built
 * before CI.
 */
object MediaSessionAccess {

    private const val TAG = "MediaSessionAccess"

    /** The component the platform checks the grant against. */
    private fun component(context: Context) =
        ComponentName(context, MediaSessionListenerService::class.java)

    /**
     * Whether the user has granted notification-listener access.
     *
     * Read from the secure setting rather than from a permission check, for the
     * same reason `UsageStatsReader.hasAccess` uses an app-op: a special access
     * is not a permission, so `checkSelfPermission` would answer "granted" for
     * the manifest declaration whether or not anyone ever allowed it — and the
     * feature would silently find no sessions and look broken.
     */
    fun hasAccess(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        if (enabled.isEmpty()) return false
        val ours = component(context).flattenToString()
        val oursShort = component(context).flattenToShortString()
        // The setting is a colon-separated list of flattened components, and
        // which of the two spellings the platform wrote depends on the version.
        // Matched exactly against a split rather than by `contains`, because
        // another app whose component name merely ends with ours would
        // otherwise read as our grant.
        return enabled.split(':').any { it == ours || it == oursShort }
    }

    /**
     * The system screen where it is granted.
     *
     * **It cannot be requested in-app** — there is no dialog and no
     * `requestPermissions` for this one — so the caller has to explain *before*
     * sending someone here and check [hasAccess] again when they come back.
     * That is a UI obligation this function cannot enforce, which is why it is
     * stated here rather than left to whoever calls it.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Every active session on the device, as controllers.
     *
     * Empty rather than throwing when access has not been granted: "no external
     * session" is a first-class answer here — it is what
     * [PlaybackModeDecision.ownershipFor] reads as "we cannot follow this", and
     * a refused grant must produce that rather than a crash.
     */
    fun controllers(context: Context): List<MediaController> {
        if (!hasAccess(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        // `getActiveSessions` throws `SecurityException` when the listener is
        // not enabled, which the check above should already have caught — but a
        // grant can be revoked between the two, and a crash there would be a
        // crash caused by the user changing their mind in system settings.
        return runCatching { manager.getActiveSessions(component(context)) }
            .onFailure { Log.w(TAG, "could not read active sessions", it) }
            .getOrNull()
            .orEmpty()
    }

    /**
     * Flatten a controller into the facts a decision needs.
     *
     * `null` when the session has no `PlaybackState` at all — an app that has
     * registered a session and never described it has told us nothing to follow,
     * which is [MediaSessionDecisions.sameTrack]'s rule about silence applied
     * one level up.
     */
    fun candidateOf(controller: MediaController, nowMs: Long): MediaSessionDecisions.Candidate? {
        val state = controller.playbackState ?: return null
        val metadata = controller.metadata
        return MediaSessionDecisions.Candidate(
            packageName = controller.packageName,
            state = stateName(state.state),
            // The one field that decides FULL against START_ONLY. An app that
            // does not advertise it can be started and never *placed*, and the
            // ladder must not run against it.
            //
            // The parentheses are load-bearing: Kotlin's infix `and` binds
            // *looser* than `!=`, so without them this reads as
            // `actions and (ACTION_SEEK_TO != 0L)` — which does not even
            // typecheck, and would have been a comparison against a constant if
            // it did.
            canSeek = (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            positionMs = state.position.coerceAtLeast(0),
            // **Not `nowMs`.** `PlaybackState` reports where the playhead was at
            // `lastPositionUpdateTime`, on the same `SystemClock.elapsedRealtime`
            // base the caller must pass in as `nowMs`. Substituting "now" here
            // would silently claim every reading was fresh, and extrapolation —
            // the thing that number exists for — would add nothing at all.
            reportedAtMs = state.lastPositionUpdateTime,
            // Not decoration: a podcast at 1.5x advances half again as fast, and
            // extrapolating at 1.0 drifts by half a second per second. A zero or
            // negative speed is a stopped session, not a slow one.
            speed = state.playbackSpeed.takeIf { it > 0f } ?: 0f,
            trackKey = MediaSessionDecisions.trackKey(
                title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            ),
        )
    }

    /**
     * Which session to follow, or `null`.
     *
     * **Our own package is excluded, and that is not an optimisation.** Comrade
     * publishes a `MediaSession` for the foreground service that keeps a file
     * session alive ([TogetherService]). A picker that did not exclude it would
     * find Comrade, sync Comrade to Comrade, and feed every correction straight
     * back into the player that produced it. From a bug report that reads as "it
     * randomly jumps", and it is the single worst trap in this feature — which
     * is why the exclusion is [MediaSessionDecisions.pick]'s required argument
     * rather than something a caller may forget.
     */
    fun pick(context: Context, nowMs: Long): MediaController? {
        val controllers = controllers(context)
        val candidates = controllers.mapNotNull { candidateOf(it, nowMs) }
        val chosen = MediaSessionDecisions.pick(candidates, context.packageName) ?: return null
        return controllers.firstOrNull { it.packageName == chosen.packageName }
    }

    /** Whether anything at all could be followed right now. */
    fun anySession(context: Context, nowMs: Long): Boolean = pick(context, nowMs) != null

    /**
     * The framework's ints, named.
     *
     * Mapped here and nowhere else. `MediaSessionDecisions` takes text so that
     * it can have no Android imports at all, which is what lets the JVM lane run
     * it — the same arrangement [TogetherDecisions.embedState] uses for the
     * embed's enum. Two sources, one decision file.
     */
    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        // Every flavour of "about to play but not playing yet" is buffering, and
        // buffering is never reported to the peer as a pause (§10). Skipping
        // between tracks belongs here too: the playhead is not where it says it
        // is, and telling the other side about a position mid-skip would move
        // them to a moment in a song neither of them is in yet.
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> "buffering"
        PlaybackState.STATE_STOPPED -> "stopped"
        // STATE_NONE and STATE_ERROR both mean there is nothing to follow, and
        // an int this build has not learned is treated the same way rather than
        // guessed at.
        else -> "none"
    }
}
