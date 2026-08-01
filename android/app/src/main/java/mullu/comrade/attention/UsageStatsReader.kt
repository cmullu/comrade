package mullu.comrade.attention

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar
import java.util.Locale

/**
 * The thin Android shim over `UsageStatsManager` for the attention mirror —
 * phase 1 of `docs/ATTENTION.md`. All the logic worth testing lives in the
 * Android-free [UsageMirror]; this file only reads events and hands them over.
 *
 * ## Opt-in, and honestly so
 *
 * `PACKAGE_USAGE_STATS` is a **special-access** grant: there is no runtime
 * permission dialog for it, only a trip to a system settings screen, and it
 * lets an app see every application the user opens. That is a real disclosure,
 * so:
 *
 * - nothing here runs until the user has gone to that screen and granted it
 *   ([hasAccess] gates every call);
 * - the explainer that sends them there says what is read, and that it stays on
 *   the device (there is nowhere for it to go — Comrade has no server);
 * - what is read is reduced to three integers and dropped ([UsageMirror]);
 * - revoking the grant later simply stops the mirror. Days already recorded
 *   remain, because they are the user's own record, and the panic wipe reaches
 *   them like everything else.
 *
 * `OQ11` in the design doc asked whether to take this permission at all. The
 * shipped answer is: offer it, never require it, and let the rest of the pillar
 * (focus sessions, the reader, quiet hours, the gentle stop) work fully without
 * it — which it does.
 */
object UsageStatsReader {

    /**
     * Whether the user has granted usage access. Checked via [AppOpsManager]
     * rather than a permission check, because a special-access grant is an
     * app-op, not a permission — `checkSelfPermission` returns "granted" for
     * the manifest declaration whether or not the user ever allowed it, which
     * would make the mirror silently read nothing and look broken.
     */
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The system screen where usage access is granted. */
    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Today's rollup so far, or `null` when access has not been granted (the
     * caller shows the explainer instead of a zeroed card, which would read as
     * a measurement of a day the app cannot actually see).
     *
     * `doomPackages` is the user's own list, read from the encrypted store by
     * the caller — this object never decides what counts as a scroll trap.
     */
    fun todayRollup(context: Context, doomPackages: Set<String>): UsageMirror.DayRollup? {
        if (!hasAccess(context)) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val stretches = readStretches(manager, startOfToday(), now) ?: return null
        return UsageMirror.reduce(
            stretches = stretches,
            doomPackages = doomPackages,
            ownPackage = context.packageName,
        )
    }

    /**
     * Pair up foreground resume/pause events into stretches.
     *
     * Deliberately uses the *event* stream rather than `queryUsageStats`
     * buckets: the daily buckets report total foreground time per app but no
     * pickup count, and their day boundaries are the platform's, not the
     * device timezone's. Returns `null` if the stream cannot be read at all.
     */
    private fun readStretches(
        manager: UsageStatsManager,
        fromMs: Long,
        toMs: Long,
    ): List<UsageMirror.Stretch>? {
        val events = runCatching { manager.queryEvents(fromMs, toMs) }.getOrNull() ?: return null
        val stretches = mutableListOf<UsageMirror.Stretch>()
        val openedAt = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> openedAt[pkg] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    val start = openedAt.remove(pkg) ?: continue
                    if (event.timeStamp > start) {
                        stretches += UsageMirror.Stretch(pkg, start, event.timeStamp)
                    }
                }
            }
        }
        // Whatever is still in the foreground right now is a stretch that ends
        // now — otherwise the app the user is currently holding never counts,
        // which is most visible precisely when they open Comrade to look.
        for ((pkg, start) in openedAt) {
            if (toMs > start) stretches += UsageMirror.Stretch(pkg, start, toMs)
        }
        return stretches
    }

    /** Local midnight, from the device's own calendar. */
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Today's local calendar date as `YYYY-MM-DD` — the key the Rust side
     * stores rollups under. Formed here, not there, because only this side
     * knows the device's timezone (see `ComradeRuntime::record_attention_day`).
     */
    fun today(): String = isoDate(System.currentTimeMillis())

    /** `YYYY-MM-DD` for a wall-clock instant, in the device's timezone. */
    fun isoDate(atMs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = atMs }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }
}
