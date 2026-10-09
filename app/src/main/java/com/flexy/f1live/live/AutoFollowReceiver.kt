package com.flexy.f1live.live

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flexy.f1live.widget.WidgetUpdater
import com.flexy.f1live.widget.standings.StandingsWidgetUpdater
import java.util.concurrent.atomic.AtomicInteger

class AutoFollowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetUpdater.ACTION_WIDGET_REFRESH -> {
                val pending = goAsync()
                WidgetUpdater.refresh(context) { pending.finish() }
                return
            }
            StandingsWidgetUpdater.ACTION_REFRESH -> {
                val pending = goAsync()
                StandingsWidgetUpdater.refresh(context) { pending.finish() }
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
        val pending = goAsync()
        val remaining = AtomicInteger(3)
        val done = { if (remaining.decrementAndGet() == 0) pending.finish() }
        AutoFollow.reschedule(context, done)
        WidgetUpdater.refresh(context, done)
        StandingsWidgetUpdater.refresh(context, onDone = done)
    }
}
