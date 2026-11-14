package de.jeanlucmakiola.calendula.data.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The watch behind "Also back up after changes" (#516): a JobScheduler job
 * with a content trigger on the provider's root URI, where it reports every
 * change, whoever made it (this app, a sync adapter or another app). It waits
 * for [SETTLE_MINUTES] of quiet, at most [MAX_WAIT_MINUTES], so a burst makes
 * one run. A trigger fires once; each firing queues a change run and schedules
 * the next watch under the same id before finishing, which hands over the
 * changes seen meanwhile, so exactly one watch exists and none are lost.
 */
class BackupChangeJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                val deps = EntryPointAccessors.fromApplication(applicationContext, BackupWorker.Deps::class.java)
                BackupScheduler.changed(applicationContext, deps.settingsPrefs())
            } catch (e: Exception) {
                Log.w(TAG, "Queueing a backup after a change failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Stopped before it could re-arm: run again rather than leave the provider unwatched. */
    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Above the range WorkManager is given (see CalendulaApp). */
        const val JOB_ID = 0x0516_0001
        private const val SETTLE_MINUTES = 2L
        private const val MAX_WAIT_MINUTES = 15L
        private const val TAG = "BackupChangeJob"

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(TimeUnit.MINUTES.toMillis(SETTLE_MINUTES))
                .setTriggerContentMaxDelay(TimeUnit.MINUTES.toMillis(MAX_WAIT_MINUTES))
                .build()
            if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "The change watch could not be scheduled")
            }
        }

        fun isPending(context: Context): Boolean =
            context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
