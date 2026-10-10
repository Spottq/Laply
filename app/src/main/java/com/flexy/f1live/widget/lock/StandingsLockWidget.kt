package com.flexy.f1live.widget.lock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.flexy.f1live.MainActivity
import com.flexy.f1live.R
import com.flexy.f1live.widget.standings.StandingsSnapshot
import com.flexy.f1live.widget.standings.StandingsWidgetKind
import com.flexy.f1live.widget.standings.StandingsWidgetPlanner
import com.flexy.f1live.widget.standings.StandingsWidgetUpdater
import com.flexy.f1live.widget.standings.leaderFooter
import com.flexy.f1live.widget.standings.leaderOverline
import com.flexy.f1live.widget.standings.overline
import java.util.Locale

abstract class StandingsLockWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        StandingsWidgetUpdater.refresh(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        StandingsWidgetUpdater.refresh(context, fetch = false) { pending.finish() }
    }
}

class DriverStandingsLockWidgetReceiver : StandingsLockWidgetReceiver()

class TeamStandingsLockWidgetReceiver : StandingsLockWidgetReceiver()

class TitleFightLockWidgetReceiver : StandingsLockWidgetReceiver()

object StandingsLockWidget {

    private const val TAG = "StandingsLockWidget"
    private const val REQUEST_OPEN = 51

    private val receivers: Map<StandingsWidgetKind, Class<out AppWidgetProvider>> = mapOf(
        StandingsWidgetKind.Drivers to DriverStandingsLockWidgetReceiver::class.java,
        StandingsWidgetKind.Teams to TeamStandingsLockWidgetReceiver::class.java,
        StandingsWidgetKind.Leader to TitleFightLockWidgetReceiver::class.java,
    )

    private val rowIds = intArrayOf(
        R.id.lock_standings_row1,
        R.id.lock_standings_row2,
        R.id.lock_standings_row3,
        R.id.lock_standings_row4,
        R.id.lock_standings_row5,
    )
    private val positionIds = intArrayOf(
        R.id.lock_standings_position1,
        R.id.lock_standings_position2,
        R.id.lock_standings_position3,
        R.id.lock_standings_position4,
        R.id.lock_standings_position5,
    )
    private val nameIds = intArrayOf(
        R.id.lock_standings_name1,
        R.id.lock_standings_name2,
        R.id.lock_standings_name3,
        R.id.lock_standings_name4,
        R.id.lock_standings_name5,
    )
    private val pointsIds = intArrayOf(
        R.id.lock_standings_points1,
        R.id.lock_standings_points2,
        R.id.lock_standings_points3,
        R.id.lock_standings_points4,
        R.id.lock_standings_points5,
    )
    private val lineIds = intArrayOf(
        R.id.lock_standings_line1,
        R.id.lock_standings_line2,
        R.id.lock_standings_line3,
        R.id.lock_standings_line4,
    )

    fun anyPlaced(context: Context): Boolean = placed(context).isNotEmpty()

    private fun placed(context: Context): Map<StandingsWidgetKind, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return receivers
            .mapValues { (_, receiver) ->
                runCatching { manager.getAppWidgetIds(ComponentName(context, receiver)) }.getOrNull() ?: IntArray(0)
            }
            .filterValues { it.isNotEmpty() }
    }

    // The receivers ship disabled: Samsung's lock screen has 2x2 slots on tablets and foldables only.
    fun syncEnabled(context: Context) {
        val pm = context.packageManager
        val wanted = if (SamsungLockWidgetLarge.supported(context)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        for (receiver in receivers.values) {
            val component = ComponentName(context, receiver)
            val current = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull() ?: continue
            if (current == wanted) continue
            runCatching { pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP) }
                .onFailure { Log.w(TAG, "could not switch ${receiver.simpleName}", it) }
        }
    }

    fun push(context: Context, snapshot: StandingsSnapshot) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        for ((kind, ids) in placed(context)) {
            runCatching { manager.updateAppWidget(ids, views(context, kind, snapshot, heightDp(manager, ids))) }
                .onFailure { Log.w(TAG, "lock widget update failed", it) }
        }
    }

    private fun heightDp(manager: AppWidgetManager, ids: IntArray): Int =
        ids.asList().mapNotNull { id ->
            runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                ?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                ?.takeIf { it > 0 }
        }.minOrNull() ?: LockWidgetLargePlan.DEFAULT_HEIGHT_DP

    fun views(context: Context, kind: StandingsWidgetKind, snapshot: StandingsSnapshot, heightDp: Int): RemoteViews {
        val views = if (kind == StandingsWidgetKind.Leader) {
            leaderViews(context, snapshot, heightDp)
        } else {
            tableViews(context, kind, snapshot, heightDp)
        }
        views.setOnClickPendingIntent(R.id.lock_standings_root, openIntent(context, kind))
        return views
    }

    private fun tableViews(
        context: Context,
        kind: StandingsWidgetKind,
        snapshot: StandingsSnapshot,
        heightDp: Int,
    ): RemoteViews {
        val teams = kind == StandingsWidgetKind.Teams
        val views = RemoteViews(
            context.packageName,
            if (teams) R.layout.widget_lock_standings_teams else R.layout.widget_lock_standings_drivers,
        )
        val entries = if (teams) snapshot.teams else snapshot.drivers
        val fight = if (teams) snapshot.teamsFight else snapshot.driversFight
        val plan = StandingsLockPlan.table(
            heightDp = heightDp,
            fontScale = context.resources.configuration.fontScale,
            entries = entries.size,
            contenders = fight?.contenders,
        )
        val label = if (teams) R.string.standings_widget_teams else R.string.standings_widget_drivers
        views.setTextViewText(
            R.id.lock_standings_overline,
            if (entries.isEmpty()) {
                context.getString(R.string.standings_widget_empty_title).uppercase(Locale.getDefault())
            } else {
                overline(context, label, snapshot, wide = false)
            },
        )
        // Every slot is set both ways: a host may re-apply these views onto the previous hierarchy.
        rowIds.forEachIndexed { index, rowId ->
            val entry = entries.getOrNull(index)?.takeIf { index < plan.rows }
            if (entry == null) {
                views.setViewVisibility(rowId, View.GONE)
            } else {
                views.setViewVisibility(rowId, View.VISIBLE)
                views.setTextViewText(positionIds[index], entry.position.toString())
                views.setTextViewText(nameIds[index], if (teams) entry.name else entry.code)
                views.setTextViewText(pointsIds[index], StandingsWidgetPlanner.formatPoints(entry.points))
            }
        }
        lineIds.forEachIndexed { index, lineId ->
            views.setViewVisibility(lineId, if (plan.lineAfter == index + 1) View.VISIBLE else View.GONE)
        }
        return views
    }

    private fun leaderViews(context: Context, snapshot: StandingsSnapshot, heightDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lock_title_fight)
        val lines = StandingsLockPlan.leaderLines(heightDp, context.resources.configuration.fontScale)
        val leader = snapshot.drivers.firstOrNull()
        if (leader == null) {
            views.setViewVisibility(R.id.lock_fight_overline, View.GONE)
            views.setTextViewText(R.id.lock_fight_name, context.getString(R.string.standings_widget_empty_title))
            views.setViewVisibility(R.id.lock_fight_points_row, View.GONE)
            views.setTextViewText(R.id.lock_fight_gaps, context.getString(R.string.standings_widget_empty_body))
            views.setViewVisibility(R.id.lock_fight_gaps, if (lines >= 1) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.lock_fight_detail, View.GONE)
            return views
        }
        val fight = snapshot.driversFight
        views.setViewVisibility(R.id.lock_fight_overline, View.VISIBLE)
        views.setTextViewText(R.id.lock_fight_overline, leaderOverline(context, snapshot, fight, wide = false))
        views.setTextViewText(R.id.lock_fight_name, leader.lastName)
        views.setViewVisibility(R.id.lock_fight_points_row, View.VISIBLE)
        views.setTextViewText(R.id.lock_fight_points, StandingsWidgetPlanner.formatPoints(leader.points))
        val gaps = snapshot.drivers.drop(1).take(StandingsLockPlan.MAX_CHASERS).joinToString(" · ") {
            it.code + " " + StandingsWidgetPlanner.gapText(leader.points, it.points)
        }
        views.setTextViewText(R.id.lock_fight_gaps, gaps)
        views.setViewVisibility(R.id.lock_fight_gaps, if (gaps.isNotEmpty() && lines >= 1) View.VISIBLE else View.GONE)
        val detail = leaderFooter(context, fight)
        views.setTextViewText(R.id.lock_fight_detail, detail.orEmpty())
        views.setViewVisibility(R.id.lock_fight_detail, if (detail != null && lines >= 2) View.VISIBLE else View.GONE)
        return views
    }

    private fun openIntent(context: Context, kind: StandingsWidgetKind): PendingIntent {
        val action = if (kind == StandingsWidgetKind.Teams) {
            MainActivity.ACTION_OPEN_TEAM_STANDINGS
        } else {
            MainActivity.ACTION_OPEN_DRIVER_STANDINGS
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN + kind.ordinal,
            Intent(context, MainActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
