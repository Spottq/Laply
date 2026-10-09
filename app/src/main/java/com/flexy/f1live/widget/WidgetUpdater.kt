package com.flexy.f1live.widget

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.collection.intSetOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.flexy.f1live.data.CircuitMaps
import com.flexy.f1live.data.Graph
import com.flexy.f1live.live.AutoFollowReceiver
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.settings.ThemeMode
import com.flexy.f1live.ui.components.flagCdnUrl
import com.flexy.f1live.widget.standings.StandingsWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Everything a widget draws, computed once for all widget instances and sizes. [nowMillis] is the
 * moment it was computed: the countdown text is relative to it, which is also why every refresh
 * produces a new snapshot and so a recomposition of any running widget session.
 */
data class WidgetSnapshot(
    val nowMillis: Long,
    val entries: List<WidgetEntry>,
    /** False when no calendar could be read at all (first run offline). */
    val calendarLoaded: Boolean,
    /** Flag bitmaps by lowercase ISO country code; missing ones are simply not drawn. */
    val flags: Map<String, Bitmap>,
    /** The first listed weekend's track outline (dark line on transparency), tinted when drawn. */
    val outline: Bitmap?,
    /**
     * [outline] cropped to bleed off two edges, per corner it is pinned to, for the faint
     * background decoration; empty when that setting is off or there is no outline.
     */
    val decorations: Map<DecorationCorner, Bitmap>,
    val dynamicColor: Boolean,
    /**
     * The One UI blur style, when the setting is on and this is One UI 7+; each widget still only
     * uses it when One UI Home hosts it. Null: the normal opaque widget everywhere.
     */
    val glass: GlassStyle? = null,
    /** A widget-picker preview: text countdowns only, since a Chronometer there would be frozen. */
    val preview: Boolean = false,
)

/** Translucent One UI glass: the tint's [alpha] (1..254) and whether it is light or dark. */
data class GlassStyle(val alpha: Int, val tone: ThemeMode)

/** Where the background circuit sits; its crop bleeds off the two edges of that corner. */
enum class DecorationCorner { BottomEnd, TopEnd }

/**
 * Data, refresh timing and previews for the home-screen widgets.
 *
 * Refresh strategy: widgets are redrawn only when what they show changes, per
 * [WidgetPlanner.nextRefreshAt]. One non-wakeup exact alarm is armed for that moment (a sleeping
 * phone has nobody looking at its home screen; the alarm is delivered when it wakes). Under a day
 * before a session the countdown is a system Chronometer ticking by itself, so there are no
 * per-minute redraws. The alarm goes through [AutoFollowReceiver], which already gets the
 * reboot / clock / time-zone / app-update broadcasts after which widgets must be redrawn too (a
 * Chronometer is anchored to elapsedRealtime, which a reboot resets). A calendar fetch and the
 * two appearance settings (dynamic colour, circuit background) also redraw.
 */
object WidgetUpdater {

    const val ACTION_WIDGET_REFRESH = "com.flexy.f1live.widget.action.REFRESH"

    private const val TAG = "F1Widget"
    private const val REQUEST_REFRESH_ALARM = 12
    private const val SCHEDULE_TIMEOUT_MS = 6_000L
    private const val IMAGE_TIMEOUT_MS = 4_000L

    /** A snapshot this fresh is reused by the next widget session instead of being rebuilt. */
    private const val REUSE_MS = 10_000L

    private const val PREFS = "widgets"
    private const val KEY_PREVIEW_AT = "preview_published_at"
    private const val KEY_PREVIEW_APPEARANCE = "preview_appearance"
    private const val PREVIEW_INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** Flag PNGs are 80 px wide; the outline canvas is 121 x 85 - drawn at ~120 dp, so 2x. */
    private val FLAG_SIZE = Size(80, 60)
    private val OUTLINE_SIZE = Size(242, 170)

    /** Share of the outline kept on each axis for the background decoration; the rest bleeds off. */
    private const val DECORATION_KEEP = 0.8f

    val receivers: List<Class<out F1WidgetReceiver>> = listOf(
        NextSessionWidgetReceiver::class.java,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** Serialises [pushAll]: Glance allows one direct composition per widget id at a time. */
    private val pushLock = Mutex()

    /** One preview publish at a time: every refused call still counts against the rate limit. */
    private val previewLock = Mutex()

    private val _snapshot = MutableStateFlow<WidgetSnapshot?>(null)

    /** The latest snapshot; running widget sessions collect it, so a refresh recomposes them. */
    val snapshot: StateFlow<WidgetSnapshot?> = _snapshot.asStateFlow()

    /** Called once from F1App.onCreate. */
    fun attach(app: Application) {
        scope.launch {
            // Monet on/off changes every colour of the widget.
            AppSettings.dynamicColor.drop(1).distinctUntilChanged().collect {
                refresh(app)
                publishPreviewsIfDue(app, force = true)
            }
        }
        scope.launch {
            // The circuit background switch in Settings: redraw every placed widget right away.
            AppSettings.widgetTrackBackground.drop(1).distinctUntilChanged().collect {
                refresh(app)
                publishPreviewsIfDue(app, force = true)
            }
        }
        scope.launch {
            // The One UI glass settings: blur on/off, opacity, tone. Previews never show glass.
            combine(
                AppSettings.widgetSamsungBlur,
                AppSettings.widgetBlurAlpha,
                AppSettings.widgetTone,
            ) { blur, alpha, tone -> Triple(blur, alpha, tone) }
                .drop(1)
                .distinctUntilChanged()
                .collect { refresh(app) }
        }
        scope.launch { publishPreviewsIfDue(app) }
    }

    /**
     * Rebuilds the snapshot and redraws every placed widget. Fire and forget; [onDone] runs in all
     * cases (a receiver's goAsync() finish).
     */
    fun refresh(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        // Samsung lock-screen widget (widget/lock, separate RemoteViews provider): same triggers.
        com.flexy.f1live.widget.lock.SamsungLockWidget.updateAll(app)
        // Its 2x2 sibling for tablets / foldables (also switches that receiver on where supported).
        com.flexy.f1live.widget.lock.SamsungLockWidgetLarge.updateAll(app)
        scope.launch {
            try {
                refreshNow(app)
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed", e)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private suspend fun refreshNow(context: Context) {
        val ids = placedWidgetIds(context)
        if (ids.isEmpty()) {
            cancelAlarm(context)
            return
        }
        current(context, maxAgeMs = 0L)
        val glanceManager = GlanceAppWidgetManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widget = F1Widget()
        // Composed right here and pushed straight to the launcher, for every placed widget at
        // once: GlanceAppWidget.update() would start each idle widget's session through
        // WorkManager, which can run it many seconds later (a settings change then reached one
        // widget long after the other). A session still running recomposes from [snapshot] too.
        // One direct composition per widget at a time: two overlapping refreshes (an app update
        // and a calendar fetch, say) would otherwise both claim the same widget id.
        pushLock.withLock { pushAll(context, ids, glanceManager, appWidgetManager, widget) }
        // Picker previews refused by the system's rate limit are retried with the next redraw.
        publishPreviewsIfDue(context)
    }

    private suspend fun pushAll(
        context: Context,
        ids: IntArray,
        glanceManager: GlanceAppWidgetManager,
        appWidgetManager: AppWidgetManager,
        widget: F1Widget,
    ) {
        for (id in ids) {
            val glanceId = glanceManager.getGlanceIdBy(id)
            runCatching {
                val options = appWidgetManager.getAppWidgetOptions(id)
                appWidgetManager.updateAppWidget(id, widget.compose(context, glanceId, options))
            }.onFailure {
                Log.w(TAG, "direct widget update failed, falling back to a session", it)
                widget.update(context, glanceId)
            }
        }
    }

    /**
     * The snapshot for a widget session: reused when it is younger than [maxAgeMs] (several widgets
     * and sizes render in the same second), rebuilt otherwise. Rebuilding also re-arms the alarm.
     */
    suspend fun current(context: Context, maxAgeMs: Long = REUSE_MS): WidgetSnapshot = lock.withLock {
        val now = System.currentTimeMillis()
        _snapshot.value?.let { cached ->
            if (now - cached.nowMillis in 0..maxAgeMs &&
                cached.dynamicColor == AppSettings.dynamicColor.value &&
                cached.glass == glassStyle(context) &&
                cached.decorations.isNotEmpty() == (AppSettings.widgetTrackBackground.value && cached.outline != null)
            ) {
                return@withLock cached
            }
        }
        val built = build(context, now, preview = false)
        _snapshot.value = built
        armAlarm(context, WidgetPlanner.nextRefreshAt(built.entries, now, ZoneId.systemDefault()))
        built
    }

    /** A snapshot for the widget picker: real data, but never a ticking Chronometer. */
    suspend fun previewSnapshot(context: Context): WidgetSnapshot =
        build(context, System.currentTimeMillis(), preview = true)

    private suspend fun build(context: Context, now: Long, preview: Boolean): WidgetSnapshot {
        val weekends = withTimeoutOrNull(SCHEDULE_TIMEOUT_MS) { loadWeekends(now) }
        val entries = weekends?.let { WidgetPlanner.entries(it, now) }.orEmpty()
        val (flags, outline) = loadImages(context, entries)
        val decorations = outline
            ?.takeIf { AppSettings.widgetTrackBackground.value }
            ?.let { bitmap ->
                runCatching { decorations(bitmap) }
                    .onFailure { Log.w(TAG, "circuit decoration failed", it) }
                    .getOrNull()
            }
            .orEmpty()
        return WidgetSnapshot(
            nowMillis = now,
            entries = entries,
            calendarLoaded = weekends != null,
            flags = flags,
            outline = outline,
            decorations = decorations,
            dynamicColor = AppSettings.dynamicColor.value,
            glass = glassStyle(context),
            preview = preview,
        )
    }

    private fun glassStyle(context: Context): GlassStyle? =
        if (AppSettings.widgetSamsungBlur.value && OneUi.supportsHomeBlur(context)) {
            GlassStyle(AppSettings.widgetBlurAlpha.value.coerceIn(1, 254), AppSettings.widgetTone.value)
        } else {
            null
        }

    /**
     * This season, plus next season's once this one has nothing left (December). The repository
     * serves memory, then its disk copy, and only then the network. Null when nothing is readable.
     */
    private suspend fun loadWeekends(now: Long): List<RaceWeekend>? {
        val schedule = runCatching { Graph.schedule }.getOrNull() ?: return null
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val current = schedule.getSeason(year).getOrNull()
        if (current != null && WidgetPlanner.entries(current, now, limit = WidgetPlanner.MAX_ENTRIES)
                .size >= WidgetPlanner.MAX_ENTRIES
        ) {
            return current
        }
        val next = schedule.getSeason(year + 1).getOrNull()
        if (current == null && next == null) return null
        return current.orEmpty() + next.orEmpty()
    }

    /**
     * Flags of every listed weekend and the first weekend's outline, through Coil's shared loader
     * (and so its disk cache, which the app has usually filled already). Each image has its own
     * timeout; offline and uncached simply means no image.
     */
    private suspend fun loadImages(
        context: Context,
        entries: List<WidgetEntry>,
    ): Pair<Map<String, Bitmap>, Bitmap?> = coroutineScope {
        val codes = entries.mapNotNull { it.weekend.countryCode?.lowercase() }.distinct()
        val flagJobs = codes.map { code ->
            async { flagCdnUrl(code)?.let { url -> loadBitmap(context, url, FLAG_SIZE) }?.let { code to it } }
        }
        val outlineJob = async {
            entries.firstOrNull()?.weekend?.let(CircuitMaps::f1OutlineUrl)
                ?.let { url -> loadBitmap(context, url, OUTLINE_SIZE) }
        }
        flagJobs.awaitAll().filterNotNull().toMap() to outlineJob.await()
    }

    private suspend fun loadBitmap(context: Context, url: String, size: Size): Bitmap? =
        withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(size)
                    // RemoteViews parcel their bitmaps: a hardware bitmap cannot be sent.
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                    ?.image?.toBitmap()
            }.getOrNull()
        }

    /**
     * The outline cropped so that, pinned to a corner, it runs off that corner's two edges: the
     * part kept is the one facing into the widget. Made once per snapshot so every size and
     * widget shares the same Bitmap instances (RemoteViews de-duplicates them by identity).
     */
    private fun decorations(outline: Bitmap): Map<DecorationCorner, Bitmap> {
        val width = outline.width
        val height = outline.height
        val keepWidth = (width * DECORATION_KEEP).toInt().coerceAtLeast(1)
        val keepHeight = (height * DECORATION_KEEP).toInt().coerceAtLeast(1)
        return mapOf(
            DecorationCorner.BottomEnd to Bitmap.createBitmap(outline, 0, 0, keepWidth, keepHeight),
            DecorationCorner.TopEnd to Bitmap.createBitmap(outline, 0, height - keepHeight, keepWidth, keepHeight),
        )
    }

    // ------------------------------------------------------------------ placed widgets

    fun placedWidgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return receivers
            .flatMap { receiver ->
                runCatching { manager.getAppWidgetIds(ComponentName(context, receiver)).toList() }
                    .getOrDefault(emptyList())
            }
            .toIntArray()
    }

    // ------------------------------------------------------------------ alarm

    /**
     * Non-wakeup and exact: nobody sees a widget on a sleeping phone, and when it wakes the pending
     * alarm is delivered straight away. USE_EXACT_ALARM (already declared for auto-follow) makes it
     * exact; without it a plain alarm is still close enough for an hour-accurate countdown.
     */
    private fun armAlarm(context: Context, triggerAt: Long) {
        if (placedWidgetIds(context).isEmpty()) {
            cancelAlarm(context)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (exact) {
                manager.setExact(AlarmManager.RTC, triggerAt, pending)
            } else {
                manager.set(AlarmManager.RTC, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            runCatching { manager.set(AlarmManager.RTC, triggerAt, pending) }
        }
    }

    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun alarmIntent(context: Context, flag: Int): PendingIntent? {
        val intent = Intent(context, AutoFollowReceiver::class.java).setAction(ACTION_WIDGET_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH_ALARM,
            intent,
            PendingIntent.FLAG_IMMUTABLE or flag,
        )
    }

    // ------------------------------------------------------------------ picker previews

    /**
     * Android 15+ widget pickers show a preview generated from the real widget. Publishing is
     * rate-limited by the system, so it happens at most every [PREVIEW_INTERVAL_MS], or when the
     * app was updated or an appearance setting changed; older launchers use the static
     * previewLayout from the provider XML.
     */
    fun publishPreviewsIfDue(context: Context, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val app = context.applicationContext
        scope.launch {
            if (!previewLock.tryLock()) return@launch
            try {
                publishPreviews(app, force)
            } finally {
                previewLock.unlock()
            }
        }
    }

    private suspend fun publishPreviews(app: Context, force: Boolean) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Both appearance settings folded into one value: a change of either republishes.
        val appearance = (if (AppSettings.dynamicColor.value) 1 else 0) +
            (if (AppSettings.widgetTrackBackground.value) 2 else 0)
        val publishedAt = prefs.getLong(KEY_PREVIEW_AT, 0L)
        // An app update may change how the widget looks: its previews are then out of date.
        val installedAt = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val due = force ||
            now - publishedAt !in 0..PREVIEW_INTERVAL_MS ||
            installedAt > publishedAt ||
            prefs.getInt(KEY_PREVIEW_APPEARANCE, -1) != appearance
        if (!due) return
        val manager = GlanceAppWidgetManager(app)
        var allPublished = true
        // The standings widgets' entries too: one schedule for every preview of the app.
        for (receiver in receivers + StandingsWidgetUpdater.receivers) {
            val result = runCatching {
                manager.setWidgetPreviews(
                    receiver.kotlin,
                    intSetOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN),
                )
            }.onFailure { Log.w(TAG, "widget preview failed", it) }.getOrNull()
            if (result != GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
                // Usually the system's rate limit: stop here, the next redraw or app start retries.
                Log.i(TAG, "widget preview for ${receiver.simpleName} not published: $result")
                allPublished = false
                break
            }
        }
        if (allPublished) {
            prefs.edit().putLong(KEY_PREVIEW_AT, now).putInt(KEY_PREVIEW_APPEARANCE, appearance).apply()
        }
    }
}
