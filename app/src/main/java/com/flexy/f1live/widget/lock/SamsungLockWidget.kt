package com.flexy.f1live.widget.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.flexy.f1live.MainActivity
import com.flexy.f1live.R
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.flexy.f1live.data.CircuitMaps
import com.flexy.f1live.data.Graph
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.ui.components.formatCountdown
import com.flexy.f1live.widget.Countdown
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

/*
 * Samsung One UI lock-screen (and AOD / cover screen) widget, 2x1: the next F1 session and a
 * countdown. It is a plain AppWidgetProvider with RemoteViews, not Glance: Samsung's lock-screen
 * host finds it through widgetCategory 0x2000 and the Samsung attributes in xml/lock_2x1_info.xml
 * plus the "samsung.appwidget.monotone.info" metadata, and recolours its white TextViews to suit
 * the wallpaper. Stock launchers never list it (they only show home-screen widgets).
 *
 * Provider / ServiceBox pattern adapted from twidget (MIT, © 2026 Josh Skinner,
 * github.com/thatjoshguy67/twidget, LockScreenFollowerWidgets.kt) and Codex-Meter (MIT,
 * SamsungLockWidgetSupport.java / SamsungLockServiceBoxReceiver.java).
 */

/** The lock-screen widget provider. Also receives its own redraw alarm ([SamsungLockWidget.ACTION_REFRESH]). */
class SamsungLockWidgetReceiver : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SamsungLockWidget.ACTION_REFRESH) {
            val pending = goAsync()
            SamsungLockWidget.updateAll(context) { pending.finish() }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        SamsungLockWidget.updateAll(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        SamsungLockWidget.updateAll(context) { pending.finish() }
    }

    override fun onDisabled(context: Context) {
        SamsungLockWidget.cancelAlarm(context)
    }
}

/**
 * Legacy Samsung SystemUI "ServiceBox" lock-screen pages: SystemUI asks with
 * REQUEST_SERVICEBOX_REMOTEVIEWS and gets our RemoteViews back. Kept because twidget still ships
 * it; harmless where nobody asks.
 */
class SamsungLockServiceBoxReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SamsungLockWidget.ACTION_REQUEST_SERVICEBOX) return
        val requested = intent.getStringExtra(SamsungLockWidget.EXTRA_PAGE_ID)
        if (!requested.isNullOrEmpty() && requested != SamsungLockWidget.PAGE_ID) return
        val pending = goAsync()
        SamsungLockWidget.respondToServiceBox(context) { pending.finish() }
    }
}

/**
 * Rendering and refresh timing of the lock-screen widget. Like the home-screen widgets (see
 * [com.flexy.f1live.widget.WidgetUpdater]) it is redrawn only when what it shows changes, per
 * [WidgetPlanner.nextRefreshAt], with its own alarm (the home widgets' alarm is only armed while a
 * home widget is placed): hourly while the countdown shows days, every minute under a day, with
 * non-wakeup alarms (delivered as the screen comes on), waking only for a session start or end.
 * WidgetUpdater.refresh also calls [updateAll], which covers calendar fetches, reboot, app update
 * and clock / zone changes.
 */
object SamsungLockWidget {

    const val ACTION_REFRESH = "com.flexy.f1live.widget.lock.action.REFRESH"
    const val ACTION_REQUEST_SERVICEBOX = "com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS"
    private const val ACTION_RESPONSE_SERVICEBOX = "com.samsung.android.intent.action.RESPONSE_SERVICEBOX_REMOTEVIEWS"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val EXTRA_PAGE_ID = "pageId"
    const val PAGE_ID = "f1live_next_session_2x1"

    private const val TAG = "F1LockWidget"
    private const val REQUEST_ALARM = 31
    private const val REQUEST_OPEN = 32
    private const val SCHEDULE_TIMEOUT_MS = 6_000L
    private const val IMAGE_TIMEOUT_MS = 4_000L

    /** Retry after this when no calendar could be read (offline first run, slow disk). */
    private const val RETRY_MS = 30L * WidgetPlanner.MINUTE_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun placedIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(context, SamsungLockWidgetReceiver::class.java))
        }.getOrNull() ?: IntArray(0)
    }

    /** Redraws every placed lock-screen widget and re-arms the alarm. Fire and forget; [onDone] always runs. */
    fun updateAll(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                lock.withLock { updateNow(app) }
            } catch (e: Exception) {
                Log.w(TAG, "lock widget update failed", e)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private suspend fun updateNow(context: Context) {
        val ids = placedIds(context)
        if (ids.isEmpty()) {
            // Nothing placed: no work, no alarm. A ServiceBox page asks for itself (receiver above).
            cancelAlarm(context)
            return
        }
        val now = System.currentTimeMillis()
        val entries = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadEntries(now) }
        val outline = outline(context, entries.orEmpty(), widgetSizePx(context, ids))
        val views = views(context, entries.orEmpty(), now, outline)
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        val next = if (entries == null) {
            now + RETRY_MS
        } else {
            WidgetPlanner.nextRefreshAt(entries, now, ZoneId.systemDefault())
        }
        val contentChange = entries.orEmpty().any { next == it.startUtcMillis || next == it.endUtcMillis }
        armAlarm(context, next, wakeup = contentChange)
        if (isSamsung()) sendServiceBox(context, views)
    }

    fun respondToServiceBox(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val entries = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadEntries(now) }.orEmpty()
                sendServiceBox(app, views(app, entries, now, outline(app, entries, widgetSizePx(app, placedIds(app)))))
            } catch (e: Exception) {
                Log.w(TAG, "ServiceBox response failed", e)
            } finally {
                onDone()
            }
        }
    }

    private fun sendServiceBox(context: Context, views: RemoteViews) {
        try {
            context.sendBroadcast(
                Intent(ACTION_RESPONSE_SERVICEBOX)
                    .setPackage(SYSTEMUI_PACKAGE)
                    .putExtra("package", context.packageName)
                    .putExtra(EXTRA_PAGE_ID, PAGE_ID)
                    .putExtra("show", true)
                    .putExtra("origin", views)
                    .putExtra("aod", views),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "ServiceBox broadcast failed", e)
        }
    }

    /**
     * The running session (if any) and the next one; this season's calendar, plus next season's
     * once this one is almost over. Memory, then disk, then network (the repository's order).
     * Null when no calendar is readable at all.
     */
    private suspend fun loadEntries(now: Long): List<WidgetEntry>? {
        val schedule = runCatching { Graph.schedule }.getOrNull() ?: return null
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val current = schedule.getSeason(year).getOrNull()
        val fromCurrent = current?.let { WidgetPlanner.entries(it, now, LockWidgetPlan.ENTRIES) }.orEmpty()
        if (fromCurrent.size >= LockWidgetPlan.ENTRIES) return fromCurrent
        val next = schedule.getSeason(year + 1).getOrNull()
        if (current == null && next == null) return null
        return WidgetPlanner.entries(current.orEmpty() + next.orEmpty(), now, LockWidgetPlan.ENTRIES)
    }

    // ------------------------------------------------------------------ circuit outline

    /** Opacity of the white outline: faint on a dark wallpaper, still there on a light one. */
    private const val OUTLINE_ALPHA = 0x3D // ~24 %

    /** Lock-screen slot when the host reports no size: Samsung's 2x1 is about 160 x 64 dp. */
    private const val DEFAULT_WIDTH_DP = 160
    private const val DEFAULT_HEIGHT_DP = 64

    /** Upper bound per side, whatever the host reports: keeps the RemoteViews parcel small. */
    private const val MAX_SIDE_PX = 400

    private data class OutlineKey(val url: String, val width: Int, val height: Int)

    @Volatile private var outlineCache: Pair<OutlineKey, Bitmap>? = null

    /** The widget's largest reported size in px (all placed instances share one RemoteViews). */
    private fun widgetSizePx(context: Context, ids: IntArray): Pair<Int, Int> {
        val manager = AppWidgetManager.getInstance(context)
        var widthDp = 0
        var heightDp = 0
        for (id in ids) {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull() ?: continue
            widthDp = maxOf(widthDp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
            heightDp = maxOf(heightDp, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
        }
        if (widthDp <= 0 || heightDp <= 0) {
            widthDp = DEFAULT_WIDTH_DP
            heightDp = DEFAULT_HEIGHT_DP
        }
        val density = context.resources.displayMetrics.density
        return (widthDp * density).toInt().coerceIn(1, MAX_SIDE_PX) to
            (heightDp * density).toInt().coerceIn(1, MAX_SIDE_PX)
    }

    /**
     * The next weekend's circuit outline, fitted into [sizePx] and turned faint white, or null when
     * the "Circuit background" setting is off, the circuit has no outline, or it cannot be loaded.
     * Through Coil's shared loader, so usually its disk cache; the result is kept in memory.
     */
    private suspend fun outline(context: Context, entries: List<WidgetEntry>, sizePx: Pair<Int, Int>): Bitmap? {
        if (!AppSettings.widgetTrackBackground.value) return null
        val weekend = entries.firstOrNull()?.weekend ?: return null
        val url = CircuitMaps.f1OutlineUrl(weekend) ?: return null
        val key = OutlineKey(url, sizePx.first, sizePx.second)
        outlineCache?.let { (cachedKey, bitmap) -> if (cachedKey == key) return bitmap }
        val source = withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(key.width, key.height))
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    // RemoteViews parcel their bitmaps: a hardware bitmap cannot be sent.
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }.onFailure { Log.w(TAG, "circuit outline failed", it) }.getOrNull()
        } ?: return null
        val tinted = runCatching { faintWhite(source, key.width, key.height) }.getOrNull() ?: return null
        outlineCache = key to tinted
        return tinted
    }

    /** [source] scaled to fit [maxWidth] x [maxHeight], every opaque pixel white at [OUTLINE_ALPHA]. */
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

    fun views(context: Context, entries: List<WidgetEntry>, now: Long, outline: Bitmap? = null): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lock_2x1)
        if (outline != null) {
            views.setImageViewBitmap(R.id.lock_outline, outline)
            views.setViewVisibility(R.id.lock_outline, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.lock_outline, View.GONE)
        }
        views.setViewVisibility(R.id.lock_status, View.VISIBLE)
        when (val plan = LockWidgetPlan.of(entries, now)) {
            LockWidgetPlan.Empty -> {
                views.setTextViewText(R.id.lock_title, context.getString(R.string.lock_widget_empty_title))
                views.setTextViewText(R.id.lock_status, context.getString(R.string.lock_widget_empty_body))
            }
            is LockWidgetPlan.Live -> {
                views.setTextViewText(R.id.lock_title, WidgetPlanner.shortName(plan.entry.weekend.name))
                views.setTextViewText(
                    R.id.lock_status,
                    context.getString(R.string.lock_widget_live, sessionLabel(context, plan.entry)),
                )
            }
            is LockWidgetPlan.Upcoming -> {
                views.setTextViewText(
                    R.id.lock_title,
                    context.getString(
                        R.string.lock_widget_title,
                        sessionLabel(context, plan.entry),
                        WidgetPlanner.shortName(plan.entry.weekend.name),
                    ),
                )
                views.setTextViewText(R.id.lock_status, countdownText(context, plan.countdown))
            }
        }
        views.setOnClickPendingIntent(R.id.lock_root, openAppIntent(context))
        return views
    }

    /** The app's wording: "in 1d 13h", "in 16h 41m", "in 8 min", "Starting now". */
    private fun countdownText(context: Context, countdown: Countdown): String = when (countdown) {
        is Countdown.Until -> formatCountdown(context.resources, countdown.units)
        // Live is drawn as LockWidgetPlan.Live and never reaches here.
        Countdown.Live -> context.getString(R.string.countdown_now)
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

    /** Opens the app; on the lock screen the system asks to unlock first. */
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

    /**
     * Exact when allowed (USE_EXACT_ALARM is declared for auto-follow). Waking only for a session
     * start / end, which the always-on display should show on time; the hourly and per-minute
     * countdown steps are non-wakeup and wait for the screen to come on.
     */
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
        Intent(context, SamsungLockWidgetReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or flag,
    )
}
