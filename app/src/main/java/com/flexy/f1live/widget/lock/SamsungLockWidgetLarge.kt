package com.flexy.f1live.widget.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.flexy.f1live.MainActivity
import com.flexy.f1live.R
import com.flexy.f1live.data.CircuitMaps
import com.flexy.f1live.data.Graph
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.ui.components.formatCountdown
import com.flexy.f1live.ui.components.formatTime
import com.flexy.f1live.widget.DayLabel
import com.flexy.f1live.widget.WidgetEntry
import com.flexy.f1live.widget.WidgetPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class SamsungLockWidgetLargeReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SamsungLockWidgetLarge.ACTION_REFRESH) {
            val pending = goAsync()
            SamsungLockWidgetLarge.updateAll(context) { pending.finish() }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        SamsungLockWidgetLarge.updateAll(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        SamsungLockWidgetLarge.updateAll(context) { pending.finish() }
    }

    override fun onDisabled(context: Context) {
        SamsungLockWidgetLarge.cancelAlarm(context)
    }
}

object SamsungLockWidgetLarge {

    const val ACTION_REFRESH = "com.flexy.f1live.widget.lock.action.REFRESH_LARGE"

    private const val TAG = "F1LockWidget2x2"
    private const val REQUEST_ALARM = 41
    private const val REQUEST_OPEN = 42
    private const val SCHEDULE_TIMEOUT_MS = 6_000L
    private const val IMAGE_TIMEOUT_MS = 4_000L

    private const val RETRY_MS = 30L * WidgetPlanner.MINUTE_MS

    private const val FEATURE_HINGE_ANGLE = "android.hardware.sensor.hinge_angle"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private fun component(context: Context) = ComponentName(context, SamsungLockWidgetLargeReceiver::class.java)

    fun placedIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching { manager.getAppWidgetIds(component(context)) }.getOrNull() ?: IntArray(0)
    }

    fun supported(context: Context): Boolean =
        context.resources.getBoolean(R.bool.lock_large_tablet) ||
            context.packageManager.hasSystemFeature(FEATURE_HINGE_ANGLE)

    fun syncEnabled(context: Context) {
        val pm = context.packageManager
        val component = component(context)
        val current = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull() ?: return
        val wanted = if (supported(context)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        if (current == wanted) return
        runCatching { pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP) }
            .onFailure { Log.w(TAG, "could not switch the 2x2 lock widget", it) }
    }

    fun updateAll(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                syncEnabled(app)
                lock.withLock { updateNow(app) }
            } catch (e: Exception) {
                Log.w(TAG, "2x2 lock widget update failed", e)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private suspend fun updateNow(context: Context) {
        val ids = placedIds(context)
        if (ids.isEmpty()) {
            cancelAlarm(context)
            return
        }
        val now = System.currentTimeMillis()
        val entries = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadEntries(now) }
        val size = widgetSize(context, ids)
        val outline = outline(context, entries.orEmpty(), size.maxWidthDp to size.maxHeightDp)
        val views = views(context, entries.orEmpty(), now, size.minHeightDp, outline)
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        val shown = entries.orEmpty()
        val next = if (entries == null) {
            now + RETRY_MS
        } else {
            WidgetPlanner.nextRefreshAt(shown, now, ZoneId.systemDefault())
        }
        val contentChange = shown.any { next == it.startUtcMillis || next == it.endUtcMillis }
        armAlarm(context, next, wakeup = contentChange)
    }

    private suspend fun loadEntries(now: Long): List<WidgetEntry>? {
        val schedule = runCatching { Graph.schedule }.getOrNull() ?: return null
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val current = schedule.getSeason(year).getOrNull()
        val fromCurrent = current?.let { WidgetPlanner.entries(it, now, LockWidgetLargePlan.ENTRIES) }.orEmpty()
        if (fromCurrent.size >= LockWidgetLargePlan.ENTRIES) return fromCurrent
        val next = schedule.getSeason(year + 1).getOrNull()
        if (current == null && next == null) return null
        return WidgetPlanner.entries(current.orEmpty() + next.orEmpty(), now, LockWidgetLargePlan.ENTRIES)
    }

    private data class WidgetSize(val maxWidthDp: Int, val maxHeightDp: Int, val minHeightDp: Int)

    private fun widgetSize(context: Context, ids: IntArray): WidgetSize {
        val manager = AppWidgetManager.getInstance(context)
        var maxWidth = 0
        var maxHeight = 0
        var minHeight = Int.MAX_VALUE
        for (id in ids) {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull() ?: continue
            maxWidth = maxOf(maxWidth, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
            maxHeight = maxOf(maxHeight, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
            val low = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (low > 0) minHeight = minOf(minHeight, low)
        }
        if (maxWidth <= 0 || maxHeight <= 0) {
            maxWidth = LockWidgetLargePlan.DEFAULT_WIDTH_DP
            maxHeight = LockWidgetLargePlan.DEFAULT_HEIGHT_DP
        }
        if (minHeight == Int.MAX_VALUE) minHeight = maxHeight
        return WidgetSize(maxWidth, maxHeight, minHeight)
    }

    // ------------------------------------------------------------------ circuit outline

    private const val OUTLINE_ALPHA = 0x3D

    private const val OUTLINE_SHARE = 0.7f

    private const val MAX_SIDE_PX = 360

    private data class OutlineKey(val url: String, val width: Int, val height: Int)

    @Volatile private var outlineCache: Pair<OutlineKey, Bitmap>? = null

    private suspend fun outline(context: Context, entries: List<WidgetEntry>, sizeDp: Pair<Int, Int>): Bitmap? {
        if (!AppSettings.widgetTrackBackground.value) return null
        val weekend = entries.firstOrNull()?.weekend ?: return null
        val url = CircuitMaps.f1OutlineUrl(weekend) ?: return null
        val density = context.resources.displayMetrics.density
        val key = OutlineKey(
            url,
            (sizeDp.first * density * OUTLINE_SHARE).toInt().coerceIn(1, MAX_SIDE_PX),
            (sizeDp.second * density * OUTLINE_SHARE).toInt().coerceIn(1, MAX_SIDE_PX),
        )
        outlineCache?.let { (cachedKey, bitmap) -> if (cachedKey == key) return bitmap }
        val source = withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(key.width, key.height))
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }.onFailure { Log.w(TAG, "circuit outline failed", it) }.getOrNull()
        } ?: return null
        val tinted = runCatching { faintWhite(source, key.width, key.height) }.getOrNull() ?: return null
        outlineCache = key to tinted
        return tinted
    }

    private fun faintWhite(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height, 1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(
                android.graphics.Color.argb(OUTLINE_ALPHA, 255, 255, 255),
                PorterDuff.Mode.SRC_IN,
            )
        }
        Canvas(out).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), paint)
        return out
    }

    // ------------------------------------------------------------------ views

    fun views(
        context: Context,
        entries: List<WidgetEntry>,
        now: Long,
        heightDp: Int = LockWidgetLargePlan.DEFAULT_HEIGHT_DP,
        outline: Bitmap? = null,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lock_2x2)
        if (outline != null) {
            views.setImageViewBitmap(R.id.lock2_outline, outline)
            views.setViewVisibility(R.id.lock2_outline, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.lock2_outline, View.GONE)
        }
        val rowIds = intArrayOf(R.id.lock2_row1, R.id.lock2_row2)
        val entry = when (val plan = LockWidgetPlan.of(entries, now)) {
            LockWidgetPlan.Empty -> null
            is LockWidgetPlan.Live -> plan.entry
            is LockWidgetPlan.Upcoming -> plan.entry
        }
        // Set every hidden view both ways: a host may re-apply these onto the previous hierarchy.
        when (entry) {
            null -> {
                views.setViewVisibility(R.id.lock2_overline, View.GONE)
                views.setViewVisibility(R.id.lock2_session, View.GONE)
                views.setTextViewText(R.id.lock2_title, context.getString(R.string.lock_widget_empty_title))
                views.setTextViewText(R.id.lock2_countdown, context.getString(R.string.lock_widget_empty_body))
                rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            }
            else -> {
                val weekend = entry.weekend
                val overlineRes = if (WidgetPlanner.weekendUnderway(weekend, now)) {
                    R.string.widget_this_weekend_overline
                } else {
                    R.string.next_round_overline
                }
                views.setViewVisibility(R.id.lock2_overline, View.VISIBLE)
                views.setTextViewText(
                    R.id.lock2_overline,
                    context.getString(overlineRes, weekend.round).uppercase(Locale.getDefault()),
                )
                views.setTextViewText(R.id.lock2_title, WidgetPlanner.shortName(weekend.name))
                views.setViewVisibility(R.id.lock2_session, View.VISIBLE)
                views.setTextViewText(
                    R.id.lock2_session,
                    context.getString(
                        R.string.lock_large_session_when,
                        sessionLabel(context, entry),
                        whenText(context, entry.startUtcMillis, now),
                    ),
                )
                views.setTextViewText(
                    R.id.lock2_countdown,
                    if (entry.isLive(now)) {
                        context.getString(R.string.widget_live)
                    } else {
                        formatCountdown(context.resources, entry.startUtcMillis - now)
                    },
                )
                val fontScale = context.resources.configuration.fontScale
                val rows = LockWidgetLargePlan.rows(entries, LockWidgetLargePlan.rowsThatFit(heightDp, fontScale))
                rowIds.forEachIndexed { index, id ->
                    val row = rows.getOrNull(index)
                    if (row == null) {
                        views.setViewVisibility(id, View.GONE)
                    } else {
                        views.setViewVisibility(id, View.VISIBLE)
                        views.setTextViewText(id, rowText(context, row, entry, now))
                    }
                }
            }
        }
        views.setOnClickPendingIntent(R.id.lock2_root, openAppIntent(context))
        return views
    }

    private fun rowText(context: Context, row: WidgetEntry, headline: WidgetEntry, now: Long): String {
        val label = sessionLabel(context, row)
        val time = whenText(context, row.startUtcMillis, now)
        val sameWeekend = row.weekend.round == headline.weekend.round && row.weekend.season == headline.weekend.season
        return if (sameWeekend) {
            context.getString(R.string.lock_large_session_when, label, time)
        } else {
            context.getString(R.string.lock_large_row_other_weekend, WidgetPlanner.shortName(row.weekend.name), label, time)
        }
    }

    private val weekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    private fun whenText(context: Context, start: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val time = formatTime(start)
        val day = Instant.ofEpochMilli(start).atZone(zone)
        return when (WidgetPlanner.dayLabel(start, now, zone)) {
            DayLabel.TODAY -> context.getString(R.string.lock_large_when_today, time)
            DayLabel.TOMORROW -> context.getString(R.string.lock_large_when_tomorrow, time)
            DayLabel.THIS_WEEK -> weekdayFormatter.format(day) + " " + time
            DayLabel.LATER -> dateFormatter.format(day) + " " + time
        }
    }

    private fun sessionLabel(context: Context, entry: WidgetEntry): String =
        when (lockSessionLabel(entry.session.kind)) {
            LockSessionLabel.FP1 -> context.getString(R.string.lock_session_fp1)
            LockSessionLabel.FP2 -> context.getString(R.string.lock_session_fp2)
            LockSessionLabel.FP3 -> context.getString(R.string.lock_session_fp3)
            LockSessionLabel.SQ -> context.getString(R.string.lock_session_sprint_qualifying)
            LockSessionLabel.SPRINT -> context.getString(R.string.lock_session_sprint)
            LockSessionLabel.QUALI -> context.getString(R.string.lock_session_qualifying)
            LockSessionLabel.RACE -> context.getString(R.string.lock_session_race)
            null -> entry.session.name
        }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ------------------------------------------------------------------ alarm

    private fun armAlarm(context: Context, triggerAt: Long, wakeup: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val type = if (wakeup) AlarmManager.RTC_WAKEUP else AlarmManager.RTC
        val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (exact) manager.setExact(type, triggerAt, pending) else manager.set(type, triggerAt, pending)
        } catch (_: SecurityException) {
            runCatching { manager.set(type, triggerAt, pending) }
        }
    }

    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun alarmIntent(context: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, SamsungLockWidgetLargeReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or flag,
    )
}
