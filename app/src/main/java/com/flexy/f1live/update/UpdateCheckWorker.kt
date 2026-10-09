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

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.checkUpdates.value) return Result.success()
        return when (val result = UpdateChecker.check(applicationContext, Graph.http)) {
            is UpdateChecker.Result.Available -> {
                UpdateChecker.notifyIfNew(applicationContext, result.release)
                Result.success()
            }
            is UpdateChecker.Result.UpToDate -> Result.success()
            is UpdateChecker.Result.Failed ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "laply-update-check"
        private const val MAX_RETRIES = 2

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
