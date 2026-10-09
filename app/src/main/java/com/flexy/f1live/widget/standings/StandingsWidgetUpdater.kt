package com.flexy.f1live.widget.standings

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.compose
import com.flexy.f1live.data.ConstructorStanding
import com.flexy.f1live.data.DriverStanding
import com.flexy.f1live.data.Graph
import com.flexy.f1live.data.JolpicaStandingsRepository
import com.flexy.f1live.data.Standings
import com.flexy.f1live.data.TitleFight
import com.flexy.f1live.live.AutoFollowReceiver
import com.flexy.f1live.model.RaceWeekend
import com.flexy.f1live.settings.AppSettings
import com.flexy.f1live.widget.GlassStyle
import com.flexy.f1live.widget.OneUi
import com.flexy.f1live.widget.lock.StandingsLockWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset

data class StandingsSnapshot(
    val nowMillis: Long,
    val season: Int,
    val round: Int,
    val drivers: List<StandingsEntry>,
    val teams: List<StandingsEntry>,
    val driversFight: TitleFight?,
    val teamsFight: TitleFight?,
    val fetchedAtMillis: Long,
    val dynamicColor: Boolean,
    val glass: GlassStyle? = null,
)

object StandingsWidgetUpdater {

    const val ACTION_REFRESH = "com.flexy.f1live.widget.action.REFRESH_STANDINGS"

    private const val TAG = "StandingsWidget"
    private const val REQUEST_REFRESH_ALARM = 13
    private const val LOAD_TIMEOUT_MS = 8_000L

    private const val REUSE_MS = 10_000L

    val receivers: List<Class<out GlanceAppWidgetReceiver>>
        get() = StandingsWidgetKind.entries.map { it.receiver }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val pushLock = Mutex()

    private val _snapshot = MutableStateFlow<StandingsSnapshot?>(null)
    val snapshot: StateFlow<StandingsSnapshot?> = _snapshot.asStateFlow()

    fun attach(app: Application) {
        scope.launch {
            JolpicaStandingsRepository.updates.collect { standings ->
                if (standings.fetchedAtMillis != _snapshot.value?.fetchedAtMillis) refresh(app, fetch = false)
            }
        }
        scope.launch {
            combine(
                AppSettings.dynamicColor,
                AppSettings.widgetSamsungBlur,
                AppSettings.widgetBlurAlpha,
                AppSettings.widgetTone,
            ) { dynamic, blur, alpha, tone -> listOf(dynamic, blur, alpha, tone) }
                .drop(1)
                .distinctUntilChanged()
                .collect { refresh(app, fetch = false) }
        }
    }

    fun refresh(context: Context, fetch: Boolean = true, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                refreshNow(app, fetch)
            } catch (e: Exception) {
                Log.w(TAG, "standings widget refresh failed", e)
            } finally {
                onDone?.invoke()
            }
        }
    }

    private suspend fun refreshNow(context: Context, fetch: Boolean) {
        StandingsLockWidget.syncEnabled(context)
        val placed = placedWidgets(context)
        if (placed.isEmpty() && !StandingsLockWidget.anyPlaced(context)) {
            cancelAlarm(context)
            return
        }
        current(context, maxAgeMs = 0L, fetch = fetch)
        val glanceManager = GlanceAppWidgetManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        pushLock.withLock {
            for ((kind, ids) in placed) {
                val widget = kind.newWidget()
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
        }
    }

    suspend fun current(context: Context, maxAgeMs: Long = REUSE_MS, fetch: Boolean = true): StandingsSnapshot =
        lock.withLock {
            val now = System.currentTimeMillis()
            _snapshot.value?.let { cached ->
                if (now - cached.nowMillis in 0..maxAgeMs &&
                    cached.dynamicColor == AppSettings.dynamicColor.value &&
                    cached.glass == glassStyle(context)
                ) {
                    return@withLock cached
                }
            }
            val (built, nextCheckAt) = build(context, now, fetch)
            _snapshot.value = built
            StandingsLockWidget.push(context, built)
            armAlarm(context, nextCheckAt)
            built
        }

    suspend fun previewSnapshot(context: Context): StandingsSnapshot {
        val now = System.currentTimeMillis()
        val (built, _) = build(context, now, fetch = false)
        return if (built.drivers.isEmpty()) sample(now) else built.copy(glass = null)
    }

    private suspend fun build(context: Context, now: Long, fetch: Boolean): Pair<StandingsSnapshot, Long> {
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        val weekends = calendar(year)
        val current = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            val stored = JolpicaStandingsRepository.cached(year)
            if (fetch && (stored == null || StandingsWidgetPlanner.isDue(stored.fetchedAtMillis, weekends, now))) {
                JolpicaStandingsRepository.fetch(year).getOrNull() ?: stored
            } else {
                stored
            }
        }
        val nextCheckAt = StandingsWidgetPlanner.nextCheckAt(current?.fetchedAtMillis ?: 0L, weekends, now)

        var standings = current
        var standingsWeekends = weekends
        if (current == null || current.drivers.isEmpty()) {
            val lastYear = calendar(year - 1)
            val previous = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                val stored = JolpicaStandingsRepository.cached(year - 1)
                if (fetch && (stored == null || StandingsWidgetPlanner.missesResults(stored.fetchedAtMillis, lastYear))) {
                    JolpicaStandingsRepository.fetch(year - 1).getOrNull() ?: stored
                } else {
                    stored
                }
            }
            if (previous != null && previous.drivers.isNotEmpty()) {
                standings = previous
                standingsWeekends = lastYear
            }
        }
        return snapshotOf(context, now, year, standings, standingsWeekends) to nextCheckAt
    }

    private fun snapshotOf(
        context: Context,
        now: Long,
        year: Int,
        standings: Standings?,
        weekends: List<RaceWeekend>,
    ): StandingsSnapshot {
        val drivers = standings?.drivers.orEmpty()
        val teams = standings?.constructors.orEmpty()
        val round = standings?.round ?: 0
        return StandingsSnapshot(
            nowMillis = now,
            season = standings?.season ?: year,
            round = round,
            drivers = StandingsWidgetPlanner.driverEntries(drivers),
            teams = StandingsWidgetPlanner.teamEntries(teams),
            driversFight = TitleFight.of(drivers, round, weekends, now),
            teamsFight = TitleFight.ofConstructors(teams, round, weekends, now),
            fetchedAtMillis = standings?.fetchedAtMillis ?: 0L,
            dynamicColor = AppSettings.dynamicColor.value,
            glass = glassStyle(context),
        )
    }

    private suspend fun calendar(season: Int): List<RaceWeekend> {
        val schedule = runCatching { Graph.schedule }.getOrNull() ?: return emptyList()
        return withTimeoutOrNull(LOAD_TIMEOUT_MS) { schedule.getSeason(season).getOrNull() }.orEmpty()
    }

    private fun glassStyle(context: Context): GlassStyle? =
        if (AppSettings.widgetSamsungBlur.value && OneUi.supportsHomeBlur(context)) {
            GlassStyle(AppSettings.widgetBlurAlpha.value.coerceIn(1, 254), AppSettings.widgetTone.value)
        } else {
            null
        }

    private fun sample(now: Long): StandingsSnapshot {
        fun driver(position: Int, points: Double, wins: Int, code: String, first: String, last: String, team: String, id: String) =
            DriverStanding(position, points, wins, code, null, first, last, "", team, id)
        val drivers = listOf(
            driver(1, 324.0, 7, "PIA", "Oscar", "Piastri", "McLaren", "mclaren"),
            driver(2, 302.0, 6, "NOR", "Lando", "Norris", "McLaren", "mclaren"),
            driver(3, 241.0, 4, "VER", "Max", "Verstappen", "Red Bull", "red_bull"),
            driver(4, 198.0, 1, "RUS", "George", "Russell", "Mercedes", "mercedes"),
            driver(5, 181.0, 0, "LEC", "Charles", "Leclerc", "Ferrari", "ferrari"),
            driver(6, 142.0, 0, "HAM", "Lewis", "Hamilton", "Ferrari", "ferrari"),
        )
        val teams = listOf(
            ConstructorStanding(1, 626.0, 13, "McLaren", "mclaren", ""),
            ConstructorStanding(2, 323.0, 0, "Ferrari", "ferrari", ""),
            ConstructorStanding(3, 295.0, 1, "Mercedes", "mercedes", ""),
            ConstructorStanding(4, 268.0, 4, "Red Bull", "red_bull", ""),
            ConstructorStanding(5, 104.0, 0, "Williams", "williams", ""),
        )
        return StandingsSnapshot(
            nowMillis = now,
            season = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year,
            round = 20,
            drivers = StandingsWidgetPlanner.driverEntries(drivers),
            teams = StandingsWidgetPlanner.teamEntries(teams),
            driversFight = TitleFight(racesLeft = 4, sprintsLeft = 1, contenders = 3),
            teamsFight = TitleFight(
                racesLeft = 4,
                sprintsLeft = 1,
                contenders = 1,
                perRace = TitleFight.RACE_ONE_TWO,
                perSprint = TitleFight.SPRINT_ONE_TWO,
            ),
            fetchedAtMillis = 0L,
            dynamicColor = AppSettings.dynamicColor.value,
        )
    }

    // ------------------------------------------------------------------ placed widgets

    private fun placedWidgets(context: Context): Map<StandingsWidgetKind, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return StandingsWidgetKind.entries
            .associateWith { kind ->
                runCatching { manager.getAppWidgetIds(ComponentName(context, kind.receiver)) }
                    .getOrDefault(IntArray(0))
            }
            .filterValues { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ alarm

    private fun armAlarm(context: Context, triggerAt: Long) {
        if (placedWidgets(context).isEmpty() && !StandingsLockWidget.anyPlaced(context)) {
            cancelAlarm(context)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching { manager.set(AlarmManager.RTC, triggerAt, pending) }
            .onFailure { Log.w(TAG, "standings alarm not armed", it) }
    }

    private fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun alarmIntent(context: Context, flag: Int): PendingIntent? {
        val intent = Intent(context, AutoFollowReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH_ALARM,
            intent,
            PendingIntent.FLAG_IMMUTABLE or flag,
        )
    }
}
