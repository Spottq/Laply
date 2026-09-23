package com.flexy.f1live.live

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.flexy.f1live.R
import com.flexy.f1live.data.Graph
import com.flexy.f1live.model.LiveSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset

/**
 * "Auto-follow sessions": with the Follow opt-in on ([FollowPreferences.enabled]) the Live Update
 * appears by itself when a session starts, whether or not the app is open.
 *
 * Three triggers start [LiveUpdateService], all of them no-ops while the opt-in is off:
 *  1. **Alarm** - one exact alarm, armed [AutoFollowPlanner.LEAD_MS] before the next scheduled
 *     session, fires [AutoFollowReceiver]. An exact alarm is one of the few places Android 12+
 *     still lets an app start a foreground service from the background; when that start is refused
 *     anyway, [postSessionStarting] leaves a plain "tap to follow" notification instead.
 *  2. **App in the foreground** - [onForeground] (MainActivity.onResume) starts the service when
 *     the schedule says a session is on right now, covering missed or inexact alarms.
 *  3. **Feed went live** - an observer on [Graph.liveTiming] starts it the moment the feed the Live
 *     screen is showing reports a running session.
 *
 * The alarm is re-armed after every fire, at process start, when the opt-in is switched on, when
 * the calendar is fetched, and after reboot / app update / clock changes ([AutoFollowReceiver]).
 */
object AutoFollow {

    const val ACTION_SESSION_ALARM = "com.flexy.f1live.live.action.SESSION_ALARM"

    private const val REQUEST_ALARM = 10
    private const val REQUEST_OPEN = 11
    private const val FALLBACK_CHANNEL_ID = "session_start"
    const val FALLBACK_NOTIFICATION_ID = 4103

    /** A receiver's goAsync() budget is ~10 s; leave room for the alarm call after the read. */
    private const val SCHEDULE_TIMEOUT_MS = 8_000L

    /** Inexact fallback window: fire somewhere in the ten minutes before the usual lead time. */
    private const val INEXACT_WINDOW_MS = 10L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alarmLock = Mutex()

    /** Wires everything up; called once from F1App.onCreate, after [Graph] is populated. */
    fun attach(app: Application) {
        FollowPreferences.load(app)
        reschedule(app)
        scope.launch {
            combine(
                Graph.liveTiming.state,
                FollowPreferences.enabled,
                LiveUpdateController.serviceRunning,
            ) { state, enabled, running ->
                // A cached replay is last week's session, never a reason to start anything.
                enabled && !running && state.isLive &&
                    state.source != LiveSource.CACHE && state.source != LiveSource.NONE
            }
                .distinctUntilChanged()
                .collect { shouldStart -> if (shouldStart) onFeedLive(app) }
        }
    }

    /** The Follow button. Off also stops a running Live Update; on arms the alarm right away. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        FollowPreferences.setEnabled(app, enabled)
        if (enabled) {
            reschedule(app)
            onForeground(app)
        } else {
            cancelAlarm(app)
            LiveUpdateService.stop(app)
        }
    }

    // ------------------------------------------------------------------ triggers

    /**
     * App came to the foreground: if a session is on and nothing suppresses it, start the Live
     * Update now. A foreground app may always start a foreground service, so this also repairs an
     * alarm that fired while the background start was refused.
     */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        if (!FollowPreferences.isEnabled(app) || LiveUpdateController.serviceRunning.value) return
        scope.launch {
            val now = System.currentTimeMillis()
            if (now < FollowPreferences.dismissedUntil(app)) return@launch
            if (now < FollowPreferences.handledUntil(app)) return@launch
            val target = currentTarget(now) ?: return@launch
            LiveUpdateService.start(app, target)
        }
    }

    /** The shared feed reports a running session and no Live Update is showing it. */
    private suspend fun onFeedLive(app: Context) {
        val now = System.currentTimeMillis()
        if (now < FollowPreferences.dismissedUntil(app)) return
        // The label and the dismissal window come from the schedule; the start does not need it.
        LiveUpdateService.start(app, currentTarget(now))
    }

    /**
     * The alarm fired ([AutoFollowReceiver], synchronously in onReceive so the exact-alarm
     * foreground-service allowance is still fresh). Never throws.
     */
    fun onAlarm(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!FollowPreferences.isEnabled(app)) return
        val target = LiveUpdateService.readTarget(intent) ?: return
        val now = System.currentTimeMillis()
        // Delivered hours late (device was off, Doze held it): that session is gone.
        if (now >= AutoFollowPlanner.windowEnd(target)) return
        if (target.startUtcMillis <= FollowPreferences.dismissedUntil(app)) return
        if (!LiveUpdateService.start(app, target)) postSessionStarting(app, target)
    }

    // ------------------------------------------------------------------ alarm

    /** Fire-and-forget [rescheduleNow]. */
    fun reschedule(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                rescheduleNow(app)
            } finally {
                onDone?.invoke()
            }
        }
    }

    /**
     * Arms the alarm for the next session, or cancels it when the opt-in is off or no session is
     * left. When the calendar cannot be read at all the existing alarm is left alone: it was armed
     * from the same calendar a little earlier and is more likely right than nothing.
     */
    suspend fun rescheduleNow(context: Context) = alarmLock.withLock {
        if (!FollowPreferences.isEnabled(context)) {
            cancelAlarm(context)
            return@withLock
        }
        val now = System.currentTimeMillis()
        val targets = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadTargets(now) } ?: return@withLock
        val alarm = AutoFollowPlanner.nextAlarm(targets, now)
        if (alarm == null) cancelAlarm(context) else setAlarm(context, alarm)
    }

    /**
     * Exact when allowed - only an exact alarm lets the receiver start a foreground service from the
     * background. USE_EXACT_ALARM (API 33+) is granted at install; on API 31/32 the user can revoke
     * SCHEDULE_EXACT_ALARM, and then a windowed alarm still gets the fallback notification out.
     */
    private fun setAlarm(context: Context, alarm: AutoFollowPlanner.Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, alarm.target)
        val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pending)
            } else {
                setInexact(manager, alarm.triggerAtMillis, pending)
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
            runCatching { setInexact(manager, alarm.triggerAtMillis, pending) }
        }
    }

    private fun setInexact(manager: AlarmManager, triggerAt: Long, pending: PendingIntent) {
        manager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAt - INEXACT_WINDOW_MS,
            INEXACT_WINDOW_MS,
            pending,
        )
    }

    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AutoFollowReceiver::class.java).setAction(ACTION_SESSION_ALARM)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    /** One request code, so arming a new alarm always replaces the previous one. */
    private fun alarmIntent(context: Context, target: SessionTarget): PendingIntent {
        val intent = Intent(context, AutoFollowReceiver::class.java).setAction(ACTION_SESSION_ALARM)
        LiveUpdateService.putTarget(intent, target)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ------------------------------------------------------------------ schedule

    /**
     * This season's sessions, plus next season's once this one has none left (December). Null
     * when the calendar could not be read at all. The repository serves memory, then the disk
     * copy in `filesDir/cache-json/`, and only then the network, so in a receiver this is normally
     * a small file read.
     */
    private suspend fun loadTargets(now: Long): List<SessionTarget>? {
        val schedule = runCatching { Graph.schedule }.getOrNull() ?: return null
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val current = schedule.getSeason(year).getOrNull()?.let(AutoFollowPlanner::targets)
        if (current != null && AutoFollowPlanner.nextAlarm(current, now) != null) return current
        val next = schedule.getSeason(year + 1).getOrNull()?.let(AutoFollowPlanner::targets)
        if (current == null && next == null) return null
        return current.orEmpty() + next.orEmpty()
    }

    private suspend fun currentTarget(now: Long): SessionTarget? {
        val targets = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadTargets(now) } ?: return null
        return AutoFollowPlanner.currentWindow(targets, now)
    }

    // ------------------------------------------------------------------ fallback

    /**
     * Posted when Android refused to start the foreground service from the background (inexact
     * alarm, OEM restrictions). Tapping it opens the app, whose [onForeground] then starts the
     * Live Update with the app in the foreground, where it is always allowed.
     */
    fun postSessionStarting(context: Context, target: SessionTarget?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    FALLBACK_CHANNEL_ID,
                    context.getString(R.string.channel_session_start_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_session_start_desc) },
            )
        }
        val builder = Notification.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_f1)
            .setContentTitle(target?.label?.takeIf { it.isNotBlank() } ?: "Laply")
            .setContentText(context.getString(R.string.auto_follow_fallback_text))
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setColor(LiveNotificationBuilder.COLOR_FALLBACK)
        target?.let { builder.setWhen(it.startUtcMillis).setShowWhen(true) }
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_OPEN,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        runCatching { manager.notify(FALLBACK_NOTIFICATION_ID, builder.build()) }
    }
}
