package com.flexy.f1live.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flexy.f1live.data.Graph
import com.flexy.f1live.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * The daily update check: asks GitHub for the latest release and posts a notification when it
 * is newer than the installed build. Scheduled by [UpdateCheckWorker.sync] while the
 * "Check for updates" setting is on.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The switch may have been turned off after this run was queued.
        if (!AppSettings.checkUpdates.value) return Result.success()
        return when (val result = UpdateChecker.check(applicationContext, Graph.http)) {
            is UpdateChecker.Result.Available -> {
                UpdateChecker.notifyIfNew(applicationContext, result.release)
                Result.success()
            }
            is UpdateChecker.Result.UpToDate -> Result.success()
            // A couple of backed-off retries for a flaky network, then wait for tomorrow's run.
            is UpdateChecker.Result.Failed ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "laply-update-check"
        private const val MAX_RETRIES = 2

        /**
         * Enqueues the 24 h check when [enabled], cancels it otherwise. KEEP leaves an already
         * scheduled check (and its next run time) alone, so calling this on every start is cheap.
         */
        fun sync(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
