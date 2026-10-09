package com.flexy.f1live.live

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import androidx.annotation.RequiresApi
import com.flexy.f1live.R
import com.flexy.f1live.data.Graph
import com.flexy.f1live.data.LiveTimingClient
import com.flexy.f1live.model.DriverTiming
import com.flexy.f1live.model.LiveSessionState
import com.flexy.f1live.model.LiveSource
import com.flexy.f1live.model.SessionBreak
import com.flexy.f1live.model.SessionKind
import com.flexy.f1live.model.SessionStatus
import com.flexy.f1live.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class LiveUpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notifications: NotificationManager

    private var lastSummary: String? = null

    private var latestState: LiveSessionState = LiveSessionState.EMPTY
    private var latestContent: LiveNotificationBuilder.Content? = null

    private var wantedIconKey: String? = null

    private var shuttingDown = false

    private var feedJob: Job? = null

    private var target: SessionTarget? = null

    private var waitDeadline = 0L

    private var sawLive = false

    private var feedStarted = false

    private var finishJob: Job? = null

    private var demoJob: Job? = null

    private var styleObserved = false

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveUpdateController.setServiceRunning(true)
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    // Each start but ACTION_STOP came via startForegroundService() and must call startForeground().
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dismissForThisSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DEMO) {
            startDemo()
            return START_NOT_STICKY
        }
        if (demoJob != null) {
            demoJob?.cancel()
            demoJob = null
            sawLive = false
            lastSummary = null
        }

        val now = System.currentTimeMillis()
        val newTarget = intent?.let { readTarget(it) }
        if (newTarget != null && newTarget != target) {
            target = newTarget
            waitDeadline = maxOf(waitDeadline, AutoFollowPlanner.waitDeadline(newTarget, now))
        } else if (waitDeadline == 0L) {
            waitDeadline = AutoFollowPlanner.waitDeadline(null, now)
        }

        val content = contentFor(latestState)
        latestContent = content
        lastSummary = content.summary
        if (!promote(content)) {
            shuttingDown = true
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { notifications.cancel(AutoFollow.FALLBACK_NOTIFICATION_ID) }

        if (!feedStarted) {
            feedStarted = true
            val client = client()
            runCatching { client?.start() }
            if (client != null) observe(client)
            startWatchdog()
            observeStyleSetting()
        }
        return START_NOT_STICKY
    }

    private fun promote(content: LiveNotificationBuilder.Content): Boolean {
        val notification = buildNotification(content, icon = null)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            AutoFollow.postSessionStarting(this, target)
            false
        } catch (_: RuntimeException) {
            AutoFollow.postSessionStarting(this, target)
            false
        }
    }

    private fun startDemo() {
        if (feedStarted && demoJob == null) return
        demoJob?.cancel()
        sawLive = true
        val first = LiveNotificationBuilder.build(LiveDemo.frame(0), connectingText())
        latestContent = first
        lastSummary = first.summary
        if (!promote(first)) {
            shuttingDown = true
            stopSelf()
            return
        }
        observeStyleSetting()
        demoJob = scope.launch {
            for (index in 0 until LiveDemo.FRAME_COUNT) {
                val content = LiveNotificationBuilder.build(LiveDemo.frame(index), connectingText())
                latestContent = content
                ensureIcon(content.leader)
                post(content)
                delay(LiveDemo.FRAME_INTERVAL_MS)
            }
            delay(DEMO_HOLD_MS)
            shuttingDown = true
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (feedStarted && !LiveUpdateController.uiActive) runCatching { client()?.stop() }
        LiveUpdateController.setServiceRunning(false)
        AvatarIcons.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ feed

    private fun client(): LiveTimingClient? = runCatching { Graph.liveTiming }.getOrNull()

    private fun observe(client: LiveTimingClient) {
        feedJob?.cancel()
        feedJob = scope.launch {
            client.state.collect { state ->
                render(state)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun observeStyleSetting() {
        if (Build.VERSION.SDK_INT < AppSettings.API_METRIC_STYLE || styleObserved) return
        styleObserved = true
        scope.launch {
            AppSettings.metricStyle.drop(1).collect { latestContent?.let { post(it) } }
        }
    }

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                checkAutoStop(latestState)
            }
        }
    }

    private fun render(state: LiveSessionState) {
        if (shuttingDown) return
        latestState = state
        if (!sawLive && isNetworkLive(state)) sawLive = true
        val content = contentFor(state)
        latestContent = content
        if (sawLive) ensureIcon(content.leader)
        if (content.summary != lastSummary) {
            lastSummary = content.summary
            post(content)
        }
        checkAutoStop(state)
    }

    private fun post(content: LiveNotificationBuilder.Content) {
        if (shuttingDown) return
        val icon = content.leader?.let { AvatarIcons.cached(it) ?: AvatarIcons.fallback(it) }
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification(content, icon)) }
    }

    private fun contentFor(state: LiveSessionState): LiveNotificationBuilder.Content {
        if (sawLive) return LiveNotificationBuilder.build(state, connectingText())
        val base = LiveNotificationBuilder.build(LiveSessionState.EMPTY, waitingText(state))
        val label = SessionBreak.delayText(state)?.takeIf { delayAnnounced(state) }
            ?: target?.label?.takeIf { it.isNotBlank() }
            ?: return base
        return base.copy(title = label, summary = base.summary + "|" + label)
    }

    private fun waitingText(state: LiveSessionState): String {
        val session = target ?: return connectingText()
        val time = DateFormat.getTimeFormat(this).format(Date(session.startUtcMillis))
        return getString(
            if (delayAnnounced(state)) R.string.live_notif_delayed else R.string.live_notif_waiting,
            time,
        )
    }

    private fun delayAnnounced(state: LiveSessionState): Boolean {
        val session = target ?: return false
        return state.source != LiveSource.CACHE &&
            state.source != LiveSource.NONE &&
            session.kind != SessionKind.UNKNOWN &&
            state.sessionKind == session.kind &&
            SessionBreak.delayNotice(state) != null
    }

    private fun ensureIcon(driver: DriverTiming?) {
        if (driver == null) return
        val key = AvatarIcons.keyFor(driver)
        if (key == wantedIconKey) return
        wantedIconKey = key
        if (AvatarIcons.cached(driver) != null) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AvatarIcons.load(applicationContext, driver) }.getOrNull()
            }
            if (loaded != null && wantedIconKey == key) {
                latestContent?.let { post(it) }
            }
        }
    }

    // ------------------------------------------------------------------ auto-stop

    private fun checkAutoStop(state: LiveSessionState) {
        if (shuttingDown) return
        if (!sawLive) {
            val now = System.currentTimeMillis()
            val held = delayAnnounced(state) &&
                target?.let { now < AutoFollowPlanner.windowEnd(it) } == true
            val expired = now >= waitDeadline && !held
            if (expired || targetAlreadyOver(state)) quit()
            return
        }
        val session = target
        if (session != null &&
            System.currentTimeMillis() >= AutoFollowPlanner.windowEnd(session) + OVERRUN_GRACE_MS
        ) {
            quit()
            return
        }
        if (!LiveNotificationBuilder.isFinished(state)) {
            finishJob?.cancel()
            finishJob = null
            return
        }
        if (finishJob != null) return
        finishJob = scope.launch {
            delay(FINISHED_LINGER_MS)
            quit()
        }
    }

    private fun quit() {
        shuttingDown = true
        markHandled()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun dismissForThisSession() {
        if (demoJob != null) {
            demoJob?.cancel()
            shuttingDown = true
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        val until = AutoFollowPlanner.suppressUntil(target, System.currentTimeMillis())
        FollowPreferences.setDismissedUntil(this, until)
        shuttingDown = true
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun markHandled() {
        val session = target ?: return
        FollowPreferences.setHandledUntil(this, AutoFollowPlanner.windowEnd(session))
    }

    private fun targetAlreadyOver(state: LiveSessionState): Boolean {
        val session = target ?: return false
        if (session.kind == SessionKind.UNKNOWN || state.sessionKind != session.kind) return false
        if (state.source == LiveSource.CACHE || state.source == LiveSource.NONE) return false
        return LiveNotificationBuilder.isSessionOver(state.status)
    }

    private fun isNetworkLive(state: LiveSessionState): Boolean {
        if (state.source == LiveSource.CACHE || state.source == LiveSource.NONE) return false
        if (state.isLive) return true
        val session = target ?: return false
        return session.kind != SessionKind.UNKNOWN &&
            state.sessionKind == session.kind &&
            state.status == SessionStatus.FINISHED &&
            !LiveNotificationBuilder.isFinished(state)
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_live_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_live_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(
        content: LiveNotificationBuilder.Content,
        icon: Icon?,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_f1)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(content.accentColor)
            .setShowWhen(false)
            .addAction(stopAction(content.accentColor))
        contentIntent()?.let { builder.setContentIntent(it) }

        if (Build.VERSION.SDK_INT >= AppSettings.API_METRIC_STYLE && useMetricStyle(content)) {
            val metric = buildMetricNotification(builder, content)
            if (metric != null) return metric
        }
        if (Build.VERSION.SDK_INT >= API_LIVE_UPDATES) {
            applyLiveUpdate(builder, content, icon)
        } else {
            applyLegacyProgress(builder, content)
        }
        return builder.build()
    }

    private fun useMetricStyle(content: LiveNotificationBuilder.Content): Boolean =
        AppSettings.metricStyle.value && content.metrics.isNotEmpty()

    @RequiresApi(AppSettings.API_METRIC_STYLE)
    private fun buildMetricNotification(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
    ): Notification? {
        val promotable = runCatching { notifications.canPostPromotedNotifications() }.getOrDefault(false)
        return runCatching {
            val metrics = content.metrics.take(LiveNotificationBuilder.MAX_METRICS).map { spec ->
                Notification.Metric(
                    Notification.Metric.FixedText(spec.value),
                    spec.label,
                    semanticStyle(spec.semantic),
                )
            }
            val style = Notification.MetricStyle().setMetrics(metrics)
            val critical = content.criticalMetric
            style.setCriticalMetric(
                if (critical in metrics.indices) critical else Notification.MetricStyle.METRIC_INDEX_NONE,
            )
            if (promotable) builder.setRequestPromotedOngoing(true)
            content.shortCriticalText?.let { builder.setShortCriticalText(it) }
            builder.setStyle(style)
            val notification = builder.build()
            if (promotable && !notification.hasPromotableCharacteristics()) null else notification
        }.getOrNull().also { built ->
            if (built == null) builder.setStyle(null)
        }
    }

    @RequiresApi(AppSettings.API_METRIC_STYLE)
    private fun semanticStyle(semantic: LiveNotificationBuilder.MetricSemantic): Int = when (semantic) {
        LiveNotificationBuilder.MetricSemantic.UNSPECIFIED -> Notification.SEMANTIC_STYLE_UNSPECIFIED
        LiveNotificationBuilder.MetricSemantic.INFO -> Notification.SEMANTIC_STYLE_INFO
        LiveNotificationBuilder.MetricSemantic.SAFE -> Notification.SEMANTIC_STYLE_SAFE
        LiveNotificationBuilder.MetricSemantic.CAUTION -> Notification.SEMANTIC_STYLE_CAUTION
        LiveNotificationBuilder.MetricSemantic.DANGER -> Notification.SEMANTIC_STYLE_DANGER
    }

    @RequiresApi(API_LIVE_UPDATES)
    private fun applyLiveUpdate(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
        icon: Icon?,
    ) {
        val promotable = runCatching { notifications.canPostPromotedNotifications() }.getOrDefault(false)
        if (promotable) builder.setRequestPromotedOngoing(true)

        content.shortCriticalText?.let { builder.setShortCriticalText(it) }

        val style = Notification.ProgressStyle().setStyledByProgress(true)
        icon?.let { style.setProgressTrackerIcon(it) }
        when (val progress = content.progress) {
            is LiveNotificationBuilder.Progress.Indeterminate -> {
                style.setProgressIndeterminate(true)
                style.setProgressSegments(
                    List(INDETERMINATE_SEGMENTS) { Notification.ProgressStyle.Segment(1) },
                )
                style.setProgress(0)
            }

            is LiveNotificationBuilder.Progress.Segmented -> {
                style.setProgressIndeterminate(false)
                style.setProgressSegments(
                    progress.segments.map { (length, color) ->
                        Notification.ProgressStyle.Segment(length)
                            .also { segment -> color?.let { segment.setColor(it) } }
                    },
                )
                style.setProgress(progress.current)
            }
        }
        builder.setStyle(style)
    }

    private fun applyLegacyProgress(
        builder: Notification.Builder,
        content: LiveNotificationBuilder.Content,
    ) {
        when (val progress = content.progress) {
            is LiveNotificationBuilder.Progress.Indeterminate ->
                builder.setProgress(0, 0, true)

            is LiveNotificationBuilder.Progress.Segmented ->
                builder.setProgress(progress.max, progress.current, false)
        }
    }

    private fun connectingText(): String = getString(R.string.live_notif_connecting)

    private fun stopAction(accent: Int): Notification.Action {
        val intent = Intent(this, LiveUpdateService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // setColor() no longer tints and colorizing would cost the promotion: the accent is a span.
        val label = SpannableString(getString(R.string.live_notif_stop)).apply {
            setSpan(ForegroundColorSpan(accent), 0, length, 0)
        }
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_f1),
            label,
            pending,
        ).build()
    }

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_STOP = "com.flexy.f1live.live.action.STOP"
        const val ACTION_DEMO = "com.flexy.f1live.live.action.DEMO"

        private const val CHANNEL_ID = "live_timing"
        private const val NOTIFICATION_ID = 4101

        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        private const val API_LIVE_UPDATES = 36

        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val FINISHED_LINGER_MS = 10L * 60L * 1000L

        private const val OVERRUN_GRACE_MS = 60L * 60L * 1000L
        private const val INDETERMINATE_SEGMENTS = 3
        private const val DEMO_HOLD_MS = 10_000L

        private const val EXTRA_START = "com.flexy.f1live.live.extra.START"
        private const val EXTRA_KIND = "com.flexy.f1live.live.extra.KIND"
        private const val EXTRA_LABEL = "com.flexy.f1live.live.extra.LABEL"

        fun start(context: Context, target: SessionTarget? = null): Boolean {
            val intent = Intent(context, LiveUpdateService::class.java)
            target?.let { putTarget(intent, it) }
            return try {
                context.startForegroundService(intent) != null
            } catch (_: ForegroundServiceStartNotAllowedException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }

        fun startDemo(context: Context): Boolean = try {
            val intent = Intent(context, LiveUpdateService::class.java).setAction(ACTION_DEMO)
            context.startForegroundService(intent) != null
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveUpdateService::class.java)) }
        }

        fun putTarget(intent: Intent, target: SessionTarget) {
            intent.putExtra(EXTRA_START, target.startUtcMillis)
            intent.putExtra(EXTRA_KIND, target.kind.name)
            intent.putExtra(EXTRA_LABEL, target.label)
        }

        fun readTarget(intent: Intent): SessionTarget? {
            val start = intent.getLongExtra(EXTRA_START, 0L).takeIf { it > 0L } ?: return null
            val kind = intent.getStringExtra(EXTRA_KIND)
                ?.let { name -> SessionKind.entries.firstOrNull { it.name == name } }
                ?: SessionKind.UNKNOWN
            return SessionTarget(start, kind, intent.getStringExtra(EXTRA_LABEL).orEmpty())
        }
    }
}
