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

data class WidgetSnapshot(
    val nowMillis: Long,
    val entries: List<WidgetEntry>,
    val calendarLoaded: Boolean,
    val flags: Map<String, Bitmap>,
    val outline: Bitmap?,
    val decorations: Map<DecorationCorner, Bitmap>,
    val dynamicColor: Boolean,
    val glass: GlassStyle? = null,
    val preview: Boolean = false,
)

data class GlassStyle(val alpha: Int, val tone: ThemeMode)

enum class DecorationCorner { BottomEnd, TopEnd }

object WidgetUpdater {

    const val ACTION_WIDGET_REFRESH = "com.flexy.f1live.widget.action.REFRESH"

    private const val TAG = "F1Widget"
    private const val REQUEST_REFRESH_ALARM = 12
    private const val SCHEDULE_TIMEOUT_MS = 6_000L
    private const val IMAGE_TIMEOUT_MS = 4_000L

    private const val REUSE_MS = 10_000L

    private const val PREFS = "widgets"
    private const val KEY_PREVIEW_AT = "preview_published_at"
    private const val KEY_PREVIEW_APPEARANCE = "preview_appearance"
    private const val PREVIEW_INTERVAL_MS = 12L * 60L * 60L * 1000L

    private val FLAG_SIZE = Size(80, 60)
    private val OUTLINE_SIZE = Size(242, 170)

    private const val DECORATION_KEEP = 0.8f

    val receivers: List<Class<out F1WidgetReceiver>> = listOf(
        NextSessionWidgetReceiver::class.java,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val pushLock = Mutex()

    private val previewLock = Mutex()

    private val _snapshot = MutableStateFlow<WidgetSnapshot?>(null)
    val snapshot: StateFlow<WidgetSnapshot?> = _snapshot.asStateFlow()

    fun attach(app: Application) {
        scope.launch {
            AppSettings.dynamicColor.drop(1).distinctUntilChanged().collect {
                refresh(app)
                publishPreviewsIfDue(app, force = true)
            }
        }
        scope.launch {
            AppSettings.widgetTrackBackground.drop(1).distinctUntilChanged().collect {
                refresh(app)
                publishPreviewsIfDue(app, force = true)
            }
        }
        scope.launch {
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

    fun refresh(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        com.flexy.f1live.widget.lock.SamsungLockWidget.updateAll(app)
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
        pushLock.withLock { pushAll(context, ids, glanceManager, appWidgetManager, widget) }
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
                    .allowHardware(false)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                    ?.image?.toBitmap()
            }.getOrNull()
        }

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
        val appearance = (if (AppSettings.dynamicColor.value) 1 else 0) +
            (if (AppSettings.widgetTrackBackground.value) 2 else 0)
        val publishedAt = prefs.getLong(KEY_PREVIEW_AT, 0L)
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
        for (receiver in receivers + StandingsWidgetUpdater.receivers) {
            val result = runCatching {
                manager.setWidgetPreviews(
                    receiver.kotlin,
                    intSetOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN),
                )
            }.onFailure { Log.w(TAG, "widget preview failed", it) }.getOrNull()
            if (result != GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
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
