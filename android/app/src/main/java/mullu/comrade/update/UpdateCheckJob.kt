package mullu.comrade.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Looks for a new release while Comrade is closed, and posts the notification
 * that says so.
 *
 * ## Why this exists
 *
 * The check itself is not new — [UpdateChecker.maybeCheck] has run from
 * `MainActivity.onResume` since the feature shipped. What was missing is the only
 * situation in which a *notification* is worth anything: the app not being open.
 * A notice about a new version that can only appear while the user is already
 * looking at the app tells them something the settings card was about to tell
 * them anyway. This is the half that reaches someone who has not opened Comrade
 * in a week — which, for a sideloaded app whose updates carry security fixes, is
 * the person who needs it most.
 *
 * ## Why `JobScheduler` and not WorkManager
 *
 * WorkManager is the usual answer and would be a new dependency (it pulls Room
 * in with it) for one periodic job on a `minSdk 26` app. The platform scheduler
 * does everything needed here — a period with flex, a network precondition,
 * survival across reboot, Doze-aware batching — and it is what WorkManager
 * delegates to on this API level anyway.
 *
 * ## What this does not do
 *
 * It is not push. Nothing is delivered *to* the device: the device asks GitHub,
 * once a day, on the schedule below. A real push channel would mean a server
 * that knows this install exists, which is the thing Comrade is built not to
 * have. The cost of asking is the disclosure [UpdateChecker]'s doc comment
 * spells out, and it stays behind the same setting — switch auto-check off and
 * [sync] takes the job out of the queue rather than leaving it to keep asking.
 */
class UpdateCheckJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // The setting can have been switched off since this job was queued (a
        // persisted job survives reboots, and the queue is only reconciled when
        // the app runs). Honour it here too, and take the job out.
        if (!UpdateChecker.isAutoCheckEnabled(this)) {
            Log.i(TAG, "auto-check is off; dropping the scheduled check")
            cancel(this)
            return false
        }
        // True means "work continues after this returns" — so every path out of
        // checkInBackground must reach jobFinished, including the ones that
        // decide not to make a request at all. A job that never finishes is held
        // against the app's future scheduling.
        UpdateChecker.checkInBackground(this) { outcome ->
            jobFinished(params, UpdateCheck.shouldRetryScheduledCheck(outcome))
        }
        return true
    }

    /**
     * The system is taking the job away (the network dropped, or Doze tightened
     * around us). True asks for it back: an unfinished check is worth retrying,
     * and the request itself is idempotent.
     */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val TAG = "UpdateCheckJob"

        /** Distinct from every other id this app hands the platform. */
        private const val JOB_ID = 0xC0DE25

        /**
         * Make the queue match the setting. Idempotent and cheap, so it runs at
         * every process start ([mullu.comrade.ComradeApplication]) as well as
         * whenever the setting is toggled — a persisted job outlives both the
         * process and the reboot that scheduled it, so "already queued" is the
         * normal case and re-queueing it would reset its period. See
         * [UpdateCheck.shouldScheduleCheckJob].
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
            val enabled = UpdateChecker.isAutoCheckEnabled(app)
            val pending = runCatching { scheduler.getPendingJob(JOB_ID) }.getOrNull()
            if (!enabled) {
                if (pending != null) {
                    Log.i(TAG, "auto-check off; cancelling the scheduled check")
                    runCatching { scheduler.cancel(JOB_ID) }
                }
                return
            }
            if (!UpdateCheck.shouldScheduleCheckJob(true, pending?.intervalMillis)) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(app, UpdateCheckJob::class.java))
                // Any network: this is one small GET, and refusing to run on
                // mobile data would hide a security fix from someone who has no
                // WiFi. Cheap enough that the user's own metered-data settings
                // are the right place to say otherwise.
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // Survives reboot — which is what makes this reach someone who
                // has not opened the app. Needs RECEIVE_BOOT_COMPLETED in the
                // manifest; the platform throws without it.
                .setPersisted(true)
                .setPeriodic(UpdateCheck.CHECK_INTERVAL_MS, UpdateCheck.CHECK_JOB_FLEX_MS)
                .build()
            val result = runCatching { scheduler.schedule(job) }
                .onFailure { Log.w(TAG, "could not schedule the update check", it) }
                .getOrDefault(JobScheduler.RESULT_FAILURE)
            if (result != JobScheduler.RESULT_SUCCESS) {
                // Said out loud rather than swallowed: the in-app check still
                // works, so this degrades to the old behaviour rather than
                // breaking, but nothing should read as if the job is queued.
                Log.w(TAG, "update check job was not accepted (result=$result)")
            }
        }

        /** Take the job out of the queue — auto-check was switched off. */
        fun cancel(context: Context) {
            val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java) ?: return
            runCatching { scheduler.cancel(JOB_ID) }
                .onFailure { Log.w(TAG, "could not cancel the update check", it) }
        }
    }
}
