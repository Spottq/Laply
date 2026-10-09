package com.flexy.f1live.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.flexy.f1live.R
import com.flexy.f1live.live.LiveNotificationBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object UpdateChecker {

    const val LATEST_RELEASE_URL = "https://api.github.com/repos/Spottq/Laply/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/Spottq/Laply/releases"

    private const val TAG = "LaplyUpdate"
    private const val USER_AGENT = "Laply-Android (+https://github.com/Spottq/Laply)"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4201
    private const val PREFS = "update_check"
    private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"

    sealed interface Result {
        data class UpToDate(val installed: String) : Result
        data class Available(val release: GitHubRelease) : Result
        data class Failed(val error: Throwable) : Result
    }

    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    suspend fun check(context: Context, http: OkHttpClient): Result {
        val installed = installedVersion(context)
        return try {
            val release = fetchLatest(http) ?: return Result.UpToDate(installed)
            if (installed.isNotBlank() && AppVersion.isNewer(release.tagName, installed)) {
                Result.Available(release)
            } else {
                Result.UpToDate(installed)
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            runCatching { Log.w(TAG, "Update check failed", error) }
            Result.Failed(error)
        }
    }

    private suspend fun fetchLatest(http: OkHttpClient): GitHubRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code} $LATEST_RELEASE_URL")
                else -> GitHubRelease.parse(response.body.string())
            }
        }
    }

    fun downloadIntent(release: GitHubRelease): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl ?: release.htmlUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notifyIfNew(context: Context, release: GitHubRelease): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_NOTIFIED_TAG, null) == release.tagName) return false
        if (!canNotify(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_updates_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_updates_desc) },
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, REQUEST_DOWNLOAD, downloadIntent(release), flags)
        val notes = PendingIntent.getActivity(
            context,
            REQUEST_NOTES,
            Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_f1)
            .setColor(LiveNotificationBuilder.COLOR_FALLBACK)
            .setContentTitle(context.getString(R.string.update_notif_title, release.version))
            .setContentText(release.summary ?: context.getString(R.string.update_notif_text))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.update_release_notes), notes)
                    .build(),
            )
            .build()
        val posted = runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
        if (posted) prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, release.tagName).apply()
        return posted
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    private const val REQUEST_DOWNLOAD = 4201
    private const val REQUEST_NOTES = 4202
}
