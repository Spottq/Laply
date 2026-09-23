package com.flexy.f1live.live

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flexy.f1live.widget.WidgetUpdater
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives the session alarm armed by [AutoFollow], the widgets' redraw alarm armed by
 * [WidgetUpdater], and the system events after which both must be re-armed: reboot and app update (both drop every alarm), clock and time-zone changes,
 * and the user granting "Alarms & reminders" (so a windowed alarm can become an exact one).
 *
 * Only the session alarm ever starts the foreground service. Boot must not: Android 15 forbids
 * several service types from BOOT_COMPLETED, and a Live Update at boot would be wrong anyway.
 * Application.onCreate runs before any receiver, so [com.flexy.f1live.data.Graph] and the
 * preferences are already wired by the time [onReceive] runs.
 */
class AutoFollowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // The home-screen widgets' own redraw alarm (see WidgetUpdater): widgets only.
            WidgetUpdater.ACTION_WIDGET_REFRESH -> {
                val pending = goAsync()
                WidgetUpdater.refresh(context) { pending.finish() }
                return
            }
            AutoFollow.ACTION_SESSION_ALARM -> AutoFollow.onAlarm(context, intent)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Unit

            else -> return
        }
        // Reading the calendar is a (small) disk read: keep the process alive until it is done.
        // The same events also move or invalidate what the widgets show - a reboot even resets
        // the clock their countdown Chronometer is anchored to - so they are redrawn alongside.
        val pending = goAsync()
        val remaining = AtomicInteger(2)
        val done = { if (remaining.decrementAndGet() == 0) pending.finish() }
        AutoFollow.reschedule(context, done)
        WidgetUpdater.refresh(context, done)
    }
}
